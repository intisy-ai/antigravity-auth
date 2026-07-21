/**
 * Constants used for Antigravity OAuth flows and Cloud Code Assist API integration.
 */
import { getNewestVersion } from "./plugin/versions.js";

// Antigravity's single public installed-app OAuth client, the same one for every
// user (non-confidential per RFC 8252; already public via the opencode-antigravity-auth
// npm package). Not customizable; there is only ever this one client.
export const ANTIGRAVITY_CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com";

export const ANTIGRAVITY_CLIENT_SECRET = "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";

/**
 * Scopes required for Antigravity integrations.
 */
export const ANTIGRAVITY_SCOPES: readonly string[] = [
  "https://www.googleapis.com/auth/cloud-platform",
  "https://www.googleapis.com/auth/userinfo.email",
  "https://www.googleapis.com/auth/userinfo.profile",
  "https://www.googleapis.com/auth/cclog",
  "https://www.googleapis.com/auth/experimentsandconfigs",
];

/**
 * OAuth redirect URI used by the local CLI callback server.
 */
export const ANTIGRAVITY_REDIRECT_URI = "http://localhost:51121/oauth-callback";

/**
 * Root endpoints for the Antigravity API (in fallback order).
 * CLIProxy and Vibeproxy use the daily sandbox endpoint first,
 * then fallback to autopush and prod if needed.
 */
export const ANTIGRAVITY_ENDPOINT_DAILY = "https://daily-cloudcode-pa.sandbox.googleapis.com";
export const ANTIGRAVITY_ENDPOINT_AUTOPUSH = "https://autopush-cloudcode-pa.sandbox.googleapis.com";
export const ANTIGRAVITY_ENDPOINT_PROD = "https://cloudcode-pa.googleapis.com";

/**
 * Endpoint fallback order (prod → daily → autopush).
 * Prod (cloudcode-pa.googleapis.com) is where regular accounts are licensed; the
 * daily/autopush sandboxes return 403 "no valid license" (#3501) for non-internal
 * accounts, so prod is tried first. The handle falls through to the others on a
 * non-ok status in case an account is only provisioned on a sandbox endpoint.
 */
export const ANTIGRAVITY_ENDPOINT_FALLBACKS = [
  ANTIGRAVITY_ENDPOINT_PROD,
  ANTIGRAVITY_ENDPOINT_DAILY,
  ANTIGRAVITY_ENDPOINT_AUTOPUSH,
] as const;

/**
 * Preferred endpoint order for project discovery (prod first, then fallbacks).
 * loadCodeAssist appears to be best supported on prod for managed project resolution.
 */
export const ANTIGRAVITY_LOAD_ENDPOINTS = [
  ANTIGRAVITY_ENDPOINT_PROD,
  ANTIGRAVITY_ENDPOINT_DAILY,
  ANTIGRAVITY_ENDPOINT_AUTOPUSH,
] as const;

/**
 * Primary endpoint to use (prod - where regular accounts are licensed).
 */
export const ANTIGRAVITY_ENDPOINT = ANTIGRAVITY_ENDPOINT_PROD;

/**
 * Gemini CLI endpoint (production).
 * Used for models without :antigravity suffix.
 * Same as opencode-gemini-auth's GEMINI_CODE_ASSIST_ENDPOINT.
 */
export const GEMINI_CLI_ENDPOINT = ANTIGRAVITY_ENDPOINT_PROD;

// The newest real released version (from the runtime-refreshed version pool), so
// the quota/models headers don't advertise a stale hardcoded build. The per-account
// serving User-Agent is picked separately (weighted) in fingerprint.ts.
export function getAntigravityVersion(): string { return getNewestVersion(); }

export function getAntigravityHeaders(): HeaderSet & { "Client-Metadata": string } {
  return {
    "User-Agent": `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Antigravity/${getAntigravityVersion()} Chrome/138.0.7204.235 Electron/37.3.1 Safari/537.36`,
    "X-Goog-Api-Client": "google-cloud-sdk vscode_cloudshelleditor/0.1",
    "Client-Metadata": `{"ideType":"ANTIGRAVITY","platform":"${process.platform === "win32" ? "WINDOWS" : "MACOS"}","pluginType":"GEMINI"}`,
  };
}

export const GEMINI_CLI_HEADERS = {
  "User-Agent": "google-api-nodejs-client/9.15.1",
  "X-Goog-Api-Client": "gl-node/22.17.0",
  "Client-Metadata": "ideType=IDE_UNSPECIFIED,platform=PLATFORM_UNSPECIFIED,pluginType=GEMINI",
} as const;

export type HeaderSet = {
  "User-Agent": string;
  "X-Goog-Api-Client"?: string;
  "Client-Metadata"?: string;
};

