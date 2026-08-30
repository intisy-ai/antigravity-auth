/**
 * Config access for the antigravity provider, backed entirely by core.
 *
 * loadConfig() returns the effective config (registered defaults + on-disk
 * config/antigravity.json). getKeepThinking() reads the single runtime flag the
 * request transform needs. No project-level config, no validation, core owns it.
 */

import { loadConfig as coreLoadConfig, getConfigValue as coreGetConfigValue } from "@intisy-ai/basekit";
import { DEFAULT_CONFIG, type AntigravityConfig } from "./schema.js";

/**
 * This provider's whole configuration.
 *
 * @returns the defaults with whatever is on disk written over them
 */
export function loadConfig(): AntigravityConfig {
  return { ...DEFAULT_CONFIG, ...(coreLoadConfig("antigravity") as Partial<AntigravityConfig>) };
}

/**
 * Whether thinking blocks stay in a request, read fresh rather than from the cached config.
 *
 * @returns what is on disk, or the default when nothing has been set
 */
export function getKeepThinking(): boolean {
  return (coreGetConfigValue("antigravity", "keep_thinking") as boolean | undefined) ?? DEFAULT_CONFIG.keep_thinking;
}
