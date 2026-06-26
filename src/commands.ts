// @ts-nocheck
// Cross-app slash-commands for antigravity-auth. The config command targets the
// provider's real config file (config/antigravity.json — note the name differs
// from the package), so `/antigravity-config` edits exactly what the driver reads.
// Account name is namespaced (`/antigravity-accounts`) so it never collides with
// the other providers' account commands; the loaders own the unified `/accounts`.
import { configCommand, runConfigCli } from "../core/src/index.js";
import { listAccounts } from "../core-auth/dist/index.js";

const PROVIDER_ID = "antigravity";

export const ANTIGRAVITY_COMMANDS = [
  configCommand("antigravity"),
  {
    name: "antigravity-accounts",
    description: "List signed-in Antigravity accounts",
    shell: 'node "{{BUNDLE}}" accounts',
    body: "Above are the Antigravity accounts and their enabled state. Report them; if none, tell the user to add one from the account menu (`oc auth login`).",
  },
];

function runAccounts() {
  let accounts = [];
  try {
    accounts = listAccounts(PROVIDER_ID) || [];
  } catch (e) {
    console.log(`Could not read accounts: ${e?.message || e}`);
    return;
  }
  if (!accounts.length) {
    console.log("No Antigravity accounts. Add one from the account menu (oc auth login).");
    return;
  }
  for (const a of accounts) {
    const state = a.enabled === false ? " (disabled)" : "";
    console.log(`- ${a.email || a.id}${state}`);
  }
}

// `configName` is the provider config file base (antigravity.json), not the package.
export async function maybeRunCli(configName) {
  const argv = process.argv.slice(2);
  if (argv[0] === "config") {
    runConfigCli(configName, argv.slice(1));
    return true;
  }
  if (argv[0] === "accounts") {
    runAccounts();
    return true;
  }
  return false;
}
