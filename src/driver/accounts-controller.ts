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

// ---- Pool detection (no hardcoded pool map) ---------------------------------
// Families DEFAULT to separate quota bars. Two families merge into one pool only
// with strong evidence they share a backend quota: across the persisted refresh
// history they must always carry identical remaining fractions AND identical
// reset timestamps, AND show correlated movement (at least one refresh where both
// moved by the same delta) below full quota. A single same-% snapshot is never
// enough — that's exactly how distinct pools look when idle/full.
const QUOTA_HISTORY_LIMIT = 30;
const QUOTA_EPS = 0.001;

function familiesAlwaysMatch(samples, a, b) {
  return samples.every((s) => {
    const fa = s.families[a], fb = s.families[b];
    return Math.abs(fa.remainingFraction - fb.remainingFraction) < QUOTA_EPS
      && String(fa.resetTime || "") === String(fb.resetTime || "");
  });
}

function correlatedMovement(samples, a, b) {
  for (let i = 1; i < samples.length; i++) {
    const dA = samples[i].families[a].remainingFraction - samples[i - 1].families[a].remainingFraction;
    const dB = samples[i].families[b].remainingFraction - samples[i - 1].families[b].remainingFraction;
    if (Math.abs(dA) > QUOTA_EPS && Math.abs(dA - dB) < QUOTA_EPS) return true;
  }
  return false;
}

function shouldMergeFamilies(history, a, b) {
  const samples = history.filter((s) => s && s.families && s.families[a] && s.families[b]);
  if (samples.length < 3) return false;
  if (!familiesAlwaysMatch(samples, a, b)) return false;
  // require real usage observed (a reset timestamp + below-full fraction) so a
  // full-quota coincidence can never merge distinct pools
  if (!samples.some((s) => s.families[a].remainingFraction < 1 - QUOTA_EPS && s.families[a].resetTime)) return false;
  return correlatedMovement(samples, a, b);
}

// Group the current per-family quota into pools using the history evidence.
function groupFamilies(perFamily, history) {
  const fams = Object.keys(perFamily);
  const parent = {};
  const find = (x) => (parent[x] === x ? x : (parent[x] = find(parent[x])));
  for (const fam of fams) parent[fam] = fam;
  for (let i = 0; i < fams.length; i++) {
    for (let j = i + 1; j < fams.length; j++) {
      if (shouldMergeFamilies(history, fams[i], fams[j])) parent[find(fams[j])] = find(fams[i]);
    }
  }
  const members = {};
  for (const fam of fams) { const root = find(fam); (members[root] = members[root] || []).push(fam); }
  const groups = {};
  for (const list of Object.values(members)) {
    const label = list.sort().join(" + ");
    let pooled = null;
    for (const fam of list) {
      const q = perFamily[fam];
      if (!pooled) pooled = { remainingFraction: q.remainingFraction, resetTime: q.resetTime };
      else {
        pooled.remainingFraction = Math.min(pooled.remainingFraction, q.remainingFraction);
        if (q.resetTime && (!pooled.resetTime || Date.parse(q.resetTime) < Date.parse(pooled.resetTime))) pooled.resetTime = q.resetTime;
      }
    }
    groups[label] = pooled;
  }
  return groups;
}

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
  const proxy = proxyManager.selectForAccount(id);
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

// Fetch + persist one account's quota (history sample + evidence-grouped pools).
async function refreshQuotaOne(manager, id) {
  const perFamily = await fetchQuotaFamilies(manager, id);
  if (!perFamily) return false;
  manager.mutate(id, (a) => {
    a.meta = a.meta || {};
    // persisted refresh history is the merge evidence (see shouldMergeFamilies)
    const history = Array.isArray(a.meta.quotaHistory) ? a.meta.quotaHistory : [];
    history.push({ at: Date.now(), families: perFamily });
    while (history.length > QUOTA_HISTORY_LIMIT) history.shift();
    a.meta.quotaHistory = history;
    a.meta.cachedQuota = groupFamilies(perFamily, history);
    a.meta.cachedQuotaUpdatedAt = Date.now();
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
    const proxy = proxyManager.selectForAccount(view.id);
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
