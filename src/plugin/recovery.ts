// @ts-nocheck
// Error classification for the request path: maps an upstream/API error to a
// recoverable-error type so the transform layer (request.ts) can retry a turn
// (e.g. thinking_block_order) instead of failing. This is serving logic; the
// former opencode session-recovery hook (file re-injection) has been removed.

type RecoveryErrorType =
  | "tool_result_missing"
  | "thinking_block_order"
  | "thinking_disabled_violation"
  | null;

/** Extract a normalized (lower-cased) error message string from an unknown error. */
function getErrorMessage(error: unknown): string {
  if (!error) return "";
  if (typeof error === "string") return error.toLowerCase();

  const errorObj = error as Record<string, unknown>;
  const paths = [
    errorObj.data,
    errorObj.error,
    errorObj,
    (errorObj.data as Record<string, unknown>)?.error,
  ];

  for (const obj of paths) {
    if (obj && typeof obj === "object") {
      const msg = (obj as Record<string, unknown>).message;
      if (typeof msg === "string" && msg.length > 0) return msg.toLowerCase();
    }
  }

  try {
    return JSON.stringify(error).toLowerCase();
  } catch {
    return "";
  }
}

/** Detect the type of recoverable error from an error object. */
export function detectErrorType(error: unknown): RecoveryErrorType {
  const message = getErrorMessage(error);
  const hasExpectedFoundThinkingOrder =
    (message.includes("expected thinking") || message.includes("expected a thinking")) &&
    message.includes("found");

  // tool_result_missing: a tool_use turn left without its tool_result.
  if (message.includes("tool_use") && message.includes("tool_result")) {
    return "tool_result_missing";
  }

  // thinking_block_order: thinking blocks corrupted/stripped/out of order.
  if (
    message.includes("thinking") &&
    (message.includes("first block") ||
      message.includes("must start with") ||
      message.includes("preceeding") ||
      message.includes("preceding") ||
      hasExpectedFoundThinkingOrder)
  ) {
    return "thinking_block_order";
  }

  // thinking_disabled_violation: thinking content sent to a non-thinking model.
  if (message.includes("thinking is disabled") && message.includes("cannot contain")) {
    return "thinking_disabled_violation";
  }

  return null;
}

/** Check if an error is recoverable. */
export function isRecoverableError(error: unknown): boolean {
  return detectErrorType(error) !== null;
}
