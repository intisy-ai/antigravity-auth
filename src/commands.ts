// The CLI action behind this provider's slash command, which the manifest declares and a host
// deploys. It shells back into this same bundle (`node <bundle> accounts`), so maybeRunCli runs the
// action and the process exits before the provider boots. The account command is namespaced
// (`/antigravity-accounts`) so it never collides with the other providers'; the loaders own the
// unified `/accounts`.
import { printAccounts } from "@intisy-ai/basekit/auth";
import { driver } from "./driver/index.js";

const PROVIDER_ID = "antigravity";

/**
 * Prints this provider's accounts, with the status and quota its own controller resolves.
 */
export function runAccounts(): void {
  try {
    printAccounts(PROVIDER_ID, driver.accounts);
  } catch (error) {
    console.log(`Could not read accounts: ${error instanceof Error ? error.message : error}`);
  }
}

/**
 * Runs this provider's slash-command action when the process was started for one.
 *
 * @returns whether an action ran, so the caller exits instead of booting the provider
 */
export async function maybeRunCli(): Promise<boolean> {
  const argv = process.argv.slice(2);
  if (argv[0] === "accounts") {
    runAccounts();
    return true;
  }
  return false;
}
