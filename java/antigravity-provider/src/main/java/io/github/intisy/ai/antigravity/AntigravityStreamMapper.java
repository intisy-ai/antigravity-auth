package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of the PURE core of {@code geminiToAnthropicStream} in
 * {@code src/plugin/anthropic-bridge.ts} (T7d): the SSE-event state machine
 * ({@code handleObj}/{@code ensureStart}/{@code closeBlock}/{@code openText}/{@code openThinking}/
 * {@code sse}, :102-179) plus the two {@code flush} emissions (:197-198). Given a parsed Gemini
 * object (already-JSON {@link Map}) it RETURNS the ordered list of Anthropic-Messages SSE event
 * strings, instead of {@code ctrl.enqueue}. The {@code new TransformStream({transform,flush})} shell,
 * the {@code TextEncoder}/{@code TextDecoder} and the line-buffering loop (:181-200) STAY in TS
 * (Bucket C) -- this class is what that shell would drive.
 *
 * <p>Usage: construct once per response ({@code msgId} is minted at construction, mirroring the TS
 * closure), call {@link #handle(Object)} for each parsed {@code data:} object in stream order, then
 * {@link #finish()} once for the flush ({@code message_delta} + {@code message_stop}).
 *
 * <h2>Injected edge -- ids</h2>
 * The TS builds ids with {@code "msg_" + Date.now().toString(36) + Math.random().toString(36).slice(...)}
 * and {@code "toolu_" + ...}. Those are Date.now/Math.random EDGES, and JS
 * {@code Number.prototype.toString(36)} on a fractional {@code Math.random()} value is NOT reproducible
 * by Java's numeric formatting -- so the id base36 FORMAT stays in the TS production edge (which lives
 * in the Bucket-C TransformStream shell anyway), and Java receives ids through the injected
 * {@link IdGenerator} functional interface. Parity tests replay the exact ids the real TS emitted
 * (harness-captured, with Date.now/Math.random stubbed). No real UUID/Date/Math.random is called here.
 *
 * <h2>Gotchas honored</h2>
 * <ul>
 *   <li><b>Branch order/precedence in {@code handleObj}</b>: per part {@code functionCall} &rarr;
 *       {@code thought/reasoning} &rarr; {@code text} (exactly this order). {@code functionCall} closes
 *       any open block, opens a {@code tool_use} block ({@code content_block_start} &rarr;
 *       {@code input_json_delta} &rarr; {@code content_block_stop}) and sets {@code stopReason="tool_use"}.</li>
 *   <li><b>thinking block close emits {@code signature_delta} "antigravity-bridge"</b> before
 *       {@code content_block_stop} (endpoint placeholder).</li>
 *   <li><b>finishReason</b> maps via {@link #GEMINI_STOP} ONLY when {@code stopReason} isn't already
 *       {@code "tool_use"} (tool_use wins).</li>
 * </ul>
 *
 * <p>{@code sse(event, data)} = {@code "event: " + event + "\ndata: " + json.stringify(data) + "\n\n"};
 * the emitted event objects are built as {@link LinkedHashMap}s in the exact TS key order so the bytes
 * match. Numeric {@code index} is emitted as {@link Integer} (no decimal); token counts pass through
 * whatever the parsed usageMetadata carried. TeaVM-transpilable.
 */
public final class AntigravityStreamMapper {

    /** anthropic-bridge.ts:100 -- Gemini finishReason -> Anthropic stop_reason. */
    static final Map<String, String> GEMINI_STOP = new LinkedHashMap<>();

    static {
        GEMINI_STOP.put("STOP", "end_turn");
        GEMINI_STOP.put("MAX_TOKENS", "max_tokens");
    }

    /** Injected id source: {@code msg_...} minted once per stream, {@code toolu_...} per functionCall. */
    public interface IdGenerator {
        String newMessageId();

        String newToolId();
    }

    private final JsonCodec json;
    private final IdGenerator ids;
    private final Object model;

    private boolean started = false;
    private boolean blockOpen = false;
    private String blockType = null; // "text" | "tool_use" | "thinking"
    private int index = -1;
    private Object inputTokens = 0;
    private Object outputTokens = 0;
    private String stopReason = "end_turn";
    private final String msgId;

    public AntigravityStreamMapper(JsonCodec json, IdGenerator ids, Object model) {
        this.json = json;
        this.ids = ids;
        this.model = model;
        this.msgId = ids.newMessageId();
    }

    // ---- public surface --------------------------------------------------------------------------

    /** Process one parsed Gemini SSE object; returns the SSE event strings it produced, in order. */
    public List<String> handle(Object objRaw) {
        List<String> out = new ArrayList<>();
        handleObj(objRaw, out);
        return out;
    }

    /** The {@code flush} emissions: ensureStart + closeBlock + message_delta + message_stop. */
    public List<String> finish() {
        List<String> out = new ArrayList<>();
        ensureStart(out);
        closeBlock(out);
        out.add(sse("message_delta", messageDeltaData()));
        out.add(sse("message_stop", messageStopData()));
        return out;
    }

    // ---- sse (anthropic-bridge.ts:114) -----------------------------------------------------------

    private String sse(String event, Object data) {
        return "event: " + event + "\ndata: " + json.stringify(data) + "\n\n";
    }

    // ---- ensureStart / closeBlock / openText / openThinking (anthropic-bridge.ts:116-143) --------

    private void ensureStart(List<String> out) {
        if (started) return;
        started = true;
        out.add(sse("message_start", messageStartData()));
    }

    private void closeBlock(List<String> out) {
        if (!blockOpen) return;
        if ("thinking".equals(blockType)) {
            out.add(sse("content_block_delta", signatureDeltaData()));
        }
        out.add(sse("content_block_stop", stopData()));
        blockOpen = false;
        blockType = null;
    }

    private void openText(List<String> out) {
        if (blockOpen && "text".equals(blockType)) return;
        closeBlock(out);
        index++;
        blockOpen = true;
        blockType = "text";
        out.add(sse("content_block_start", textStartData()));
    }

    private void openThinking(List<String> out) {
        if (blockOpen && "thinking".equals(blockType)) return;
        closeBlock(out);
        index++;
        blockOpen = true;
        blockType = "thinking";
        out.add(sse("content_block_start", thinkingStartData()));
    }

    // ---- handleObj (anthropic-bridge.ts:145-179) -------------------------------------------------

    private void handleObj(Object objRaw, List<String> out) {
        ensureStart(out);
        if (!(objRaw instanceof Map)) return; // obj.usageMetadata/obj.candidates undefined -> return
        Map<String, Object> obj = JsCoercion.asMap(objRaw);

        Object um = obj.get("usageMetadata");
        if (JsCoercion.isTruthy(um) && um instanceof Map) {
            Map<String, Object> umm = JsCoercion.asMap(um);
            if (umm.get("promptTokenCount") != null) inputTokens = umm.get("promptTokenCount");
            if (umm.get("candidatesTokenCount") != null) outputTokens = umm.get("candidatesTokenCount");
        }

        // var cand = obj.candidates && obj.candidates[0]; if (!cand) return;
        Object candList = obj.get("candidates");
        Object cand = null;
        if (JsCoercion.isTruthy(candList) && candList instanceof List) {
            List<Object> l = JsCoercion.asList(candList);
            cand = l.isEmpty() ? null : l.get(0);
        }
        if (!JsCoercion.isTruthy(cand)) return;

        Map<String, Object> candM = cand instanceof Map ? JsCoercion.asMap(cand) : new LinkedHashMap<String, Object>();
        Object contentObj = candM.get("content");
        Object partsObj = contentObj instanceof Map ? JsCoercion.asMap(contentObj).get("parts") : null;
        List<Object> parts = partsObj instanceof List ? JsCoercion.asList(partsObj) : new ArrayList<Object>();

        for (Object pObj : parts) {
            Map<String, Object> p = pObj instanceof Map ? JsCoercion.asMap(pObj) : new LinkedHashMap<String, Object>();

            if (JsCoercion.isTruthy(p.get("functionCall"))) {
                closeBlock(out);
                index++;
                blockOpen = true;
                blockType = "tool_use";
                Map<String, Object> fc = p.get("functionCall") instanceof Map
                        ? JsCoercion.asMap(p.get("functionCall")) : new LinkedHashMap<String, Object>();
                String tid = ids.newToolId();
                out.add(sse("content_block_start", toolStartData(tid, fc.get("name"))));
                Object args = JsCoercion.isTruthy(fc.get("args")) ? fc.get("args") : new LinkedHashMap<>();
                out.add(sse("content_block_delta", inputJsonDeltaData(json.stringify(args))));
                closeBlock(out);
                stopReason = "tool_use";
            } else if (JsCoercion.isTruthy(p.get("thought")) || "reasoning".equals(p.get("type"))) {
                Object think = p.get("thinking") != null ? p.get("thinking") : JsCoercion.firstTruthy(p.get("text"), "");
                if (JsCoercion.isTruthy(think)) {
                    openThinking(out);
                    out.add(sse("content_block_delta", thinkingDeltaData(think)));
                }
            } else if (JsCoercion.isTruthy(p.get("text"))) {
                openText(out);
                out.add(sse("content_block_delta", textDeltaData(p.get("text"))));
            }
        }

        Object fr = candM.get("finishReason");
        if (JsCoercion.isTruthy(fr) && GEMINI_STOP.containsKey(jsStr(fr)) && !"tool_use".equals(stopReason)) {
            stopReason = GEMINI_STOP.get(jsStr(fr));
        }
    }

    // ---- event object builders (exact TS key order) ----------------------------------------------

    private Map<String, Object> messageStartData() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", 0);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", msgId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.put("content", new ArrayList<>());
        message.put("stop_reason", null);
        message.put("stop_sequence", null);
        message.put("usage", usage);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "message_start");
        data.put("message", message);
        return data;
    }

    private Map<String, Object> signatureDeltaData() {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("type", "signature_delta");
        delta.put("signature", "antigravity-bridge");
        return blockDelta(delta);
    }

    private Map<String, Object> stopData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "content_block_stop");
        data.put("index", index);
        return data;
    }

    private Map<String, Object> textStartData() {
        Map<String, Object> cb = new LinkedHashMap<>();
        cb.put("type", "text");
        cb.put("text", "");
        return blockStart(cb);
    }

    private Map<String, Object> thinkingStartData() {
        Map<String, Object> cb = new LinkedHashMap<>();
        cb.put("type", "thinking");
        cb.put("thinking", "");
        return blockStart(cb);
    }

    private Map<String, Object> toolStartData(String tid, Object name) {
        Map<String, Object> cb = new LinkedHashMap<>();
        cb.put("type", "tool_use");
        cb.put("id", tid);
        cb.put("name", name);
        cb.put("input", new LinkedHashMap<>());
        return blockStart(cb);
    }

    private Map<String, Object> inputJsonDeltaData(String partialJson) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("type", "input_json_delta");
        delta.put("partial_json", partialJson);
        return blockDelta(delta);
    }

    private Map<String, Object> thinkingDeltaData(Object think) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("type", "thinking_delta");
        delta.put("thinking", think);
        return blockDelta(delta);
    }

    private Map<String, Object> textDeltaData(Object text) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("type", "text_delta");
        delta.put("text", text);
        return blockDelta(delta);
    }

    private Map<String, Object> blockStart(Map<String, Object> contentBlock) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "content_block_start");
        data.put("index", index);
        data.put("content_block", contentBlock);
        return data;
    }

    private Map<String, Object> blockDelta(Map<String, Object> delta) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "content_block_delta");
        data.put("index", index);
        data.put("delta", delta);
        return data;
    }

    private Map<String, Object> messageDeltaData() {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("stop_reason", stopReason);
        delta.put("stop_sequence", null);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("output_tokens", outputTokens);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "message_delta");
        data.put("delta", delta);
        data.put("usage", usage);
        return data;
    }

    private Map<String, Object> messageStopData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "message_stop");
        return data;
    }

    private static String jsStr(Object v) {
        return v instanceof String ? (String) v : String.valueOf(v);
    }
}
