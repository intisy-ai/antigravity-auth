// @ts-nocheck
// The delegation shell that runs antigravity-auth's `handleIr` decision loop (and the account-view
// quota/catalog helpers below) through the TeaVM-compiled Java orchestrator
// (`AntigravityHandleOrchestrator` plus `AntigravityProviderJs`'s other exports). The driver's
// `handleIr` dynamically imports this module on the first request; the TeaVM ESM loads via the
// lazily-memoized `loadOrchestrator()` below, so the ~MB compiled bundle only evaluates once needed.
//
// Split of responsibility (mirrors AntigravityHandleOrchestrator's javadoc):
//   - The Java orchestrator owns EVERY decision: model resolve + Auto candidate walk, the
//     account/endpoint retry+rotation loop, project-context discovery control flow, the
//     status->action branching (rate-limit classify / rotate / fall-through), and the terminal
//     selection (gemini-cli vs antigravity quota-reset).
//   - This TS shell owns host I/O: the fetch + IP-proxy transport (jsExec), account
//     acquisition/reporting over the shared `manager` (jsAcquire/jsAccountOps), the project fetch
//     loops (jsLoad/jsOnboard), and building the final Response from the orchestrator's decision.
//   - NO response body ever crosses into Java: SERVE/SERVE_RAW return the RETAINED live Response
//     verbatim (SSE/stream intact); only SYNTHETIC/TERMINAL bodies are built here from the decision.
//
// `jsPreparer` calls the Java prepare export `prepareAntigravityRequestProd` (via `prepareViaJava`),
// injecting real host seams: sha256 hasher, the disk-backed signature-cache lookup, a
// `defaultSignatureStore` adapter, and real Math.random/crypto.randomUUID. The SERVE response
// transform routes through the Java exports `transformServeBodyProd` / `newResponseSseTransformer`
// (real-seamed with the real signature store, image sink, and cache), see javaStream.ts's
// `makeResponseTransformStream`.

import crypto from "node:crypto";
import { proxyManager, getAutoCandidates, chatError, HandleIrError, proxiedFetch, safeJsonParse, lazyModule, initCoreAuth } from "@intisy-ai/core-auth";
// Re-exported at this same path (not just imported) so `instanceof HandleIrError` still holds for
// callers that import it from here: esbuild bundles dist/index.js and dist/handler.js independently,
// so a class imported fresh in each bundle stays a single, shared identity only if every importer
// reaches it through the same module graph, which this re-export preserves.
export { HandleIrError };
import { manager } from "./index.js";
import { getPluginSessionId, SYNTHETIC_THINKING_PLACEHOLDER, shouldCacheThinkingSignatures } from "../plugin/request.js";
import { loadManagedProject, onboardManagedProject } from "../plugin/project.js";
import { getKeepThinking, loadConfig, DEFAULT_CONFIG } from "../plugin/config/index.js";
import { defaultSignatureStore } from "../plugin/stores/signature-store.js";
import { getCachedSignature, cacheSignature } from "../plugin/cache.js";
import { processImageData } from "../plugin/image-saver.js";
import { isGemini3Model } from "../plugin/transform-java.js";
import { makeIrStream, jsIds, makeResponseTransformStream } from "./javaStream.js";

// Cached once at module load; a runtime config edit needs a process restart.
let config;
try { config = loadConfig(); } catch { config = DEFAULT_CONFIG; }

const PROVIDER_ID = "antigravity";
const GEMINI_CLI_PROVIDER_ID = "gemini-cli";

// The gemini-cli provider forces the free CLI quota lane for the request; the antigravity
// provider (or a legacy caller with no HandlerCtx.provider) keeps the config default, so
// existing antigravity serving is unchanged.
export function laneCliFirstFor(ctx) {
  if (ctx && ctx.provider === GEMINI_CLI_PROVIDER_ID) return true;
  return !!config.cli_first;
}

// Lazily-memoized dynamic import of the TeaVM ESM, staged to src/generated/ by core/teavm-build.mjs
// at build time and bundled (deferred) by esbuild. loadOrchestrator/getLoadedOrchestrator are kept as
// named exports (the parity tests and other host modules import them directly) backed by core-auth's
// shared lazyModule memoization.
const orchestratorModule = lazyModule(() => import("../generated/antigravity-orchestrator.teavm.js"));
export function loadOrchestrator() {
  return orchestratorModule.load();
}
// For callback contracts that can't await (accounts-controller.ts's synchronous status/availableAt/
// quota view); null until the first loadOrchestrator() resolves.
export function getLoadedOrchestrator() {
  return orchestratorModule.getLoaded();
}

// Routes fetchModels' catalog build through the Java buildCatalog export
// (AntigravityCatalog.buildAntigravityCatalog).
export async function buildCatalogViaJava(payload) {
  const orchestrator = await loadOrchestrator();
  return JSON.parse(orchestrator.buildCatalog(JSON.stringify(payload)));
}

// Synthetic (ad-hoc, unpersisted) project id for login.ts's checkAntigravityAccess verify-ping and
// accounts-controller.ts's verify() diagnostic.
export async function generateSyntheticProjectIdViaJava() {
  const orchestrator = await loadOrchestrator();
  return orchestrator.generateSyntheticProjectIdProd(jsRandom, jsUuid);
}

// fetchModels' project-id resolution, routed through the same AntigravityHandleOrchestrator.resolveProjectId
// the serve path uses. Fixed to one pre-selected `proxy` (fetchModels picks a proxy up front), unlike
// the per-attempt-account proxy the serve loop resolves via a closure.
export async function resolveProjectIdViaJava(manager, account, access, log, proxy) {
  const orchestrator = await loadOrchestrator();
  const jsLoad = async (accessToken, projectId) => {
    const payload = await loadManagedProject(accessToken, projectId || undefined, proxy || undefined);
    return payload ? JSON.stringify(payload) : null;
  };
  const jsOnboard = async (accessToken, tierId, projectId) => {
    const managedId = await onboardManagedProject(accessToken, tierId, projectId || undefined, undefined, undefined, proxy || undefined);
    return managedId ? JSON.stringify(managedId) : null;
  };
  const configJson = JSON.stringify({ platform: process.platform, arch: process.arch });
  const resultJson = await orchestrator.resolveProjectIdProd(
    JSON.stringify(account), access, jsRandom, jsUuid, jsLoad, jsOnboard, configJson,
  );
  const result = JSON.parse(resultJson);
  const meta = result.meta || {};
  // Mirrors runGeminiViaJava's jsAccountOps.mutate: the orchestrator only ever sets these two fields.
  manager.mutate(account.id, (a) => {
    a.meta = a.meta || {};
    if ("syntheticProjectId" in meta) a.meta.syntheticProjectId = meta.syntheticProjectId;
    if ("managedProjectId" in meta) a.meta.managedProjectId = meta.managedProjectId;
  });
  return result.projectId;
}

// The antigravity rate-limit statuses.
function isRateLimitStatus(status) {
  return status === 429 || status === 503 || status === 529;
}

// config.request_jitter_max_ms: a small random pre-request delay to desynchronize concurrent requests
// across accounts/sessions. Default 0 disables it. Exported so request-jitter.test.ts can exercise it
// with a mocked config without standing up the full orchestrator harness.
export function sleepMs(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
export async function applyRequestJitter() {
  const maxMs = config.request_jitter_max_ms;
  if (!maxMs || maxMs <= 0) return;
  await sleepMs(Math.random() * maxMs);
}

// ---- real host seams for prepareAntigravityRequestProd / cacheSignaturesFromResponse -------------
// Real production entropy: Math.random / crypto.randomUUID, never baked.
const jsRandom = () => Math.random();
const jsUuid = () => crypto.randomUUID();
// sha256 returning full hex; Java truncates to 16 hex chars itself (AntigravityRequestKeys.Hasher).
const jsHasher = (input) => crypto.createHash("sha256").update(input, "utf8").digest("hex");
// getCachedSignature adapted to JsCacheLookupFn (null, not undefined, on a miss).
const jsCacheLookup = (sessionId, text) => getCachedSignature(sessionId, text) ?? null;

// Adapter: defaultSignatureStore (stores/signature-store.ts) is object-shaped
// (get(key)->SignedThinking|undefined, set(key,{text,signature}), has, delete); JsSignatureStoreFns
// needs get(key)->JSON string|null, set(key,text,signature) (3-arg). This bridges the two shapes
// without changing the underlying store.
function makeJsSignatureStore(store) {
  return {
    get(key) {
      const v = store.get(key);
      return v ? JSON.stringify(v) : null;
    },
    has(key) { return store.has(key); },
    delete(key) { store.delete(key); },
    set(sessionKey, text, signature) { store.set(sessionKey, { text, signature }); },
  };
}
const jsSignatureStore = makeJsSignatureStore(defaultSignatureStore);

// The response-transform seams (counterparts to the prepare seams above).
// cacheSignature adapted to JsCacheSignatureFn: the on-disk signature-cache WRITE (jsCacheLookup is the READ).
const jsCacheSignatureFn = (sessionKey, text, signature) => cacheSignature(sessionKey, text, signature);
// processImageData adapted to JsImageSinkFn: real fs write to ~/.opencode|.claude/generated-images/,
// returning a markdown link. A missing mimeType/data arrives as "" (Java's bridge never sends raw null
// across the boundary); processImageData's `mimeType || 'image/png'` / `if (!data) return null` treat
// "" and undefined identically.
const jsImageSink = (mimeType, base64Data) => processImageData({ mimeType: mimeType || undefined, data: base64Data || undefined }) ?? null;

// The Gemini-3 SSE-reconnect thought-dedup seam: a process-lifetime `Set<string>` (created once, never
// reset) feeding the Java SERVE streaming transform.
const javaSessionDisplayedThinkingHashes = new Set();
const jsThoughtDedup = {
  has(hash) { return javaSessionDisplayedThinkingHashes.has(hash); },
  add(hash) { javaSessionDisplayedThinkingHashes.add(hash); },
};

// The debug-only `requestedModel` field. The Java export returns only the driver-relevant fields, so
// it's re-derived here from the same url the export parses. Substring extraction, not decision logic.
function extractRequestedModel(url) {
  const m = typeof url === "string" ? url.match(/\/models\/([^:]+):(\w+)/) : null;
  return m ? m[1] : undefined;
}

// debugText resolution for the SERVE transform. materializeDecision never passes a debug-TUI
// transcript, so this is just the getKeepThinking() fallback. Returns "" (not undefined) for "no debug
// text"; the Java exports treat an empty string as "none", matching JS truthiness.
function computeDebugText() {
  return getKeepThinking() ? SYNTHETIC_THINKING_PLACEHOLDER : "";
}

// Calls the Java prepare export and reassembles the result shape the driver's callers (jsPreparer plus
// the parity test) expect. `orchestrator` is the already-resolved loadOrchestrator() module.
export function prepareViaJava(
  orchestrator, url, method, headersJson, bodyText,
  accessToken, projectId, endpointOverride, headerStyle, fingerprint,
  claudeToolHardening = DEFAULT_CONFIG.claude_tool_hardening,
  claudePromptAutoCaching = DEFAULT_CONFIG.claude_prompt_auto_caching,
  cliFirst = DEFAULT_CONFIG.cli_first,
) {
  const fingerprintJson = JSON.stringify(fingerprint ?? null);
  const resultJson = orchestrator.prepareAntigravityRequestProd(
    url, method, headersJson, bodyText ?? "",
    accessToken, projectId, headerStyle, fingerprintJson,
    getKeepThinking(), getPluginSessionId(), endpointOverride ?? "",
    !!claudeToolHardening, !!claudePromptAutoCaching, !!cliFirst,
    jsRandom, jsUuid, jsHasher, jsCacheLookup, jsSignatureStore,
  );
  const r = JSON.parse(resultJson);
  return {
    request: r.request,
    init: { method, headers: new Headers(r.headers || {}), body: r.body },
    streaming: !!r.streaming,
    requestedModel: extractRequestedModel(url),
    effectiveModel: r.effectiveModel,
    projectId: r.projectId,
    endpoint: r.request,
    sessionId: r.sessionId,
    headerStyle: r.headerStyle,
  };
}

// The Gemini-path delegation: build the orchestrator inputs, wire the host seams, run
// handleAntigravityRequestAsync, and materialize its HandleDecision into a Response. This is the
// provider's IR<->upstream transport core: handleIrViaJavaOrchestrator encodes the decoded IR to a
// Gemini request and hands it here. Exported so the regression harness can drive the SAME upstream
// decision loop (quota rotation, terminal-error, synthetic project id) directly.
export async function runGeminiViaJava(request, ctx, laneCliFirst) {
  const log = (ctx && ctx.log) || (() => {});
  const cliFirst = laneCliFirst == null ? laneCliFirstFor(ctx) : laneCliFirst;
  // The sync jsAccountOps callbacks below (reportError/reportRateLimit/reportSuccess/
  // nextAvailableAt) call getCoreAuth(), which throws unless initCoreAuth() has already
  // resolved; ensure that before the orchestrator can reach them.
  await initCoreAuth();
  const orchestrator = await loadOrchestrator();
  const { handleAntigravityRequestAsync } = orchestrator;

  let bodyText;
  try { bodyText = await request.clone().text(); } catch { bodyText = undefined; }

  const inputsJson = JSON.stringify({
    url: request.url,
    method: request.method,
    headers: Object.fromEntries(request.headers),
    bodyText: bodyText ?? "",
    ctxModel: (ctx && ctx.model) || "",
  });
  // Real clock + real platform (no fixed nowMs): the export's parseClock falls back to
  // System.currentTimeMillis() (compiled to Date.now()), so the orchestrator's soonestQuotaReset /
  // resetTimeFor track wall clock. The leaderboard stays host-side: getAutoCandidates is passed in.
  const configJson = JSON.stringify({ platform: process.platform, arch: process.arch });
  const autoCandidatesJson = JSON.stringify(getAutoCandidates(PROVIDER_ID) || []);

  // ---- per-request host state (isolated to this call) ----------------------------------------
  const responses = [];                 // retained live Response objects, indexed by attemptRef
  const preparedRequests = [];          // retained prepared {request, init, ...}, indexed by requestRef
  const proxyByAccount = new Map();     // proxy URL selected for each account this request
  let currentAccountId = null;          // set on acquire; project seams read it to select the account's proxy

  // Proxy selected once per account (memoized), used for project resolution and every endpoint fetch
  // (one selectForAccount per acquired account).
  function proxyForAccount(accountId) {
    if (!proxyByAccount.has(accountId)) {
      proxyByAccount.set(accountId, proxyManager.selectForAccount(accountId, PROVIDER_ID) || null);
    }
    return proxyByAccount.get(accountId);
  }

  // jsAcquire: await manager.acquire(lane), null when nothing is acquired. Carries the full account
  // object so the orchestrator's resolveProjectId/syntheticProjectFor can read and mutate account.meta.
  const jsAcquire = async (lane) => {
    const acquired = await manager.acquire(lane);
    if (!acquired || !acquired.account) return null;
    currentAccountId = acquired.account.id;
    return JSON.stringify({ accountId: acquired.account.id, access: acquired.access || "", account: acquired.account });
  };

  // jsPreparer: calls the Java prepare export prepareAntigravityRequestProd (via prepareViaJava).
  // Retains the prepared {request, init} host-side and hands Java only an opaque requestRef plus the
  // response-transform params. A prepare throw returns null, which the JsRequestPreparerBridge
  // re-raises so the orchestrator skips the endpoint. Threads config.claude_tool_hardening /
  // claude_prompt_auto_caching / cli_first through to the model resolver.
  const jsPreparer = (url, bodyText2, method, headersJson, access, projectId, endpoint, headerStyle, accountJson) => {
    const account = safeJsonParse(accountJson, {});
    const fingerprint = (account.meta && account.meta.fingerprint) ?? null;
    let prepared;
    try {
      prepared = prepareViaJava(
        orchestrator, url, method, headersJson, bodyText2, access, projectId, endpoint, headerStyle, fingerprint,
        config.claude_tool_hardening, config.claude_prompt_auto_caching, cliFirst,
      );
    } catch (error) {
      log("prepare failed: " + error);
      return null;
    }
    const requestRef = preparedRequests.push(prepared) - 1;
    return JSON.stringify({
      requestRef,
      params: {
        requestedModel: prepared.requestedModel,
        projectId: prepared.projectId,
        endpoint: prepared.endpoint,
        effectiveModel: prepared.effectiveModel,
        sessionId: prepared.sessionId,
        streaming: prepared.streaming,
      },
    });
  };

  // jsExec: pure transport, lifted into core-auth's proxiedFetch (proxy-then-direct-retry, sharing the
  // account's already-resolved proxy so selectForAccount is still called at most once per account per
  // request, matching proxyForAccount's own memoization). Applies config.request_jitter_max_ms's
  // pre-fetch delay, then on a rate-limit response extracts {errorMessage, errorReason} (unwrapping the
  // cloudcode-pa [{error}] array). Retains the live Response host-side; no body bytes cross to Java. The
  // rate-limit reset regex, classification, and reporting stay the orchestrator's.
  const jsExec = async (accountId, preparedRefJson) => {
    const requestRef = safeJsonParse(preparedRefJson, -1);
    const prepared = preparedRequests[requestRef];
    if (!prepared) return JSON.stringify({ status: 0, ok: false, transportFailed: true, attemptRef: -1, proxyUsed: false });

    const proxyUrl = proxyForAccount(accountId);
    await applyRequestJitter();

    const shimProxyManager = {
      selectForAccount: () => proxyUrl,
      reportResult: (url, ok, elapsedMs) => proxyManager.reportResult(url, ok, elapsedMs),
    };
    const { response, proxyUsed: proxiedUsed, transportFailed } = await proxiedFetch(prepared.request, prepared.init, {
      proxyManager: shimProxyManager, log,
    });
    if (transportFailed) {
      return JSON.stringify({ status: 0, ok: false, transportFailed: true, attemptRef: -1, proxyUsed: proxiedUsed });
    }

    if (!response.ok) {
      let snippet = "";
      try { snippet = (await response.clone().text()).slice(0, 300); } catch {}
      log("antigravity response " + response.status + " from " + prepared.endpoint + (snippet ? " body: " + snippet : ""));
    }

    const attemptRef = responses.push(response) - 1;
    const proxyUsed = proxiedUsed; // gates the proxy rate-limit re-fire

    if (isRateLimitStatus(response.status)) {
      let message, reason;
      try {
        let j = await response.clone().json();
        if (Array.isArray(j)) j = j[0]; // cloudcode-pa returns [{error}] for capacity 429s
        message = j && j.error && j.error.message;
        reason = j && j.error && (j.error.status || j.error.reason);
      } catch {}
      return JSON.stringify({
        status: response.status, ok: false, transportFailed: false, attemptRef,
        errorMessage: message ?? null, errorReason: reason ?? null, proxyUsed,
      });
    }
    if (response.ok) {
      return JSON.stringify({ status: response.status, ok: true, transportFailed: false, attemptRef, proxyUsed });
    }
    // Non-ok, non-rate-limit (sandbox 403 etc.), kept as a fallback (the orchestrator never lets it
    // mask a real rate-limit).
    return JSON.stringify({ status: response.status, ok: false, transportFailed: false, attemptRef, proxyUsed });
  };

  // jsLoad / jsOnboard: the host project-context fetch loops (project.ts). The orchestrator's
  // resolveProjectId passes proxy=null (host-owned), so select the acquired account's proxy here.
  const jsLoad = async (accessToken, projectId, _proxy) => {
    const payload = await loadManagedProject(accessToken, projectId || undefined, proxyForAccount(currentAccountId) || undefined);
    return payload ? JSON.stringify(payload) : null;
  };
  const jsOnboard = async (accessToken, tierId, projectId, _proxy) => {
    const managedId = await onboardManagedProject(accessToken, tierId, projectId || undefined, undefined, undefined, proxyForAccount(currentAccountId) || undefined);
    return managedId ? JSON.stringify(managedId) : null;
  };

  // jsAccountOps: the synchronous account-reporting callbacks over the shared `manager`. The proxy
  // reportRateLimit re-fire lives here: the orchestrator computes ipSuspected (accountHasQuota over the
  // fresh list()); the host applies it to the proxy it chose.
  const jsAccountOps = {
    nextAvailableAt(lane) {
      const next = manager.nextAvailableAt(lane);
      return JSON.stringify(next == null ? null : next);
    },
    reportError(accountId, lane, attempt, message) {
      manager.reportError(accountId, lane, attempt, message);
    },
    reportRateLimit(accountId, lane, resetMs) {
      manager.reportRateLimit(accountId, lane, resetMs);
    },
    reportSuccess(accountId) {
      manager.reportSuccess(accountId);
    },
    reportProxyRateLimit(accountId, ipSuspected) {
      const proxyUrl = proxyByAccount.get(accountId);
      if (proxyUrl) proxyManager.reportRateLimit(proxyUrl, { ipSuspected });
    },
    list() {
      return JSON.stringify(manager.list());
    },
    mutate(accountId, updatedAccountJson) {
      const updated = safeJsonParse(updatedAccountJson, null);
      if (!updated) return;
      // The orchestrator only ever sets meta.syntheticProjectId / meta.managedProjectId; copy exactly
      // those so nothing else is disturbed.
      manager.mutate(accountId, (a) => {
        if (updated.meta) {
          a.meta = a.meta || {};
          if ("syntheticProjectId" in updated.meta) a.meta.syntheticProjectId = updated.meta.syntheticProjectId;
          if ("managedProjectId" in updated.meta) a.meta.managedProjectId = updated.meta.managedProjectId;
        }
      });
    },
  };

  // Real entropy seams (module-level jsRandom/jsUuid above): the orchestrator's Random SPI and the
  // IdGenerator that feeds generateSyntheticProjectId use production Math.random / crypto.randomUUID,
  // so each account lacking a discovered managed project mints a unique synthetic x-goog-user-project,
  // and the MODEL_CAPACITY cooldown keeps its +/-15s jitter.
  const decisionJson = await handleAntigravityRequestAsync(
    inputsJson, configJson, jsExec, jsAcquire, jsAccountOps, jsLoad, jsOnboard, jsPreparer, autoCandidatesJson,
    jsRandom, jsUuid,
  );
  const decision = JSON.parse(decisionJson);
  return materializeDecision(decision, responses, log, orchestrator);
}

// Routes SERVE's response transform through Java. response.ok is always true here (the orchestrator
// only ever emits SERVE on a 2xx attempt), so the non-ok error-body branch is intentionally not
// reproduced.
export async function transformServeViaJava(orchestrator, response, p) {
  const contentType = response.headers.get("content-type") ?? "";
  const isJsonResponse = contentType.includes("application/json");
  const isEventStreamResponse = contentType.includes("text/event-stream");
  if (!isJsonResponse && !isEventStreamResponse) return response; // non-JSON, non-SSE passes through unchanged

  const debugText = computeDebugText();

  if (p.streaming && isEventStreamResponse && response.body) {
    // Headers pass through unchanged (no usage-header mutation on this path).
    const headers = new Headers(response.headers);
    const cacheSignatures = shouldCacheThinkingSignatures(p.effectiveModel);
    // Gemini-3 models get the thought-dedup seam; other models pass null.
    const thoughtDedupSeam = p.effectiveModel && isGemini3Model(p.effectiveModel) ? jsThoughtDedup : null;
    const sseHandle = orchestrator.newResponseSseTransformer(
      p.sessionId ?? "", debugText, cacheSignatures,
      jsSignatureStore, jsCacheSignatureFn, jsImageSink, thoughtDedupSeam,
    );
    const stream = response.body.pipeThrough(makeResponseTransformStream(sseHandle));
    return new Response(stream, { status: response.status, statusText: response.statusText, headers });
  }

  // Buffered JSON path (parse, preview-error rewrite, usage headers, debug-inject,
  // transformThinkingParts), handled by transformServeBodyProd.
  const headersJson = JSON.stringify(Object.fromEntries(new Headers(response.headers)));
  const text = await response.text();
  const resultJson = orchestrator.transformServeBodyProd(
    text, response.status, headersJson, p.requestedModel ?? "", debugText, jsImageSink,
  );
  const result = JSON.parse(resultJson);
  return new Response(result.body, { status: result.status, statusText: response.statusText, headers: result.headers || {} });
}

// Build the final Response from a HandleDecision, one per decision kind. NO response bytes crossed
// into Java: SERVE/SERVE_RAW/BRIDGE_STREAM return the host-retained live Response.
async function materializeDecision(decision, responses, log, orchestrator) {
  switch (decision.kind) {
    case "SERVE": {
      // ok upstream response through the Java-driven transform, SSE/stream intact.
      const retained = responses[decision.attemptRef];
      if (!retained) return serveRefError();
      const p = decision.params || {};
      return await transformServeViaJava(orchestrator, retained, p);
    }
    case "SERVE_RAW": {
      // A real 429/non-ok fallback, or the transient-limit passthrough, served verbatim.
      const retained = responses[decision.attemptRef];
      return retained || serveRefError();
    }
    case "SYNTHETIC":
      // errorResponse: the no-account 503 / exhausted 502.
      return new Response(decision.body, { status: decision.status, headers: decision.headers });
    case "TERMINAL_ERROR":
      return buildTerminalError(decision.terminal);
    case "BRIDGE_STREAM": {
      // The async orchestrator surface never emits BRIDGE_STREAM (app-wire re-encoding is the
      // front-door's job). Defensive only: serve the retained response verbatim.
      const retained = responses[decision.attemptRef];
      return retained || serveRefError();
    }
    default:
      return serveRefError();
  }
}

// The two lane-accurate terminal chatErrors. Java owns the branch, the static message text, and the
// epoch; the host owns the Date.toLocaleString formatting and the chatError call.
function buildTerminalError(terminal) {
  if (!terminal) return chatError("request failed", { format: "gemini", rateLimited: true });
  if (terminal.kind === "GEMINI_CLI_EXHAUSTED") {
    return chatError(terminal.messagePrefix, { format: "gemini", rateLimited: true });
  }
  // ANTIGRAVITY_QUOTA_RESET: PREFIX + <locale date> + SUFFIX.
  const when = new Date(terminal.resetEpochMs).toLocaleString(undefined, {
    month: "short", day: "numeric", hour: "numeric", minute: "2-digit",
  });
  const message = terminal.messagePrefix + when + terminal.messageSuffix;
  return chatError(message, { format: "gemini", rateLimited: true, retryAfterMs: terminal.resetEpochMs - Date.now() });
}

function serveRefError() {
  // Defensive: a SERVE/RAW/BRIDGE ref with no retained response should be impossible (every ref comes
  // from a jsExec success). Surface a 502 rather than throwing into the host.
  return new Response(JSON.stringify({ error: { message: "internal: serve ref not retained" } }), {
    status: 502, headers: { "content-type": "application/json" },
  });
}

// Builds the canonical typed transport error (HandleIrError) for a handleIr non-2xx outcome, carrying
// the real status/headers/body so the front door can reconstruct an equivalent Response (rate-limit
// fallback / verbatim 4xx still work). The error body is error-shaped JSON, not app-wire content: the
// front-door owns app<->IR translation.
function anthropicHandleIrError(status, errorType, message, retryAfterMs) {
  const body = JSON.stringify({ type: "error", error: { type: errorType, message } });
  const headers = { "content-type": "application/json" };
  if (errorType === "rate_limit_error") headers["x-hub-rate-limited"] = "1";
  return new HandleIrError({ status, headers, body, retryAfterMs: retryAfterMs || undefined });
}

// The provider's IR-native serving path. Receives an already app-wire-decoded IR request (the
// front-door owns app<->IR translation) and runs only the IR<->upstream core: antigravity's
// thinking-budget resolution plus the neutral IR->Gemini encode, the same account-rotation/retry
// upstream call as the Gemini path (runGeminiViaJava), then decodes the upstream Gemini SSE back into
// a raw IR event stream. No app-wire (Anthropic) format code lives here. Always returns a stream:
// antigravity's upstream call is always streamGenerateContent in production, preserving true
// end-to-end streaming rather than buffering into an IrResponse.
//
// Errors (rate-limit exhaustion, no-account, transport failure) have no IR-shaped representation to
// return, so they are thrown as the canonical HandleIrError (via anthropicHandleIrError) instead of
// encoded into a Response, matching the handleIr contract, so the front door can reconstruct the real
// status/headers/body.
export async function handleIrViaJavaOrchestrator(ir, ctx) {
  const log = (ctx && ctx.log) || (() => {});
  const orchestrator = await loadOrchestrator();
  const model = (ctx && ctx.model) || "antigravity-claude-sonnet-4-6";
  const geminiBody = orchestrator.resolveThinkingBudgetAndEncodeGemini(JSON.stringify(ir), model);
  const geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":streamGenerateContent?alt=sse";
  const geminiReq = new Request(geminiUrl, { method: "POST", headers: { "content-type": "application/json" }, body: geminiBody });
  const geminiRes = await runGeminiViaJava(geminiReq, ctx, laneCliFirstFor(ctx)); // geminiUrl isn't /v1/messages -> normal Gemini path
  if (geminiRes && geminiRes.headers && geminiRes.headers.get("x-hub-chat-error")) {
    let msg = "request failed";
    try { const p = JSON.parse(await geminiRes.clone().text()); msg = (p.error && p.error.message) || msg; } catch {}
    if (geminiRes.headers.get("x-hub-rate-limited") === "1") {
      // No-account/exhaustion terminal condition: retryAfterMs comes from the x-hub-retry-after-ms
      // hint the pool/quota logic already computed (TerminalError.resetEpochMs / the account pool's
      // soonestQuotaReset).
      const retryAfterMs = Number(geminiRes.headers.get("x-hub-retry-after-ms")) || undefined;
      throw anthropicHandleIrError(429, "rate_limit_error", msg, retryAfterMs);
    }
    throw anthropicHandleIrError(geminiRes.status || 400, "invalid_request_error", msg);
  }
  if (!geminiRes || !geminiRes.ok || !geminiRes.body) {
    let detail = "";
    try { detail = geminiRes ? (await geminiRes.clone().text()).slice(0, 500) : ""; } catch {}
    log("handleIr: upstream error " + (geminiRes && geminiRes.status) + " " + detail);
    // Upstream non-2xx (SERVE_RAW / transport failure): real status carried through, verbatim detail
    // text as the message.
    const msg = detail || ("antigravity upstream error " + (geminiRes && geminiRes.status));
    throw anthropicHandleIrError((geminiRes && geminiRes.status) || 502, "api_error", msg);
  }
  return geminiRes.body.pipeThrough(makeIrStream(orchestrator.newIrStreamMapper, ir.model || model, jsIds));
}
