/**
 * Read/write helpers for the generic settings UI exposed by core-auth.
 * Thin wrappers over core's per-plugin config store (config/antigravity.json):
 * get reads the on-disk value; set writes it (and creates the file on first change).
 */

import { getConfigValue as coreGetConfigValue, setConfigValue as coreSetConfigValue } from "@intisy-ai/basekit";

/**
 * One setting's effective value.
 *
 * @param key - the setting's key
 * @returns what is on disk, or `undefined` when nothing has been set
 */
export function getConfigValue(key: string): any {
  return coreGetConfigValue("antigravity", key);
}

/**
 * Persists one setting, creating the config file on the first change.
 *
 * @param key - the setting's key
 * @param value - what to store
 */
export function setConfigValue(key: string, value: any): void {
  coreSetConfigValue("antigravity", key, value);
}
