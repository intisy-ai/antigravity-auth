/**
 * Read/write helpers for the generic settings UI exposed by core-auth.
 * Thin wrappers over core's per-plugin config store (config/antigravity.json):
 * get reads the on-disk value; set writes it (and creates the file on first change).
 */

import { getConfigValue as coreGetConfigValue, setConfigValue as coreSetConfigValue } from "@intisy-ai/core";

export function getConfigValue(key: string): any {
  return coreGetConfigValue("antigravity", key);
}

export function setConfigValue(key: string, value: any): void {
  coreSetConfigValue("antigravity", key, value);
}
