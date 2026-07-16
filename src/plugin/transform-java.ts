// Task 6a: the pure TS transform layer (src/plugin/transform/*) is deleted — the Java orchestrator
// owns that logic now (ClaudeTransformsTest/GeminiTransformsTest/CrossModelSanitizerTest/
// AntigravityModelResolverTest). isGemini3Model is kept here verbatim: javaHandle.ts's SERVE
// streaming-transform seam (transformServeViaJava) still needs it host-side to gate the Gemini-3
// SSE-reconnect thought-dedup Set before the Java call, so it can't move fully into Java.
export function isGemini3Model(model: string): boolean {
  return model.toLowerCase().includes("gemini-3");
}
