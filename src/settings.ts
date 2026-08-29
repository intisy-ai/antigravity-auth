// What each of this provider's settings is called and how a surface renders it, beside the values
// the manifest declares. Data the settings capability answers with.
import { COMMON_PROVIDER_CAPABILITIES, toCapabilitiesFields, retryBackoffCapabilities } from "@intisy-ai/basekit/auth";
import type { CapabilitySchema } from "@intisy-ai/basekit";
import { ANTIGRAVITY_SETTINGS_SCHEMA, RETRY_KEYS } from "./driver/index.js";

export const ANTIGRAVITY_SETTINGS: CapabilitySchema = {
  fields: [
    ...COMMON_PROVIDER_CAPABILITIES,
    { key: "logging", type: "boolean", label: "Logging", description: "Write this plugin's log file.", group: "General" },
    ...toCapabilitiesFields(ANTIGRAVITY_SETTINGS_SCHEMA),
    ...retryBackoffCapabilities(RETRY_KEYS),
  ],
  actions: [
    { id: "accounts", label: "List accounts", description: "Print the signed-in Antigravity accounts." },
  ],
};
