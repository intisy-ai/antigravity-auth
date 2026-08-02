// Proves the injected OpenCode front-door is actually wired: with driver.serveDirect set
// (src/index.ts), core-auth's dispatchOpencodeFetch takes the in-process direct path instead of
// the no-front-door 503, serving an offline OpenCode request (no proxy daemon running) for real,
// all the way through the Anthropic-wire codec back to a JSON response.
import { describe, it, expect } from "vitest";
import { serveDirect } from "../../opencode-proxy/dist/index.js";

describe("antigravity offline OpenCode front-door", () => {
  it("dispatches through the injected serveDirect instead of 503ing", async () => {
    const def: any = {
      id: "antigravity",
      handleIr: async () => ({
        id: "m",
        model: "m",
        content: [{ kind: "text", text: "ok" }],
        stopReason: "end_turn",
        usage: { inputTokens: 1, outputTokens: 1 },
      }),
      serveDirect,
    };
    const { dispatchOpencodeFetch } = await import("../../core-auth/dist/opencode-fetch.js");
    const request = new Request("https://api.anthropic.com/v1/messages", {
      method: "POST",
      body: JSON.stringify({ model: "gemini", max_tokens: 8, messages: [{ role: "user", content: "hi" }] }),
    });
    const res = await dispatchOpencodeFetch(def, request, {}, { configDir: "/tmp", log() {} });

    expect(res.status).not.toBe(503);
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.content?.[0]?.text).toBe("ok");
  });
});
