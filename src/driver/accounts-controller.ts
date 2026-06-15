// @ts-nocheck
// Antigravity's AccountController — the provider owns all account status / quota /
// settings semantics here; core-auth's helper handles the generic list/enable/
// remove plumbing, and the shared core TUI only renders what this returns.

import { accountControllerFromManager } from "../../core-auth/dist/index.js";
import { login } from "./login.js";

// antigravity-specific status: a Google-verification hold outranks rate-limit state
function antigravityStatus(account, now) {
  if (account.enabled === false) return "disabled";
  if (account.meta && account.meta.verificationRequired) return "verification-required";
  if (typeof account.coolingDownUntil === "number" && account.coolingDownUntil > now) return "cooling-down";
  const lanes = account.rateLimitResetTimes || {};
  if (Object.values(lanes).some((reset) => typeof reset === "number" && reset > now)) return "rate-limited";
  return "active";
}

// quota comes from the provider's cached per-group quota (meta.cachedQuota)
function antigravityQuota(account) {
  const cached = account.meta && account.meta.cachedQuota;
  if (!cached) return undefined;
  return Object.entries(cached).map(([label, quota]) => ({
    label,
    remainingFraction: quota && typeof quota.remainingFraction === "number" ? quota.remainingFraction : undefined,
    resetTime: quota && quota.resetTime,
  }));
}

export function createAntigravityAccounts(manager) {
  return accountControllerFromManager(manager, {
    status: antigravityStatus,
    quota: antigravityQuota,
    login: async () => {
      const account = await login({ log: (message) => process.stderr.write(message + "\n") });
      return account ? { id: account.id, email: account.email, status: "active", enabled: true } : null;
    },
  });
}
