// @ts-nocheck
// Google OAuth login for antigravity. loginFlow() is the split begin/complete form core-auth's opencode oauth method drives; login() is the all-in-one form the CLI uses (opens the browser itself).

import { appendFileSync } from "fs";
import { homedir } from "os";
import { join } from "path";
import { defineOAuthLogin, proxyManager, toCoreAccount as toCoreAccountBase, timeoutFetch } from "../../core-auth/dist/index.js";
import { authorizeAntigravity, exchangeAntigravity } from "../antigravity/oauth.js";
import { parseRefreshParts } from "../plugin/auth.js";
import { generateFingerprint } from "../plugin/fingerprint.js";
import { ANTIGRAVITY_REDIRECT_URI, ANTIGRAVITY_ENDPOINT_PROD, getAntigravityHeaders } from "../constants.js";

// Google OAuth succeeds for ANY Google account, but Antigravity only accepts
// enabled accounts, so we must actually call the Antigravity API before saving,
// or ineligible accounts (e.g. non-enabled domains) get added and then fail on use.
// Mirrors accounts-controller verify(): 200/400 = accepted. 401/403 = rejected.
// Anything else (5xx, network, timeout) is INCONCLUSIVE → fail-open (don't block a
// valid account because the check itself was flaky).
async function checkAntigravityAccess(access, projectId, proxy) {
  try {
    const headers = { ...getAntigravityHeaders(), Authorization: "Bearer " + access, "Content-Type": "application/json" };
    if (!projectId) {
      const { generateSyntheticProjectIdViaJava } = await import("./javaHandle.js");
      projectId = await generateSyntheticProjectIdViaJava();
    }
    headers["x-goog-user-project"] = projectId;
    const body = JSON.stringify({ model: "gemini-3-flash", request: { model: "gemini-3-flash", contents: [{ role: "user", parts: [{ text: "ping" }] }], generationConfig: { maxOutputTokens: 1, temperature: 0 } } });
    const res = await timeoutFetch(ANTIGRAVITY_ENDPOINT_PROD + "/v1internal:streamGenerateContent?alt=sse", { method: "POST", headers, body, proxy });
    if (res.status === 200 || res.status === 400) return { ok: true };
    if (res.status === 401 || res.status === 403) return { ok: false, status: res.status };
    return { ok: true, inconclusive: res.status };
  } catch (e) {
    return { ok: true, inconclusive: (e && e.message) || "error" };
  }
}

const PROVIDER_ID = "antigravity";
const LOGIN_TIMEOUT_MS = 5 * 60 * 1000;

// Unconditional trace to a fixed file, the account-menu TUI clears the screen on
// every redraw, so stderr/errors from login() never stay visible. Read it with:
//   cat ~/.config/opencode/antigravity-login.log
function dbg(message) {
  try {
    const base = process.env.XDG_CONFIG_HOME || join(homedir(), ".config");
    appendFileSync(join(base, "opencode", "antigravity-login.log"), "[" + new Date().toISOString() + "] " + message + "\n");
  } catch {}
}

// A connection-level failure means the request never reached Google, so the auth
// code is untouched and a proxy-less retry is safe. (Grant/auth errors are NOT
// matched, those mean the code was consumed and must not be retried.)
function isConnectError(message) {
  return /unable to connect|failed to connect|could not connect|fetch failed|ECONNREFUSED|ECONNRESET|ETIMEDOUT|ENOTFOUND|EHOSTUNREACH|EAI_AGAIN|socket|proxy|tunnel|network/i.test(String(message || ""));
}

// antigravity stores a composite refresh string (refreshToken|projectId|managedProjectId), so it must
// run it through parseRefreshParts before handing off to core-auth's shared toCoreAccount, then merge
// its own meta (projectId/managedProjectId/fingerprint) onto the result; not a drop-in.
async function toCoreAccount(result) {
  const parts = parseRefreshParts(result.refresh);
  const account = toCoreAccountBase({ ...result, refresh: parts.refreshToken });
  account.meta = { projectId: result.projectId || parts.projectId, managedProjectId: parts.managedProjectId };
  try { account.meta.fingerprint = await generateFingerprint(); } catch {}
  return account;
}

// Every generic part of the flow (the settled guard, rebuilding a missing state, saving the
// account, the loopback listener, racing a paste against the browser) comes from core-auth.
// What stays here is antigravity's own: binding a login proxy, the direct-retry when that
// proxy cannot connect, and the access gate above.
export const { loginFlow, login } = defineOAuthLogin({
  provider: PROVIDER_ID,
  instructions:
    "Sign in with Google, approve in your browser and we'll detect it automatically. In a container the localhost redirect won't load, so copy the full URL from your address bar (or just the code) and paste it here instead.",
  redirectUri: ANTIGRAVITY_REDIRECT_URI,
  timeoutMs: LOGIN_TIMEOUT_MS,
  // Bind a proxy up front so the token exchange and project discovery never touch Google
  // from the server's own IP.
  begin: () => ({ proxy: proxyManager.pickForLogin(PROVIDER_ID) }),
  authorize: async () => {
    const authorization = await authorizeAntigravity();
    return { ...authorization, stateExtra: { projectId: authorization.projectId } };
  },
  exchange: (code, state, ctx) => exchangeAntigravity(code, state, ctx.proxy ? { proxy: ctx.proxy } : {}),
  // A dead login proxy bricks the exchange without the code ever reaching Google, so it is
  // untouched and retrying directly is safe. Grant errors are not matched: those consumed it.
  retry: (result, ctx) => {
    if (!ctx.proxy || !isConnectError(result.error)) return null;
    dbg("finish: proxied exchange could not connect via " + ctx.proxy + ", retrying directly");
    process.stderr.write("antigravity: login proxy " + ctx.proxy + " unreachable, retrying token exchange without a proxy.\n");
    return { proxy: null };
  },
  toAccount: toCoreAccount,
  accept: async (account, result, ctx) => {
    const projectId = account.meta.managedProjectId || account.meta.projectId || result.projectId || "";
    const check = await checkAntigravityAccess(result.access, projectId, ctx.proxy);
    if (!check.ok) {
      dbg("finish: Antigravity REJECTED " + (result.email || "?") + " (status " + check.status + "), not adding");
      return { ok: false, message: "antigravity: this account isn't enabled for Antigravity (HTTP " + check.status + "), not added.\nUse a Google account that has Antigravity/Gemini access." };
    }
    if (check.inconclusive) dbg("finish: access check inconclusive (" + check.inconclusive + "), adding anyway");
    return { ok: true };
  },
  onSaved: (account, ctx) => {
    dbg("finish: addAccount done id=" + account.id);
    if (ctx.proxy) proxyManager.bindAccountProxy(account.id, ctx.proxy);
  },
  signInMessage:
    "Open this URL in your browser to sign in with Google.\nApprove in your browser, we'll detect it automatically. In a container the localhost page won't load; copy the full URL from your address bar and paste it below.",
  pastePrompt: "Paste the full redirect URL from your browser (or just the code), then Enter: ",
});
