// @ts-nocheck
// T7g2 DORMANCY PROOF — with the flag OFF (default) the delegation shell javaHandle.ts is NEVER
// reached, so neither it nor the ~1.9MB TeaVM orchestrator ESM it imports is ever loaded/executed:
// zero runtime risk to live `oc`. javaHandle is mocked to a spy here; the guard's dynamic import
// `await import("./javaHandle.js"); return handleViaJavaOrchestrator(...)` runs the spy immediately
// once entered, so "spy NOT called" ⟺ "the ON branch was never entered" ⟺ the delegation (and its
// orchestrator import) never happened. Flag ON drives the spy, proving the guard routes correctly.
// (A complementary built-bundle module-load-counter probe lives in
// .superpowers/sdd/t7g2-harness/dormancy-bundle.mjs.)

import { describe, it, expect, beforeEach, afterAll, vi } from "vitest";

const D = vi.hoisted(() => {
  const state: any = { acquireNull: true, calls: [] };
  class FakeAccountManager {
    constructor() {}
    async acquire() { return state.acquireNull ? null : { account: { id: "x", meta: { managedProjectId: "mp-x" } }, access: "t" }; }
    list() { return []; }
    mutate() {}
    reportError() {}
    reportRateLimit() {}
    reportSuccess() {}
    nextAvailableAt() { return null; }
  }
  const proxy = { selectForAccount: () => null, reportResult: () => {}, reportRateLimit: () => {} };
  const delegateSpy = vi.fn(async () => new Response("DELEGATED", { status: 299 }));
  return { state, FakeAccountManager, proxy, delegateSpy };
});

vi.mock("../../core-auth/dist/index.js", async (importOriginal) => {
  const actual: any = await importOriginal();
  return { ...actual, AccountManager: D.FakeAccountManager, proxyManager: D.proxy, getAutoCandidates: () => [] };
});

// Replace the whole delegation shell with a spy — so if the OFF path ever imported/invoked it we
// would see the call, and importing this test file never pulls in the real TeaVM orchestrator.
vi.mock("../driver/javaHandle.js", () => ({ handleViaJavaOrchestrator: D.delegateSpy }));

// Neutralize the fire-and-forget version-feed refresh (unrelated background host side-effect) so the
// dormancy test never touches the network.
vi.mock("../plugin/versions.js", async (importOriginal) => {
  const actual: any = await importOriginal();
  return { ...actual, refreshVersions: () => Promise.reject(new Error("noop-in-test")) };
});

import { driver } from "../driver/index.js";

// A plain Gemini generateContent request (NOT /v1/messages) so the flag-OFF path takes the direct
// Gemini decision loop, not the Anthropic bridge.
const req = () => new Request("https://cloudcode-pa.googleapis.com/v1internal/models/antigravity-claude-sonnet-4-6:generateContent", {
  method: "POST", headers: { "content-type": "application/json" },
  body: JSON.stringify({ contents: [] }),
});
const ctx = { model: "antigravity-claude-sonnet-4-6", log: () => {} };

beforeEach(() => {
  delete process.env.HUB_ANTIGRAVITY_JAVA_HANDLE;
  D.delegateSpy.mockClear();
  D.state.acquireNull = true;
});
afterAll(() => { delete process.env.HUB_ANTIGRAVITY_JAVA_HANDLE; });

describe("dormancy: flag OFF never touches the Java delegation shell", () => {
  it("flag OFF (default) takes the pure-TS path and never calls the delegate", async () => {
    const r = await driver.handle(req(), ctx); // no account -> pure-TS 503 (no quota reset known)
    expect(D.delegateSpy).not.toHaveBeenCalled();
    expect(r.status).toBe(503); // proves the real TS branch ran (not the 299 delegate stub)
  });

  it("env HUB_ANTIGRAVITY_JAVA_HANDLE=1 routes into the delegation shell", async () => {
    process.env.HUB_ANTIGRAVITY_JAVA_HANDLE = "1";
    const r = await driver.handle(req(), ctx);
    expect(D.delegateSpy).toHaveBeenCalledTimes(1);
    expect(r.status).toBe(299); // the spy's response, proving delegation occurred
  });
});
