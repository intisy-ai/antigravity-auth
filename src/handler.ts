// @ts-nocheck
// Claude entry: the named handleIr() the claude-code-loader proxy front-door imports for the
// antigravity provider. The front-door owns app<->IR translation and calls handleIr; the provider
// exposes no app-wire (Anthropic) handle().

import { runProviderMenu, buildAccountMenu } from "../core-auth/dist/index.js";
import { driver } from "./driver/index.js";

export const handleIr = driver.handleIr;
export const accounts = driver.accounts;
export const menu = () => runProviderMenu(driver);   // standalone (full-screen select), Claude loader / oc auth login
export const menuModel = () => buildAccountMenu(driver);   // the menu MODEL, opencode loader renders it natively in-tab
export const def = {
  id: driver.id,
  label: driver.label,
  models: driver.models,
  hasOAuth: typeof driver.loginFlow === "function",
  settings: driver.settings,
};
