// @ts-nocheck
// The antigravity driver: a thin object on top of core-auth. core-auth owns
// account storage, selection, token refresh, and rate-limit/cooldown state; this
// driver owns only the antigravity-specific request transform + endpoint dispatch,
// reusing the existing plugin/request + plugin/project + plugin/transform code.

import { defineProvider, AccountManager, proxyManager, getAutoCandidates, notify, chatError } from "../../core-auth/dist/index.js";
import { prepareAntigravityRequest, transformAntigravityResponse, generateSyntheticProjectId } from "../plugin/request.js";
import { anthropicToGemini, geminiToAnthropicStream, isAnthropicMessages } from "../plugin/anthropic-bridge.js";
import { ensureProjectContext } from "../plugin/project.js";
import { fetchAvailableModels, buildAntigravityCatalog } from "../plugin/models-fetch.js";
import { refreshVersions, driftVersion } from "../plugin/versions.js";
import { formatRefreshParts, parseRefreshParts } from "../plugin/auth.js";
import { ANTIGRAVITY_ENDPOINT_FALLBACKS, ANTIGRAVITY_ENDPOINT_PROD } from "../constants.js";
import { models } from "./models.js";
import { oauthConfig } from "./config.js";
import { laneFor, headerStyleFor, parseRateLimitReason, resetTimeFor } from "./lanes.js";
import { login, loginFlow } from "./login.js";
import { createAntigravityAccounts } from "./accounts-controller.js";
import { getConfigValue, setConfigValue, loadConfig, initRuntimeConfig, DEFAULT_CONFIG } from "../plugin/config/index.js";
import { initializeDebug } from "../plugin/debug.js";

const PROVIDER_ID = "antigravity";
const MAX_ATTEMPTS = 6;   // total account/endpoint attempts before giving up
const lastAccountByLane = {};   // lane -> last account id, to notify only on real rotation

// Soonest quota-pool reset (epoch ms) among EXHAUSTED pools, from the last-fetched
// cachedQuota. This is the real "try again" time — the 429 retry-after is a short
// backoff, not the quota refill (which can be days out). 0 when unknown.
function soonestQuotaReset() {
  let soonest = 0;
  for (const a of manager.list()) {
    const cq = a.meta && a.meta.cachedQuota;
    if (!cq) continue;
    for (const label of Object.keys(cq)) {
      const q = cq[label];
      if (!q || !q.resetTime) continue;
      if (q.remainingFraction === 0 || q.remainingFraction === undefined) {
        const t = Date.parse(q.resetTime);
        if (Number.isFinite(t) && (!soonest || t < soonest)) soonest = t;
      }
    }
  }
  return soonest;
}

// User config, loaded once at startup (changes apply on restart). Only the handful
// of keys actually consumed by this provider are wired below — account selection
// (core-auth's engine), the Claude request flags passed into prepareAntigravityRequest,
// and keep_thinking (read by the request transform via getKeepThinking). The other
// historical AntigravityConfig keys have no consumer here, so the settings UI omits them.
let config;
try { config = loadConfig(process.cwd()); } catch { config = DEFAULT_CONFIG; }
initRuntimeConfig(config);   // so getKeepThinking() in the request transform reads keep_thinking
initializeDebug(config);     // enables the log.debug(...) calls in request/project (debug, debug_tui, log_dir)

// core-auth account engine. The driver availability hook keeps antigravity's
// "skip accounts pending Google verification" behavior without leaking it into core.
const manager = new AccountManager(PROVIDER_ID, {
  selection: config.account_selection_strategy || "hybrid",
  oauth: oauthConfig(),
  // transient-error cooldown (AccountManager.reportError -> calculateBackoffMs)
  backoff: { baseMs: (config.default_retry_after_seconds || 60) * 1000, maxMs: (config.max_backoff_seconds || 60) * 1000 },
  isAvailable: (account) => !(account.meta && account.meta.verificationRequired),
});

// reconstruct the OAuthAuthDetails the existing project/transform code expects;
// the legacy refresh string packs the project ids that ensureProjectContext reads.
function buildAuth(account, access) {
  const meta = account.meta || {};
  return {
    type: "oauth",
    access,
    expires: account.expires,
    refresh: formatRefreshParts({ refreshToken: account.refresh, projectId: meta.projectId, managedProjectId: meta.managedProjectId }),
  };
}

function endpointsFor(headerStyle) {
  return headerStyle === "gemini-cli" ? [ANTIGRAVITY_ENDPOINT_PROD] : [...ANTIGRAVITY_ENDPOINT_FALLBACKS];
}

function isRateLimitStatus(status) {
  return status === 429 || status === 503 || status === 529;
}

// model id without depending on the (currently broken) url-helpers module
function modelFromRequest(url, bodyText, ctxModel) {
  if (ctxModel) return ctxModel;
  const match = typeof url === "string" && url.match(/\/models\/([^:/?]+)/);
  if (match) return decodeURIComponent(match[1]);
  try { const parsed = JSON.parse(bodyText || "{}"); if (parsed.model) return parsed.model; } catch {}
  return "antigravity-auto";
}

// a stable per-account project id, so accounts without a discovered managed
// project never share the same x-goog-user-project (which would correlate them)
function syntheticProjectFor(account) {
  let synthetic = account.meta && account.meta.syntheticProjectId;
  if (!synthetic) {
    synthetic = generateSyntheticProjectId();
    manager.mutate(account.id, (a) => { a.meta = a.meta || {}; a.meta.syntheticProjectId = synthetic; });
  }
  return synthetic;
}

async function resolveProjectId(account, access, log, proxy) {
  const meta = account.meta || {};
  const fallbackProjectId = syntheticProjectFor(account);
  let projectId = meta.managedProjectId || meta.projectId || "";
  try {
    const result = await ensureProjectContext(buildAuth(account, access), { proxy, fallbackProjectId });
    if (result && result.effectiveProjectId) projectId = result.effectiveProjectId;
    const discovered = parseRefreshParts(result.auth.refresh).managedProjectId;
    if (discovered && discovered !== meta.managedProjectId) {
      manager.mutate(account.id, (a) => { a.meta = a.meta || {}; a.meta.managedProjectId = discovered; });
    }
  } catch (error) { log("ensureProjectContext failed: " + error); }
  return projectId || fallbackProjectId;
}

function errorResponse(status, message) {
  return new Response(JSON.stringify({ error: { message } }), { status, headers: { "content-type": "application/json" } });
}

// Run one model through the account/endpoint attempt loop. Returns the upstream
// response (transformed on success); a rate-limit status means "all accounts for
// this model's lane are spent" so the Auto caller can fall through to the next.
async function attemptModel(model, url, init, ctx, log) {
  const lane = laneFor(model);
  const headerStyle = headerStyleFor(model);
  let lastResponse = null;
  for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
    const acquired = await manager.acquire(lane);
    if (!acquired || !acquired.account) {
      // No account free for this lane — almost always its quota pool is spent.
      const next = manager.nextAvailableAt(lane);
      const secs = next ? Math.max(0, Math.round((next - Date.now()) / 1000)) : 0;
      const msg = secs > 0
        ? `${lane} quota exhausted — resets in ~${secs}s. Pick another model or use Auto (it falls through to a free pool).`
        : `No available antigravity account for lane ${lane}.`;
      // No toast here: the per-lane 503 lets Auto fall through to the next pool, and
      // the final give-up already surfaces a clean chat error (handle() -> chatError)
      // with the real quota reset. A toast here would double up and show the short
      // 429 back-off time (nextAvailableAt), not the actual reset — the wrong number.
      return errorResponse(503, msg);
    }
    const account = acquired.account;
    // Tell the user which account is serving — on first use of a lane and whenever it
    // switches. Deduped by last-account-per-lane so repeated same-account requests
    // don't spam a notification every turn. Message: provider · email (N/total) · pool.
    if (lastAccountByLane[lane] !== account.id) {
      const accts = manager.list();
      const idx = accts.findIndex((a) => a.id === account.id);
      const who = account.email || account.id;
      const pos = idx >= 0 ? `${idx + 1}/${accts.length}` : `?/${accts.length}`;
      notify(`Antigravity · ${who} (account ${pos}) · pool: ${lane}`, "info");
    }
    lastAccountByLane[lane] = account.id;
    const access = acquired.access;
    if (!access) { manager.reportError(account.id, attempt, "missing access token"); continue; }

    const proxyUrl = proxyManager.selectForAccount(account.id);
    const projectId = await resolveProjectId(account, access, log, proxyUrl);

    let rateLimited = false;
    for (const endpoint of endpointsFor(headerStyle)) {
      let prepared;
      try {
        prepared = prepareAntigravityRequest(url, init, access, projectId, endpoint, headerStyle, false, {
          fingerprint: account.meta && account.meta.fingerprint,
          claudeToolHardening: config.claude_tool_hardening,
          claudePromptAutoCaching: config.claude_prompt_auto_caching,
          debugGeminiPayloads: config.debug_gemini_payloads,
        });
      } catch (error) { log("prepare failed: " + error); continue; }
      if (proxyUrl) prepared.init.proxy = proxyUrl;   // Bun fetch honors .proxy

      let response;
      let proxyOk = false;
      const started = Date.now();
      try { response = await fetch(prepared.request, prepared.init); proxyOk = !!proxyUrl; }
      catch (error) {
        if (proxyUrl) {
          proxyManager.reportResult(proxyUrl, false);
          // proxy unreachable -> retry this request directly (a dead proxy gives
          // no isolation anyway, and otherwise every account/attempt fails).
          log("fetch via proxy " + proxyUrl + " failed: " + error + " — retrying directly");
          try {
            const directInit = { ...prepared.init };
            delete directInit.proxy;
            response = await fetch(prepared.request, directInit);
          } catch (directError) { log("direct retry failed: " + directError); continue; }
        } else { log("fetch failed: " + error); continue; }
      }
      if (proxyOk) proxyManager.reportResult(proxyUrl, true, Date.now() - started);

      if (!response.ok) {
        let snippet = "";
        try { snippet = (await response.clone().text()).slice(0, 300); } catch {}
        log("antigravity response " + response.status + " from " + endpoint + (snippet ? " body: " + snippet : ""));
      }

      if (isRateLimitStatus(response.status)) {
        rateLimited = true;
        lastResponse = response;
        let reason, message;
        try {
          let j = await response.clone().json();
          if (Array.isArray(j)) j = j[0];   // cloudcode-pa returns [{error}] for capacity 429s
          message = j && j.error && j.error.message;
          reason = j && j.error && (j.error.status || j.error.reason);
        } catch {}
        const parsed = parseRateLimitReason(reason, message, response.status);
        // honor the server's stated reset ("...reset after 38s") so a short rolling
        // window (e.g. the Gemini CLI free pool) isn't over-blocked by our backoff.
        const retryMatch = message && /reset(?:s)?\s+(?:after|in)\s+(\d+)\s*s/i.exec(message);
        const retryAfterMs = retryMatch ? parseInt(retryMatch[1], 10) * 1000 : 0;
        manager.reportRateLimit(account.id, lane, resetTimeFor(parsed, attempt, retryAfterMs));
        if (proxyUrl) proxyManager.reportRateLimit(proxyUrl);   // possible IP rate-limit -> penalize the proxy
        continue;   // next endpoint, then rotate account
      }

      if (response.ok) {
        manager.reportSuccess(account.id);
        return await transformAntigravityResponse(
          response, prepared.streaming, null,
          prepared.requestedModel, prepared.projectId, prepared.endpoint,
          prepared.effectiveModel, prepared.sessionId,
        );
      }

      // Non-ok, non-rate-limit (e.g. 403 "no valid license" from a sandbox
      // endpoint the account isn't provisioned for): keep the response and try
      // the next endpoint. Only the last endpoint's error is surfaced.
      lastResponse = response;
      continue;
    }

    if (!rateLimited) break;
  }
  return lastResponse || errorResponse(502, "antigravity request failed after " + MAX_ATTEMPTS + " attempts");
}

function isAutoModel(model) {
  const stripped = String(model || "").replace(/^antigravity-/i, "");
  return stripped === "auto" || stripped.startsWith("auto-");
}

function rewriteModelInUrl(url, model) {
  return String(url).replace(/\/models\/[^:/?]+/, "/models/" + model);
}

// Claude Code sends the Anthropic Messages API (/v1/messages) through the loader
// proxy; bridge it to the Gemini format cloudcode-pa speaks (and translate the
// streamed response back). Non-Anthropic (OpenCode/Gemini) requests fall through.
async function handleAnthropicMessages(request, ctx) {
  const log = (ctx && ctx.log) || (() => {});
  let anthropicBody;
  try { anthropicBody = JSON.parse((await request.clone().text()) || "{}"); } catch { anthropicBody = {}; }
  const model = (ctx && ctx.model) || "antigravity-claude-sonnet-4-6";
  const geminiBody = anthropicToGemini(anthropicBody);
  const geminiUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":streamGenerateContent?alt=sse";
  const geminiReq = new Request(geminiUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(geminiBody) });
  const geminiRes = await handle(geminiReq, ctx);   // geminiUrl isn't /v1/messages -> normal Gemini path, no recursion
  // A terminal chatError is already a proper Anthropic error (HTTP 4xx + {error:{message}}).
  // Pass it straight through — Claude Code parses the status + error.message into a clean
  // "API Error: <message>". Do NOT run it through the Gemini->Anthropic translator or the
  // api_error re-wrap below (that double-wrapped it and leaked the raw JSON), and do NOT
  // turn it into a 200 SSE (Claude then reports "empty/malformed HTTP 200").
  if (geminiRes && geminiRes.headers && geminiRes.headers.get("x-hub-chat-error")) {
    // route() produced a Gemini-format terminal error (for opencode). Convert it to the
    // Anthropic shape so Claude Code renders a clean "API Error: <message>".
    let msg = "request failed";
    try { const p = JSON.parse(await geminiRes.clone().text()); msg = (p.error && p.error.message) || msg; } catch {}
    return new Response(
      JSON.stringify({ type: "error", error: { type: "invalid_request_error", message: msg } }),
      { status: geminiRes.status || 400, headers: { "content-type": "application/json" } },
    );
  }
  if (!geminiRes || !geminiRes.ok || !geminiRes.body) {
    let detail = "";
    try { detail = geminiRes ? (await geminiRes.clone().text()).slice(0, 500) : ""; } catch {}
    log("anthropic bridge: upstream error " + (geminiRes && geminiRes.status) + " " + detail);
    return new Response(JSON.stringify({ type: "error", error: { type: "api_error", message: detail || ("antigravity upstream error " + (geminiRes && geminiRes.status)) } }), { status: (geminiRes && geminiRes.status) || 502, headers: { "content-type": "application/json" } });
  }
  const stream = geminiRes.body.pipeThrough(geminiToAnthropicStream(anthropicBody.model || model));
  return new Response(stream, { status: 200, headers: { "content-type": "text/event-stream", "cache-control": "no-cache", "connection": "keep-alive" } });
}

// Bump each account's stored UA version FORWARD when it's stale (>14d since last
// pick, or never set) — simulating an IDE auto-update. Never downgrades; keeps the
// device's platform/arch. New accounts already get a weighted version at login.
const VERSION_DRIFT_MS = 14 * 24 * 60 * 60 * 1000;
function driftAccountVersions(log) {
  const now = Date.now();
  for (const account of manager.list()) {
    const fp = account.meta && account.meta.fingerprint;
    if (!fp || !fp.userAgent) continue;
    if (typeof fp.versionPickedAt === "number" && now - fp.versionPickedAt < VERSION_DRIFT_MS) continue;
    const current = fp.version || (String(fp.userAgent).match(/antigravity\/([^ ]+)/) || [])[1] || "";
    const next = driftVersion(current);
    manager.mutate(account.id, (a) => {
      const f = a.meta && a.meta.fingerprint;
      if (!f) return;
      f.userAgent = String(f.userAgent).replace(/antigravity\/[^ ]+/, "antigravity/" + next);
      f.version = next;
      f.versionPickedAt = now;
    });
    if (log && next !== current) log("antigravity UA version drift " + (account.email || account.id) + ": " + (current || "?") + " -> " + next);
  }
}

// Refresh the version pool from the release feed + drift accounts — triggered from
// the serving path (throttled), so CLI/command invocations never hit the network.
// Fire-and-forget; never blocks a request.
let versionMaintenanceAt = 0;
function maybeMaintainVersions(log) {
  const now = Date.now();
  if (now - versionMaintenanceAt < 6 * 60 * 60 * 1000) return;
  versionMaintenanceAt = now;
  refreshVersions(log).then(() => driftAccountVersions(log)).catch(() => {});
}

async function handle(request, ctx) {
  const log = (ctx && ctx.log) || (() => {});
  maybeMaintainVersions(log);
  if (isAnthropicMessages(request.url)) return handleAnthropicMessages(request, ctx);
  const url = request.url;
  let bodyText;
  try { bodyText = await request.clone().text(); } catch { bodyText = undefined; }
  const requestedModel = modelFromRequest(url, bodyText, ctx && ctx.model);
  const init = { method: request.method, headers: Object.fromEntries(request.headers), body: bodyText };

  // Auto: walk the user-ranked candidate models, falling through to the next when
  // one is rate-limited (smart fallback). Non-auto models run exactly once.
  let candidates = [requestedModel];
  if (isAutoModel(requestedModel)) {
    const ranked = getAutoCandidates(PROVIDER_ID);   // full catalog ids (already prefixed)
    if (ranked.length) candidates = ranked;
  }

  let lastResponse = null;
  let lastModel = null;
  for (const model of candidates) {
    lastModel = model;
    const candidateUrl = candidates.length > 1 ? rewriteModelInUrl(url, model) : url;
    const response = await attemptModel(model, candidateUrl, init, ctx, log);
    lastResponse = response;
    if (!response || !isRateLimitStatus(response.status)) return response;   // success / non-retryable
    if (candidates.length > 1) log("auto: " + model + " rate-limited (" + response.status + "); trying next candidate");
  }
  // Everything is rate-limited — surface a lane-accurate TERMINAL error (else the
  // host retries forever). The reset shown must belong to the FAILED lane:
  if (lastResponse && isRateLimitStatus(lastResponse.status)) {
    const lane = laneFor(lastModel || requestedModel);
    if (lane === "gemini-cli") {
      // The Gemini CLI free pool has no quota API and its 429 ("exhausted your
      // capacity on this model") carries no reset — so never show the antigravity
      // pool's reset date here (that's a different, unrelated quota).
      return chatError("The Gemini CLI free pool is exhausted for this model. Pick another model or try again later.", { format: "gemini" });
    }
    // Antigravity lanes: use the real quota-pool reset when a pool is genuinely
    // exhausted; otherwise it was a transient burst — return the retryable status
    // so the host backs off and retries instead of seeing a false "quota resets X".
    const reset = soonestQuotaReset();
    if (reset) {
      const when = ` Quota resets ${new Date(reset).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" })}.`;
      return chatError(`All Antigravity accounts are rate-limited for this model.${when} Try again later or pick another model.`, { format: "gemini" });
    }
    return lastResponse;   // transient limit — let the host retry with backoff
  }
  return lastResponse || errorResponse(502, "all antigravity Auto candidates exhausted");
}

// Live model discovery for core-auth: pick the first usable account, fetch the
// account's real available models, and build the catalog (+ ranking/default for
// Auto). Returns null when no account exists or the fetch fails -> core-auth then
// falls back to the cache (or an empty catalog before first login).
async function fetchModels(ctx) {
  const log = (ctx && ctx.log) || (() => {});
  const account = manager.list().find((a) => a.enabled !== false && a.refresh);
  if (!account) return null;
  let access;
  try { access = await manager.ensureAccess(account.id); } catch (error) { log("fetchModels token refresh failed: " + error); return null; }
  if (!access) return null;
  const proxyUrl = proxyManager.selectForAccount(account.id);
  const projectId = await resolveProjectId(account, access, log, proxyUrl);
  const payload = await fetchAvailableModels(access, projectId, proxyUrl, log);
  if (!payload) return null;
  return buildAntigravityCatalog(payload);
}

// Settings shown in core-auth's settings UI. ONLY options actually consumed by
// this provider at runtime are listed — verified by tracing each to its consumer:
//   account_selection_strategy -> AccountManager(selection) above
//   keep_thinking              -> request transform via getKeepThinking() (initRuntimeConfig above)
//   claude_tool_hardening / claude_prompt_auto_caching / debug_gemini_payloads
//                              -> passed into prepareAntigravityRequest options in handle()
// The other historical AntigravityConfig keys (scheduling/rate-limit/quota/health/
// token-bucket/recovery/notifications/etc.) have NO consumer in the core-auth
// provider form — their behavior is owned by core-auth's own engine — so exposing
// them would let users set no-ops. They are intentionally omitted.
const settingsGroups = [
  {
    title: "Account rotation",
    fields: [
      { key: "account_selection_strategy", label: "Account selection", type: "enum", options: ["sticky", "round-robin", "hybrid"], hint: "How accounts are picked: sticky keeps prompt cache, round-robin maximizes throughput, hybrid balances by availability." },
    ],
  },
  {
    title: "Rate limits",
    fields: [
      { key: "default_retry_after_seconds", label: "Base retry delay (s)", type: "number", min: 1, max: 300, hint: "Base cooldown after a transient account error; doubles per attempt (AccountManager backoff)." },
      { key: "max_backoff_seconds", label: "Max backoff (s)", type: "number", min: 5, max: 300, hint: "Caps how long the per-account error backoff can grow." },
    ],
  },
  {
    title: "Claude request handling",
    fields: [
      { key: "keep_thinking", label: "Keep thinking blocks", type: "bool", hint: "Preserve Claude thinking blocks (with signature caching) instead of stripping them." },
      { key: "claude_tool_hardening", label: "Tool hardening", type: "bool", hint: "Inject parameter signatures + strict tool-usage rules to curb Claude tool hallucination." },
      { key: "claude_prompt_auto_caching", label: "Prompt auto-caching", type: "bool", hint: "Add top-level cache_control to Claude prompts when absent." },
    ],
  },
  {
    title: "Debug",
    fields: [
      { key: "debug", label: "Debug logging", type: "bool", hint: "Enable debug logging to a file." },
      { key: "debug_tui", label: "Debug in TUI", type: "bool", hint: "Show debug logs in the TUI log panel (independent of file logging)." },
      { key: "log_dir", label: "Log directory", type: "string", hint: "Custom directory for debug logs." },
      { key: "debug_gemini_payloads", label: "Debug Gemini payloads", type: "bool", hint: "Write the raw payload sent to Gemini models to a debug log file." },
    ],
  },
];

export const driver = {
  id: PROVIDER_ID,
  label: "Antigravity",
  opencodeProvider: "antigravity",
  opencodeNpm: "@ai-sdk/google",   // matches the Gemini-format transform; keeps the real "google" provider free
  models,
  fetchModels,
  sorts: ["leaderboard"],   // opt into core's built-in quality sort (manual is automatic)
  // Shown under the Quota view: the gemini-cli models are a separate fallback pool
  // whose quota the antigravity API doesn't expose (fetchAvailableModels 403s under
  // the CLI header context), so it can't be graphed like the metered pools.
  quotaNote: "Gemini CLI models use a separate fallback pool. Its quota isn't reported by the API, so it can't be shown here.",
  handle,
  login,
  loginFlow,
  accounts: createAntigravityAccounts(manager),
  proxies: true,
  settings: {
    groups: settingsGroups,
    get: getConfigValue,
    set: setConfigValue,
  },
};

export const AntigravityProvider = defineProvider(driver).opencode;
