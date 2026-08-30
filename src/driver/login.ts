// Google OAuth login for antigravity. loginFlow() is the split begin/complete form basekit/auth's opencode oauth method drives; login() is the all-in-one form the CLI uses (opens the browser itself).

import { appendFileSync } from "fs";
import { homedir } from "os";
import { join } from "path";
import { defineOAuthLogin, proxyManager, toCoreAccount as toCoreAccountBase, timeoutFetch } from "@intisy-ai/basekit/auth";
import { authorizeAntigravity, exchangeAntigravity } from "../antigravity/oauth.js";
import { parseRefreshParts } from "../plugin/auth.js";
import { generateFingerprint } from "../plugin/fingerprint.js";
import { generateSyntheticProjectIdViaJava } from "./java.js";
import type { ProxiedInit } from "@intisy-ai/basekit/auth";
import { ANTIGRAVITY_REDIRECT_URI, ANTIGRAVITY_ENDPOINT_PROD, getAntigravityHeaders } from "../constants.js";
import type { CoreAccount, OauthExchangeResult } from "@intisy-ai/basekit/auth";

// The per-attempt state this provider carries around the exchange. basekit types it `unknown` and
// never inspects it, so narrowing it is the provider's job and happens in one place.
interface LoginContext {
  proxy?: string | null;
}

function contextOf(context: unknown): LoginContext {
  return (context ?? {}) as LoginContext;
}

// What the access gate concluded: rejected outright, or accepted with the reason it could not tell.
interface AccessCheck {
  ok: boolean;
  status?: number;
  inconclusive?: number | string;
}

// Google OAuth succeeds for ANY Google account, but Antigravity only accepts
// enabled accounts, so we must actually call the Antigravity API before saving,
// or ineligible accounts (e.g. non-enabled domains) get added and then fail on use.
// Mirrors accounts-controller verify(): 200/400 = accepted. 401/403 = rejected.
// Anything else (5xx, network, timeout) is INCONCLUSIVE → fail-open (don't block a
// valid account because the check itself was flaky).
async function checkAntigravityAccess(access: string | undefined, projectId: string, proxy?: string | null): Promise<AccessCheck> {
  try {
    const headers: Record<string, string> = { ...getAntigravityHeaders(), Authorization: "Bearer " + access, "Content-Type": "application/json" };
    if (!projectId) projectId = generateSyntheticProjectIdViaJava();
    headers["x-goog-user-project"] = projectId;
    const body = JSON.stringify({ model: "gemini-3-flash", request: { model: "gemini-3-flash", contents: [{ role: "user", parts: [{ text: "ping" }] }], generationConfig: { maxOutputTokens: 1, temperature: 0 } } });
    const res = await timeoutFetch(ANTIGRAVITY_ENDPOINT_PROD + "/v1internal:streamGenerateContent?alt=sse", { method: "POST", headers, body, proxy: proxy ?? undefined } as ProxiedInit);
    if (res.status === 200 || res.status === 400) return { ok: true };
    if (res.status === 401 || res.status === 403) return { ok: false, status: res.status };
    return { ok: true, inconclusive: res.status };
  } catch (error) {
    return { ok: true, inconclusive: (error instanceof Error && error.message) || "error" };
  }
}

const PROVIDER_ID = "antigravity";
const LOGIN_TIMEOUT_MS = 5 * 60 * 1000;

// Unconditional trace to a fixed file, the account-menu TUI clears the screen on
// every redraw, so stderr/errors from login() never stay visible. Read it with:
//   cat ~/.config/opencode/antigravity-login.log
function dbg(message: string): void {
  try {
    const base = process.env.XDG_CONFIG_HOME || join(homedir(), ".config");
    appendFileSync(join(base, "opencode", "antigravity-login.log"), "[" + new Date().toISOString() + "] " + message + "\n");
  } catch {}
}

// A connection-level failure means the request never reached Google, so the auth
// code is untouched and a proxy-less retry is safe. (Grant/auth errors are NOT
// matched, those mean the code was consumed and must not be retried.)
function isConnectError(message: string | undefined): boolean {
  return /unable to connect|failed to connect|could not connect|fetch failed|ECONNREFUSED|ECONNRESET|ETIMEDOUT|ENOTFOUND|EHOSTUNREACH|EAI_AGAIN|socket|proxy|tunnel|network/i.test(String(message || ""));
}

// antigravity stores a composite refresh string (refreshToken|projectId|managedProjectId), so it must
// run it through parseRefreshParts before handing off to basekit/auth's shared toCoreAccount, then merge
// its own meta (projectId/managedProjectId/fingerprint) onto the result; not a drop-in.
async function toCoreAccount(result: OauthExchangeResult): Promise<CoreAccount> {
  // The exchange also answers with the project id directly, but it puts the same value into the
  // composite refresh, so parsing it back out is the one reading rather than two that can disagree.
  const parts = parseRefreshParts(result.refresh);
  const account = toCoreAccountBase({ ...result, refresh: parts.refreshToken });
  const meta: Record<string, unknown> = { projectId: parts.projectId, managedProjectId: parts.managedProjectId };
  try { meta.fingerprint = await generateFingerprint(); } catch {}
  account.meta = meta;
  return account;
}

// Every generic part of the flow (the settled guard, rebuilding a missing state, saving the
// account, the loopback listener, racing a paste against the browser) comes from basekit/auth.
// What stays here is antigravity's own: binding a login proxy, the direct-retry when that
// proxy cannot connect, and the access gate above.
/**
 * This provider's two login forms: the split begin and complete flow a surface drives, and the
 * all-in-one form the account CLI uses.
 */
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
  exchange: (code, state, context) => {
    const { proxy } = contextOf(context);
    return exchangeAntigravity(code, state, proxy ? { proxy } : {});
  },
  // A dead login proxy bricks the exchange without the code ever reaching Google, so it is
  // untouched and retrying directly is safe. Grant errors are not matched: those consumed it.
  retry: (result, context) => {
    const { proxy } = contextOf(context);
    const error = "error" in result ? result.error : undefined;
    if (!proxy || !isConnectError(error)) return null;
    dbg("finish: proxied exchange could not connect via " + proxy + ", retrying directly");
    process.stderr.write("antigravity: login proxy " + proxy + " unreachable, retrying token exchange without a proxy.\n");
    return { proxy: null };
  },
  toAccount: toCoreAccount,
  accept: async (account, result, context) => {
    const meta = account.meta ?? {};
    const projectId = String(meta.managedProjectId || meta.projectId || "");
    const check = await checkAntigravityAccess(result.access, projectId, contextOf(context).proxy);
    if (!check.ok) {
      dbg("finish: Antigravity REJECTED " + (result.email || "?") + " (status " + check.status + "), not adding");
      return { ok: false, message: "antigravity: this account isn't enabled for Antigravity (HTTP " + check.status + "), not added.\nUse a Google account that has Antigravity/Gemini access." };
    }
    if (check.inconclusive) dbg("finish: access check inconclusive (" + check.inconclusive + "), adding anyway");
    return { ok: true };
  },
  onSaved: (account, context) => {
    dbg("finish: addAccount done id=" + account.id);
    const { proxy } = contextOf(context);
    if (proxy) proxyManager.bindAccountProxy(account.id, proxy);
  },
  signInMessage:
    "Open this URL in your browser to sign in with Google.\nApprove in your browser, we'll detect it automatically. In a container the localhost page won't load; copy the full URL from your address bar and paste it below.",
  pastePrompt: "Paste the full redirect URL from your browser (or just the code), then Enter: ",
});
