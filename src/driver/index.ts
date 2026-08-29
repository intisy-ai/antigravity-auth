// @ts-nocheck
// The antigravity driver: a thin object on top of basekit/auth. basekit/auth owns
// account storage, selection, token refresh, and rate-limit/cooldown state; this
// driver owns only the antigravity-specific request transform + endpoint dispatch,
// reusing the existing plugin/request + plugin/project + plugin/transform code.

import { AccountManager, proxyManager, commonManagerOptions, retryBackoffMs, toSettingsGroups, retryBackoffSettingsGroups, type ProviderSettingsSchema, type SettingsMenuGroup, type ProviderSort } from "@intisy-ai/basekit/auth";
import { fetchAvailableModels } from "../plugin/models-fetch.js";
import { refreshVersions, getVersionList } from "../plugin/versions.js";
import { models } from "./models.js";
import { oauthConfig } from "./config.js";
import { login, loginFlow } from "./login.js";
import { createAntigravityAccounts } from "./accounts-controller.js";
import { getConfigValue, setConfigValue, loadConfig, DEFAULT_CONFIG } from "../plugin/config/index.js";
import { initializeDebug } from "../plugin/debug.js";
import { initSignatureCache } from "../plugin/cache.js";

const PROVIDER_ID = "antigravity";
// The free Gemini CLI quota pool, exposed as a second first-class provider sharing the
// antigravity account pool. Its provider id, arriving as HandlerCtx.provider, forces the
// gemini-cli lane per request (see javaHandle's laneCliFirst).
export const GEMINI_CLI_PROVIDER_ID = "gemini-cli";
const lastAccountByLane = {};   // lane -> last account id, to notify only on real rotation

// User config, loaded once at startup (changes apply on restart). Only the handful
// of keys actually consumed by this provider are wired below, account selection
// (basekit/auth's engine), the Claude request flags passed into prepareAntigravityRequest,
// and keep_thinking (read fresh by the request transform via getKeepThinking). The other
// historical AntigravityConfig keys have no consumer here, so the settings UI omits them.
let config;
try { config = loadConfig(); } catch { config = DEFAULT_CONFIG; }
initializeDebug(config);     // enables the log.debug(...) calls in request/project (debug, debug_tui, log_dir)
initSignatureCache(config.signature_cache); // constructs the disk-backed SignatureCache when enabled (inert otherwise)

// antigravity's own retry/backoff config key names + default values (60s/60s); the shape,
// coercion, and settings presentation are shared via basekit/auth's provider-common.
export const RETRY_KEYS = { baseKey: "default_retry_after_seconds", maxKey: "max_backoff_seconds" };
const RETRY_DEFAULTS = { baseSeconds: 60, maxSeconds: 60 };

// basekit auth account engine. The driver availability hook keeps antigravity's
// "skip accounts pending Google verification" behavior without leaking it into core.
const manager = new AccountManager(PROVIDER_ID, {
  ...commonManagerOptions(config),
  oauth: oauthConfig(),
  // transient-error cooldown (AccountManager.reportError -> calculateBackoffMs)
  backoff: retryBackoffMs(config, RETRY_KEYS, RETRY_DEFAULTS),
  isAvailable: (account) => !(account.meta && account.meta.verificationRequired),
});

// Exported so javaHandle.ts (the Java-orchestrator delegation shell) shares this ONE
// AccountManager instance (state consistency).
export { manager };

// Bump accounts' stored UA version FORWARD over time, simulating an IDE auto-update.
// Each account has its OWN randomized due date (fp.nextVersionDriftAt), so they never
// update in lockstep, versions roll forward gradually. The first time an account is
// seen it's only SCHEDULED (no change), which is what staggers the initial migration
// off the old hardcoded version. Never downgrades; platform/arch preserved.
// The DECISION (Java decides, host applies) is AntigravityHandleRouting.driftAccountVersions
// (real jsRandom); this just applies the returned mutations + logs.
async function driftAccountVersions(log) {
  const { loadOrchestrator } = await import("./javaHandle.js");
  const orchestrator = await loadOrchestrator();
  const now = Date.now();
  const accounts = manager.list();
  const drifts = JSON.parse(orchestrator.driftAccountVersionsProd(
    JSON.stringify(accounts), now, JSON.stringify(getVersionList()), () => Math.random(),
  ));
  for (const d of drifts) {
    const account = accounts.find((a) => a.id === d.accountId);
    const fp = account && account.meta && account.meta.fingerprint;
    const current = fp ? (fp.version || (String(fp.userAgent).match(/antigravity\/([^ ]+)/) || [])[1] || "") : "";
    manager.mutate(d.accountId, (a) => {
      const f = a.meta && a.meta.fingerprint;
      if (!f) return;
      if (d.scheduleOnly) { f.nextVersionDriftAt = d.nextVersionDriftAt; return; }
      f.userAgent = d.userAgent;
      f.version = d.version;
      f.versionPickedAt = d.versionPickedAt;
      f.nextVersionDriftAt = d.nextVersionDriftAt;
    });
    if (!d.scheduleOnly && log && d.version !== current) {
      log("antigravity UA version drift " + (account.email || account.id) + ": " + (current || "?") + " -> " + d.version);
    }
  }
}

// Refresh the version pool from the release feed + drift accounts, triggered from
// the serving path (throttled), so CLI/command invocations never hit the network.
// Fire-and-forget; never blocks a request.
let versionMaintenanceAt = 0;
function maybeMaintainVersions(log) {
  const now = Date.now();
  if (now - versionMaintenanceAt < 6 * 60 * 60 * 1000) return;
  versionMaintenanceAt = now;
  refreshVersions(log).then(() => driftAccountVersions(log)).catch(() => {});
}

// `handleIr` is the provider's IR-native serving entry: a lazy delegate to the TeaVM-compiled Java
// orchestrator (javaHandle.ts), which owns the whole decision loop (model resolve, account/endpoint
// retry, rate-limit handling). It receives an already app-wire-decoded IR (the front-door owns
// app<->IR translation) and returns an IR event stream; no app-wire (Anthropic) format code lives
// in this provider. The dynamic import keeps the ~MB TeaVM ESM out of the module graph until the
// first request.
async function handleIr(ir, ctx) {
  const log = (ctx && ctx.log) || (() => {});
  maybeMaintainVersions(log);
  const { handleIrViaJavaOrchestrator } = await import("./javaHandle.js");
  return handleIrViaJavaOrchestrator(ir, ctx);
}

// Live model discovery for basekit/auth: pick the first usable account, fetch the
// account's real available models, and build the catalog (+ ranking/default for
// Auto). Returns null when no account exists or the fetch fails -> basekit/auth then
// falls back to the cache (or an empty catalog before first login).
async function fetchWholeCatalog(ctx) {
  const log = (ctx && ctx.log) || (() => {});
  const account = manager.list().find((a) => a.enabled !== false && a.refresh);
  if (!account) return null;
  let access;
  try { access = await manager.ensureAccess(account.id); } catch (error) { log("fetchModels token refresh failed: " + error); return null; }
  if (!access) return null;
  const proxyUrl = proxyManager.selectForAccount(account.id, PROVIDER_ID);
  const { resolveProjectIdViaJava, buildCatalogViaJava } = await import("./javaHandle.js");
  const projectId = await resolveProjectIdViaJava(manager, account, access, log, proxyUrl);
  const payload = await fetchAvailableModels(access, projectId, proxyUrl, log);
  if (!payload) return null;
  return buildCatalogViaJava(payload);
}

// One upstream fetch serves both lanes, and basekit/auth resolves each provider's catalog
// separately, so the result is held briefly rather than fetched twice in a row.
const CATALOG_MEMO_MS = 60 * 1000;
let catalogMemo = null;

async function wholeCatalog(ctx) {
  if (catalogMemo && Date.now() - catalogMemo.at < CATALOG_MEMO_MS) return catalogMemo.catalog;
  const catalog = await fetchWholeCatalog(ctx);
  if (catalog) catalogMemo = { at: Date.now(), catalog };
  return catalog;
}

// The upstream account serves two lanes at once, and the catalog says which is which: a model
// this provider meters carries its own id prefix, and what is left is the free gemini-cli
// pool. Splitting here is what keeps each provider's model count its own, instead of filing
// every model under whichever lane happened to do the fetch.
export function laneOf(modelId) {
  return modelId.startsWith(PROVIDER_ID + "-") ? PROVIDER_ID : GEMINI_CLI_PROVIDER_ID;
}

export function catalogForLane(catalog, lane) {
  if (!catalog) return null;
  const models = {};
  for (const [id, model] of Object.entries(catalog.models || {})) {
    if (laneOf(id) === lane) models[id] = model;
  }
  if (Object.keys(models).length === 0) return null;
  const ranking = (catalog.ranking || Object.keys(catalog.models || {})).filter((id) => id in models);
  const defaultModelId = catalog.defaultModelId && models[catalog.defaultModelId] ? catalog.defaultModelId : undefined;
  return { models, ranking, defaultModelId };
}

async function fetchModels(ctx) {
  return catalogForLane(await wholeCatalog(ctx), PROVIDER_ID);
}

async function fetchGeminiCliModels(ctx) {
  return catalogForLane(await wholeCatalog(ctx), GEMINI_CLI_PROVIDER_ID);
}

// Settings shown in basekit/auth's settings UI. ONLY options actually consumed by
// this provider at runtime are listed, verified by tracing each to its consumer:
//   account_selection_strategy -> AccountManager(selection) above
//   keep_thinking              -> request transform via getKeepThinking() (initRuntimeConfig above)
//   claude_tool_hardening / claude_prompt_auto_caching
//                              -> read by javaHandle.ts's Java prepare path
//   cli_first                  -> threaded into prepareViaJava -> Java's resolveModelForHeaderStyle
//                                 (AntigravityRequestPrep.Input.cliFirst -> resolveModelWithTier)
//   request_jitter_max_ms      -> pre-fetch delay in javaHandle.ts's jsExec transport
//   signature_cache.*          -> initSignatureCache() at startup (plugin/cache.ts's diskCache)
// The other historical AntigravityConfig keys (scheduling/rate-limit/quota/health/
// token-bucket/recovery/notifications/etc.) have NO consumer in the basekit auth
// provider form, their behavior is owned by basekit/auth's own engine, so exposing
// them would let users set no-ops. They are intentionally omitted.
const ACCOUNT_ROTATION_SETTINGS_GROUP = {
  title: "Account rotation",
  fields: [
    { key: "account_selection_strategy", label: "Account selection", type: "enum", options: ["sticky", "round-robin", "hybrid"], hint: "How accounts are picked: sticky keeps prompt cache, round-robin maximizes throughput, hybrid balances by availability." },
  ],
} satisfies SettingsMenuGroup;

// The rest of antigravity's own settings, unified into ONE schema shared by both the loader-TUI
// settings menu (toSettingsGroups below) and Cairn's capabilities panel (toCapabilitiesFields, in
// ../index.ts), so the two surfaces can't drift out of key-set sync. account_selection_strategy
// (above) and the retry/backoff pair (RETRY_KEYS, via basekit/auth's provider-common) are shared with
// every basekit auth provider, so they stay out of this schema and are composed back in separately by
// each consumer.
export const ANTIGRAVITY_SETTINGS_SCHEMA: ProviderSettingsSchema = [
  {
    title: "Rate limits",
    fields: [
      { key: "request_jitter_max_ms", label: "Request jitter (ms)", type: "number", min: 0, max: 10000, hint: "Random delay (0 to this many ms) added before each outbound request; 0 disables it." },
    ],
  },
  {
    title: "Model routing",
    fields: [
      { key: "cli_first", label: "Prefer gemini-cli routing", type: "bool", hint: "Route eligible Gemini models through the gemini-cli quota pool before Antigravity." },
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
    title: "Signature cache",
    fields: [
      { key: "signature_cache.enabled", label: "Enable disk cache", type: "bool", hint: "Persist thinking-block signatures to disk (in addition to the in-memory cache)." },
      { key: "signature_cache.memory_ttl_seconds", label: "Memory TTL (s)", type: "number", min: 1, max: 86400, hint: "How long a signature stays valid in the in-memory cache." },
      { key: "signature_cache.disk_ttl_seconds", label: "Disk TTL (s)", type: "number", min: 1, max: 2592000, hint: "How long a signature stays valid in the on-disk cache." },
      { key: "signature_cache.write_interval_seconds", label: "Disk write interval (s)", type: "number", min: 1, max: 3600, hint: "How often dirty entries are flushed to disk." },
    ],
  },
  {
    title: "Debug",
    fields: [
      { key: "debug", label: "Debug logging", type: "bool", hint: "Enable debug logging to a file." },
      { key: "debug_tui", label: "Debug in TUI", type: "bool", hint: "Show debug logs in the TUI log panel (independent of file logging)." },
      { key: "log_dir", label: "Log directory", type: "string", hint: "Custom directory for debug logs." },
    ],
  },
];

export const driver = {
  id: PROVIDER_ID,
  label: "Antigravity",
  geminiCliProviderId: GEMINI_CLI_PROVIDER_ID,
  geminiCliLabel: "Gemini CLI",
  // Both lanes come out of the one live fetch, split by id prefix, so neither declares a
  // static list and each reports only its own models.
  geminiCliModels: {},
  fetchGeminiCliModels,
  appProviderId: "antigravity",
  appNpm: "@ai-sdk/google",   // matches the Gemini-format transform; keeps the real "google" provider free
  models,
  fetchModels,
  sorts: ["leaderboard"] satisfies ProviderSort[],   // opt into core's built-in quality sort (manual is automatic)
  // Shown under the Quota view: the gemini-cli models are a separate fallback pool
  // whose quota the antigravity API doesn't expose (fetchAvailableModels 403s under
  // the CLI header context), so it can't be graphed like the metered pools.
  quotaNote: "Gemini CLI models use a separate fallback pool. Its quota isn't reported by the API, so it can't be shown here.",
  handleIr,
  login,
  loginFlow,
  accounts: createAntigravityAccounts(manager),
  proxies: true,
  settings: {
    groups: [ACCOUNT_ROTATION_SETTINGS_GROUP, ...toSettingsGroups(ANTIGRAVITY_SETTINGS_SCHEMA), ...retryBackoffSettingsGroups(RETRY_KEYS)],
    get: getConfigValue,
    set: setConfigValue,
  },
};
