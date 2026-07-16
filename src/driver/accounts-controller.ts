// @ts-nocheck
// Antigravity's AccountController: provider-owned status/quota + Verify / Refresh
// actions, layered on core-auth's generic list/enable/remove helper. Proxies are
// handled entirely by the core proxy subsystem (Manage proxies / Select proxies).

import { accountControllerFromManager, proxyManager } from "../../core-auth/dist/index.js";
import { ANTIGRAVITY_ENDPOINT_PROD, getAntigravityHeaders } from "../constants.js";
import { login } from "./login.js";

function out(message) { process.stdout.write(message + "\n"); }

// Task 7a — the status/quota-view logic (allPoolsExhausted/antigravityStatus/antigravityAvailableAt/
// antigravityQuota/familyLabel + the per-family quota aggregation) now lives in Java
// (AntigravityQuotaParser), reached via AntigravityProviderJs's exports. core-auth's `list()` callback
// contract (antigravityStatus/antigravityAvailableAt/antigravityQuota) is synchronous, so the Java
// module — still a dynamic import, javaHandle.ts's own heavier imports stay off this module's load
// path — is warmed on the FIRST call rather than at module load (module-load-time would race
// javaHandle.ts's own circular import back into driver/index.ts, which is still mid-evaluation at that
// point); a call landing before the warm-up resolves falls back to a benign default and self-corrects
// on the next render.
let orchestrator = null;
let warming = false;
function warmOrchestrator() {
  if (warming || orchestrator) return;
  warming = true;
  import("./javaHandle.js").then(({ loadOrchestrator }) => loadOrchestrator()).then((o) => { orchestrator = o; });
}

function antigravityStatus(account, now) {
  warmOrchestrator();
  if (!orchestrator) return account.enabled === false ? "disabled" : "active";
  return orchestrator.antigravityStatus(JSON.stringify(account), now);
}

function antigravityAvailableAt(account, now) {
  warmOrchestrator();
  if (!orchestrator) return account.enabled === false ? Infinity : now;
  return orchestrator.antigravityAvailableAt(JSON.stringify(account), now);
}

function antigravityQuota(account) {
  warmOrchestrator();
  if (!orchestrator) return undefined;
  const resultJson = orchestrator.antigravityQuota(JSON.stringify(account));
  return resultJson == null ? undefined : JSON.parse(resultJson);
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

  // Per-FAMILY aggregation (worst remaining + earliest reset across that family's models; an
  // exhausted pool's dropped remainingFraction counts as 0, per Java's aggregateQuotaFamilies) —
  // routed through the Java export (safe to await here: unlike the account-view quintet above,
  // this call site is already async).
  const { loadOrchestrator } = await import("./javaHandle.js");
  const o = await loadOrchestrator();
  return JSON.parse(o.aggregateQuota(JSON.stringify(models)));
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
    let projectId = meta.managedProjectId || meta.projectId || meta.syntheticProjectId;
    if (!projectId) {
      const { generateSyntheticProjectIdViaJava } = await import("./javaHandle.js");
      projectId = await generateSyntheticProjectIdViaJava();
    }
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
