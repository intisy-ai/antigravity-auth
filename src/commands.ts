// @ts-nocheck
// The CLI action behind this provider's slash command, which the manifest declares and a host
// deploys. It shells back into this same bundle (`node <bundle> accounts`), so maybeRunCli runs the
// action and the process exits before the provider boots. The account command is namespaced
// (`/antigravity-accounts`) so it never collides with the other providers'; the loaders own the
// unified `/accounts`.
import { listAccounts, printAccounts } from "@intisy-ai/core-auth";

const PROVIDER_ID = "antigravity";

export function runAccounts() {
  try {
    printAccounts(PROVIDER_ID, { list: () => listAccounts(PROVIDER_ID) || [] });
  } catch (e) {
    console.log(`Could not read accounts: ${e?.message || e}`);
  }
}

export async function maybeRunCli() {
  const argv = process.argv.slice(2);
  if (argv[0] === "accounts") {
    runAccounts();
    return true;
  }
  return false;
}
