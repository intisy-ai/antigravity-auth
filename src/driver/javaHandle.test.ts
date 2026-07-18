// @ts-nocheck
// E-wiring focused tests for two of the three newly-wired config features (the third,
// signature_cache, is covered in plugin/cache.test.ts):
//
//   - cli_first: previously every live call to prepareAntigravityRequestProd hardcoded `false` for
//     the Java resolver's cliFirst param; prepareViaJava now threads the real value through as its
//     14th positional argument (after claudeToolHardening/claudePromptAutoCaching, before the
//     jsRandom/jsUuid/... host seams). These tests prove the configured value actually reaches that
//     call, without needing the full TeaVM orchestrator (a fake orchestrator captures the args).
//
//   - request_jitter_max_ms: a pre-fetch random delay in jsExec's transport. applyRequestJitter is
//     exported so it can be exercised directly against a mocked config, without standing up the
//     full account/orchestrator harness handle-parity.test.ts uses.
import { describe, it, expect, afterEach, vi } from "vitest";
import { prepareViaJava } from "./javaHandle.js";

function fakeOrchestrator() {
  const calls: any[] = [];
  return {
    calls,
    prepareAntigravityRequestProd(...args: any[]) {
      calls.push(args);
      return JSON.stringify({
        request: "https://example.invalid/", headers: {}, body: null, streaming: false,
        effectiveModel: "m", projectId: "p", sessionId: "s", headerStyle: "antigravity",
      });
    },
  };
}

// Positional index of `cliFirst` in the orchestrator.prepareAntigravityRequestProd call built by
// prepareViaJava: url,method,headersJson,body,accessToken,projectId,headerStyle,fingerprintJson,
// keepThinking,pluginSessionId,endpointOverride,claudeToolHardening,claudePromptAutoCaching,cliFirst,...
const CLI_FIRST_ARG_INDEX = 13;

describe("cli_first (E-wiring): threaded into prepareAntigravityRequestProd", () => {
  it("passes an explicit true cliFirst through to the Java call", () => {
    const orchestrator = fakeOrchestrator();
    prepareViaJava(
      orchestrator, "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
      "POST", "{}", "{}", "tok", "proj", null, "antigravity", null,
      /* claudeToolHardening */ true, /* claudePromptAutoCaching */ false, /* cliFirst */ true,
    );
    expect(orchestrator.calls).toHaveLength(1);
    expect(orchestrator.calls[0][CLI_FIRST_ARG_INDEX]).toBe(true);
  });

  it("passes an explicit false cliFirst through unchanged", () => {
    const orchestrator = fakeOrchestrator();
    prepareViaJava(
      orchestrator, "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
      "POST", "{}", "{}", "tok", "proj", null, "antigravity", null,
      true, false, false,
    );
    expect(orchestrator.calls[0][CLI_FIRST_ARG_INDEX]).toBe(false);
  });

  it("defaults to DEFAULT_CONFIG.cli_first (false) when the caller omits it — the pre-wiring behavior", () => {
    const orchestrator = fakeOrchestrator();
    prepareViaJava(
      orchestrator, "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
      "POST", "{}", "{}", "tok", "proj", null, "antigravity", null,
    );
    expect(orchestrator.calls[0][CLI_FIRST_ARG_INDEX]).toBe(false);
  });
});

describe("request_jitter_max_ms (E-wiring): applyRequestJitter", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.resetModules();
    vi.doUnmock("../plugin/config/index.js");
  });

  it("is a no-op at the default (request_jitter_max_ms = 0) — no behavior change out of the box", async () => {
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
