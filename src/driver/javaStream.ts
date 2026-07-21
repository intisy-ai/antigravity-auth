// @ts-nocheck
// Thin host TransformStream shells driving the Java-side stateful stream mappers. Each shell decodes
// upstream bytes to text and forwards them; the Java mapper owns SSE line-buffering, JSON parsing,
// and every per-line decision.

// Real Date.now/Math.random id minting, injected into the Java stream mapper so it never bakes entropy.
export const jsIds = {
  newMessageId() {
    return "msg_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
  },
  newToolId() {
    return "toolu_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  },
};

// handleIr's stream-decode half: the Java newIrStreamMapper returns a JSON array of enriched
// (id-minted, model-overwritten) IrStreamEvents, so this shell decodes bytes to text and parses the
// array. No wire-format knowledge here.
export function makeIrStream(newIrStreamMapperFn, model, ids) {
  const mapper = newIrStreamMapperFn(model, ids);
  const dec = new TextDecoder();

  return new TransformStream({
    transform(chunk, ctrl) {
      const events = JSON.parse(mapper.handle(dec.decode(chunk, { stream: true })));
      for (const ev of events) ctrl.enqueue(ev);
    },
    flush(ctrl) {
      const events = JSON.parse(mapper.finish());
      for (const ev of events) ctrl.enqueue(ev);
    },
  });
}

// The streaming half of SERVE's response transform. This shell owns the host-side framing: the
// TextEncoder/TextDecoder, the '\n'-buffering loop, the 45s silence watchdog, and the synthetic-usage
// flush when no usageMetadata was seen. Every line's dedup/signature-cache/debug-inject/
// thinking-transform decision runs in Java via sseHandle.
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
