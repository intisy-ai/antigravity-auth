// The stable plugin session id and the thinking-signature-caching model check used by the Java
// orchestrator (driver/javaHandle.ts + driver/javaStream.ts), which owns the request/response transform.
import crypto from "node:crypto";

const PLUGIN_SESSION_ID = `-${crypto.randomUUID()}`;

export function shouldCacheThinkingSignatures(model?: string): boolean {
  if (typeof model !== "string") return false;
  const lower = model.toLowerCase();

  return lower.includes("claude") || lower.includes("gemini-3");
}

/**
 * Synthetic thinking placeholder text used when keep_thinking=true but debug mode is off.
 * Injected via the same path as debug text (injectDebugThinking) to ensure consistent
 * signature caching and multi-turn handling.
 */
export const SYNTHETIC_THINKING_PLACEHOLDER = "[Thinking preserved]\n";

/**
 * Gets the stable session ID for this plugin instance.
 */
export function getPluginSessionId(): string {
  return PLUGIN_SESSION_ID;
}

