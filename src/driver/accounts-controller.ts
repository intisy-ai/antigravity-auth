// Antigravity's AccountController: provider-owned status/quota + Verify / Refresh
// actions, layered on basekit/auth's generic list/enable/remove helper. Proxies are
// handled entirely by the core proxy subsystem (Manage proxies / Select proxies).

import { accountControllerFromManager, proxyManager, timeoutFetch, verifyAllAccounts, refreshAccountToken, type AccountController, type AccountManagerLike, type AccountQuota, type AccountStatus, type CoreAccount } from "@intisy-ai/basekit/auth";
import { ANTIGRAVITY_ENDPOINT_PROD, getAntigravityHeaders } from "../constants.js";
import { login } from "./login.js";
import { orchestrator, generateSyntheticProjectIdViaJava } from "./java.js";
import type { ProxiedInit } from "@intisy-ai/basekit/auth";

/** The manager surface this controller needs beyond the generic one: an access token per account. */
type AntigravityManager = AccountManagerLike & { ensureAccess(id: string): Promise<string | undefined | null> };

/** The per-family quota aggregate the upstream model list is reduced to. */
type QuotaFamilies = Record<string, AccountQuota>;

function out(message: string): void { process.stdout.write(message + "\n"); }

// The status/quota-view helpers (allPoolsExhausted/antigravityStatus/antigravityAvailableAt/
// antigravityQuota/familyLabel plus the per-family quota aggregation) run in Java
// (AntigravityQuotaParser), reached through the statically imported seam. basekit/auth's `list()`
// callback contract is synchronous, and the seam answers synchronously, so these three need no
// warm-up and have no degraded first render.

function antigravityStatus(account: CoreAccount, now: number): AccountStatus {
  return orchestrator.antigravityStatus(JSON.stringify(account), now) as AccountStatus;
}

function antigravityAvailableAt(account: CoreAccount, now: number): number {
  return orchestrator.antigravityAvailableAt(JSON.stringify(account), now);
}

function antigravityQuota(account: CoreAccount): AccountQuota[] | undefined {
  const resultJson = orchestrator.antigravityQuota(JSON.stringify(account));
  return resultJson == null ? undefined : JSON.parse(resultJson);
}

// ---- Pool display (no merging, no hardcoded pool map) ------------------------
// Every detected family (Claude / GPT-OSS / Gemini) is ALWAYS its own quota bar.
// Even when two families share a backend pool their bars simply move together;
// per explicit user direction, a merged "Claude + GPT-OSS" label never appears.

// Fetch live quota for one account via cloudcode-pa fetchAvailableModels; returns
// the per-FAMILY aggregate { <family>: { remainingFraction, resetTime } } (worst
// remaining + earliest reset), or null when the call fails / reports no quota.
async function fetchQuotaFamilies(manager: AntigravityManager, id: string): Promise<QuotaFamilies | null> {
  const access = await manager.ensureAccess(id);
  if (!access) return null;
  const account = manager.list().find((a) => a.id === id);
  const meta = account?.meta ?? {};
  const projectId = meta.managedProjectId || meta.projectId || meta.syntheticProjectId;
  // NOTE: do NOT send x-goog-user-project here, fetchAvailableModels 403s ("API not
  // enabled in project …") when it's present; the project belongs in the body only.
  const headers: Record<string, string> = { ...getAntigravityHeaders(), Authorization: "Bearer " + access, "Content-Type": "application/json" };
  const proxy = proxyManager.selectForAccount(id, "antigravity");
  let response: Response;
  try {
    response = await timeoutFetch(ANTIGRAVITY_ENDPOINT_PROD + "/v1internal:fetchAvailableModels", {
      method: "POST", headers, body: JSON.stringify(projectId ? { project: projectId } : {}), proxy: proxy ?? undefined,
    } as ProxiedInit);
  } catch { return null; }
  if (!response.ok) return null;
  let data: { models?: unknown };
  try { data = await response.json(); } catch { return null; }
  const models = data?.models ?? {};

  // Per-FAMILY aggregation (worst remaining + earliest reset across that family's models; an
  // exhausted pool's dropped remainingFraction counts as 0, per Java's aggregateQuotaFamilies).
  return JSON.parse(orchestrator.aggregateQuota(JSON.stringify(models)));
}

// Fetch + persist one account's quota, one bar per family, never merged.
async function refreshQuotaOne(manager: AntigravityManager, id: string): Promise<boolean> {
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

const QUOTA_CACHE_TTL_MS = 60000;

// Refresh cachedQuota for all enabled accounts (skips accounts refreshed within the TTL).
async function refreshAllQuota(manager: AntigravityManager): Promise<void> {
  const now = Date.now();
  for (const account of manager.list()) {
    if (account.enabled === false) continue;
    const updatedAt = account.meta?.cachedQuotaUpdatedAt;
    if (typeof updatedAt === "number" && now - updatedAt < QUOTA_CACHE_TTL_MS) continue;
    try { await refreshQuotaOne(manager, account.id); } catch { /* leave stale cache */ }
  }
}

async function verify(manager: AntigravityManager, view: Pick<CoreAccount, "id" | "email">): Promise<void> {
  const name = view.email || view.id;
  try {
    const access = await manager.ensureAccess(view.id);
    if (!access) { out("✗ " + name + ": no access token"); return; }
    const account = manager.list().find((a) => a.id === view.id);
    const meta = account?.meta ?? {};
    let projectId = String(meta.managedProjectId || meta.projectId || meta.syntheticProjectId || "");
    if (!projectId) projectId = generateSyntheticProjectIdViaJava();
    const headers: Record<string, string> = { ...getAntigravityHeaders(), Authorization: "Bearer " + access, "Content-Type": "application/json" };
    if (projectId) headers["x-goog-user-project"] = projectId;
    const body = JSON.stringify({ model: "gemini-3-flash", request: { model: "gemini-3-flash", contents: [{ role: "user", parts: [{ text: "ping" }] }], generationConfig: { maxOutputTokens: 1, temperature: 0 } } });
    const proxy = proxyManager.selectForAccount(view.id, "antigravity");
    const response = await timeoutFetch(ANTIGRAVITY_ENDPOINT_PROD + "/v1internal:streamGenerateContent?alt=sse", { method: "POST", headers, body, proxy: proxy ?? undefined } as ProxiedInit);
    if (response.status === 200 || response.status === 400) out("✓ " + name + ": verified");
    else if (response.status === 401) out("✗ " + name + ": token expired or revoked (401)");
    else if (response.status === 403) out("✗ " + name + ": forbidden, may need verification (403)");
    else out("✗ " + name + ": " + response.status);
  } catch (error) { out("✗ " + name + ": " + (error instanceof Error ? error.message : error)); }
}

/**
 * This provider's account operations, layered on the generic manager-backed controller.
 *
 * @param manager - the account manager holding this provider's accounts
 * @returns the controller every account surface drives
 */
export function createAntigravityAccounts(manager: AntigravityManager): AccountController {
  return accountControllerFromManager(manager, {
    status: antigravityStatus,
    availableAt: antigravityAvailableAt,
    quota: antigravityQuota,
    refreshQuota: () => refreshAllQuota(manager),
    refreshQuotaOne: async (id) => { if (!(await refreshQuotaOne(manager, id))) throw new Error("quota fetch failed"); },
    login: async () => {
      const account = await login({ log: (message: string) => { process.stderr.write(message + "\n"); } });
      return account ? { id: account.id, email: account.email, status: "active", enabled: true } : null;
    },
    actions: () => [{ label: "Verify all accounts", run: () => verifyAllAccounts(manager, (_shared, view) => verify(manager, view)) }],
    accountActions: (view) => [
      { label: "Verify access", run: () => verify(manager, view) },
      { label: "Refresh token", run: () => refreshAccountToken(manager, view) },
    ],
  });
}
