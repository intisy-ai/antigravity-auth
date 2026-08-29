// @ts-nocheck
// Antigravity driver configuration; secrets are env-first with public-installed-app constants as fallback so the repo stays secret-free.

import {
  ANTIGRAVITY_CLIENT_ID,
  ANTIGRAVITY_CLIENT_SECRET,
} from "../constants.js";
import { oauthConfigFor } from "@intisy-ai/basekit/auth";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";

export function clientId() { return process.env.ANTIGRAVITY_CLIENT_ID || ANTIGRAVITY_CLIENT_ID; }
export function clientSecret() { return process.env.ANTIGRAVITY_CLIENT_SECRET || ANTIGRAVITY_CLIENT_SECRET; }

export function oauthConfig() {
  return oauthConfigFor({ tokenUrl: TOKEN_URL, clientId: clientId(), clientSecret: clientSecret() });
}
