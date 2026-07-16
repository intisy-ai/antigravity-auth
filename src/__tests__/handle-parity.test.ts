// @ts-nocheck
// JAVA-PATH REGRESSION (post flag removal): `driver.handle` unconditionally delegates to the
// TeaVM-compiled Java orchestrator now, so there is no second TS path to diff against. Instead this
// asserts driver.handle's output — final Response (status/headers/streamed body), the ordered
// manager.*/proxyManager.* call sequence, and the byte-identical outbound fetch requests — against
// the FROZEN fixture `handle-scenarios.expected.json` (captured from this same Java path). Any
// unintended behavior drift in the orchestrator or its host seams fails this test.
//
// Fakes: core-auth's AccountManager + proxyManager + getAutoCandidates are replaced with an
// instrumented harness; global fetch is scripted per scenario. Date, Math.random, and
// crypto.randomUUID are pinned so the run is fully deterministic and matches the fixture capture.

import { describe, it, expect, beforeEach, afterAll, vi } from "vitest";
import crypto, { randomUUID as realRandomUuid } from "node:crypto";
import { getPluginSessionId } from "../plugin/request.js";
import fixture from "./handle-scenarios.expected.json";

// PLUGIN_SESSION_ID (request.ts) is minted once via crypto.randomUUID() at module load, BEFORE any
// per-test mock is installed, so it differs across process runs. Redact it to a stable placeholder
// so the outbound body compares equal to the frozen fixture regardless of the real value.
const SESSION_PLACEHOLDER = "SESSION_ID";
function redactSession(text: string | null) {
  if (typeof text !== "string") return text;
  const id = getPluginSessionId();
  return id ? text.split(id).join(SESSION_PLACEHOLDER) : text;
}

const H = vi.hoisted(() => {
  const harness: any = {
    accounts: [],
    acquireScript: [],
    acquireIdx: 0,
    fetchScript: [],
    fetchIdx: 0,
    proxyScript: {},
    nextAvailable: null,
    autoCandidates: [],
    calls: [],
    outbound: [],
    now: 1_700_000_000_000,
  };

  const record = (entry: any[]) => harness.calls.push(entry);
  const snapMeta = (a: any) => a && a.meta ? JSON.parse(JSON.stringify({ syntheticProjectId: a.meta.syntheticProjectId, managedProjectId: a.meta.managedProjectId })) : null;
  const find = (id: string) => harness.accounts.find((x: any) => x.id === id);

  class FakeAccountManager {
    constructor() {}
    async acquire(lane: string) {
      const next = harness.acquireScript[harness.acquireIdx++] ?? null;
      record(["manager.acquire", lane, next && next.account ? next.account.id : null]);
      return next;
    }
    list() { return harness.accounts; } // NOT recorded (fresh-account lookup / soonestQuotaReset)
    mutate(id: string, fn: (a: any) => void) {
      const a = find(id);
      if (a) fn(a);
      record(["manager.mutate", id, snapMeta(a)]);
    }
    reportError(id: string, attempt: number, reason: string) {
      record(["manager.reportError", id, attempt, reason]);
    }
    reportRateLimit(id: string, lane: string, resetMs: any) {
      const a = find(id);
      if (a) { a.rateLimitResetTimes = a.rateLimitResetTimes || {}; a.rateLimitResetTimes[lane] = resetMs; }
      record(["manager.reportRateLimit", id, lane, resetMs]);
    }
    reportSuccess(id: string) { record(["manager.reportSuccess", id]); }
    nextAvailableAt(lane: string) {
      record(["manager.nextAvailableAt", lane]);
      return harness.nextAvailable;
    }
  }

  const fakeProxyManager = {
    selectForAccount(accountId: string, providerId: string) {
      const url = harness.proxyScript[accountId] ?? null;
      record(["proxy.selectForAccount", accountId, providerId, url]);
      return url;
    },
    // ms (latency) is dropped from the record: it is Date.now()-started, non-deterministic, and not a
    // correctness signal; ok already distinguishes success from failure.
    reportResult(url: string, ok: boolean) { record(["proxy.reportResult", url, ok]); },
    reportRateLimit(url: string, opts: any) { record(["proxy.reportRateLimit", url, opts]); },
  };

  return { harness, FakeAccountManager, fakeProxyManager };
});

vi.mock("../../core-auth/dist/index.js", async (importOriginal) => {
  const actual: any = await importOriginal();
  return {
    ...actual,
    AccountManager: H.FakeAccountManager,
    proxyManager: H.fakeProxyManager,
    getAutoCandidates: () => H.harness.autoCandidates,
  };
});

// Neutralize the fire-and-forget version-feed refresh (maybeMaintainVersions in index.ts): it is an
// unrelated background host side-effect, NOT part of the decision loop, and left live it would do a
// real network fetch (consuming a scripted fetch and polluting the run).
vi.mock("../plugin/versions.js", async (importOriginal) => {
  const actual: any = await importOriginal();
  return { ...actual, refreshVersions: () => Promise.reject(new Error("noop-in-test")) };
});

import { driver } from "../driver/index.js";
import { handleViaJavaOrchestrator } from "../driver/javaHandle.js";

const harness = H.harness;
const PROD = "https://cloudcode-pa.googleapis.com";
const jsonHeaders = { "content-type": "application/json" };

// A fixed fingerprint so prepareAntigravityRequestProd's headers are deterministic (bypasses
// getSessionFingerprint).
const FIXED_FP = {
  deviceId: "dev-fixed", sessionToken: "sess-fixed", userAgent: "antigravity/1.2.3",
  apiClient: "cli-fixed", clientMetadata: { ideType: "ANTIGRAVITY", platform: "LINUX_AMD64", pluginType: "GEMINI" },
  createdAt: 1_700_000_000_000, version: "1.2.3",
};

// A "quiet" account: managed project already known -> resolveProjectId short-circuits (no loader).
function quietAccount(id: string, extraMeta: any = {}) {
  return { id, enabled: true, refresh: "rt-" + id, expires: harness.now + 3_600_000, meta: { managedProjectId: "mp-" + id, syntheticProjectId: "syn-" + id, fingerprint: FIXED_FP, ...extraMeta } };
}

// --- fetch script thunks ----------------------------------------------------
function resp(status: number, headers: Record<string, string>, body: string) {
  return () => new Response(body, { status, headers });
}
function rate(status: number, message: string, reason?: string, array = false) {
  const err: any = { error: { message } };
  if (reason) err.error.status = reason;
  return () => new Response(JSON.stringify(array ? [err] : err), { status, headers: jsonHeaders });
}

function resetForRun(sc: any) {
  harness.accounts = JSON.parse(JSON.stringify(sc.accounts || []));
  harness.acquireScript = (sc.acquire || []).map((e: any) =>
    e === null ? null : { account: harness.accounts.find((a: any) => a.id === e.id) || { id: e.id }, access: e.access },
  );
  harness.acquireIdx = 0;
  harness.fetchScript = (sc.fetch || []).slice();
  harness.fetchIdx = 0;
  harness.proxyScript = { ...(sc.proxy || {}) };
  harness.nextAvailable = sc.nextAvailable ?? null;
  harness.autoCandidates = sc.autoCandidates || [];
  harness.calls = [];
  harness.outbound = [];
}

// Capture the OUTBOUND request driver.handle hands to fetch, so the harness can assert the Java
// prepare path produces the SAME wire request each run (url/method/headers/body + host-set proxy).
// Header keys sorted (order not wire-significant); case preserved.
function captureOutbound(url: any, init: any) {
  init = init || {};
  const rawHeaders = init.headers || {};
  const headers: Record<string, string> = {};
  for (const k of Object.keys(rawHeaders).sort()) headers[k] = String(rawHeaders[k]);
  return { url: String(url), method: init.method ?? null, headers, body: redactSession(init.body ?? null), proxy: init.proxy ?? null };
}

async function snapshotResponse(r: Response) {
  return {
    status: r.status,
    headers: Object.fromEntries([...r.headers.entries()].sort()),
    body: await r.text(),
  };
}

async function runOnce(sc: any) {
  const makeReq = () =>
    new Request(sc.url || (PROD + "/v1internal/models/antigravity-claude-sonnet-4-6:generateContent"), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: sc.body ?? JSON.stringify({ contents: [{ role: "user", parts: [{ text: "hi" }] }] }),
    });
  const ctx = { model: sc.model ?? "antigravity-claude-sonnet-4-6", log: () => {} };

  resetForRun(sc);
  const r = await driver.handle(makeReq(), ctx);
  const snap = await snapshotResponse(r);
  return { snap, calls: harness.calls.slice(), outbound: harness.outbound.slice() };
}

// The Java orchestrator's System.currentTimeMillis compiles to a real-clock read; freeze it (and
// Date.now) to the fixture-capture instant so every now-dependent reset/jitter matches the fixture.
const RealDate = globalThis.Date;
class FrozenDate extends RealDate {
  constructor(...args: any[]) {
    if (args.length === 0) super(harness.now);
    else super(...(args as [any]));
  }
  static now() { return harness.now; }
}

let realFetch: any;
beforeEach(() => {
  globalThis.Date = FrozenDate as any;
  vi.spyOn(Math, "random").mockReturnValue(0.5);               // matches the fixture capture's pin
  vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-0000-0000-000000000000"); // per-call requestId
  realFetch = globalThis.fetch;
  globalThis.fetch = (async (url: any, init: any) => {
    harness.outbound.push(captureOutbound(url, init));
    const thunk = harness.fetchScript[harness.fetchIdx++];
    if (!thunk) throw new Error("parity harness: fetch script exhausted");
    return thunk();
  }) as any;
});
afterAll(() => { globalThis.fetch = realFetch; globalThis.Date = RealDate; vi.restoreAllMocks(); });

// A minimal gemini SSE body so the streaming transform + anthropic bridge produce real bytes.
const GEMINI_SSE = 'data: {"response":{"candidates":[{"content":{"parts":[{"text":"hello"}]},"finishReason":"STOP"}]}}\n\n';

// --- scenarios (kept identical to the pre-conversion dual-path harness) ----------------------

const scenarios: any[] = [
  {
    name: "happy path — 200 on attempt 0 (SERVE via transformAntigravityResponse + reportSuccess)",
    accounts: [quietAccount("acc1")],
    acquire: [{ id: "acc1", access: "tok1" }],
    fetch: [resp(200, jsonHeaders, '{"response":{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}}')],
  },
  {
    name: "happy path via proxy — proxy.reportResult(true) then SERVE",
    accounts: [quietAccount("acc1")],
    acquire: [{ id: "acc1", access: "tok1" }],
    proxy: { acc1: "http://proxy1" },
    fetch: [resp(200, jsonHeaders, '{"response":{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}}')],
  },
  {
    name: "429 (reset-after) x3 endpoints then rotate to ok via proxy — ipSuspected:true (has quota)",
    accounts: [quietAccount("acc1", { cachedQuota: { Claude: { remainingFraction: 0.5, resetTime: "2099-01-01T00:00:00Z" } } }), quietAccount("acc2")],
    acquire: [{ id: "acc1", access: "tok1" }, { id: "acc2", access: "tok2" }],
    proxy: { acc1: "http://proxy1" },
    fetch: [rate(429, "quota reached, resets after 30s"), rate(429, "quota reached, resets after 30s"), rate(429, "quota reached, resets after 30s"), resp(200, jsonHeaders, '{"response":{"candidates":[]}}')],
  },
  {
    name: "429 x3 endpoints then rotate to ok via proxy — ipSuspected:false (no quota)",
    accounts: [quietAccount("acc1"), quietAccount("acc2")],
    acquire: [{ id: "acc1", access: "tok1" }, { id: "acc2", access: "tok2" }],
    proxy: { acc1: "http://proxy1" },
    fetch: [rate(429, "quota reached, resets after 30s"), rate(429, "quota reached, resets after 30s"), rate(429, "quota reached, resets after 30s"), resp(200, jsonHeaders, '{"response":{"candidates":[]}}')],
  },
  {
    name: "429 cloudcode-pa [{error}] array unwrap (capacity) then rotate to ok — reason classification parity",
    accounts: [quietAccount("acc1"), quietAccount("acc2")],
    acquire: [{ id: "acc1", access: "tok1" }, { id: "acc2", access: "tok2" }],
    fetch: [rate(429, "resource exhausted", "RESOURCE_EXHAUSTED", true), rate(429, "resource exhausted", "RESOURCE_EXHAUSTED", true), rate(429, "resource exhausted", "RESOURCE_EXHAUSTED", true), resp(200, jsonHeaders, '{"response":{"candidates":[]}}')],
  },
  {
    name: "503 no-reset then rotate to ok — MODEL_CAPACITY cooldown parity (jitter pinned)",
    accounts: [quietAccount("acc1"), quietAccount("acc2")],
    acquire: [{ id: "acc1", access: "tok1" }, { id: "acc2", access: "tok2" }],
    fetch: [rate(503, "overloaded"), rate(503, "overloaded"), rate(503, "overloaded"), resp(200, jsonHeaders, '{"response":{"candidates":[]}}')],
  },
  {
    name: "endpoint fallback — PROD 403 (non-rate-limit) then DAILY 200 (SERVE on the 2nd endpoint)",
    accounts: [quietAccount("acc1")],
    acquire: [{ id: "acc1", access: "tok1" }],
    fetch: [resp(403, jsonHeaders, "no valid license"), resp(200, jsonHeaders, '{"response":{"candidates":[]}}')],
  },
  {
    name: "missing access token -> reportError then rotate to ok",
    accounts: [quietAccount("acc1"), quietAccount("acc2")],
    acquire: [{ id: "acc1", access: "" }, { id: "acc2", access: "tok2" }],
    fetch: [resp(200, jsonHeaders, '{"response":{"candidates":[]}}')],
  },
  {
    name: "no account free, no next-available -> SYNTHETIC 503 (no-account wording)",
    accounts: [],
    acquire: [null],
  },
  {
    name: "no account free, next-available set -> SYNTHETIC 503 (quota-exhausted wording)",
    accounts: [],
    acquire: [null],
    nextAvailable: 1_700_000_000_000 + 30_000,
  },
  {
    name: "Auto fall-through — candidate1 (gemini-pro) 503 rotates to candidate2 (claude) ok",
    model: "antigravity-auto",
    url: PROD + "/v1internal/models/antigravity-auto:generateContent",
    autoCandidates: ["antigravity-gemini-3-pro", "antigravity-claude-sonnet-4-6"],
    accounts: [quietAccount("acc1")],
    acquire: [null, { id: "acc1", access: "tok1" }],
    fetch: [resp(200, jsonHeaders, '{"response":{"candidates":[]}}')],
  },
  {
    name: "TERMINAL gemini-cli exhausted — bare gemini model all rate-limited",
    model: "gemini-3-pro",
    url: PROD + "/v1internal/models/gemini-3-pro:generateContent",
    accounts: [],
    acquire: [null],
    nextAvailable: 1_700_000_000_000 + 5_000,
  },
  {
    name: "TERMINAL antigravity quota-reset — all rate-limited + a pool exhausted with a reset time",
    accounts: [quietAccount("acc1", { cachedQuota: { Claude: { remainingFraction: 0, resetTime: "2099-06-15T12:34:00Z" } } })],
    acquire: [null],
    nextAvailable: 1_700_000_000_000 + 5_000,
  },
  {
    name: "exhaustion — 6 accounts all 429 -> SERVE_RAW the last real upstream 429 (transient, no quota)",
    accounts: Array.from({ length: 6 }, (_, i) => quietAccount("acc" + i)),
    acquire: Array.from({ length: 6 }, (_, i) => ({ id: "acc" + i, access: "tok" + i })),
    fetch: Array.from({ length: 18 }, () => rate(429, "quota reached, resets after 30s")()),
  },
  {
    name: "anthropic bridge success — /v1/messages -> gemini 200 SSE piped through geminiToAnthropicStream",
    url: "https://loader.local/v1/messages",
    body: JSON.stringify({ model: "claude-sonnet-4", messages: [{ role: "user", content: "hi" }] }),
    accounts: [quietAccount("acc1")],
    acquire: [{ id: "acc1", access: "tok1" }],
    fetch: [resp(200, { "content-type": "text/event-stream" }, GEMINI_SSE)],
  },
  {
    name: "anthropic bridge api_error — /v1/messages -> inner non-ok 400 -> api_error passthrough",
    url: "https://loader.local/v1/messages",
    body: JSON.stringify({ model: "claude-sonnet-4", messages: [{ role: "user", content: "hi" }] }),
    accounts: [quietAccount("acc1")],
    acquire: [{ id: "acc1", access: "tok1" }],
    fetch: [resp(400, jsonHeaders, "bad request"), resp(400, jsonHeaders, "bad request"), resp(400, jsonHeaders, "bad request")],
  },
  {
    name: "anthropic bridge rate_limit_error — /v1/messages -> inner terminal quota-reset -> 429 rate_limit_error",
    url: "https://loader.local/v1/messages",
    body: JSON.stringify({ model: "claude-sonnet-4", messages: [{ role: "user", content: "hi" }] }),
    accounts: [quietAccount("acc1", { cachedQuota: { Claude: { remainingFraction: 0, resetTime: "2099-06-15T12:34:00Z" } } })],
    acquire: [null],
    nextAvailable: 1_700_000_000_000 + 5_000,
  },
];

describe("handle regression: Java path (driver.handle) vs frozen fixture", () => {
  for (const sc of scenarios) {
    it(sc.name, async () => {
      const expected = (fixture as any)[sc.name];
      expect(expected, "fixture must have an entry for every scenario").toBeTruthy();
      const { snap, calls, outbound } = await runOnce(sc);
      expect(snap, "final Response must match the frozen fixture").toEqual(expected.snap);
      expect(calls, "ordered manager/proxy call sequence must match the frozen fixture").toEqual(expected.calls);
      expect(outbound, "outbound fetch requests must match the frozen fixture").toEqual(expected.outbound);
    });
  }

  it("fixture has no stale entries beyond the current scenario set", () => {
    const names = new Set(scenarios.map((sc) => sc.name));
    for (const key of Object.keys(fixture as any)) {
      expect(names.has(key), `fixture entry "${key}" has no matching scenario`).toBe(true);
    }
  });
});

// CRITICAL-1 regression guard: fresh accounts (no discovered managed project, no pre-set synthetic id)
// must mint a UNIQUE per-account synthetic project id — otherwise every such account gets the SAME
// x-goog-user-project-equivalent (the outbound body `project` field) and gets correlated. Uses REAL
// entropy (not the deterministic pin) via handleViaJavaOrchestrator directly.
describe("CRITICAL-1: fresh-account synthetic project id is unique per account (no correlation)", () => {
  it("two fresh accounts get DISTINCT persisted meta.syntheticProjectId AND distinct outbound body project on the Java path", async () => {
    vi.spyOn(crypto, "randomUUID").mockImplementation(() => realRandomUuid()); // un-pin -> real entropy

    const geminiReq = () => new Request(PROD + "/v1internal/models/antigravity-claude-sonnet-4-6:generateContent", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ contents: [{ role: "user", parts: [{ text: "hi" }] }] }),
    });
    // A fresh account: NO managedProjectId, NO syntheticProjectId -> resolveProjectId mints+persists a
    // synthetic id, and (load+onboard both fail) the synthetic id becomes the effective project id in
    // the outbound body. 3 loadCodeAssist endpoints + 3 onboardUser endpoints all 404, then the main 200.
    const freshFetch = () => [
      resp(404, jsonHeaders, "no"), resp(404, jsonHeaders, "no"), resp(404, jsonHeaders, "no"),
      resp(404, jsonHeaders, "no"), resp(404, jsonHeaders, "no"), resp(404, jsonHeaders, "no"),
      resp(200, jsonHeaders, '{"response":{"candidates":[]}}'),
    ];

    const results: Array<{ persisted: string; bodyProject: string }> = [];
    for (const id of ["fresh1", "fresh2"]) {
      resetForRun({
        accounts: [{ id, enabled: true, refresh: "rt-" + id, expires: harness.now + 3_600_000, meta: { fingerprint: FIXED_FP } }],
        acquire: [{ id, access: "tok-" + id }],
        fetch: freshFetch(),
      });
      await handleViaJavaOrchestrator(geminiReq(), { model: "antigravity-claude-sonnet-4-6", log: () => {} });
      const persisted = harness.accounts[0].meta.syntheticProjectId;
      const mainOut = harness.outbound.find((o) => String(o.url).includes(":generateContent"));
      let bodyProject = "";
      try { bodyProject = JSON.parse(String(mainOut?.body ?? "{}")).project; } catch {}
      results.push({ persisted, bodyProject });
    }

    expect(results[0].persisted, "account 1 must persist a synthetic id").toBeTruthy();
    expect(results[1].persisted, "account 2 must persist a synthetic id").toBeTruthy();
    expect(results[0].bodyProject, "synthetic id must reach the outbound body project field").toBe(results[0].persisted);
    expect(results[1].persisted, "two fresh accounts must NOT share a synthetic project id (correlation)").not.toBe(results[0].persisted);
    expect(results[1].bodyProject, "two fresh accounts must NOT share the outbound body project").not.toBe(results[0].bodyProject);
  });
});
