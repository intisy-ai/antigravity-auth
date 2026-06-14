// @ts-nocheck
// OpenCode entry: the antigravity provider now runs entirely on core-auth via
// src/driver. The legacy createAntigravityPlugin monolith (src/plugin.ts) is
// superseded and no longer wired here. OAuth helpers stay exported for the login
// flow until it is reimplemented on core.

export { AntigravityProvider, AntigravityProvider as default } from "./driver/index.js";
export { authorizeAntigravity, exchangeAntigravity } from "./antigravity/oauth.js";
