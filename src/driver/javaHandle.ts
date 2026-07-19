// @ts-nocheck
// The delegation shell that runs antigravity-auth's `handle` decision loop (and the account-view
// quota/catalog helpers below) through the TeaVM-compiled Java orchestrator
// (`AntigravityHandleOrchestrator` + `AntigravityProviderJs`'s other exports from :antigravity-teavm)
// instead of duplicated TS. index.ts's `handle` dynamically imports this module on the first request;
// the TeaVM ESM itself is loaded via the lazily-memoized `loadOrchestrator()` below, so the ~MB
// compiled bundle is never bundled statically and only evaluates once actually needed.
//
// Split of responsibility (mirrors AntigravityHandleOrchestrator's javadoc):
//   - The Java orchestrator owns EVERY decision: model resolve + Auto candidate walk, the
//     account/endpoint retry+rotation loop, project-context discovery control flow, the
//     status→action branching (rate-limit classify / rotate / fall-through), and the terminal
//     selection (gemini-cli vs antigravity quota-reset).
//   - This TS shell owns host I/O: the fetch + IP-proxy transport (jsExec, reproducing
//     index.ts:156-218 verbatim), account acquisition/reporting over the shared `manager`
//     (jsAcquire/jsAccountOps), the project fetch loops (jsLoad/jsOnboard = TS
//     loadManagedProject/onboardManagedProject), and building the final Response from the
//     orchestrator's decision (transformAntigravityResponse / chatError + its locale date format).
//   - NO response body ever crosses into Java: SERVE/SERVE_RAW return the RETAINED live Response
//     verbatim (SSE/stream intact); only SYNTHETIC/TERMINAL bodies are built here from the decision.
//
// Task 3 (TeaVM de-dup): `jsPreparer` now calls the Java PRODUCTION export
// `prepareAntigravityRequestProd` (see `prepareViaJava` below) instead of the TS
// `prepareAntigravityRequest`, injecting real host seams (sha256 hasher, the disk-backed signature
// cache lookup, a `defaultSignatureStore` adapter, real Math.random/crypto.randomUUID). The
// Anthropic bridge (`handleAnthropicMessagesViaJava`) calls the Java `anthropicToGemini` export
// and pipes through `javaStream.ts`'s `makeAnthropicStream` (Java `newStreamMapper`) instead of the
// old TS `anthropicToGemini`/`geminiToAnthropicStream`.
// SP-2: the Java side of that bridge (`AntigravityFormatBridge`/`AntigravityStreamMapper`) has been
// replaced by core-ir's canonical IR + AnthropicTranslator/GeminiTranslator (`AntigravityIrBridge`/
// `AntigravityGeminiSseBridge`) -- this file's own call sites (`anthropicToGemini`, `newStreamMapper`)
// are unchanged, since the TeaVM export surface kept the same names/shapes.
// Task 3c: closes the 3 Task-3 gaps. (1) `prepareAntigravityRequestProd` now takes `endpointOverride`
// directly (the `applyEndpointOverride` URL-substitution workaround is gone). (2)
// `claudeToolHardening`/`claudePromptAutoCaching` are read from the SAME `loadConfig()` the TS path
// uses and threaded through. (3) `materializeDecision`'s SERVE case now routes through the Java
// response-transform exports (`transformServeBodyProd` / `newResponseSseTransformer`, real-seamed:
// real `defaultSignatureStore`, real `processImageData`, real `cacheSignature`/`getCachedSignature`,
// real `getKeepThinking()`) instead of the retained TS `transformAntigravityResponse` — see
// javaStream.ts's `makeResponseTransformStream` for the streaming shell.

import crypto from "node:crypto";
import { proxyManager, getAutoCandidates, chatError } from "../../core-auth/dist/index.js";
import { translators } from "../../core-ir/dist/index.js";
import { manager } from "./index.js";
import { getPluginSessionId, SYNTHETIC_THINKING_PLACEHOLDER, shouldCacheThinkingSignatures } from "../plugin/request.js";
import { loadManagedProject, onboardManagedProject } from "../plugin/project.js";
import { getKeepThinking, loadConfig, DEFAULT_CONFIG } from "../plugin/config/index.js";
import { defaultSignatureStore } from "../plugin/stores/signature-store.js";
import { getCachedSignature, cacheSignature } from "../plugin/cache.js";
import { processImageData } from "../plugin/image-saver.js";
import { isGemini3Model } from "../plugin/transform-java.js";
import { makeAnthropicStream, makeIrStream, jsIds, makeResponseTransformStream } from "./javaStream.js";

// Cached once at module load — mirrors driver/index.ts:53's own `config` (the same config drives
// both paths identically; a runtime config edit needs a process restart for either path).
let config;
try { config = loadConfig(); } catch { config = DEFAULT_CONFIG; }

const PROVIDER_ID = "antigravity";

// Lazily-memoized dynamic import of the TeaVM ESM — staged to src/generated/ by core/teavm-build.mjs
// at build time and bundled (deferred) by esbuild. Exported (read-only usage) so the parity test can
// load the same memoized module instance without re-importing it.
let orchestratorPromise = null;
let orchestratorLoaded = null;   // synchronously readable once loadOrchestrator() resolves (Task 7a)
export function loadOrchestrator() {
  if (!orchestratorPromise) {
    orchestratorPromise = import("../generated/antigravity-orchestrator.teavm.js").then((m) => (orchestratorLoaded = m));
  }
  return orchestratorPromise;
}
// For callback contracts that can't await (accounts-controller.ts's synchronous status/availableAt/
// quota view) — null until the first loadOrchestrator() resolves.
export function getLoadedOrchestrator() {
  return orchestratorLoaded;
}

// Task 7a — routes fetchModels' catalog build through the Java buildCatalog export
// (AntigravityCatalog.buildAntigravityCatalog), replacing the deleted TS buildAntigravityCatalog.
export async function buildCatalogViaJava(payload) {
  const orchestrator = await loadOrchestrator();
  return JSON.parse(orchestrator.buildCatalog(JSON.stringify(payload)));
}

// Task 7b-2 — generateSyntheticProjectId's real-seam replacement (login.ts's checkAntigravityAccess
// verify-ping + accounts-controller.ts's verify() diagnostic; both ad-hoc, unpersisted ids).
export async function generateSyntheticProjectIdViaJava() {
  const orchestrator = await loadOrchestrator();
  return orchestrator.generateSyntheticProjectIdProd(jsRandom, jsUuid);
}

// Task 7b-2 — fetchModels' project-id resolution, routed through the SAME Java
// AntigravityHandleOrchestrator.resolveProjectId the live SERVE path (runGeminiViaJava) already uses
// — no re-implementation. jsLoad/jsOnboard mirror runGeminiViaJava's (project.ts's loadManagedProject/
// onboardManagedProject), fixed to the ALREADY-SELECTED `proxy` (fetchModels picks one proxy up front,
// unlike the per-attempt-account proxy the SERVE loop resolves via a closure).
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

// True when the inbound request is Claude Code's Anthropic Messages API (formerly plugin/
// anthropic-bridge.ts's isAnthropicMessages, deleted in SP-2 along with the rest of the bespoke
// Anthropic<->Gemini bridge -- this is pure request-routing, not format translation, so it stays
// TS rather than round-tripping through Java for one substring check).
function isAnthropicMessages(url) {
  return typeof url === "string" && url.indexOf("/v1/messages") !== -1;
}

// index.ts:82 — the antigravity rate-limit statuses.
function isRateLimitStatus(status) {
  return status === 429 || status === 503 || status === 529;
}

// config.request_jitter_max_ms (E-wiring) — a small random pre-request delay to desynchronize
// concurrent requests across accounts/sessions. Default 0 = disabled, no behavior change.
// Exported so request-jitter.test.ts can exercise it directly (with a mocked config) without
// standing up the full orchestrator harness.
export function sleepMs(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
export async function applyRequestJitter() {
  const maxMs = config.request_jitter_max_ms;
  if (!maxMs || maxMs <= 0) return;
  await sleepMs(Math.random() * maxMs);
}

// ---- Task 3: real host seams for prepareAntigravityRequestProd / cacheSignaturesFromResponse -----
// REAL entropy (CRITICAL-1): production Math.random / crypto.randomUUID, never baked.
const jsRandom = () => Math.random();
const jsUuid = () => crypto.randomUUID();
// request.ts:112-114's exact sha256 (Java truncates to 16 hex chars itself; this seam returns the
// full hex, matching AntigravityRequestKeys.Hasher's contract).
const jsHasher = (input) => crypto.createHash("sha256").update(input, "utf8").digest("hex");
// cache.ts's getCachedSignature -> JsCacheLookupFn (null, not undefined, on a miss).
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

// Task 3c gap 3 seams — the real response-transform seams (mirrors the prepare seams above).
// cache.ts's cacheSignature -> JsCacheSignatureFn (the on-disk signature-cache WRITE; distinct from
// jsCacheLookup above, which is the READ).
const jsCacheSignatureFn = (sessionKey, text, signature) => cacheSignature(sessionKey, text, signature);
// image-saver.ts's processImageData -> JsImageSinkFn: real fs write to ~/.opencode|.claude/generated-
// images/, returning a markdown link (never DATA_URL_SINK's fake data-URL stand-in). A missing
// mimeType/data arrives as "" (Java's bridge never sends raw null across the boundary); processImageData's
// own `mimeType || 'image/png'` / `if (!data) return null` treat "" and undefined identically.
const jsImageSink = (mimeType, base64Data) => processImageData({ mimeType: mimeType || undefined, data: base64Data || undefined }) ?? null;

// Task 3d — the Gemini-3 SSE-reconnect thought-dedup seam: a process-lifetime `Set<string>` (created
// once, never reset) feeding the Java SERVE streaming transform, mirroring the deleted TS path's own
// module-private `sessionDisplayedThinkingHashes` (request.ts, removed with the rest of the pure-TS
// decision loop — this is now the only implementation).
const javaSessionDisplayedThinkingHashes = new Set();
const jsThoughtDedup = {
  has(hash) { return javaSessionDisplayedThinkingHashes.has(hash); },
  add(hash) { javaSessionDisplayedThinkingHashes.add(hash); },
};

// request.ts's regex-extracted `rawModel` (the debug-only `requestedModel` field) — Java's prod
// export intentionally doesn't return it (only the driver-relevant fields), so it's re-derived here
// from the SAME url the export parses; this is substring extraction, not decision logic.
function extractRequestedModel(url) {
  const m = typeof url === "string" ? url.match(/\/models\/([^:]+):(\w+)/) : null;
  return m ? m[1] : undefined;
}

// transformAntigravityResponse's debugText resolution (request.ts:1735-1740), specialized to this
// call site: materializeDecision never passes debugLines (the debug-TUI transcript), matching the
// pure-TS driver's OWN call site (driver/index.ts:235-239, also debugLines-less) — so
// `isDebugTuiEnabled() && Array.isArray(debugLines) && ...` is always false here, leaving only the
// getKeepThinking() fallback. Returns "" (not undefined) for "no debug text" — the Java exports treat
// an empty string as "none", matching JS truthiness.
function computeDebugText() {
  return getKeepThinking() ? SYNTHETIC_THINKING_PLACEHOLDER : "";
}

// Calls the Java prod prepare export and reassembles the SAME result shape
// `prepareAntigravityRequest` (TS) returns, so callers (jsPreparer + the parity test) can treat them
// interchangeably. `orchestrator` is the already-resolved loadOrchestrator() module.
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

// Top-level delegated entry: mirror index.ts's routing — the Anthropic Messages bridge, else the
// Gemini decision loop. (maybeMaintainVersions already fired host-side in index.ts before the guard.)
export async function handleViaJavaOrchestrator(request, ctx) {
  if (isAnthropicMessages(request.url)) return handleAnthropicMessagesViaJava(request, ctx);
  return runGeminiViaJava(request, ctx);
}

// The Gemini-path delegation: build the orchestrator inputs, wire the host seams, run
// handleAntigravityRequestAsync, and materialize its HandleDecision into a Response. Reused verbatim
// for the inner request of the Anthropic bridge.
async function runGeminiViaJava(request, ctx) {
  const log = (ctx && ctx.log) || (() => {});
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
  // resetTimeFor track the SAME wall clock as the pure-TS path. The leaderboard stays TS —
  // getAutoCandidates is resolved host-side and passed in.
  const configJson = JSON.stringify({ platform: process.platform, arch: process.arch });
  const autoCandidatesJson = JSON.stringify(getAutoCandidates(PROVIDER_ID) || []);

  // ---- per-request host state (isolated to this call) ----------------------------------------
  const responses = [];                 // retained live Response objects, indexed by attemptRef
  const preparedRequests = [];          // retained prepared {request, init, ...}, indexed by requestRef
  const proxyByAccount = new Map();     // proxy URL selected for each account this request (index.ts:156)
  let currentAccountId = null;          // set on acquire; project seams read it to select the account's proxy

  // Proxy selected ONCE per account (memoized), used for project resolution AND every endpoint fetch,
  // reproducing index.ts:156 (one selectForAccount per acquired account).
  function proxyForAccount(accountId) {
    if (!proxyByAccount.has(accountId)) {
      proxyByAccount.set(accountId, proxyManager.selectForAccount(accountId, PROVIDER_ID) || null);
    }
    return proxyByAccount.get(accountId);
  }

  // jsAcquire — await manager.acquire(lane); null ⇔ TS `!acquired || !acquired.account` (index.ts:133-134).
  // Carries the full account map (antigravity's Acquired has a third field beyond claude's) so the
  // orchestrator's resolveProjectId/syntheticProjectFor can read + mutate account.meta.
  const jsAcquire = async (lane) => {
    const acquired = await manager.acquire(lane);
    if (!acquired || !acquired.account) return null;
    currentAccountId = acquired.account.id;
    return JSON.stringify({ accountId: acquired.account.id, access: acquired.access || "", account: acquired.account });
  };

  // jsPreparer — Task 3: calls the Java PRODUCTION export prepareAntigravityRequestProd (via
  // prepareViaJava) instead of the TS prepareAntigravityRequest. Retains the prepared {request, init}
  // host-side and hands Java only an opaque requestRef + the response-transform params. A prepare
  // throw returns null, which the JsRequestPreparerBridge re-raises so the orchestrator skips the
  // endpoint (index.ts:169).
  // Task 3c: threads config.claude_tool_hardening / claude_prompt_auto_caching through (previously
  // silently dropped). config.cli_first is threaded the same way (E-wiring): it reaches
  // AntigravityModelResolver.resolveModelForHeaderStyle's 3-arg overload via Input.cliFirst, so the
  // two-arg resolveModelWithTier(model, cliFirst) path is actually exercised with the configured
  // value instead of the hardcoded `false` every live call previously used.
  const jsPreparer = (url, bodyText2, method, headersJson, access, projectId, endpoint, headerStyle, accountJson) => {
    let account;
    try { account = accountJson ? JSON.parse(accountJson) : {}; } catch { account = {}; }
    const fingerprint = (account.meta && account.meta.fingerprint) ?? null;
    let prepared;
    try {
      prepared = prepareViaJava(
        orchestrator, url, method, headersJson, bodyText2, access, projectId, endpoint, headerStyle, fingerprint,
        config.claude_tool_hardening, config.claude_prompt_auto_caching, config.cli_first,
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

  // jsExec — pure transport, reproducing index.ts:170-218 exactly: apply the account's proxy →
  // (E-wiring) apply config.request_jitter_max_ms's pre-fetch delay → fetch → on a proxy fetch
  // error reportResult(false)+retry-direct → on direct/no-proxy error return
  // transportFailed → log the non-ok snippet → on rate-limit extract {errorMessage, errorReason}
  // (unwrapping the cloudcode-pa [{error}] array). Retains the live Response host-side; NO body bytes
  // cross to Java. The rate-limit reset regex + classification + reporting are the orchestrator's.
  const jsExec = async (accountId, preparedRefJson) => {
    let requestRef;
    try { requestRef = JSON.parse(preparedRefJson); } catch { requestRef = -1; }
    const prepared = preparedRequests[requestRef];
    if (!prepared) return JSON.stringify({ status: 0, ok: false, transportFailed: true, attemptRef: -1, proxyUsed: false });

    const proxyUrl = proxyForAccount(accountId);
    if (proxyUrl) prepared.init.proxy = proxyUrl; // Bun fetch honors .proxy

    await applyRequestJitter();

    let response;
    const started = Date.now();
    let proxyOk = false;
    try {
      response = await fetch(prepared.request, prepared.init);
      proxyOk = !!proxyUrl;
    } catch (error) {
      if (proxyUrl) {
        proxyManager.reportResult(proxyUrl, false);
        // proxy unreachable -> retry directly (a dead proxy gives no isolation anyway)
        log("fetch via proxy " + proxyUrl + " failed: " + error + " — retrying directly");
        try {
          const directInit = { ...prepared.init };
          delete directInit.proxy;
          response = await fetch(prepared.request, directInit);
        } catch (directError) {
          log("direct retry failed: " + directError);
          return JSON.stringify({ status: 0, ok: false, transportFailed: true, attemptRef: -1, proxyUsed: !!proxyUrl });
        }
      } else {
        log("fetch failed: " + error);
        return JSON.stringify({ status: 0, ok: false, transportFailed: true, attemptRef: -1, proxyUsed: false });
      }
    }
    if (proxyOk) proxyManager.reportResult(proxyUrl, true, Date.now() - started);

    if (!response.ok) {
      let snippet = "";
      try { snippet = (await response.clone().text()).slice(0, 300); } catch {}
      log("antigravity response " + response.status + " from " + prepared.endpoint + (snippet ? " body: " + snippet : ""));
    }

    const attemptRef = responses.push(response) - 1;
    const proxyUsed = !!proxyUrl; // index.ts:213 gates the proxy rate-limit re-fire on `if (proxyUrl)`

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
    // Non-ok, non-rate-limit (sandbox 403 etc.) — keep as a fallback (the orchestrator never lets it
    // mask a real rate-limit).
    return JSON.stringify({ status: response.status, ok: false, transportFailed: false, attemptRef, proxyUsed });
  };

  // jsLoad / jsOnboard — the host project-context fetch loops (project.ts). The orchestrator's
  // resolveProjectId passes proxy=null (host-owned), so select the acquired account's proxy here.
  const jsLoad = async (accessToken, projectId, _proxy) => {
    const payload = await loadManagedProject(accessToken, projectId || undefined, proxyForAccount(currentAccountId) || undefined);
    return payload ? JSON.stringify(payload) : null;
  };
  const jsOnboard = async (accessToken, tierId, projectId, _proxy) => {
    const managedId = await onboardManagedProject(accessToken, tierId, projectId || undefined, undefined, undefined, proxyForAccount(currentAccountId) || undefined);
    return managedId ? JSON.stringify(managedId) : null;
  };

  // jsAccountOps — the synchronous account-reporting callbacks over the real shared `manager`. The
  // proxy reportRateLimit RE-FIRE (index.ts:213-216) is here: the orchestrator computed ipSuspected
  // (via the ported accountHasQuota over the fresh list()); the host applies it to the proxy it chose.
  const jsAccountOps = {
    nextAvailableAt(lane) {
      const next = manager.nextAvailableAt(lane);
      return JSON.stringify(next == null ? null : next);
    },
    reportError(accountId, attempt, message) {
      manager.reportError(accountId, attempt, message);
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
      let updated;
      try { updated = JSON.parse(updatedAccountJson); } catch { updated = null; }
      if (!updated) return;
      // The orchestrator only ever sets meta.syntheticProjectId / meta.managedProjectId
      // (index.ts:101,115); copy exactly those so nothing else is disturbed.
      manager.mutate(accountId, (a) => {
        if (updated.meta) {
          a.meta = a.meta || {};
          if ("syntheticProjectId" in updated.meta) a.meta.syntheticProjectId = updated.meta.syntheticProjectId;
          if ("managedProjectId" in updated.meta) a.meta.managedProjectId = updated.meta.managedProjectId;
        }
      });
    },
  };

  // REAL entropy seams (CRITICAL-1, module-level jsRandom/jsUuid above): the orchestrator's Random
  // SPI + the IdGenerator that feeds generateSyntheticProjectId must be production Math.random /
  // crypto.randomUUID, so each account lacking a discovered managed project mints a UNIQUE synthetic
  // x-goog-user-project (never the baked "swift-spark-00000" constant). This also restores the ±15s
  // MODEL_CAPACITY cooldown jitter.
  const decisionJson = await handleAntigravityRequestAsync(
    inputsJson, configJson, jsExec, jsAcquire, jsAccountOps, jsLoad, jsOnboard, jsPreparer, autoCandidatesJson,
    jsRandom, jsUuid,
  );
  const decision = JSON.parse(decisionJson);
  return materializeDecision(decision, responses, log, orchestrator);
}

// Task 3c — routes SERVE's response transform through Java (transformAntigravityResponse's ok-branch,
// request.ts:1711-1926). response.ok is always true here (the orchestrator only ever emits SERVE on a
// 2xx attempt — confirmed by AntigravityResponseTransform's own javadoc and this module's TS twin call
// site, driver/index.ts:233-239, which is likewise gated by `if (response.ok)`), so the giant
// !response.ok branch (request.ts:1788-1861, error-body debug-info/recovery/retry-after handling) is
// unreachable here and intentionally not reproduced.
export async function transformServeViaJava(orchestrator, response, p) {
  const contentType = response.headers.get("content-type") ?? "";
  const isJsonResponse = contentType.includes("application/json");
  const isEventStreamResponse = contentType.includes("text/event-stream");
  if (!isJsonResponse && !isEventStreamResponse) return response; // request.ts:1743-1748 passthrough

  const debugText = computeDebugText();

  if (p.streaming && isEventStreamResponse && response.body) {
    // request.ts:1751-1780 — headers pass through UNCHANGED (no usage-header mutation on this path).
    const headers = new Headers(response.headers);
    const cacheSignatures = shouldCacheThinkingSignatures(p.effectiveModel);
    // request.ts:1770's exact gate: `effectiveModel && isGemini3Model(effectiveModel) ? <set> : undefined`.
    const thoughtDedupSeam = p.effectiveModel && isGemini3Model(p.effectiveModel) ? jsThoughtDedup : null;
    const sseHandle = orchestrator.newResponseSseTransformer(
      p.sessionId ?? "", debugText, cacheSignatures,
      jsSignatureStore, jsCacheSignatureFn, jsImageSink, thoughtDedupSeam,
    );
    const stream = response.body.pipeThrough(makeResponseTransformStream(sseHandle));
    return new Response(stream, { status: response.status, statusText: response.statusText, headers });
  }

  // request.ts:1782-1926 — buffered JSON path (parse -> preview-error rewrite -> usage headers ->
  // debug-inject -> transformThinkingParts), fully reproduced by transformServeBodyProd.
  const headersJson = JSON.stringify(Object.fromEntries(new Headers(response.headers)));
  const text = await response.text();
  const resultJson = orchestrator.transformServeBodyProd(
    text, response.status, headersJson, p.requestedModel ?? "", debugText, jsImageSink,
  );
  const result = JSON.parse(resultJson);
  return new Response(result.body, { status: result.status, statusText: response.statusText, headers: result.headers || {} });
}

// Build the final Response from a HandleDecision — the 5 decision kinds. NO response bytes crossed
// into Java: SERVE/SERVE_RAW/BRIDGE_STREAM return the host-retained live Response.
async function materializeDecision(decision, responses, log, orchestrator) {
  switch (decision.kind) {
    case "SERVE": {
      // ok upstream response through the Java-driven transform (Task 3c), SSE/stream intact.
      const retained = responses[decision.attemptRef];
      if (!retained) return serveRefError();
      const p = decision.params || {};
      return await transformServeViaJava(orchestrator, retained, p);
    }
    case "SERVE_RAW": {
      // a real 429/non-ok fallback, or the transient-limit passthrough (index.ts:236/442) — verbatim.
      const retained = responses[decision.attemptRef];
      return retained || serveRefError();
    }
    case "SYNTHETIC":
      // errorResponse: the no-account 503 / exhausted 502 (index.ts:121-123,145,242,371,397,457).
      return new Response(decision.body, { status: decision.status, headers: decision.headers });
    case "TERMINAL_ERROR":
      return buildTerminalError(decision.terminal);
    case "BRIDGE_STREAM": {
      // The exported orchestrator.handle() never emits BRIDGE_STREAM — it is produced only by
      // classifyAnthropicResult, which the T7g1 async surface does not expose. The Anthropic-messages
      // bridge (incl. the geminiToAnthropicStream pipe = index.ts:347) is therefore reproduced
      // host-side in handleAnthropicMessagesViaJava. This branch is defensive/forward-compat: serve
      // the retained response verbatim.
      const retained = responses[decision.attemptRef];
      return retained || serveRefError();
    }
    default:
      return serveRefError();
  }
}

// index.ts:432/438-440 — the two lane-accurate terminal chatErrors. Java owns the branch + the static
// message text + the epoch; the host owns the Date.toLocaleString formatting + the chatError call.
function buildTerminalError(terminal) {
  if (!terminal) return chatError("request failed", { format: "gemini", rateLimited: true });
  if (terminal.kind === "GEMINI_CLI_EXHAUSTED") {
    return chatError(terminal.messagePrefix, { format: "gemini", rateLimited: true });
  }
  // ANTIGRAVITY_QUOTA_RESET: PREFIX + <locale date> + SUFFIX, byte-matching index.ts:439-440.
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

// A thrown handleIr error carries enough classification (status/errorType/rate-limit retry hint)
// for a caller that wants to rebuild a wire-shaped error response (handleAnthropicMessagesViaJava
// below) to do so byte-exactly -- while a generic caller (e.g. core-proxy's Router) can still just
// read `.message` and treat any handleIr rejection as a flat failure, per the shared contract.
function makeHandleIrError(message, { status, errorType, retryAfterMs } = {}) {
  const error = new Error(message);
  error.status = status;
  error.errorType = errorType;
  if (retryAfterMs) error.retryAfterMs = retryAfterMs;
  return error;
}

// SP-3 T2: the IR-native alternative to handleAnthropicMessagesViaJava below -- receives an
// already app-wire-decoded IR request (the caller already ran translators.anthropic.decodeRequest)
// and runs ONLY the IR<->Gemini core: antigravity's thinking-budget resolution + the neutral
// IR->Gemini encode, the SAME account-rotation/retry upstream call as the Gemini path
// (runGeminiViaJava, untouched), then decodes the upstream Gemini SSE back into a raw IR event
// stream (no Anthropic re-encoding here — that is now the caller's job). Always returns a stream:
// antigravity's upstream call is always streamGenerateContent in production, so this preserves true
// end-to-end streaming rather than buffering into an IrResponse.
//
// Errors (rate-limit exhaustion, no-account, transport failure, ...) have no IR-shaped
// representation to return, so they are thrown instead of encoded into a Response (matching the
// Java Provider SPI's handleIr contract) -- enriched via makeHandleIrError so handle()'s own thin
// wrapper can still rebuild the exact legacy error shape (see its catch block below).
export async function handleIrViaJavaOrchestrator(ir, ctx) {
  const log = (ctx && ctx.log) || (() => {});
  const orchestrator = await loadOrchestrator();
  const model = (ctx && ctx.model) || "antigravity-claude-sonnet-4-6";
  const geminiBody = orchestrator.resolveThinkingBudgetAndEncodeGemini(JSON.stringify(ir), model);
  const geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":streamGenerateContent?alt=sse";
  const geminiReq = new Request(geminiUrl, { method: "POST", headers: { "content-type": "application/json" }, body: geminiBody });
  const geminiRes = await runGeminiViaJava(geminiReq, ctx); // geminiUrl isn't /v1/messages -> normal Gemini path
  if (geminiRes && geminiRes.headers && geminiRes.headers.get("x-hub-chat-error")) {
    let msg = "request failed";
    try { const p = JSON.parse(await geminiRes.clone().text()); msg = (p.error && p.error.message) || msg; } catch {}
    if (geminiRes.headers.get("x-hub-rate-limited") === "1") {
      throw makeHandleIrError(msg, {
        status: 429, errorType: "rate_limit_error", retryAfterMs: geminiRes.headers.get("x-hub-retry-after-ms"),
      });
    }
    throw makeHandleIrError(msg, { status: geminiRes.status || 400, errorType: "invalid_request_error" });
  }
  if (!geminiRes || !geminiRes.ok || !geminiRes.body) {
    let detail = "";
    try { detail = geminiRes ? (await geminiRes.clone().text()).slice(0, 500) : ""; } catch {}
    log("handleIr: upstream error " + (geminiRes && geminiRes.status) + " " + detail);
    throw makeHandleIrError(detail || ("antigravity upstream error " + (geminiRes && geminiRes.status)), {
      status: (geminiRes && geminiRes.status) || 502, errorType: "api_error",
    });
  }
  return geminiRes.body.pipeThrough(makeIrStream(orchestrator.newIrStreamMapper, ir.model || model, jsIds));
}

// Anthropic Messages bridge (index.ts:304-349) — now a THIN wrapper (SP-3 T2): decode the inbound
// Anthropic body into IR via core-ir's own translator (the generic app<->IR step, no antigravity
// business logic), delegate the whole IR<->Gemini core to handleIrViaJavaOrchestrator above, then
// re-encode the returned IR event stream to Anthropic SSE via core-ir's encodeStream. All the
// classification (chatError passthrough → rate_limit_error 429 / invalid_request_error, the
// api_error path) and the x-hub-* header handling stay host-side, byte-exact (rebuilt from the
// thrown error's status/errorType/retryAfterMs) — only the Gemini-translation-plus-encode logic
// that used to live inline here moved into the shared handleIr core.
async function handleAnthropicMessagesViaJava(request, ctx) {
  const log = (ctx && ctx.log) || (() => {});
  let anthropicBodyText;
  try { anthropicBodyText = (await request.clone().text()) || "{}"; } catch { anthropicBodyText = "{}"; }
  const ir = await translators.anthropic.decodeRequest(anthropicBodyText);

  let irEventStream;
  try {
    irEventStream = await handleIrViaJavaOrchestrator(ir, ctx);
  } catch (error) {
    const msg = (error && error.message) || "request failed";
    log("anthropic bridge: handleIr failed: " + msg);
    const status = (error && error.status) || 502;
    const errorType = (error && error.errorType) || "api_error";
    const headers = { "content-type": "application/json" };
    if (errorType === "rate_limit_error") {
      headers["x-hub-rate-limited"] = "1";
      if (error.retryAfterMs) headers["x-hub-retry-after-ms"] = error.retryAfterMs;
    }
    return new Response(JSON.stringify({ type: "error", error: { type: errorType, message: msg } }), { status, headers });
  }

  const encodeStream = await translators.anthropic.encodeStream();
  const stream = irEventStream.pipeThrough(encodeStream).pipeThrough(new TextEncoderStream());
  return new Response(stream, { status: 200, headers: { "content-type": "text/event-stream", "cache-control": "no-cache", "connection": "keep-alive" } });
}
