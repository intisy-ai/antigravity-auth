// isGemini3Model stays host-side: javaHandle.ts's SERVE streaming-transform seam
// (transformServeViaJava) gates the Gemini-3 SSE-reconnect thought-dedup Set on it before the Java
// call, so it cannot move fully into Java.
export function isGemini3Model(model: string): boolean {
  return model.toLowerCase().includes("gemini-3");
}
