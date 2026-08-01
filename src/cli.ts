// @ts-nocheck
// Standalone CLI for antigravity account management; writes to the shared core-auth store so accounts are used by both OpenCode and Claude.

import { runAccountCli } from "../core-auth/dist/index.js";
import { driver } from "./driver/index.js";

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
