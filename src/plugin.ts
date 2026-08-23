import { providerCapability } from "@intisy-ai/core-auth";
import type { Plugin, PluginContext } from "@intisy-ai/api";
import type { ProviderCapability, ProviderDescriptor } from "@intisy-ai/core-auth";
import type { SettingsCapability } from "@intisy-ai/core";
import { driver } from "./driver/index.js";
import { ANTIGRAVITY_SETTINGS } from "./settings.js";
import { runAccounts } from "./commands.js";

/**
 * The free Gemini CLI quota pool, the second lane this one Google account pool backs.
 *
 * @remarks
 * Same `accountPool` as the metered lane, so adding an account to either serves both; the resolved
 * provider id the engine passes in selects which lane a request takes.
 */
function geminiCliLane(): ProviderDescriptor {
  return {
    id: driver.geminiCliProviderId,
    label: driver.geminiCliLabel,
    models: driver.geminiCliModels,
    hasOAuth: typeof driver.loginFlow === "function",
    accountPool: driver.id,
  };
}

/** What an in-process host loads: the api plugin this bundle's default export carries. */
const plugin: Plugin = {
  activate(context: PluginContext) {
    context.provide(context.capability<ProviderCapability>("provider"), providerCapability(driver, [geminiCliLane()]));
    context.provide(context.capability<SettingsCapability>("settings"), {
      schema: () => ANTIGRAVITY_SETTINGS,
      run: async (actionId: string) => {
        if (actionId !== "accounts") return { ok: false, message: `unknown action: ${actionId}` };
        runAccounts();
        return { ok: true };
      },
    });
  },
  deactivate() {},
};

export default plugin;
