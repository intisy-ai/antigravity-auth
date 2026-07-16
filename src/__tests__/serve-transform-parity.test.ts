// @ts-nocheck
// Task 3c parity gate: freezes the retained TS `transformAntigravityResponse`'s SERVE-transform
// output and proves the Java-driven `transformServeViaJava` (materializeDecision's new SERVE path —
// transformServeBodyProd / newResponseSseTransformer, real-seamed: real defaultSignatureStore, real
// processImageData, real cacheSignature/getCachedSignature) byte-matches it for the 3 scenarios the
// brief calls out: non-streaming JSON, streaming SSE, and the image (inlineData -> processImageData)
// case. `handle-parity.test.ts` already re-exercises the non-streaming path end-to-end (its Gemini
// scenarios are all non-streaming); this file adds direct, narrower coverage plus the two scenarios
// handle-parity's scripted fetch bodies don't happen to hit (thinking parts, image parts, streaming
// dedup).
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { transformAntigravityResponse } from "../plugin/request.js";
import { transformServeViaJava, loadOrchestrator } from "../driver/javaHandle.js";

// image-saver.ts's saveImageToDisk does a REAL `fs.writeFileSync` — stub the fs write so the test
// stays hermetic (no real file touches disk) while the CODE PATH stays the real processImageData +
// saveImageToDisk (never a stub sink). vi.mock is hoisted above the imports above by vitest, so this
// is active before javaHandle.js (which pulls in image-saver.js) is first imported. `existsSync`
// staying real (not mocked) is fine — the generated-images dir either already exists or gets a real
// (harmless) mkdirSync.
vi.mock("fs", async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, writeFileSync: () => undefined };
});

const jsonHeaders = { "content-type": "application/json" };
const sseHeaders = { "content-type": "text/event-stream" };

// Freeze only `Date` (constructor + .now()) — NOT vi.useFakeTimers(), which would also fake
// setTimeout and could interfere with the streaming TransformStream's async pump / the 45s watchdog
// timer in javaStream.ts's makeResponseTransformStream. generateImageFilename (image-saver.ts) uses
// `new Date().toISOString()`; freezing it (+ Math.random) makes the two independent
// saveImageToDisk/processImageData calls (one per path) mint the SAME deterministic filename.
const RealDate = globalThis.Date;
class FrozenDate extends RealDate {
  constructor(...args) {
    if (args.length === 0) super("2026-07-16T00:00:00.000Z");
    else super(...args);
  }
  static now() { return RealDate.parse("2026-07-16T00:00:00.000Z"); }
}
function freezeImageDeps(randomValue) {
  globalThis.Date = FrozenDate;
  vi.spyOn(Math, "random").mockReturnValue(randomValue);
}
function unfreezeImageDeps() {
  globalThis.Date = RealDate;
  vi.restoreAllMocks();
}

async function snapshotResponse(r) {
  return {
    status: r.status,
    headers: Object.fromEntries([...r.headers.entries()].sort()),
    body: await r.text(),
  };
}

// Every scenario supplies a factory (fresh Response each call — bodies/streams are single-read) plus
// the SAME driver-relevant params materializeDecision passes (decision.params shape).
async function runBoth(makeResponse, streaming, params) {
  const tsResp = await transformAntigravityResponse(
    makeResponse(), streaming, null,
    params.requestedModel, params.projectId, params.endpoint, params.effectiveModel, params.sessionId,
  );
  const tsSnap = await snapshotResponse(tsResp);

  const orchestrator = await loadOrchestrator();
  const jvResp = await transformServeViaJava(orchestrator, makeResponse(), { ...params, streaming });
  const jvSnap = await snapshotResponse(jvResp);

  return { tsSnap, jvSnap };
}

describe("SERVE-transform parity: TS transformAntigravityResponse vs Java-driven transformServeViaJava", () => {
  it("non-streaming JSON — plain candidates (regression baseline)", async () => {
    const makeResponse = () =>
      new Response(JSON.stringify({ response: { candidates: [{ content: { parts: [{ text: "hello there" }] }, finishReason: "STOP" }] } }), { status: 200, headers: jsonHeaders });

    const { tsSnap, jvSnap } = await runBoth(makeResponse, false, {
      requestedModel: "antigravity-claude-sonnet-4-6", projectId: "proj-1", endpoint: "https://cloudcode-pa.googleapis.com",
      effectiveModel: "claude-sonnet-4-6", sessionId: "sess-1",
    });
    expect(jvSnap).toEqual(tsSnap);
  });

  it("non-streaming JSON — thinking parts (transformThinkingParts real internal parser)", async () => {
    const makeResponse = () =>
      new Response(JSON.stringify({
        response: {
          candidates: [{
            content: { parts: [{ thought: true, text: "let me think about this" }, { text: "here is the answer" }] },
            finishReason: "STOP",
          }],
        },
      }), { status: 200, headers: jsonHeaders });

    const { tsSnap, jvSnap } = await runBoth(makeResponse, false, {
      requestedModel: "antigravity-claude-sonnet-4-6", projectId: "proj-1", endpoint: "https://cloudcode-pa.googleapis.com",
      effectiveModel: "claude-sonnet-4-6-thinking", sessionId: "sess-2",
    });
    expect(jvSnap).toEqual(tsSnap);
  });

  it("non-streaming JSON — usage metadata -> x-antigravity-* headers (2xx; the SERVE path only ever sees an ok upstream)", async () => {
    const makeResponse = () =>
      new Response(JSON.stringify({
        response: {
          candidates: [{ content: { parts: [{ text: "hello there" }] }, finishReason: "STOP" }],
          usageMetadata: { promptTokenCount: 10, candidatesTokenCount: 5, totalTokenCount: 15, cachedContentTokenCount: 3 },
        },
      }), { status: 200, headers: jsonHeaders });

    const { tsSnap, jvSnap } = await runBoth(makeResponse, false, {
      requestedModel: "antigravity-claude-opus-4", projectId: "proj-1", endpoint: "https://cloudcode-pa.googleapis.com",
      effectiveModel: "claude-opus-4", sessionId: "sess-3",
    });
    expect(tsSnap.headers["x-antigravity-cached-content-token-count"]).toBe("3");
    expect(jvSnap).toEqual(tsSnap);
  });

  describe("image case (real processImageData, deterministic fs)", () => {
    beforeEach(() => freezeImageDeps(0.123456789));
    afterEach(() => unfreezeImageDeps());

    it("non-streaming JSON — inlineData part routes through the REAL processImageData sink", async () => {
      const makeResponse = () =>
        new Response(JSON.stringify({
          response: {
            candidates: [{
              content: { parts: [{ inlineData: { mimeType: "image/png", data: "ZmFrZS1pbWFnZS1ieXRlcw==" } }] },
              finishReason: "STOP",
            }],
          },
        }), { status: 200, headers: jsonHeaders });

      const { tsSnap, jvSnap } = await runBoth(makeResponse, false, {
        requestedModel: "antigravity-gemini-3-pro-image", projectId: "proj-1", endpoint: "https://cloudcode-pa.googleapis.com",
        effectiveModel: "gemini-3-pro-image", sessionId: "sess-4",
      });
      // Sanity: the real code path actually ran (a markdown image link with a real saved path), not
      // the transpilability-only DATA_URL_SINK fallback (`data:...;base64,...`).
      expect(tsSnap.body).toContain("generated-images");
      expect(tsSnap.body).not.toContain("data:image/png;base64,");
      expect(jvSnap).toEqual(tsSnap);
    });
  });

  describe("streaming SSE", () => {
    it("dedup + real image sink across two chunks", async () => {
      freezeImageDeps(0.987654321);
      try {
        const line1 = JSON.stringify({ response: { candidates: [{ content: { parts: [{ thought: true, text: "thinking about the" }] } }] } });
        const line2 = JSON.stringify({ response: { candidates: [{ content: { parts: [{ thought: true, text: "thinking about the problem" }, { inlineData: { mimeType: "image/png", data: "ZmFrZS1pbWFnZS1ieXRlcw==" } }] } }] } });
        const sseBody = `data: ${line1}\n\ndata: ${line2}\n\n`;

        const makeResponse = () => new Response(sseBody, { status: 200, headers: sseHeaders });

        const { tsSnap, jvSnap } = await runBoth(makeResponse, true, {
          requestedModel: "antigravity-claude-sonnet-4-6", projectId: "proj-1", endpoint: "https://cloudcode-pa.googleapis.com",
          effectiveModel: "claude-sonnet-4-6-thinking", sessionId: "sess-5",
        });
        expect(tsSnap.body).toContain("generated-images");
        expect(jvSnap).toEqual(tsSnap);
      } finally {
        unfreezeImageDeps();
      }
    });
  });

  describe("streaming SSE — Gemini-3 thought-dedup seam (Task 3d)", () => {
    // Proves the Java-driven path now honors the SSE-reconnect `displayedThinkingHashes` gate exactly
    // like TS (request.ts:1770): a gemini-3 `effectiveModel` gets a REAL persistent dedup set (not the
    // hard-coded `null` this task closes), so an exact-repeat of a thinking part across two SEPARATE
    // streams (simulating a reconnect, each with its own fresh per-stream sent/thought buffers) is
    // dropped entirely the second time. A non-gemini-3 model must NOT dedup across streams at all
    // (the seam stays gated off, matching TS's `isGemini3Model` check exactly).
    it("gemini-3: first stream passes the thinking text through, second (reconnect) stream drops the exact repeat", async () => {
      const thinkingText = "reconsidering the approach to this particular problem";
      const thinkingLine = JSON.stringify({ response: { candidates: [{ content: { parts: [{ thought: true, text: thinkingText }] } }] } });
      const answerLine = JSON.stringify({ response: { candidates: [{ content: { parts: [{ text: "final answer" }] } }] } });
      const sseBody = `data: ${thinkingLine}\n\ndata: ${answerLine}\n\n`;
      const makeResponse = () => new Response(sseBody, { status: 200, headers: sseHeaders });

      const params = {
        requestedModel: "antigravity-gemini-3-pro", projectId: "proj-1", endpoint: "https://cloudcode-pa.googleapis.com",
        effectiveModel: "gemini-3-pro", sessionId: "sess-6",
      };
      const orchestrator = await loadOrchestrator();

      // Stream 1 (first occurrence): the thinking text is new to the process-lifetime dedup set on
      // BOTH paths, so it passes through unchanged.
      const tsSnap1 = await snapshotResponse(
        await transformAntigravityResponse(makeResponse(), true, null, params.requestedModel, params.projectId, params.endpoint, params.effectiveModel, params.sessionId),
      );
      const jvSnap1 = await snapshotResponse(
        await transformServeViaJava(orchestrator, makeResponse(), { ...params, streaming: true }),
      );
      expect(tsSnap1.body).toContain(thinkingText);
      expect(jvSnap1).toEqual(tsSnap1);

      // Stream 2 (simulated SSE reconnect): a brand-new stream (fresh per-stream buffers) resends the
      // EXACT same thinking text. Only the process-lifetime hash set (populated by stream 1) can catch
      // this -- the per-stream delta buffers alone would treat it as brand new.
      const tsSnap2 = await snapshotResponse(
        await transformAntigravityResponse(makeResponse(), true, null, params.requestedModel, params.projectId, params.endpoint, params.effectiveModel, params.sessionId),
      );
      const jvSnap2 = await snapshotResponse(
        await transformServeViaJava(orchestrator, makeResponse(), { ...params, streaming: true }),
      );
      expect(tsSnap2.body).not.toContain(thinkingText);
      expect(jvSnap2).toEqual(tsSnap2);
    });

    it("non-gemini-3: the dedup gate stays off, so an exact repeat across two streams passes through both times", async () => {
      const thinkingText = "a distinct non-gemini-3 thinking snippet";
      const thinkingLine = JSON.stringify({ response: { candidates: [{ content: { parts: [{ thought: true, text: thinkingText }] } }] } });
      const sseBody = `data: ${thinkingLine}\n\n`;
      const makeResponse = () => new Response(sseBody, { status: 200, headers: sseHeaders });

      const params = {
        requestedModel: "antigravity-claude-sonnet-4-6", projectId: "proj-1", endpoint: "https://cloudcode-pa.googleapis.com",
        effectiveModel: "claude-sonnet-4-6-thinking", sessionId: "sess-7",
      };
      const orchestrator = await loadOrchestrator();

      for (let i = 0; i < 2; i++) {
        const tsSnap = await snapshotResponse(
          await transformAntigravityResponse(makeResponse(), true, null, params.requestedModel, params.projectId, params.endpoint, params.effectiveModel, params.sessionId),
        );
        const jvSnap = await snapshotResponse(
          await transformServeViaJava(orchestrator, makeResponse(), { ...params, streaming: true }),
        );
        expect(tsSnap.body).toContain(thinkingText);
        expect(jvSnap).toEqual(tsSnap);
      }
    });
  });
});
