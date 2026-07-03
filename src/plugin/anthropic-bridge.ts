// @ts-nocheck
// Bridge so antigravity can serve Claude Code. Claude Code talks the Anthropic
// Messages API (/v1/messages); cloudcode-pa speaks Gemini (generateContent) even for
// Claude models (that's the working OpenCode path). This translates the request
// Anthropic -> Gemini and the response Gemini-SSE -> Anthropic-Messages-SSE.

import { cleanJSONSchemaForAntigravity } from "./request-helpers.js";

// ── request: Anthropic /v1/messages body -> Gemini generateContent body ──────
export function anthropicToGemini(body) {
  var contents = [];
  // Gemini pairs a functionResponse to its functionCall by NAME (not by id), so a
  // tool_result must carry the tool's NAME — but Anthropic tool_result blocks only
  // reference the tool_use_id. Pre-map every tool_use id -> name so the tool_result
  // below can look it up; without this Gemini treats every tool as unanswered and the
  // model re-runs it forever ("tool responses being lost" loop under Claude Code).
  var toolNames = {};
  for (var pm of (body.messages || [])) {
    if (Array.isArray(pm.content)) for (var pb of pm.content) {
      if (pb && pb.type === "tool_use" && pb.id) toolNames[pb.id] = pb.name;
    }
  }
  for (var msg of (body.messages || [])) {
    var role = msg.role === "assistant" ? "model" : "user";
    var parts = [];
    if (typeof msg.content === "string") {
      if (msg.content) parts.push({ text: msg.content });
    } else if (Array.isArray(msg.content)) {
      for (var block of msg.content) {
        if (block.type === "text") parts.push({ text: block.text || "" });
        else if (block.type === "tool_use") parts.push({ functionCall: { name: block.name, args: block.input || {} } });
        else if (block.type === "tool_result") {
          var c = block.content;
          var text = typeof c === "string" ? c
            : Array.isArray(c) ? c.map(function(x) { return x && x.text ? x.text : ""; }).join("")
            : c != null ? JSON.stringify(c) : "";
          var fnName = toolNames[block.tool_use_id] || block.tool_use_id || "tool";
          parts.push({ functionResponse: { name: fnName, response: { result: text } } });
        }
        // images and other block types are dropped (cloudcode-pa text path)
      }
    }
    if (parts.length) contents.push({ role: role, parts: parts });
  }

  var gem = { contents: contents };

  if (body.system) {
    var sysText = typeof body.system === "string" ? body.system
      : Array.isArray(body.system) ? body.system.map(function(s) { return s && s.text ? s.text : ""; }).join("\n")
      : "";
    if (sysText) gem.systemInstruction = { parts: [{ text: sysText }] };
  }

  var gc = {};
  if (body.max_tokens) gc.maxOutputTokens = body.max_tokens;
  if (body.temperature != null) gc.temperature = body.temperature;
  if (body.top_p != null) gc.topP = body.top_p;
  if (body.stop_sequences && body.stop_sequences.length) gc.stopSequences = body.stop_sequences;
  if (Object.keys(gc).length) gem.generationConfig = gc;

  if (Array.isArray(body.tools) && body.tools.length) {
    gem.tools = [{
      functionDeclarations: body.tools
        .filter(function(t) { return t && t.name; })
        .map(function(t) {
          // Gemini's functionDeclarations reject JSON-Schema keywords ($schema,
          // propertyNames, additionalProperties, $ref, …). Reuse the same sanitizer
          // the OpenCode Gemini path uses so tools work under Claude Code too.
          var params = cleanJSONSchemaForAntigravity(t.input_schema || { type: "object", properties: {} });
          return { name: t.name, description: t.description || "", parameters: params };
        }),
    }];
  }

  return gem;
}

// ── response: cloudcode-pa Gemini SSE -> Anthropic Messages SSE (streaming) ───
var GEMINI_STOP = { STOP: "end_turn", MAX_TOKENS: "max_tokens" };

export function geminiToAnthropicStream(model) {
  var enc = new TextEncoder();
  var dec = new TextDecoder();
  var buf = "";
  var started = false;
  var blockOpen = false;
  var blockType = null;   // "text" | "tool_use"
  var index = -1;
  var inputTokens = 0, outputTokens = 0;
  var stopReason = "end_turn";
  var msgId = "msg_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);

  function sse(event, data) { return enc.encode("event: " + event + "\ndata: " + JSON.stringify(data) + "\n\n"); }

  function ensureStart(ctrl) {
    if (started) return;
    started = true;
    ctrl.enqueue(sse("message_start", { type: "message_start", message: { id: msgId, type: "message", role: "assistant", model: model, content: [], stop_reason: null, stop_sequence: null, usage: { input_tokens: inputTokens, output_tokens: 0 } } }));
  }
  function closeBlock(ctrl) {
    if (!blockOpen) return;
    if (blockType === "thinking") {
      // Anthropic thinking blocks carry a signature; we're the endpoint so a
      // placeholder is fine (the client only echoes it back, and the request
      // translator drops incoming thinking blocks).
      ctrl.enqueue(sse("content_block_delta", { type: "content_block_delta", index: index, delta: { type: "signature_delta", signature: "antigravity-bridge" } }));
    }
    ctrl.enqueue(sse("content_block_stop", { type: "content_block_stop", index: index }));
    blockOpen = false; blockType = null;
  }
  function openText(ctrl) {
    if (blockOpen && blockType === "text") return;
    closeBlock(ctrl);
    index++; blockOpen = true; blockType = "text";
    ctrl.enqueue(sse("content_block_start", { type: "content_block_start", index: index, content_block: { type: "text", text: "" } }));
  }
  function openThinking(ctrl) {
    if (blockOpen && blockType === "thinking") return;
    closeBlock(ctrl);
    index++; blockOpen = true; blockType = "thinking";
    ctrl.enqueue(sse("content_block_start", { type: "content_block_start", index: index, content_block: { type: "thinking", thinking: "" } }));
  }

  function handleObj(obj, ctrl) {
    ensureStart(ctrl);
    var um = obj.usageMetadata;
    if (um) {
      if (um.promptTokenCount != null) inputTokens = um.promptTokenCount;
      if (um.candidatesTokenCount != null) outputTokens = um.candidatesTokenCount;
    }
    var cand = obj.candidates && obj.candidates[0];
    if (!cand) return;
    var parts = (cand.content && cand.content.parts) || [];
    for (var p of parts) {
      if (p.functionCall) {
        closeBlock(ctrl);
        index++; blockOpen = true; blockType = "tool_use";
        var tid = "toolu_" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
        ctrl.enqueue(sse("content_block_start", { type: "content_block_start", index: index, content_block: { type: "tool_use", id: tid, name: p.functionCall.name, input: {} } }));
        ctrl.enqueue(sse("content_block_delta", { type: "content_block_delta", index: index, delta: { type: "input_json_delta", partial_json: JSON.stringify(p.functionCall.args || {}) } }));
        closeBlock(ctrl);
        stopReason = "tool_use";
      } else if (p.thought || p.type === "reasoning") {
        // cloudcode-pa thinking parts -> Anthropic thinking block (not leaked as text)
        var think = p.thinking != null ? p.thinking : (p.text || "");
        if (think) {
          openThinking(ctrl);
          ctrl.enqueue(sse("content_block_delta", { type: "content_block_delta", index: index, delta: { type: "thinking_delta", thinking: think } }));
        }
      } else if (p.text) {
        openText(ctrl);
        ctrl.enqueue(sse("content_block_delta", { type: "content_block_delta", index: index, delta: { type: "text_delta", text: p.text } }));
      }
    }
    if (cand.finishReason && GEMINI_STOP[cand.finishReason] && stopReason !== "tool_use") {
      stopReason = GEMINI_STOP[cand.finishReason];
    }
  }

  return new TransformStream({
    transform: function(chunk, ctrl) {
      buf += dec.decode(chunk, { stream: true });
      var nl;
      while ((nl = buf.indexOf("\n")) >= 0) {
        var line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line || line.charAt(0) === ":" || line.indexOf("data:") !== 0) continue;
        var payload = line.slice(5).trim();
        if (!payload || payload === "[DONE]") continue;
        try { handleObj(JSON.parse(payload), ctrl); } catch (e) { /* skip partial/non-JSON */ }
      }
    },
    flush: function(ctrl) {
      ensureStart(ctrl);
      closeBlock(ctrl);
      ctrl.enqueue(sse("message_delta", { type: "message_delta", delta: { stop_reason: stopReason, stop_sequence: null }, usage: { output_tokens: outputTokens } }));
      ctrl.enqueue(sse("message_stop", { type: "message_stop" }));
    },
  });
}

// True when the inbound request is Claude Code's Anthropic Messages API.
export function isAnthropicMessages(url) {
  return typeof url === "string" && url.indexOf("/v1/messages") !== -1;
}
