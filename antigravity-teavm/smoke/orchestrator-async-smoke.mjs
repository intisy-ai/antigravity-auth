// Drives the TeaVM-compiled AntigravityProviderJs.handleAntigravityRequestAsync with GENUINELY async
// JS fakes (real setTimeout delays before every resolve), so the (up to FOUR) @Async bridges
// (JsAccountOpsBridge.acquire, JsProjectLoaderBridge.load, JsProjectOnboarderBridge.onboard,
// JsAttemptExecutorBridge.execute) actually suspend/resume inside ONE CPS-transformed orchestrator
// (handle -> attemptModel -> resolveProjectId) call graph, not synchronously-resolved promises.
//
// It asserts, per scenario, that (a) the ordered AccountOps call sequence and (b) the final
// HandleDecision match the expected values from the Java tests (AntigravityHandleOrchestratorTest:
// happyPath, rateThenRotate, noAccountNoReset). A fourth scenario drives the cold-account
// acquire->load->onboard->execute chain to prove all four @Async bridges interleave. Exits non-zero
// on ANY mismatch.
//
// Run from repo root, AFTER `./gradlew :antigravity-teavm:generateJavaScript`:
//   node antigravity-teavm/smoke/orchestrator-async-smoke.mjs

import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const HERE = dirname(fileURLToPath(import.meta.url));
const MODULE_PATH = join(HERE, "..", "build", "generated", "teavm", "js", "antigravity-provider.js");

function pathToFileUrl(p) {
  return "file://" + (p.startsWith("/") ? p : "/" + p.replace(/\\/g, "/"));
}

const { handleAntigravityRequestAsync } = await import(pathToFileUrl(MODULE_PATH));

// -- helpers -----------------------------------------------------------------

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const FIXED_NOW = 1700000000000; // deterministic clock via config.nowMs
const PROD = "https://cloudcode-pa.googleapis.com";
const ANTI_URL = PROD + "/v1internal/models/antigravity-claude-sonnet-4-6:generateContent";
const CONFIG = JSON.stringify({ nowMs: FIXED_NOW });

function deepEqual(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

let failures = 0;
function assertEqual(label, actual, expected) {
  if (deepEqual(actual, expected)) {
    console.log(`    OK   ${label}`);
  } else {
    failures++;
    console.log(`    FAIL ${label}`);
    console.log(`         expected: ${JSON.stringify(expected)}`);
    console.log(`         actual:   ${JSON.stringify(actual)}`);
  }
}

// A "quiet" account: managed project already known -> resolveProjectId short-circuits (no loader).
function quietAccount(id) {
  return {
    id,
    refresh: "rt-" + id,
    expires: FIXED_NOW + 3600000,
    meta: { managedProjectId: "mp-" + id, syntheticProjectId: "syn-" + id },
  };
}

function modelFromUrl(url) {
  const m = url.match(/\/models\/([^:/?]+)/);
  return m ? m[1] : "antigravity-auto";
}

// Derives transform params from url + endpoint (stub preparer).
function makePreparer() {
  return (url, bodyText, method, headersJson, access, projectId, endpoint, headerStyle, accountJson) => {
    const requestedModel = modelFromUrl(url);
    const streaming = url.includes("streamGenerateContent") || url.includes("alt=sse");
    return JSON.stringify({
      requestRef: { endpoint },
      params: { requestedModel, projectId, endpoint, effectiveModel: requestedModel, sessionId: "sess-fixed", streaming },
    });
  };
}

// Build the JS fakes for one scenario. `acquireScript`/`execScript` are queues (one entry per call);
// every async fake awaits a real delay first, so suspend/resume is genuinely exercised. Optional
// `loadResult`/`onboardResult` drive the project-context async bridges.
function makeFakes(opts) {
  const { acquireScript, execScript, nextAvailable = null, accounts = [], loadResult = null, onboardResult = null } = opts;
  const accountCalls = []; // ordered AccountOps calls (matches RecordingAccountOps.seq format)
  const timeline = []; // async markers, evidence of acquire/load/onboard/execute interleaving
  const mutations = {}; // accountId -> persisted account (host-applied mutate instruction)
  const counts = { acquire: 0, exec: 0, load: 0, onboard: 0 };
  let acquireIdx = 0;
  let execIdx = 0;

  const jsAcquire = async (lane) => {
    counts.acquire++;
    await delay(25);
    const next = acquireScript[acquireIdx++];
    if (!next) {
      accountCalls.push(["acquire", lane]);
      timeline.push("acquire");
      return null; // JS null -> bridge returns Java null (no account free)
    }
    accountCalls.push(["acquire", lane]);
    timeline.push("acquire");
    return JSON.stringify(next);
  };

  const jsExec = async (accountId, preparedRefJson) => {
    counts.exec++;
    await delay(35);
    const r = execScript[execIdx++];
    timeline.push("execute");
    return JSON.stringify(r);
  };

  const jsLoad = async (accessToken, projectId, proxy) => {
    counts.load++;
    await delay(20);
    timeline.push("load");
    return loadResult == null ? null : JSON.stringify(loadResult);
  };

  const jsOnboard = async (accessToken, tierId, projectId, proxy) => {
    counts.onboard++;
    await delay(20);
    timeline.push("onboard");
    return onboardResult == null ? null : JSON.stringify(onboardResult);
  };

  const jsAccountOps = {
    nextAvailableAt(lane) {
      accountCalls.push(["nextAvailableAt", lane]);
      return JSON.stringify(nextAvailable);
    },
    reportError(accountId, attempt, message) {
      accountCalls.push(["reportError", accountId, attempt, message]);
    },
    reportRateLimit(accountId, lane, resetMs) {
      accountCalls.push(["reportRateLimit", accountId, lane, resetMs]);
    },
    reportSuccess(accountId) {
      accountCalls.push(["reportSuccess", accountId]);
    },
    reportProxyRateLimit(accountId, ipSuspected) {
      accountCalls.push(["reportProxyRateLimit", accountId, ipSuspected]);
    },
    list() {
      return JSON.stringify(accounts); // NOT recorded in seq (matches RecordingAccountOps.list)
    },
    mutate(accountId, updatedAccountJson) {
      accountCalls.push(["mutate", accountId]);
      mutations[accountId] = JSON.parse(updatedAccountJson);
    },
  };

  return { jsAcquire, jsExec, jsLoad, jsOnboard, jsAccountOps, jsPreparer: makePreparer(), accountCalls, timeline, mutations, counts };
}

function ok(attemptRef) {
  return { status: 200, ok: true, transportFailed: false, attemptRef, proxyUsed: false };
}
function rate(attemptRef) {
  return {
    status: 429, ok: false, transportFailed: false, attemptRef,
    errorMessage: "quota reached, resets after 30s", errorReason: "RESOURCE_EXHAUSTED", proxyUsed: true,
  };
}

// Deterministic entropy stand-ins passed through the SAME jsRandom/jsUuid seams the production
// javaHandle wires to Math.random / crypto.randomUUID (kept fixed for determinism; the smoke's
// accounts already carry syntheticProjectId, so these don't fire).
const FIXED_RANDOM = () => 0.5;
const FIXED_UUID = () => "00000000-0000-4000-8000-000000000001";

async function call(f, inputs, autoCandidatesJson = null) {
  return JSON.parse(await handleAntigravityRequestAsync(
    inputs, CONFIG, f.jsExec, f.jsAcquire, f.jsAccountOps, f.jsLoad, f.jsOnboard, f.jsPreparer, autoCandidatesJson,
    FIXED_RANDOM, FIXED_UUID));
}

async function run() {
  console.log(`module: ${MODULE_PATH}\n`);

  // ---- Scenario 1: happy path (quiet acc -> acquire + execute, no project I/O) --------------------
  {
    console.log("Scenario 1 — happy path (async acquire -> async execute -> SERVE 200):");
    const t0 = Date.now();
    const f = makeFakes({ acquireScript: [{ accountId: "acc1", access: "at1", account: quietAccount("acc1") }], execScript: [ok(0)] });
    const inputs = JSON.stringify({ ctxModel: "antigravity-claude-sonnet-4-6", url: ANTI_URL, method: "POST", headers: {} });
    const decision = await call(f, inputs);
    const elapsed = Date.now() - t0;

    console.log(`    timeline: ${f.timeline.join(" -> ")}   elapsed: ${elapsed}ms (>=60ms => both async gaps awaited)`);
    assertEqual("real async elapsed >= 60ms", elapsed >= 60, true);
    assertEqual("no project-context async on quiet account (load/onboard uncalled)", [f.counts.load, f.counts.onboard], [0, 0]);
    assertEqual("ordered AccountOps calls", f.accountCalls, [["acquire", "claude"], ["reportSuccess", "acc1"]]);
    assertEqual("decision kind/status", [decision.kind, decision.status], ["SERVE", 200]);
    assertEqual("decision params", decision.params, {
      requestedModel: "antigravity-claude-sonnet-4-6", projectId: "mp-acc1", endpoint: PROD,
      effectiveModel: "antigravity-claude-sonnet-4-6", sessionId: "sess-fixed", streaming: false,
    });
  }

  // ---- Scenario 2: 429-then-rotate (acquire/execute interleave repeatedly across the loop) --------
  {
    console.log("\nScenario 2 — 429-then-rotate (rotate account across the retry loop):");
    const t0 = Date.now();
    const f = makeFakes({
      acquireScript: [
        { accountId: "acc1", access: "at1", account: quietAccount("acc1") },
        { accountId: "acc2", access: "at2", account: quietAccount("acc2") },
      ],
      execScript: [rate(0), rate(1), rate(2), ok(3)], // acc1 rate-limited on all 3 endpoints, acc2 ok
      accounts: [quietAccount("acc1"), quietAccount("acc2")],
    });
    const inputs = JSON.stringify({ ctxModel: "antigravity-claude-sonnet-4-6", url: ANTI_URL, method: "POST", headers: {} });
    const decision = await call(f, inputs);
    const elapsed = Date.now() - t0;

    console.log(`    timeline: ${f.timeline.join(" -> ")}`);
    console.log(`    elapsed:  ${elapsed}ms (>=190ms => 2 acquire + 4 execute async gaps awaited)`);
    assertEqual("real async elapsed >= 190ms", elapsed >= 190, true);
    assertEqual("interleave order (2x acquire, 4x execute)", f.timeline,
      ["acquire", "execute", "execute", "execute", "acquire", "execute"]);
    assertEqual("ordered AccountOps calls", f.accountCalls, [
      ["acquire", "claude"],
      ["reportRateLimit", "acc1", "claude", 1700000030000], ["reportProxyRateLimit", "acc1", false],
      ["reportRateLimit", "acc1", "claude", 1700000030000], ["reportProxyRateLimit", "acc1", false],
      ["reportRateLimit", "acc1", "claude", 1700000030000], ["reportProxyRateLimit", "acc1", false],
      ["acquire", "claude"], ["reportSuccess", "acc2"],
    ]);
    assertEqual("decision kind/status", [decision.kind, decision.status], ["SERVE", 200]);
    assertEqual("decision serves acc2's project", decision.params.projectId, "mp-acc2");
  }

  // ---- Scenario 3: no-account -> SYNTHETIC 503 ---------------------------------------------------
  {
    console.log("\nScenario 3 — no-account (async acquire -> null, no reset):");
    const t0 = Date.now();
    const f = makeFakes({ acquireScript: [], execScript: [], nextAvailable: null });
    const inputs = JSON.stringify({ ctxModel: "antigravity-claude-sonnet-4-6", url: ANTI_URL, method: "POST", headers: {} });
    const decision = await call(f, inputs);
    const elapsed = Date.now() - t0;

    console.log(`    timeline: ${f.timeline.join(" -> ")}   elapsed: ${elapsed}ms (>=25ms => acquire gap awaited)`);
    assertEqual("real async elapsed >= 25ms", elapsed >= 25, true);
    assertEqual("ordered AccountOps calls", f.accountCalls, [["acquire", "claude"], ["nextAvailableAt", "claude"]]);
    assertEqual("decision kind/status", [decision.kind, decision.status], ["SYNTHETIC", 503]);
    assertEqual("decision content-type", decision.headers["content-type"], "application/json");
    assertEqual("decision body (no-account wording)", decision.body,
      '{"error":{"message":"No available antigravity account for lane claude."}}');
  }

  // ---- Scenario 4: cold account -> FOUR @Async bridges in one call graph --------------------------
  {
    console.log("\nScenario 4 — cold account (acquire -> load -> onboard -> execute, ALL FOUR @Async):");
    const t0 = Date.now();
    const coldAccount = { id: "r1", refresh: "rt-r1", expires: FIXED_NOW + 3600000, meta: { syntheticProjectId: "syn-r1" } };
    const f = makeFakes({
      acquireScript: [{ accountId: "r1", access: "atR", account: coldAccount }],
      execScript: [ok(7)],
      accounts: [coldAccount],
      loadResult: { allowedTiers: [{ id: "FREE-TIER", isDefault: true }] }, // no cloudaicompanionProject -> onboard
      onboardResult: "onb-proj",
    });
    const inputs = JSON.stringify({ ctxModel: "antigravity-claude-sonnet-4-6", url: ANTI_URL, method: "POST", headers: {} });
    const decision = await call(f, inputs);
    const elapsed = Date.now() - t0;

    console.log(`    timeline: ${f.timeline.join(" -> ")}`);
    console.log(`    elapsed:  ${elapsed}ms (>=100ms => acquire+load+onboard+execute async gaps awaited)`);
    assertEqual("real async elapsed >= 100ms", elapsed >= 100, true);
    assertEqual("FOUR distinct @Async bridges interleaved in order", f.timeline, ["acquire", "load", "onboard", "execute"]);
    assertEqual("each project async bridge fired once", [f.counts.load, f.counts.onboard], [1, 1]);
    assertEqual("ordered AccountOps calls (mutate persists discovered managed id)", f.accountCalls,
      [["acquire", "claude"], ["mutate", "r1"], ["reportSuccess", "r1"]]);
    assertEqual("persisted managedProjectId", f.mutations["r1"].meta.managedProjectId, "onb-proj");
    assertEqual("decision kind/status", [decision.kind, decision.status], ["SERVE", 200]);
    assertEqual("decision serves the onboarded project", decision.params.projectId, "onb-proj");
  }

  console.log("");
  if (failures > 0) {
    console.log(`RESULT: ${failures} assertion(s) FAILED`);
    process.exit(1);
  }
  console.log("RESULT: all scenarios passed — up to FOUR @Async bridges composed correctly under TeaVM 0.15");
}

run().catch((e) => {
  console.error("smoke crashed:", e);
  process.exit(1);
});
