// @ts-nocheck
// OpenCode entry. OpenCode invokes every exported FUNCTION as a hook, so only the provider
// plugin is exported as one; the api host reads the non-function default instead.
// Slash-command / config invocations shell back in as `node <bundle> <action>`; handle those first and exit so they never register the provider.
import { emitEvent } from "@intisy-ai/core";
import { defineProviderPlugin, setActivityEmitter } from "@intisy-ai/core-auth";
import { maybeRunCli } from "./commands.js";
import { driver } from "./driver/index.js";

// Best-effort: let core-auth's account activity (added/removed/login/rate_limited/models_refreshed) flow onto the bus.
setActivityEmitter((spec: unknown, source: string) => emitEvent(spec, source));

const README_SPEC = {
  description:
    "Google Antigravity provider for OpenCode and Claude Code, built as a thin driver on top of [core-auth](https://github.com/intisy-ai/core-auth). core-auth owns all the generic work (multi-account storage, selection/rotation, token refresh, and rate-limit/cooldown state) while this package supplies only the antigravity specifics: the request/response transform, the Cloud Code Assist endpoints, and the Google OAuth login. The same account pool is shared by both OpenCode and Claude Code.",
  architecture: `flowchart TD
    subgraph Apps
        OC[OpenCode] -->|auth hook loader.fetch| HANDLE
        CC[Claude Code] -->|claude-code-loader proxy| HANDLE
    end

    subgraph Driver [antigravity-auth driver]
        HANDLE["handleIr(ir, ctx) - delegates to the Java orchestrator"]
        LOGIN["login - CLI: antigravity login"]
        HANDLE -->|POST + endpoint fallback| GOOGLE[(Cloud Code Assist API)]
        LOGIN -->|Google PKCE OAuth| GOOGLE
    end

    subgraph Core [core-auth library bundled in]
        MGR[AccountManager]
        STORE[(accounts.json)]
        MGR <--> STORE
    end

    HANDLE -->|acquire / reportRateLimit / reportSuccess| MGR
    LOGIN -->|addAccount| MGR`,
  structure: {
    src: [
      "`index.ts`: OpenCode entry (the core-auth provider plugin)",
      "`handler.ts`: Claude Code entry (`handleIr()` for the claude-code-loader proxy front-door)",
      "`cli.ts`: `antigravity login | list | remove`",
      "`driver/`: `index.ts` (driver + `handleIr`), `config.ts`, `models.ts`, `login.ts`, `accounts-controller.ts`, `javaHandle.ts`/`javaStream.ts` (the Java orchestrator seam)",
      "`antigravity/oauth.ts`, `plugin/{request,project,fingerprint,versions,models-fetch,...}.ts`: the host I/O this driver owns (fetch-interception, OAuth, device fingerprint, version pool, model discovery); the request/response transform and decision logic lives in `java/antigravity-provider` (TeaVM-compiled, called via `driver/javaHandle.ts`)",
      "`commands.ts`: cross-app slash-command definitions and their CLI actions",
    ],
    dist: [
      "`index.js`, `handler.js`, `cli.js` (generated; not committed). `@intisy-ai/core`, `core-auth` and `core-ir` stay external and resolve from the home's shared library store, so every plugin in a home shares one copy rather than embedding its own.",
    ],
  },
  dependencies: ["core", "core-auth", "sync-bridge"],
  extraSections: [
    {
      id: "arch-detail",
      title: "Driver Detail",
      after: "architecture",
      body: "`handleIr` delegates every decision (model/lane resolve, the account/endpoint retry+rotation loop, IR<->Gemini request/response translation, and rate-limit classification) to the Java orchestrator (`java/antigravity-provider`, TeaVM-compiled, called via `driver/javaHandle.ts`/`javaStream.ts`). The provider is IR-native: the front-door owns app<->IR translation, so no app-wire (Anthropic) format code lives here. This TS layer owns only host I/O: the fetch + proxy transport, `AccountManager` acquisition/reporting, project-context discovery, OAuth login, device fingerprinting, and the version pool.",
    },
  ],
};

// The readme registration name is the config NAME the driver reads (config/antigravity.json),
// which the manifest states too; the plugin id stays antigravity-auth.
export const AntigravityProvider = await defineProviderPlugin({
  name: "antigravity",
  driver,
  cliGuard: () => maybeRunCli(),
  readme: README_SPEC,
});

// AntigravityProvider stays exported too: OpenCode invokes every exported function, while an api host reads the default.
export { default } from "./plugin.js";
