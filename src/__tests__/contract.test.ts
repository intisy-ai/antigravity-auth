// Universal plugin contract via core's shared test-kit. configName is the
// provider's real config file (antigravity.json), which differs from the package.
import { runPluginContract } from "@intisy-ai/core/testing";

runPluginContract({
  name: "antigravity-auth",
  entry: "dist/index.js",
  configName: "antigravity",
  app: "both",
  commands: ["antigravity-accounts"],
  deploy: "load",
  actions: [["accounts"]],
  readme: true,
});
