// @ts-nocheck
// Task 3 (TeaVM de-dup) — thin host TransformStream shell driving the Java-side stateful stream
// mapper (`newStreamMapper`). SP-2: the mapper is now `AntigravityGeminiSseBridge`, built on
// core-ir's GeminiTranslator stream decoder + AnthropicTranslator stream encoder instead of the
// deleted bespoke `AntigravityStreamMapper` -- the bridge owns its OWN `data:` line-buffering/
// parsing now (core-ir's StreamDecoder contract takes raw text chunks), so this shell no longer
// does that itself; it only decodes bytes to text and re-encodes the returned SSE frame strings.

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

// Drives ONE `newStreamMapper(model, jsIds)` instance across the life of a stream. SP-2: the Java
// bridge's `handle` now takes a raw decoded text CHUNK (partial or complete SSE lines) and does its
// own `data:` line-buffering/JSON-parsing internally (core-ir's StreamDecoder contract), so this
// shell only decodes bytes to text and forwards them -- no line/JSON handling left here.
export function makeAnthropicStream(newStreamMapperFn, model, ids) {
  const mapper = newStreamMapperFn(model, ids);
  const enc = new TextEncoder();
  const dec = new TextDecoder();

  return new TransformStream({
    transform(chunk, ctrl) {
      for (const ev of mapper.handle(dec.decode(chunk, { stream: true }))) ctrl.enqueue(enc.encode(ev));
    },
    flush(ctrl) {
      for (const ev of mapper.finish()) ctrl.enqueue(enc.encode(ev));
    },
  });
}

// Task 3c — the streaming half of SERVE's response transform (transformAntigravityResponse's
// createStreamingTransformer, request.ts:1758-1780). This shell owns exactly what stays host-side:
// the TextEncoder/TextDecoder + '\n'-buffering loop, the 45s silence watchdog, and the
// no-usageMetadata-seen synthetic-usage flush (transformer.ts:290-396) — byte-for-byte the same
// framing the retained TS `createStreamingTransformer` uses. Every line's dedup/signature-cache/
// debug-inject/thinking-transform decision runs in Java via the `sseHandle` (built by
// `newResponseSseTransformer`, real-seamed: real defaultSignatureStore + real cacheSignature + real
// processImageData — see javaHandle.ts).
export function makeResponseTransformStream(sseHandle, onComplete, onWatchdogTimeout) {
  const decoder = new TextDecoder();
  const encoder = new TextEncoder();
  let buffer = "";
  let hasSeenUsageMetadata = false;

  let watchdogTimer = null;
  let isDone = false;
  let controllerRef = null;

  const resetWatchdog = () => {
    if (isDone || !controllerRef) return;
    if (watchdogTimer) clearTimeout(watchdogTimer);
    watchdogTimer = setTimeout(() => {
      if (isDone) return;
      isDone = true;
      const finish = () => {
        try { controllerRef?.terminate(); } catch {}
        onComplete?.();
      };
      if (onWatchdogTimeout) Promise.resolve(onWatchdogTimeout()).finally(finish);
      else finish();
    }, 45000);
  };

  return new TransformStream({
    start(controller) {
      controllerRef = controller;
      resetWatchdog();
    },
    transform(chunk, controller) {
      resetWatchdog();
      buffer += decoder.decode(chunk, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() || "";
      for (const line of lines) {
        if (line.includes("usageMetadata")) hasSeenUsageMetadata = true;
        const transformedLine = sseHandle.handle(line);
        controller.enqueue(encoder.encode(transformedLine + "\n"));
      }
    },
    flush(controller) {
      isDone = true;
      if (watchdogTimer) clearTimeout(watchdogTimer);
      buffer += decoder.decode();
      if (buffer) {
        if (buffer.includes("usageMetadata")) hasSeenUsageMetadata = true;
        const transformedLine = sseHandle.handle(buffer);
        controller.enqueue(encoder.encode(transformedLine));
      }
      if (!hasSeenUsageMetadata) {
        const syntheticUsage = {
          response: { usageMetadata: { promptTokenCount: 0, candidatesTokenCount: 0, totalTokenCount: 0 } },
        };
        controller.enqueue(encoder.encode(`\ndata: ${JSON.stringify(syntheticUsage)}\n\n`));
      }
      onComplete?.();
    },
  });
}
