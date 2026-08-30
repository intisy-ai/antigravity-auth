// Tests for two of the wired config features (the third, signature_cache, is covered in
// plugin/cache.test.ts):
//
//   - cli_first: prepareViaJava threads the configured value through to the Java resolver's cliFirst
//     param as its 14th positional argument (after claudeToolHardening/claudePromptAutoCaching, before
//     the jsRandom/jsUuid/... host seams). These tests prove the configured value reaches that call,
//     without needing the full TeaVM orchestrator (a mocked seam module captures the args).
//
//   - request_jitter_max_ms: a pre-fetch random delay in jsExec's transport. applyRequestJitter is
//     exported so it can be exercised directly against a mocked config, without standing up the
//     full account/orchestrator harness handle-parity.test.ts uses.
import { describe, it, expect, afterEach, beforeEach, vi } from "vitest";
import { prepareViaJava } from "./javaHandle.js";

// The seam onto the Java is a static import, so the capture replaces the seam module rather than
// being threaded in as an argument. Everything but the one export under test stays the real Java,
// because other modules in this graph call the seam as they load.
const captured = vi.hoisted(() => ({ calls: [] as unknown[][] }));

vi.mock("./java.js", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./java.js")>();
  return {
    ...actual,
    orchestrator: {
      ...actual.orchestrator,
      prepareAntigravityRequestProd(...args: unknown[]) {
        captured.calls.push(args);
        return JSON.stringify({
          request: "https://example.invalid/", headers: {}, body: null, streaming: false,
          effectiveModel: "m", projectId: "p", sessionId: "s", headerStyle: "antigravity",
        });
      },
    },
  };
});

beforeEach(() => { captured.calls.length = 0; });

// Positional index of `cliFirst` in the orchestrator.prepareAntigravityRequestProd call built by
// prepareViaJava: url,method,headersJson,body,accessToken,projectId,headerStyle,fingerprintJson,
// keepThinking,pluginSessionId,endpointOverride,claudeToolHardening,claudePromptAutoCaching,cliFirst,...
const CLI_FIRST_ARG_INDEX = 13;

describe("cli_first: threaded into prepareAntigravityRequestProd", () => {
  it("passes an explicit true cliFirst through to the Java call", () => {
    prepareViaJava(
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
      "POST", "{}", "{}", "tok", "proj", undefined, "antigravity", null,
      /* claudeToolHardening */ true, /* claudePromptAutoCaching */ false, /* cliFirst */ true,
    );
    expect(captured.calls).toHaveLength(1);
    expect(captured.calls[0][CLI_FIRST_ARG_INDEX]).toBe(true);
  });

  it("passes an explicit false cliFirst through unchanged", () => {
    prepareViaJava(
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
      "POST", "{}", "{}", "tok", "proj", undefined, "antigravity", null,
      true, false, false,
    );
    expect(captured.calls[0][CLI_FIRST_ARG_INDEX]).toBe(false);
  });

  it("defaults to DEFAULT_CONFIG.cli_first (false) when the caller omits it, the pre-wiring behavior", () => {
    prepareViaJava(
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
      "POST", "{}", "{}", "tok", "proj", undefined, "antigravity", null,
    );
    expect(captured.calls[0][CLI_FIRST_ARG_INDEX]).toBe(false);
  });
});

describe("laneCliFirstFor: provider id selects the upstream lane", () => {
  it("forces the gemini-cli lane when the resolved provider is gemini-cli", async () => {
    const { laneCliFirstFor } = await import("./javaHandle.js");
    expect(laneCliFirstFor({ handlerId: "gemini-cli" })).toBe(true);
  });

  it("keeps the config default for the antigravity provider (default false, behavior-preserving)", async () => {
    const { laneCliFirstFor } = await import("./javaHandle.js");
    expect(laneCliFirstFor({ handlerId: "antigravity" })).toBe(false);
  });

  it("keeps the config default when no provider id is present (legacy single-provider serving)", async () => {
    const { laneCliFirstFor } = await import("./javaHandle.js");
    expect(laneCliFirstFor(undefined)).toBe(false);
    expect(laneCliFirstFor({})).toBe(false);
  });

  it("honors config.cli_first as the antigravity-provider default when enabled", async () => {
    vi.resetModules();
    vi.doMock("../plugin/config/index.js", async () => {
      const actual: any = await vi.importActual("../plugin/config/index.js");
      return { ...actual, loadConfig: () => ({ ...actual.DEFAULT_CONFIG, cli_first: true }) };
    });
    const { laneCliFirstFor } = await import("./javaHandle.js");
    expect(laneCliFirstFor({ handlerId: "antigravity" })).toBe(true);
    expect(laneCliFirstFor({ handlerId: "gemini-cli" })).toBe(true);
  });
});

describe("request_jitter_max_ms: applyRequestJitter", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.resetModules();
    vi.doUnmock("../plugin/config/index.js");
  });

  it("is a no-op at the default (request_jitter_max_ms = 0), no behavior change out of the box", async () => {
    vi.resetModules();
    const { applyRequestJitter } = await import("./javaHandle.js");
    const started = Date.now();
    await applyRequestJitter();
    expect(Date.now() - started).toBeLessThan(20);
  });

  it("sleeps a random duration bounded by request_jitter_max_ms when configured > 0", async () => {
    vi.resetModules();
    vi.doMock("../plugin/config/index.js", async () => {
      const actual: any = await vi.importActual("../plugin/config/index.js");
      return { ...actual, loadConfig: () => ({ ...actual.DEFAULT_CONFIG, request_jitter_max_ms: 40 }) };
    });
    vi.spyOn(Math, "random").mockReturnValue(0.5); // -> ~20ms of a 40ms max
    const { applyRequestJitter } = await import("./javaHandle.js");

    const started = Date.now();
    await applyRequestJitter();
    const elapsed = Date.now() - started;

    expect(elapsed).toBeGreaterThanOrEqual(15); // small scheduler slack below the ~20ms target
    expect(elapsed).toBeLessThan(40);           // must never reach/exceed the configured max
  });
});
