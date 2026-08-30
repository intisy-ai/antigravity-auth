// Verifies jsPreparer's Java-driven prepare (prepareAntigravityRequestProd, via javaHandle.ts's
// exported prepareViaJava) produces the frozen output (prepare-scenarios.expected.json) for a
// representative Gemini body and a representative Claude (thinking) body.
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import crypto from "node:crypto";
import { getPluginSessionId } from "../plugin/request.js";
import { prepareViaJava } from "../driver/javaHandle.js";
import expected from "./prepare-scenarios.expected.json";

const SESSION_PLACEHOLDER = "<PLUGIN_SESSION_ID>";

const FIXED_FP = {
  deviceId: "dev-fixed", sessionToken: "sess-fixed", userAgent: "antigravity/1.2.3",
  apiClient: "cli-fixed", clientMetadata: { ideType: "ANTIGRAVITY", platform: "LINUX_AMD64", pluginType: "GEMINI" },
  createdAt: 1_700_000_000_000, version: "1.2.3",
};
const PROD = "https://cloudcode-pa.googleapis.com";

const scenarios = {
  gemini: {
    url: "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-pro:generateContent",
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: "Hello from the Gemini parity fixture" }] }] }),
    accessToken: "tok-gemini",
    projectId: "proj-gemini",
    endpointOverride: PROD,
    headerStyle: "antigravity",
  },
  claude: {
    url: "https://generativelanguage.googleapis.com/v1beta/models/claude-sonnet-4-6-thinking:generateContent",
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: "Hello from the Claude parity fixture" }] }] }),
    accessToken: "tok-claude",
    projectId: "proj-claude",
    endpointOverride: PROD,
    headerStyle: "antigravity",
  },
};

function comparable(prepared) {
  return {
    request: prepared.request,
    headers: Object.fromEntries(new Headers(prepared.init.headers)),
    body: prepared.init.body,
    streaming: prepared.streaming,
    requestedModel: prepared.requestedModel,
    effectiveModel: prepared.effectiveModel,
    projectId: prepared.projectId,
    endpoint: prepared.endpoint,
    sessionId: prepared.sessionId,
    headerStyle: prepared.headerStyle,
  };
}

// PLUGIN_SESSION_ID (request.ts:77) is fixed once at module load, before any per-test mock exists
// a real per-process random value. Normalize it to the SAME placeholder the fixture was frozen with
// (see prepare-scenarios.expected.json's generation) so the comparison isn't process-dependent.
function normalize(obj) {
  const sid = getPluginSessionId();
  return JSON.parse(JSON.stringify(obj).split(sid).join(SESSION_PLACEHOLDER));
}

beforeEach(() => {
  vi.spyOn(Math, "random").mockReturnValue(0.5);
  vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-0000-0000-000000000000");
});
afterEach(() => { vi.restoreAllMocks(); });

describe("prepare parity: Java prepareAntigravityRequestProd vs the frozen TS fixture", () => {
  for (const [name, sc] of Object.entries(scenarios)) {
    it(`${name}: Java jsPreparer output (prepareViaJava) byte-matches the frozen TS fixture`, async () => {
      const javaResult = prepareViaJava(
        sc.url, sc.method, JSON.stringify(sc.headers), sc.body,
        sc.accessToken, sc.projectId, sc.endpointOverride, sc.headerStyle, FIXED_FP,
      );
      expect(normalize(comparable(javaResult))).toEqual(expected[name]);
    });
  }
});
