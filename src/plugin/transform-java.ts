// isGemini3Model stays host-side: javaHandle.ts's SERVE streaming-transform seam
// (transformServeViaJava) gates the Gemini-3 SSE-reconnect thought-dedup Set on it before the Java
// call, so it cannot move fully into Java.
/**
 * Whether a model is a third-generation one, which reconnects mid-thought and so needs the dedup set.
 *
 * @param model - the model id
 * @returns true when it is
 */
export function isGemini3Model(model: string): boolean {
  return model.toLowerCase().includes("gemini-3");
}
