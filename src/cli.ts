// @ts-nocheck
// Standalone CLI for antigravity account management; writes to the shared basekit auth store so accounts are used by both OpenCode and Claude.

import { runAccountCli, setActivityEmitter } from "@intisy-ai/basekit/auth";
import { emitEvent } from "@intisy-ai/basekit";
import { driver } from "./driver/index.js";

// dist/cli.js is the `antigravity` bin entry, a separate process/bundle from index.js and
// handler.js with its own emitter copy; login/list/remove here must also flow onto the bus.
setActivityEmitter((spec, source) => emitEvent(spec, source));

const PROVIDER_ID = "antigravity";

function printUsage() {
  process.stderr.write("usage: antigravity <login|list|remove <email>>\n");
}

async function main() {
  const handled = await runAccountCli({ providerId: PROVIDER_ID, driver: { accounts: driver.accounts, login: driver.login } });
  if (!handled) {
    printUsage();
    process.exitCode = 1;
  }
}

main().catch((error) => {
  process.stderr.write("Error: " + (error && error.message ? error.message : String(error)) + "\n");
  process.exitCode = 1;
});
