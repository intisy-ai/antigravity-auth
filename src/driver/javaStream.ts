// Thin host TransformStream shells driving the Java-side stateful stream mappers. Each shell decodes
// upstream bytes to text and forwards them; the Java mapper owns SSE line-buffering, JSON parsing,
// and every per-line decision.

import type {
  AntigravityIrStreamMapperHandle,
  AntigravitySseTransformHandle,
  AntigravityStreamIdsShape,
} from "../generated/antigravity-orchestrator.teavm.js";
import type { IrStreamEvent } from "@intisy-ai/basekit/ir";

/**
 * Real id minting, injected into the Java stream mapper so it never bakes entropy into the bundle.
 */
export const jsIds: AntigravityStreamIdsShape = {
  newMessageId() {
    return "msg_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
  },
  newToolId() {
    return "toolu_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  },
};

/**
 * The stream-decode half of `handleIr`: upstream bytes in, canonical IR events out.
 *
 * @remarks
 * The Java mapper answers with the enriched events as a JSON array, so this shell only decodes
 * bytes to text and parses what comes back. No wire-format knowledge lives here.
 *
 * @param newIrStreamMapperFn - the Java factory for one stream's mapper
 * @param model - the model to stamp on every event
 * @param ids - the host's id minting
 * @returns the transform to pipe the upstream body through
 */
export function makeIrStream(
  newIrStreamMapperFn: (model: string, ids: AntigravityStreamIdsShape) => AntigravityIrStreamMapperHandle,
  model: string,
  ids: AntigravityStreamIdsShape,
): TransformStream<Uint8Array, IrStreamEvent> {
  const mapper = newIrStreamMapperFn(model, ids);
  const decoder = new TextDecoder();

  return new TransformStream<Uint8Array, IrStreamEvent>({
    transform(chunk, controller) {
      const events: IrStreamEvent[] = JSON.parse(mapper.handle(decoder.decode(chunk, { stream: true })));
      for (const event of events) controller.enqueue(event);
    },
    flush(controller) {
      const events: IrStreamEvent[] = JSON.parse(mapper.finish());
      for (const event of events) controller.enqueue(event);
    },
  });
}

const WATCHDOG_MS = 45000;

/**
 * The streaming half of a served response's transform.
 *
 * @remarks
 * This shell owns the host-side framing: the encoder pair, the newline-buffering loop, the silence
 * watchdog, and the synthetic usage record emitted when the upstream sent none. Every line's dedup,
 * signature-cache, debug-inject and thinking-transform decision runs in Java behind `sseHandle`.
 *
 * @param sseHandle - the Java transformer for this one response
 * @param onComplete - run once the stream is finished, however it finished
 * @param onWatchdogTimeout - run when the upstream falls silent, before the stream is terminated
 * @returns the transform to pipe the upstream body through
 */
export function makeResponseTransformStream(
  sseHandle: AntigravitySseTransformHandle,
  onComplete?: () => void,
  onWatchdogTimeout?: () => unknown,
): TransformStream<Uint8Array, Uint8Array> {
  const decoder = new TextDecoder();
  const encoder = new TextEncoder();
  let buffer = "";
  let hasSeenUsageMetadata = false;

  let watchdogTimer: ReturnType<typeof setTimeout> | null = null;
  let isDone = false;
  let controllerRef: TransformStreamDefaultController<Uint8Array> | null = null;

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
    }, WATCHDOG_MS);
  };

  return new TransformStream<Uint8Array, Uint8Array>({
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
        controller.enqueue(encoder.encode(sseHandle.handle(line) + "\n"));
      }
    },
    flush(controller) {
      isDone = true;
      if (watchdogTimer) clearTimeout(watchdogTimer);
      buffer += decoder.decode();
      if (buffer) {
        if (buffer.includes("usageMetadata")) hasSeenUsageMetadata = true;
        controller.enqueue(encoder.encode(sseHandle.handle(buffer)));
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
