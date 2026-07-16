// @ts-nocheck
// Bridge so antigravity can serve Claude Code. Claude Code talks the Anthropic Messages API
// (/v1/messages); cloudcode-pa speaks Gemini (generateContent) even for Claude models. The
// translation itself (anthropicToGemini / geminiToAnthropicStream) is now Java (see
// driver/javaHandle.ts's handleAnthropicMessagesViaJava + driver/javaStream.ts's makeAnthropicStream);
// this file keeps only the request-routing detector both the TS and Java paths need.

// True when the inbound request is Claude Code's Anthropic Messages API.
export function isAnthropicMessages(url) {
  return typeof url === "string" && url.indexOf("/v1/messages") !== -1;
}
