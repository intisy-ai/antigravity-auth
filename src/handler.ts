// @ts-nocheck
// Claude entry: the named handleIr() the claude-code-loader proxy front-door imports for the
// antigravity provider. The front-door owns app<->IR translation and calls handleIr; the provider
// exposes no app-wire (Anthropic) handle().

import { providerHandlerExports, setActivityEmitter } from "@intisy-ai/core-auth";
import { emitEvent } from "@intisy-ai/core";
import { driver } from "./driver/index.js";

// This bundle (dist/handler.js) is loaded independently of dist/index.js (the Claude proxy
// daemon and both apps' TUI account-menu dynamically import it directly), so it carries its
// own copy of core-auth's module-level emitter and needs its own one-time wiring.
setActivityEmitter((spec, source) => emitEvent(spec, source));

export const { handleIr, accounts, loginFlow, menu, menuModel } = providerHandlerExports(driver);
