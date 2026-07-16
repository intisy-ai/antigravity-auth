// @ts-nocheck
// Task 3 (TeaVM de-dup) — thin host TransformStream shell driving the Java-side stateful stream
// mapper (`newStreamMapper` / AntigravityStreamMapper), the port of `geminiToAnthropicStream`'s SSE
// state machine (anthropic-bridge.ts:102-200). This shell owns exactly what stays host-side per the
// plan: the TextEncoder/TextDecoder + line-buffering loop and the `data:` line parsing (identical to
// the TS closure it replaces) — every event-building decision runs in Java.

// Real Date.now/Math.random id minting (anthropic-bridge.ts:112/159 exactly) — injected into
// AntigravityStreamMapper via JsIdsFns so Java never bakes entropy (CRITICAL-1 lesson).
export const jsIds = {
  newMessageId() {
    return "msg_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
  },
  newToolId() {
    return "toolu_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  },
};

// Drives ONE `newStreamMapper(model, jsIds)` instance across the life of a stream — mirrors the TS
// TransformStream's transform/flush split exactly (anthropic-bridge.ts:181-200).
export function makeAnthropicStream(newStreamMapperFn, model, ids) {
  const mapper = newStreamMapperFn(model, ids);
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  let buf = "";

  function drain(text, ctrl) {
    buf += text;
    let nl;
    while ((nl = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, nl).trim();
      buf = buf.slice(nl + 1);
      if (!line || line.charAt(0) === ":" || line.indexOf("data:") !== 0) continue;
      const payload = line.slice(5).trim();
      if (!payload || payload === "[DONE]") continue;
      let obj;
      try {
        obj = JSON.parse(payload);
      } catch {
        continue; // skip partial/non-JSON, matching the TS catch(e){}
      }
      for (const ev of mapper.handle(JSON.stringify(obj))) ctrl.enqueue(enc.encode(ev));
    }
  }

  return new TransformStream({
    transform(chunk, ctrl) {
      drain(dec.decode(chunk, { stream: true }), ctrl);
    },
    flush(ctrl) {
      for (const ev of mapper.finish()) ctrl.enqueue(enc.encode(ev));
    },
  });
}

// NOTE (Task 3 scope decision — see the task report): a `makeResponseTransformStream` for SERVE's
// OWN streaming branch (transformAntigravityResponse's createStreamingTransformer, Gemini-SSE ->
// Gemini-SSE) is intentionally NOT implemented here. The only Java export that could drive it
// (`transformSseLine`) is wired with STUB seams (a no-op SignatureStore + a data-URL ImageSink
// stand-in for the real disk-saving `processImageData`) -- confirmed by reading
// AntigravityProviderJs.java, where every seam besides Task 2's three production exports is
// documented as "proves transpilability, not real behavior". Routing SERVE's transform through it
// would silently drop signature caching (breaks multi-turn Claude thinking) and break the
// generated-image file-save feature. materializeDecision therefore still calls the retained TS
// `transformAntigravityResponse` for SERVE — see the Task 3 report for the follow-up (a Task-2
// amendment exporting a real-seamed prod variant, mirroring `prepareAntigravityRequestProd`).
