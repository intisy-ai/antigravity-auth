// @ts-nocheck
// T7g2 PARITY HARNESS — runs BOTH the live pure-TS `handle` (driver.handle, flag OFF) and the
// Java-orchestrator delegation (handleViaJavaOrchestrator) against the SAME scripted scenarios,
// asserting IDENTICAL outcomes: final Response (status + headers + streamed body), the IDENTICAL
// ordered sequence of manager.* / proxyManager.* calls (incl. the reportRateLimit ipSuspected proxy
// re-fire and the rate-limit cooldown resetMs), AND — the claude T6c2 review lesson — the
// byte-identical OUTBOUND fetch requests (url/method/headers/body/proxy) each path hands to fetch.
// This offline diff is the evidence that flipping the flag in T7h is safe.
//
// Fakes: core-auth's AccountManager + proxyManager + getAutoCandidates are replaced (so both paths
// share ONE instrumented manager/proxy); the REAL prepareAntigravityRequest / transformAntigravityResponse
// / anthropic bridge / chatError all stay. Global fetch is scripted per scenario. Date, Math.random,
// and crypto.randomUUID are pinned so the real prepare is fully deterministic AND both paths agree on
// cooldowns (Java bakes random=0.5; we pin Math.random=0.5 so the TS jitter matches it exactly).

import { describe, it, expect, beforeEach, afterAll, vi } from "vitest";
import crypto, { randomUUID as realRandomUuid } from "node:crypto";

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
// real network fetch (consuming a scripted fetch on the first driver.handle call and polluting only
// the TS run). Rejecting refreshVersions makes driftAccountVersions never run (no fetch, no mutate).
vi.mock("../plugin/versions.js", async (importOriginal) => {
  const actual: any = await importOriginal();
  return { ...actual, refreshVersions: () => Promise.reject(new Error("noop-in-test")) };
});

import { driver, manager } from "../driver/index.js";
import { handleViaJavaOrchestrator } from "../driver/javaHandle.js";

const harness = H.harness;
const PROD = "https://cloudcode-pa.googleapis.com";
const jsonHeaders = { "content-type": "application/json" };

// A fixed fingerprint so prepareAntigravityRequest's headers are deterministic (bypasses
// getSessionFingerprint), identical across both runs.
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

// Capture the OUTBOUND request each path hands to fetch, so the harness can assert the TS and Java
// prepareAntigravityRequest calls produce byte-identical wire requests (url/method/headers/body +
// host-set proxy). Header keys sorted (order not wire-significant); case preserved.
function captureOutbound(url: any, init: any) {
  init = init || {};
  const rawHeaders = init.headers || {};
  const headers: Record<string, string> = {};
  for (const k of Object.keys(rawHeaders).sort()) headers[k] = String(rawHeaders[k]);
  return { url: String(url), method: init.method ?? null, headers, body: init.body ?? null, proxy: init.proxy ?? null };
}

async function snapshotResponse(r: Response) {
  return {
    status: r.status,
    headers: Object.fromEntries([...r.headers.entries()].sort()),
    body: await r.text(),
  };
}

async function runBothPaths(sc: any) {
  const makeReq = () =>
    new Request(sc.url || (PROD + "/v1internal/models/antigravity-claude-sonnet-4-6:generateContent"), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: sc.body ?? JSON.stringify({ contents: [{ role: "user", parts: [{ text: "hi" }] }] }),
    });
  const ctx = { model: sc.model ?? "antigravity-claude-sonnet-4-6", log: () => {} };

  resetForRun(sc);
  const tsResp = await driver.handle(makeReq(), ctx);
  const tsSnap = await snapshotResponse(tsResp);
  const tsCalls = harness.calls.slice();
  const tsOutbound = harness.outbound.slice();

  resetForRun(sc);
  const jvResp = await handleViaJavaOrchestrator(makeReq(), ctx);
  const jvSnap = await snapshotResponse(jvResp);
  const jvCalls = harness.calls.slice();
  const jvOutbound = harness.outbound.slice();

  return { tsSnap, jvSnap, tsCalls, jvCalls, tsOutbound, jvOutbound };
}

// The two paths read DIFFERENT clock primitives: the TS path calls Date.now(), while the TeaVM
// orchestrator's System.currentTimeMillis compiles to new Date().getTime() (and/or Date.now()).
// Freeze BOTH via a Date subclass whose no-arg construction and static now() are pinned to
// harness.now, while new Date(arg) still delegates to the real Date (so resetTime parse + the
// toLocaleString quota-reset formatting are unaffected). Makes every now-dependent reset identical
// across paths.
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
  delete process.env.HUB_ANTIGRAVITY_JAVA_HANDLE;
  globalThis.Date = FrozenDate as any;
  vi.spyOn(Math, "random").mockReturnValue(0.5);               // Java bakes random=0.5; match it
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

// --- scenarios --------------------------------------------------------------

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

describe("handle parity: TS path vs Java-orchestrator delegation", () => {
  for (const sc of scenarios) {
    it(sc.name, async () => {
      const { tsSnap, jvSnap, tsCalls, jvCalls, tsOutbound, jvOutbound } = await runBothPaths(sc);
      expect(jvSnap, "final Response must be identical").toEqual(tsSnap);
      expect(jvCalls, "ordered manager/proxy call sequence (incl. cooldown resetMs + ipSuspected) must be identical").toEqual(tsCalls);
      expect(jvOutbound, "outbound fetch requests must be byte-identical").toEqual(tsOutbound);
    });
  }
});

// CRITICAL-1 regression guard: fresh accounts (no discovered managed project, no pre-set synthetic id)
// must mint a UNIQUE per-account synthetic project id — otherwise every such account gets the SAME
// x-goog-user-project-equivalent (the outbound body `project` field) and gets correlated (index.ts:108-109).
// Before the fix the live export baked FIXED_RANDOM(0.5)+counterIds, so this ALWAYS produced
// "swift-spark-00000" for every account → this test FAILS. After injecting real Math.random/crypto.randomUUID
// into the orchestrator it produces distinct ids → PASSES. Uses REAL entropy (not the deterministic pin).
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

describe("flag routing", () => {
  it("HUB_ANTIGRAVITY_JAVA_HANDLE=1 makes driver.handle delegate identically to the direct Java path", async () => {
    const sc = scenarios[0];
    resetForRun(sc);
    const direct = await snapshotResponse(await handleViaJavaOrchestrator(
      new Request(PROD + "/v1internal/models/antigravity-claude-sonnet-4-6:generateContent", { method: "POST", headers: jsonHeaders, body: JSON.stringify({ contents: [] }) }),
      { model: "antigravity-claude-sonnet-4-6", log: () => {} },
    ));
    process.env.HUB_ANTIGRAVITY_JAVA_HANDLE = "1";
    resetForRun(sc);
    const viaFlag = await snapshotResponse(await driver.handle(
      new Request(PROD + "/v1internal/models/antigravity-claude-sonnet-4-6:generateContent", { method: "POST", headers: jsonHeaders, body: JSON.stringify({ contents: [] }) }),
      { model: "antigravity-claude-sonnet-4-6", log: () => {} },
    ));
    delete process.env.HUB_ANTIGRAVITY_JAVA_HANDLE;
    expect(viaFlag).toEqual(direct);
  });
});
