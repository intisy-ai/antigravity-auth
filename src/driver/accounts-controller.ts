// @ts-nocheck
// Antigravity's AccountController: provider-owned status/quota + Verify / Refresh
// actions, layered on core-auth's generic list/enable/remove helper. Proxies are
// handled entirely by the core proxy subsystem (Manage proxies / Select proxies).

import { accountControllerFromManager, proxyManager } from "../../core-auth/dist/index.js";
import { ANTIGRAVITY_ENDPOINT_PROD, getAntigravityHeaders } from "../constants.js";
import { generateSyntheticProjectId } from "../plugin/request.js";
import { login } from "./login.js";

function out(message) { process.stdout.write(message + "\n"); }

// True only when EVERY known quota pool is exhausted. A pool counts as having
// capacity when it reports a positive remainingFraction.
function allPoolsExhausted(cachedQuota) {
  const pools = Object.values(cachedQuota || {});
  if (!pools.length) return false;
  return pools.every((q) => !(q && typeof q.remainingFraction === "number" && q.remainingFraction > 0));
}

// Status reflects the account's real serving capacity via its quota POOLS, not the
// per-lane rate-limit backoffs. A single transient lane limit (e.g. the gemini-cli
// fallback pool, which isn't even a displayed quota pool) must not flag the whole
// account as rate-limited while Gemini/Claude still have quota. Only when every
// pool is exhausted is the account truly rate-limited. Falls back to the lane check
// before the first quota fetch (no cachedQuota yet).
function antigravityStatus(account, now) {
  if (account.enabled === false) return "disabled";
  if (account.meta && account.meta.verificationRequired) return "verification-required";
  if (typeof account.coolingDownUntil === "number" && account.coolingDownUntil > now) return "cooling-down";
  const cachedQuota = account.meta && account.meta.cachedQuota;
  if (cachedQuota && Object.keys(cachedQuota).length) {
    return allPoolsExhausted(cachedQuota) ? "rate-limited" : "active";
  }
  const lanes = account.rateLimitResetTimes || {};
  if (Object.values(lanes).some((reset) => typeof reset === "number" && reset > now)) return "rate-limited";
  return "active";
}

// Usability time for the account row/hint, pool-based to match antigravityStatus:
// usable NOW when any pool has capacity (or before the first quota fetch, since a
// per-lane limit still leaves other lanes serving); the soonest pool reset when
// every pool is exhausted; disabled/cooldown handled as usual.
function antigravityAvailableAt(account, now) {
  if (account.enabled === false) return Infinity;
  if (typeof account.coolingDownUntil === "number" && account.coolingDownUntil > now) return account.coolingDownUntil;
  const cachedQuota = account.meta && account.meta.cachedQuota;
  if (cachedQuota && Object.keys(cachedQuota).length && allPoolsExhausted(cachedQuota)) {
    let soonest = Infinity;
    for (const q of Object.values(cachedQuota)) {
      const t = q && q.resetTime ? Date.parse(q.resetTime) : NaN;
      if (Number.isFinite(t)) soonest = Math.min(soonest, t);
    }
    return Number.isFinite(soonest) ? soonest : now;
  }
  return now;
}

function antigravityQuota(account) {
  const cached = account.meta && account.meta.cachedQuota;
  if (!cached) return undefined;
  return Object.entries(cached).map(([label, quota]) => ({
    label,
    remainingFraction: quota && typeof quota.remainingFraction === "number" ? quota.remainingFraction : undefined,
    resetTime: quota && quota.resetTime,
  }));
}

// Friendly family name for a model. Returns null for internal/unknown models.
function familyLabel(modelName) {
  const lower = String(modelName).toLowerCase();
  if (lower.includes("claude")) return "Claude";
  if (lower.includes("gpt") || lower.includes("oss")) return "GPT-OSS";
  if (lower.includes("gemini")) return "Gemini";
  return null;
}

// ---- Pool display (no merging, no hardcoded pool map) ------------------------
// Every detected family (Claude / GPT-OSS / Gemini) is ALWAYS its own quota bar.
// Even when two families share a backend pool their bars simply move together —
// per explicit user direction, a merged "Claude + GPT-OSS" label never appears.

// Fetch live quota for one account via cloudcode-pa fetchAvailableModels; returns
// the per-FAMILY aggregate { <family>: { remainingFraction, resetTime } } (worst
// remaining + earliest reset), or null when the call fails / reports no quota.
async function fetchQuotaFamilies(manager, id) {
  const access = await manager.ensureAccess(id);
  if (!access) return null;
  const account = manager.list().find((a) => a.id === id);
  const meta = (account && account.meta) || {};
  const projectId = meta.managedProjectId || meta.projectId || meta.syntheticProjectId;
  // NOTE: do NOT send x-goog-user-project here — fetchAvailableModels 403s ("API not
  // enabled in project …") when it's present; the project belongs in the body only.
  const headers = { ...getAntigravityHeaders(), Authorization: "Bearer " + access, "Content-Type": "application/json" };
  const proxy = proxyManager.selectForAccount(id, "antigravity");
  const aborter = new AbortController();
  const timer = setTimeout(() => aborter.abort(), 20000);
  let response;
  try {
    response = await fetch(ANTIGRAVITY_ENDPOINT_PROD + "/v1internal:fetchAvailableModels", {
      method: "POST", headers, body: JSON.stringify(projectId ? { project: projectId } : {}), signal: aborter.signal, proxy,
    });
  } catch { return null; } finally { clearTimeout(timer); }
  if (!response.ok) return null;
  let data;
  try { data = await response.json(); } catch { return null; }
  const models = (data && data.models) || {};

  // Step 1 — aggregate quota per FAMILY (worst remaining + earliest reset across
  // that family's models). When a pool is exhausted cloudcode-pa drops
  // remainingFraction and returns only resetTime — treat that as 0 remaining so the
  // pool still shows (100% used, resets at X) instead of vanishing.
  const perFamily = {};
  for (const [modelName, info] of Object.entries(models)) {
    const fam = familyLabel(modelName);
    if (!fam || !info || !info.quotaInfo) continue;
    const remaining = typeof info.quotaInfo.remainingFraction === "number"
      ? info.quotaInfo.remainingFraction
      : (info.quotaInfo.resetTime ? 0 : undefined);
    if (remaining === undefined) continue;
    const reset = info.quotaInfo.resetTime || "";
    const f = perFamily[fam] || (perFamily[fam] = { remainingFraction: remaining, resetTime: reset });
    f.remainingFraction = Math.min(f.remainingFraction, remaining);
    if (reset && (!f.resetTime || Date.parse(reset) < Date.parse(f.resetTime))) f.resetTime = reset;
  }

  return Object.keys(perFamily).length ? perFamily : null;
}

// Fetch + persist one account's quota — one bar per family, never merged.
async function refreshQuotaOne(manager, id) {
  const perFamily = await fetchQuotaFamilies(manager, id);
  if (!perFamily) return false;
  manager.mutate(id, (a) => {
    a.meta = a.meta || {};
    a.meta.cachedQuota = perFamily;
    a.meta.cachedQuotaUpdatedAt = Date.now();
    delete a.meta.quotaHistory;   // merge-evidence sampling retired with merging
  });
  return true;
}

// Refresh cachedQuota for all enabled accounts (skips accounts refreshed within ttl).
async function refreshAllQuota(manager, force) {
  const now = Date.now();
  const ttl = 60000;
  for (const account of manager.list()) {
    if (account.enabled === false) continue;
    const updatedAt = account.meta && account.meta.cachedQuotaUpdatedAt;
    if (!force && typeof updatedAt === "number" && now - updatedAt < ttl) continue;
    try { await refreshQuotaOne(manager, account.id); } catch { /* leave stale cache */ }
  }
}

async function verify(manager, view) {
  const name = view.email || view.id;
  try {
    const access = await manager.ensureAccess(view.id);
    if (!access) { out("✗ " + name + ": no access token"); return; }
    const account = manager.list().find((a) => a.id === view.id);
    const meta = (account && account.meta) || {};
    const projectId = meta.managedProjectId || meta.projectId || meta.syntheticProjectId || generateSyntheticProjectId();
    const headers = { ...getAntigravityHeaders(), Authorization: "Bearer " + access, "Content-Type": "application/json" };
    if (projectId) headers["x-goog-user-project"] = projectId;
    const body = JSON.stringify({ model: "gemini-3-flash", request: { model: "gemini-3-flash", contents: [{ role: "user", parts: [{ text: "ping" }] }], generationConfig: { maxOutputTokens: 1, temperature: 0 } } });
    const aborter = new AbortController();
    const timer = setTimeout(() => aborter.abort(), 20000);
    const proxy = proxyManager.selectForAccount(view.id, "antigravity");
    let response;
    try { response = await fetch(ANTIGRAVITY_ENDPOINT_PROD + "/v1internal:streamGenerateContent?alt=sse", { method: "POST", headers, body, signal: aborter.signal, proxy }); }
    finally { clearTimeout(timer); }
    if (response.status === 200 || response.status === 400) out("✓ " + name + ": verified");
    else if (response.status === 401) out("✗ " + name + ": token expired or revoked (401)");
    else if (response.status === 403) out("✗ " + name + ": forbidden, may need verification (403)");
    else out("✗ " + name + ": " + response.status);
  } catch (error) { out("✗ " + name + ": " + (error && error.message || error)); }
}

async function verifyAll(manager) {
  for (const account of manager.list()) {
    if (account.enabled === false) { out("- " + (account.email || account.id) + ": skipped (disabled)"); continue; }
    await verify(manager, { id: account.id, email: account.email });
  }
  out("Done.");
}

async function refreshToken(manager, view) {
  const name = view.email || view.id;
  try { out(await manager.refresh(view.id) ? "✓ refreshed " + name : "✗ no OAuth config / refresh token for " + name); }
  catch (error) { out("✗ refresh failed for " + name + ": " + (error && error.message || error)); }
}

// Quota still remaining? Used to decide a rate-limit is an IP limit (proxy signal),
// not real account exhaustion. Unknown quota -> false (never blame the proxy).
export function accountHasQuota(account) {
  const cq = account && account.meta && account.meta.cachedQuota;
  if (!cq) return false;
  return Object.values(cq).some((q) => q && typeof q.remainingFraction === "number" && q.remainingFraction > 0);
}

export function createAntigravityAccounts(manager) {
  return accountControllerFromManager(manager, {
    status: antigravityStatus,
    availableAt: antigravityAvailableAt,
    quota: antigravityQuota,
    refreshQuota: (force) => refreshAllQuota(manager, force),
    refreshQuotaOne: async (id) => { if (!(await refreshQuotaOne(manager, id))) throw new Error("quota fetch failed"); },
    login: async () => {
      const account = await login({ log: (message) => process.stderr.write(message + "\n") });
      return account ? { id: account.id, email: account.email, status: "active", enabled: true } : null;
    },
    actions: () => [{ label: "Verify all accounts", run: () => verifyAll(manager) }],
    accountActions: (view) => [
      { label: "Verify access", run: () => verify(manager, view) },
      { label: "Refresh token", run: () => refreshToken(manager, view) },
    ],
  });
}
