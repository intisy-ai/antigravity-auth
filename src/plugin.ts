import { providerCapability } from "@intisy-ai/core-auth";
import type { Plugin, PluginContext, ProviderDescriptor } from "@intisy-ai/api";
import { driver } from "./driver/index.js";

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
    context.provide("provider", providerCapability(driver, [geminiCliLane()]));
  },
  deactivate() {},
};

export default plugin;
