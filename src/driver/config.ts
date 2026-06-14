// @ts-nocheck
// Antigravity driver configuration. Secrets are env-first so nothing sensitive
// has to live in the repo (the constants are public-installed-app fallbacks);
// this also keeps the bundled core-auth submodule secret-free.

import {
  ANTIGRAVITY_CLIENT_ID,
  ANTIGRAVITY_CLIENT_SECRET,
  ANTIGRAVITY_REDIRECT_URI,
  ANTIGRAVITY_SCOPES,
  ANTIGRAVITY_ENDPOINT_FALLBACKS,
  ANTIGRAVITY_LOAD_ENDPOINTS,
  ANTIGRAVITY_ENDPOINT,
  GEMINI_CLI_ENDPOINT,
} from "../constants.js";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";

export function clientId() { return process.env.ANTIGRAVITY_CLIENT_ID || ANTIGRAVITY_CLIENT_ID; }
export function clientSecret() { return process.env.ANTIGRAVITY_CLIENT_SECRET || ANTIGRAVITY_CLIENT_SECRET; }

// consumed by core-auth's AccountManager for refresh_token grants
export function oauthConfig() {
  return {
    tokenUrl: TOKEN_URL,
    clientId: clientId(),
    clientSecret: clientSecret(),
  };
}

export const endpoints = {
  token: TOKEN_URL,
  authorize: AUTHORIZE_URL,
  redirectUri: ANTIGRAVITY_REDIRECT_URI,
  scopes: ANTIGRAVITY_SCOPES,
  request: ANTIGRAVITY_ENDPOINT_FALLBACKS,   // daily -> autopush -> prod
  project: ANTIGRAVITY_LOAD_ENDPOINTS,       // prod -> daily -> autopush
  primary: ANTIGRAVITY_ENDPOINT,
  geminiCli: GEMINI_CLI_ENDPOINT,
};
