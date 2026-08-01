// @ts-nocheck
// Claude entry: the named handleIr() the claude-code-loader proxy front-door imports for the
// antigravity provider. The front-door owns app<->IR translation and calls handleIr; the provider
// exposes no app-wire (Anthropic) handle().

import { providerHandlerExports } from "../core-auth/dist/index.js";
import { driver } from "./driver/index.js";

export const { handleIr, accounts, loginFlow, menu, menuModel, def } = providerHandlerExports(driver);

// One Google account pool ("antigravity") backs two upstream lanes exposed as first-class
// providers: the metered antigravity pool and the free gemini-cli quota pool. Both share the
// SAME account store (accountPool), so adding an account to either serves both; the resolved
// provider id (HandlerCtx.provider) selects the lane per request.
export const defs = [
  def,
  {
    id: driver.geminiCliProviderId,
    label: driver.geminiCliLabel,
    models: driver.geminiCliModels,
    hasOAuth: def.hasOAuth,
    settings: driver.settings,
    accountPool: driver.id,
  },
];
