// @ts-nocheck
// OpenCode entry. Export ONLY the provider plugin: OpenCode runs every export as a hook, so any extra export would register as a bogus plugin and can break registration.
// Slash-command / config invocations shell back in as `node <bundle> <action>`; handle those first and exit so they never register the provider.
import { deployCommands, defineConfig } from "../core/src/index.js";
import { ANTIGRAVITY_COMMANDS, maybeRunCli } from "./commands.js";
import { DEFAULT_CONFIG } from "./plugin/config/schema.js";

// Register the FULL config schema (the driver's own DEFAULT_CONFIG — the same defaults
// its loader applies) BEFORE the CLI guard, so `config schema`/`config list`, the
// `/config` command, and the loader Configure editor expose every option (not just a
// couple). Writes no file on load. `logging` is core's logger toggle (kept).
defineConfig("antigravity", { ...DEFAULT_CONFIG, logging: true });

if (await maybeRunCli("antigravity")) {
  process.exit(0);
}
try {
  deployCommands("antigravity-auth", ANTIGRAVITY_COMMANDS);
} catch {
  /* best-effort */
}

export { AntigravityProvider } from "./driver/index.js";
