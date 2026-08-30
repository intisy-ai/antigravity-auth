// Verifies the Java-driven `transformServeViaJava` (materializeDecision's SERVE path:
// transformServeBodyProd / newResponseSseTransformer, real-seamed with the real defaultSignatureStore,
// processImageData, and cacheSignature/getCachedSignature) produces the frozen output
// (serve-transform-scenarios.expected.json) for non-streaming JSON, streaming SSE, the image
// (inlineData -> processImageData) case, and the Gemini-3 SSE-reconnect thought-dedup gate.
import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { transformServeViaJava } from "../driver/javaHandle.js";
import rawExpected from "./serve-transform-scenarios.expected.json";

// The real processImageData sink writes under the runner's home dir, so the saved-image path in the
// body varies by machine (dev vs CI). Scrub the directory prefix (keeping the deterministic filename)
// on BOTH the live snapshot and the frozen fixture so the parity stays machine-portable.
const IMG_DIR_RE = /[^"\s()`]*generated-images[\/\\]+/g;
function scrubImagePath(snap: any) {
  return snap && typeof snap.body === "string"
    ? { ...snap, body: snap.body.replace(IMG_DIR_RE, "<IMGDIR>/") }
    : snap;
}
const expected: any = Object.fromEntries(
  Object.entries(rawExpected as any).map(([k, v]) => [k, scrubImagePath(v)]),
);

// image-saver.ts's saveImageToDisk does a REAL `fs.writeFileSync`, stub the fs write so the test
// stays hermetic (no real file touches disk) while the CODE PATH stays the real processImageData +
// saveImageToDisk (never a stub sink). vi.mock is hoisted above the imports above by vitest, so this
// is active before javaHandle.js (which pulls in image-saver.js) is first imported. `existsSync`
// staying real (not mocked) is fine, the generated-images dir either already exists or gets a real
// (harmless) mkdirSync.
vi.mock("fs", async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, writeFileSync: () => undefined };
});

const jsonHeaders = { "content-type": "application/json" };
const sseHeaders = { "content-type": "text/event-stream" };

// Freeze only `Date` (constructor + .now()), NOT vi.useFakeTimers(), which would also fake
// setTimeout and could interfere with the streaming TransformStream's async pump / the 45s watchdog
// timer in javaStream.ts's makeResponseTransformStream. generateImageFilename (image-saver.ts) uses
// `new Date().toISOString()`; freezing it (+ Math.random) makes the two independent
// saveImageToDisk/processImageData calls (one per path) mint the SAME deterministic filename as the
// fixture was frozen with.
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
  return scrubImagePath({
    status: r.status,
    headers: Object.fromEntries([...r.headers.entries()].sort()),
    body: await r.text(),
  });
}

describe("SERVE-transform parity: Java-driven transformServeViaJava vs the frozen TS fixture", () => {
  it("non-streaming JSON, plain candidates (regression baseline)", async () => {
    const makeResponse = () =>
      new Response(JSON.stringify({ response: { candidates: [{ content: { parts: [{ text: "hello there" }] }, finishReason: "STOP" }] } }), { status: 200, headers: jsonHeaders });

    const jvSnap = await snapshotResponse(await transformServeViaJava(makeResponse(), {
      requestedModel: "antigravity-claude-sonnet-4-6", projectId: "proj-1", endpoint: "https://cloudcode-pa.googleapis.com",
      effectiveModel: "claude-sonnet-4-6", sessionId: "sess-1", streaming: false,
    }));
    expect(jvSnap).toEqual(expected.plain);
  });

  it("non-streaming JSON, thinking parts (transformThinkingParts real internal parser)", async () => {
    const makeResponse = () =>
      new Response(JSON.stringify({
        response: {
          candidates: [{
            content: { parts: [{ thought: true, text: "let me think about this" }, { text: "here is the answer" }] },
            finishReason: "STOP",
          }],
        },
      }), { status: 200, headers: jsonHeaders });

    const jvSnap = await snapshotResponse(await transformServeViaJava(makeResponse(), {
      requestedModel: "antigravity-claude-sonnet-4-6", projectId: "proj-1", endpoint: "https://cloudcode-pa.googleapis.com",
      effectiveModel: "claude-sonnet-4-6-thinking", sessionId: "sess-2", streaming: false,
    }));
    expect(jvSnap).toEqual(expected.thinking);
  });

  it("non-streaming JSON, usage metadata -> x-antigravity-* headers (2xx; the SERVE path only ever sees an ok upstream)", async () => {
    const makeResponse = () =>
      new Response(JSON.stringify({
        response: {
          candidates: [{ content: { parts: [{ text: "hello there" }] }, finishReason: "STOP" }],
          usageMetadata: { promptTokenCount: 10, candidatesTokenCount: 5, totalTokenCount: 15, cachedContentTokenCount: 3 },
        },
      }), { status: 200, headers: jsonHeaders });

    const jvSnap = await snapshotResponse(await transformServeViaJava(makeResponse(), {
      requestedModel: "antigravity-claude-opus-4", projectId: "proj-1", endpoint: "https://cloudcode-pa.googleapis.com",
      effectiveModel: "claude-opus-4", sessionId: "sess-3", streaming: false,
    }));
    expect(expected.usage.headers["x-antigravity-cached-content-token-count"]).toBe("3");
    expect(jvSnap).toEqual(expected.usage);
  });

  describe("image case (real processImageData, deterministic fs)", () => {
    beforeEach(() => freezeImageDeps(0.123456789));
    afterEach(() => unfreezeImageDeps());

    it("non-streaming JSON, inlineData part routes through the REAL processImageData sink", async () => {
      const makeResponse = () =>
        new Response(JSON.stringify({
          response: {
            candidates: [{
              content: { parts: [{ inlineData: { mimeType: "image/png", data: "ZmFrZS1pbWFnZS1ieXRlcw==" } }] },
              finishReason: "STOP",
            }],
          },
        }), { status: 200, headers: jsonHeaders });

      const jvSnap = await snapshotResponse(await transformServeViaJava(makeResponse(), {
        requestedModel: "antigravity-gemini-3-pro-image", projectId: "proj-1", endpoint: "https://cloudcode-pa.googleapis.com",
        effectiveModel: "gemini-3-pro-image", sessionId: "sess-4", streaming: false,
      }));
      // Sanity: the real code path actually ran (a markdown image link with a real saved path), not
      // the transpilability-only DATA_URL_SINK fallback (`data:...;base64,...`).
      expect((rawExpected as any).image.body).toContain("generated-images");
      expect((rawExpected as any).image.body).not.toContain("data:image/png;base64,");
      expect(jvSnap).toEqual(expected.image);
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

        const jvSnap = await snapshotResponse(await transformServeViaJava(makeResponse(), {
          requestedModel: "antigravity-claude-sonnet-4-6", projectId: "proj-1", endpoint: "https://cloudcode-pa.googleapis.com",
          effectiveModel: "claude-sonnet-4-6-thinking", sessionId: "sess-5", streaming: true,
        }));
        expect((rawExpected as any).streamingDedup.body).toContain("generated-images");
        expect(jvSnap).toEqual(expected.streamingDedup);
      } finally {
        unfreezeImageDeps();
      }
    });
  });

  describe("streaming SSE, Gemini-3 thought-dedup seam", () => {
    // Proves the Java-driven path honors the SSE-reconnect `displayedThinkingHashes` gate (frozen into
    // the fixture): a gemini-3 `effectiveModel` gets a REAL persistent dedup set (not a hard-coded
    // `null`), so an exact-repeat of a thinking part across
    // two SEPARATE streams (simulating a reconnect, each with its own fresh per-stream sent/thought
    // buffers) is dropped entirely the second time. A non-gemini-3 model must NOT dedup across streams
    // at all (the seam stays gated off, matching isGemini3Model exactly).
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

      // Stream 1 (first occurrence): the thinking text is new to the process-lifetime dedup set, so it
      // passes through unchanged.
      const jvSnap1 = await snapshotResponse(
        await transformServeViaJava(makeResponse(), { ...params, streaming: true }),
      );
      expect(expected.gemini3Stream1.body).toContain(thinkingText);
      expect(jvSnap1).toEqual(expected.gemini3Stream1);

      // Stream 2 (simulated SSE reconnect): a brand-new stream (fresh per-stream buffers) resends the
      // EXACT same thinking text. Only the process-lifetime hash set (populated by stream 1) can catch
      // this -- the per-stream delta buffers alone would treat it as brand new.
      const jvSnap2 = await snapshotResponse(
        await transformServeViaJava(makeResponse(), { ...params, streaming: true }),
      );
      expect(expected.gemini3Stream2.body).not.toContain(thinkingText);
      expect(jvSnap2).toEqual(expected.gemini3Stream2);
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
      const expectedPasses = [expected.nonGemini3Pass1, expected.nonGemini3Pass2];

      for (let i = 0; i < 2; i++) {
        const jvSnap = await snapshotResponse(
          await transformServeViaJava(makeResponse(), { ...params, streaming: true }),
        );
        expect(expectedPasses[i].body).toContain(thinkingText);
        expect(jvSnap).toEqual(expectedPasses[i]);
      }
    });
  });
});
