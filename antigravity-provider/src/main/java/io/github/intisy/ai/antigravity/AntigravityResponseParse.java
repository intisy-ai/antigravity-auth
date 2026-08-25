package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.Clock;
import io.github.intisy.ai.api.seam.JsonCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Response/usage/error-parse helpers: {@code parseAntigravityApiBody}, {@code extractUsageMetadata},
 * {@code extractUsageFromSsePayload}, {@code rewriteAntigravityPreviewAccessError} (with its private
 * {@code needsPreviewAccessOverride} + {@code isAntigravityModel}), {@code isEmptyResponseBody},
 * {@code isMeaningfulSseLine}, {@code recursivelyParseJsonStrings} (with its {@code SKIP_PARSE_KEYS}
 * set), and {@code createSyntheticErrorResponse}. The Anthropic tool-pairing half lives in
 * {@link AntigravityToolPairing}.
 *
 * <h2>Injected edges</h2>
 * <ul>
 *   <li><b>JsonCodec SPI</b> ({@code io.github.intisy.ai.api.seam.JsonCodec}) carries every
 *       {@code JSON.parse}/{@code JSON.stringify}: {@code parseAntigravityApiBody},
 *       {@code extractUsageFromSsePayload}, {@code isEmptyResponseBody}, {@code isMeaningfulSseLine},
 *       {@code recursivelyParseJsonStrings} and {@code createSyntheticErrorResponse} take it as their
 *       first parameter (never gson directly).</li>
 *   <li><b>Clock SPI</b> supplies the {@code Date.now()} inside {@code createSyntheticErrorResponse}'s
 *       synthetic message id ({@code msg_synthetic_<now>}): no {@code System.currentTimeMillis} at the
 *       edge, and deterministic in the parity tests.</li>
 *   <li>The signature cache ({@code getCachedSignature}/{@code cacheSignature}, Store SPI) is NOT
 *       reached by these functions, so no Store seam is introduced.</li>
 * </ul>
 *
 * <p>Data model = JSON tree {@code Map<String,Object>}/{@code List<Object>}/{@code String}/{@code Number}/
 * {@code Boolean}/{@code null}. Copy-vs-mutate fidelity: {@code rewriteAntigravityPreviewAccessError}
 * returns a NEW map ({@code {...body, error:{...error, message}}}); {@code recursivelyParseJsonStrings}
 * returns new arrays/maps. Numbers pass through as the caller's {@link Number} object. Pure logic, no
 * java.net/nio/reflection/threads, TeaVM-transpilable.
 *
 * <p>Fidelity deviations (none reachable by valid payloads):
 * (1) the {@code log.debug} calls inside {@code recursivelyParseJsonStrings}' malformed-JSON branches
 * are omitted; they have no bearing on the returned data and this class has no Logger edge.
 * (2) Wherever a nested slot is read via {@code typeof x === "object"} (a {@code response}/{@code
 * usageMetadata}/{@code content}/{@code message} container), this code requires a {@link Map}; a JSON
 * ARRAY in that exact slot (invalid input) is treated the way the all-undefined property reads would
 * resolve it (usually {@code null}/{@code true}), never crashing. {@code extractUsageMetadata} with an
 * ARRAY {@code usageMetadata} returns {@code null} here rather than an empty object (unreachable).
 */
public final class AntigravityResponseParse {

    /** preview-access enrollment link. */
    static final String ANTIGRAVITY_PREVIEW_LINK = "https://goo.gle/enable-preview-features";

    /** keys whose string values are preserved verbatim (never re-parsed as JSON). */
    static final Set<String> SKIP_PARSE_KEYS = new LinkedHashSet<>(Arrays.asList(
            "oldString", "newString", "content", "filePath", "path", "text", "code", "source", "data",
            "body", "message", "prompt", "input", "output", "result", "value", "query", "pattern",
            "replacement", "template", "script", "command", "snippet"));

    private AntigravityResponseParse() {
    }

    // ---- parseAntigravityApiBody ------------------------------------------------------------------

    /**
     * Parses {@code rawText}; handles the cloudcode-pa ARRAY-wrapped shape ({@code [{...}]}) by
     * returning the first array element that is a non-null object (Map OR List, i.e. JS
     * {@code typeof x === "object" && x !== null}). A non-array object is returned as-is; anything
     * else (or a parse failure) yields {@code null}.
     */
    public static Object parseAntigravityApiBody(JsonCodec json, String rawText) {
        Object parsed;
        try {
            parsed = json.parse(rawText);
        } catch (RuntimeException e) {
            return null;
        }
        if (parsed instanceof List) {
            for (Object item : (List<?>) parsed) {
                // typeof item === "object" && item !== null -> Map or List (non-null).
                if (item instanceof Map || item instanceof List) {
                    return item;
                }
            }
            return null;
        }
        if (parsed instanceof Map) {
            return parsed;
        }
        return null;
    }

    // ---- extractUsageMetadata ---------------------------------------------------------------------

    /**
     * Reads {@code body.response.usageMetadata} and returns a map with only the finite-number token
     * counts present (each undefined field is left ABSENT, matching {@code JSON.stringify}). Returns
     * {@code null} when there is no usageMetadata object.
     */
    public static Map<String, Object> extractUsageMetadata(Map<String, Object> body) {
        Object response = body != null ? body.get("response") : null;
        Object usage = response instanceof Map ? ((Map<?, ?>) response).get("usageMetadata") : null;

        if (!JsCoercion.isTruthy(usage) || !(usage instanceof Map)) {
            return null;
        }

        Map<?, ?> asRecord = (Map<?, ?>) usage;
        Map<String, Object> result = new LinkedHashMap<>();
        putIfFiniteNumber(result, "totalTokenCount", asRecord.get("totalTokenCount"));
        putIfFiniteNumber(result, "promptTokenCount", asRecord.get("promptTokenCount"));
        putIfFiniteNumber(result, "candidatesTokenCount", asRecord.get("candidatesTokenCount"));
        putIfFiniteNumber(result, "cachedContentTokenCount", asRecord.get("cachedContentTokenCount"));
        putIfFiniteNumber(result, "thoughtsTokenCount", asRecord.get("thoughtsTokenCount"));
        return result;
    }

    // toNumber: `typeof value === "number" && Number.isFinite(value) ? value : undefined`.
    private static void putIfFiniteNumber(Map<String, Object> target, String key, Object value) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (!Double.isNaN(d) && !Double.isInfinite(d)) {
                target.put(key, value);
            }
        }
    }

    // ---- extractUsageFromSsePayload ---------------------------------------------------------------

    /**
     * Scans {@code data:} SSE lines for the first parseable chunk whose {@code response.usageMetadata}
     * yields usage. Non-{@code data:} lines, empty payloads and unparseable JSON are skipped.
     */
    public static Map<String, Object> extractUsageFromSsePayload(JsonCodec json, String payload) {
        String[] lines = payload.split("\n", -1);
        for (String line : lines) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String jsonText = line.substring(5).trim();
            if (jsonText.isEmpty()) {
                continue;
            }
            Object parsed;
            try {
                parsed = json.parse(jsonText);
            } catch (RuntimeException e) {
                continue;
            }
            if (JsCoercion.isTruthy(parsed) && parsed instanceof Map) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("response", ((Map<?, ?>) parsed).get("response"));
                Map<String, Object> usage = extractUsageMetadata(body);
                if (usage != null) {
                    return usage;
                }
            }
        }
        return null;
    }

    // ---- rewriteAntigravityPreviewAccessError -----------------------------------------------------

    /**
     * For a 404 tied to an Antigravity/Claude/Opus model, appends the preview-access enrollment hint
     * to the error message and returns a NEW body; otherwise {@code null}.
     */
    public static Map<String, Object> rewriteAntigravityPreviewAccessError(
            Map<String, Object> body, int status, String requestedModel) {

        if (!needsPreviewAccessOverride(status, body, requestedModel)) {
            return null;
        }

        Object errorRaw = body != null ? body.get("error") : null;
        Map<String, Object> error = errorRaw instanceof Map ? JsCoercion.asMap(errorRaw) : new LinkedHashMap<>();

        Object messageRaw = error.get("message");
        String trimmedMessage = messageRaw instanceof String ? ((String) messageRaw).trim() : "";
        String messagePrefix = trimmedMessage.length() > 0
                ? trimmedMessage
                : "Antigravity preview features are not enabled for this account.";
        String enhancedMessage = messagePrefix + " Request preview access at " + ANTIGRAVITY_PREVIEW_LINK
                + " before using this model.";

        Map<String, Object> newError = new LinkedHashMap<>(error);
        newError.put("message", enhancedMessage);

        Map<String, Object> result = new LinkedHashMap<>();
        if (body != null) {
            result.putAll(body);
        }
        result.put("error", newError);
        return result;
    }

    private static boolean needsPreviewAccessOverride(int status, Map<String, Object> body, String requestedModel) {
        if (status != 404) {
            return false;
        }
        if (isAntigravityModel(requestedModel)) {
            return true;
        }
        Object errorRaw = body != null ? body.get("error") : null;
        Object messageRaw = errorRaw instanceof Map ? ((Map<?, ?>) errorRaw).get("message") : null;
        String errorMessage = messageRaw instanceof String ? (String) messageRaw : "";
        return isAntigravityModel(errorMessage);
    }

    // /antigravity/i || /opus/i || /claude/i (case-insensitive substring).
    private static boolean isAntigravityModel(String target) {
        if (target == null || target.isEmpty()) {
            return false;
        }
        String lower = target.toLowerCase();
        return lower.contains("antigravity") || lower.contains("opus") || lower.contains("claude");
    }

    // ---- isEmptyResponseBody ----------------------------------------------------------------------

    /**
     * {@code true} when the JSON body carries no usable content: no Gemini candidates/parts, no OpenAI
     * choices/message content, or an empty wrapped {@code response}. Any parse failure (including a
     * {@code null} literal, whose property read throws in JS) is treated as empty.
     */
    public static boolean isEmptyResponseBody(JsonCodec json, String text) {
        if (text == null || text.trim().isEmpty()) {
            return true;
        }
        Object parsed;
        try {
            parsed = json.parse(text);
        } catch (RuntimeException e) {
            return true;
        }
        // a null literal throws on the property read -> catch -> true.
        if (parsed == null) {
            return true;
        }
        // A non-object primitive (number/string/boolean) or an array: candidates/choices/response all
        // read undefined in JS -> falls through to `return false`.
        if (!(parsed instanceof Map)) {
            return false;
        }
        Map<?, ?> obj = (Map<?, ?>) parsed;

        if (obj.containsKey("candidates")) {
            Object candidates = obj.get("candidates");
            if (!(candidates instanceof List) || ((List<?>) candidates).isEmpty()) {
                return true;
            }
            Object firstCandidate = ((List<?>) candidates).get(0);
            if (!(firstCandidate instanceof Map)) {
                return true;
            }
            Object content = ((Map<?, ?>) firstCandidate).get("content");
            if (!(content instanceof Map)) {
                return true;
            }
            Object parts = ((Map<?, ?>) content).get("parts");
            if (!(parts instanceof List) || ((List<?>) parts).isEmpty()) {
                return true;
            }
            if (!partsHaveContent((List<?>) parts)) {
                return true;
            }
        }

        if (obj.containsKey("choices")) {
            Object choices = obj.get("choices");
            if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) {
                return true;
            }
            Object firstChoice = ((List<?>) choices).get(0);
            if (!(firstChoice instanceof Map)) {
                return true;
            }
            Object messageRaw = ((Map<?, ?>) firstChoice).get("message");
            Object message = JsCoercion.isTruthy(messageRaw) ? messageRaw : ((Map<?, ?>) firstChoice).get("delta");
            if (!(message instanceof Map)) {
                return true;
            }
            Map<?, ?> msg = (Map<?, ?>) message;
            if (!JsCoercion.isTruthy(msg.get("content"))
                    && !JsCoercion.isTruthy(msg.get("tool_calls"))
                    && !JsCoercion.isTruthy(msg.get("reasoning_content"))) {
                return true;
            }
        }

        if (obj.containsKey("response")) {
            Object response = obj.get("response");
            if (!JsCoercion.isTruthy(response) || !(response instanceof Map || response instanceof List)) {
                return true;
            }
            return isEmptyResponseBody(json, json.stringify(response));
        }

        return false;
    }

    // parts.some(part => object && ((text string non-empty) || functionCall || (thought===true && text string)))
    private static boolean partsHaveContent(List<?> parts) {
        for (Object partRaw : parts) {
            if (!(partRaw instanceof Map)) {
                continue;
            }
            Map<?, ?> part = (Map<?, ?>) partRaw;
            Object textVal = part.get("text");
            if (textVal instanceof String && !((String) textVal).isEmpty()) {
                return true;
            }
            if (JsCoercion.isTruthy(part.get("functionCall"))) {
                return true;
            }
            if (Boolean.TRUE.equals(part.get("thought")) && textVal instanceof String) {
                return true;
            }
        }
        return false;
    }

    // ---- isMeaningfulSseLine ----------------------------------------------------------------------

    /**
     * {@code true} only for a {@code data: } line whose JSON carries at least one candidate part with
     * non-empty text or a functionCall (recursing through a wrapped {@code response}). {@code [DONE]},
     * empty, non-{@code data: } and unparseable lines are not meaningful.
     */
    public static boolean isMeaningfulSseLine(JsonCodec json, String line) {
        if (!line.startsWith("data: ")) {
            return false;
        }
        String data = line.substring(6).trim();
        if (data.equals("[DONE]")) {
            return false;
        }
        if (data.isEmpty()) {
            return false;
        }
        Object parsed;
        try {
            parsed = json.parse(data);
        } catch (RuntimeException e) {
            return false;
        }
        if (!(parsed instanceof Map)) {
            return false;
        }
        Map<?, ?> obj = (Map<?, ?>) parsed;

        Object candidates = obj.get("candidates");
        if (JsCoercion.isTruthy(candidates) && candidates instanceof List) {
            for (Object candidateRaw : (List<?>) candidates) {
                Object content = candidateRaw instanceof Map ? ((Map<?, ?>) candidateRaw).get("content") : null;
                Object parts = content instanceof Map ? ((Map<?, ?>) content).get("parts") : null;
                if (parts instanceof List && !((List<?>) parts).isEmpty()) {
                    for (Object partRaw : (List<?>) parts) {
                        if (!(partRaw instanceof Map)) {
                            continue;
                        }
                        Map<?, ?> part = (Map<?, ?>) partRaw;
                        Object textVal = part.get("text");
                        if (textVal instanceof String && !((String) textVal).isEmpty()) {
                            return true;
                        }
                        if (JsCoercion.isTruthy(part.get("functionCall"))) {
                            return true;
                        }
                    }
                }
            }
        }

        Object response = obj.get("response");
        Object responseCandidates = response instanceof Map ? ((Map<?, ?>) response).get("candidates") : null;
        if (JsCoercion.isTruthy(responseCandidates)) {
            return isMeaningfulSseLine(json, "data: " + json.stringify(response));
        }

        return false;
    }

    // ---- recursivelyParseJsonStrings --------------------------------------------------------------

    /**
     * Recursively expands JSON-stringified values in the tree (default {@link #SKIP_PARSE_KEYS}
     * preserved verbatim), unescapes lone control-char escapes, and auto-corrects double-encoded JSON
     * with trailing junk. Public entry uses the default skip set with no current key.
     */
    public static Object recursivelyParseJsonStrings(JsonCodec json, Object obj) {
        return recursivelyParseJsonStrings(json, obj, SKIP_PARSE_KEYS, null);
    }

    private static Object recursivelyParseJsonStrings(JsonCodec json, Object obj, Set<String> skipParseKeys, String currentKey) {
        if (obj == null) {
            return null;
        }

        if (obj instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) obj) {
                result.add(recursivelyParseJsonStrings(json, item, skipParseKeys, null));
            }
            return result;
        }

        if (obj instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                String key = String.valueOf(entry.getKey());
                result.put(key, recursivelyParseJsonStrings(json, entry.getValue(), skipParseKeys, key));
            }
            return result;
        }

        if (!(obj instanceof String)) {
            return obj;
        }

        String str = (String) obj;

        if (currentKey != null && skipParseKeys.contains(currentKey)) {
            return str;
        }

        String stripped = str.trim();

        boolean hasControlCharEscapes = str.contains("\\n") || str.contains("\\t");
        boolean hasIntentionalEscapes = str.contains("\\\"") || str.contains("\\\\");

        if (hasControlCharEscapes && !hasIntentionalEscapes) {
            try {
                return json.parse("\"" + str + "\"");
            } catch (RuntimeException ignored) {
                // fall through
            }
        }

        if (!stripped.isEmpty() && (stripped.charAt(0) == '{' || stripped.charAt(0) == '[')) {
            if ((stripped.startsWith("{") && stripped.endsWith("}"))
                    || (stripped.startsWith("[") && stripped.endsWith("]"))) {
                try {
                    Object parsed = json.parse(str);
                    return recursivelyParseJsonStrings(json, parsed);
                } catch (RuntimeException ignored) {
                    // fall through
                }
            }

            if (stripped.startsWith("[") && !stripped.endsWith("]")) {
                try {
                    int lastBracket = stripped.lastIndexOf(']');
                    if (lastBracket > 0) {
                        String cleaned = stripped.substring(0, lastBracket + 1);
                        Object parsed = json.parse(cleaned);
                        return recursivelyParseJsonStrings(json, parsed);
                    }
                } catch (RuntimeException ignored) {
                    // fall through
                }
            }

            if (stripped.startsWith("{") && !stripped.endsWith("}")) {
                try {
                    int lastBrace = stripped.lastIndexOf('}');
                    if (lastBrace > 0) {
                        String cleaned = stripped.substring(0, lastBrace + 1);
                        Object parsed = json.parse(cleaned);
                        return recursivelyParseJsonStrings(json, parsed);
                    }
                } catch (RuntimeException ignored) {
                    // fall through
                }
            }
        }

        return str;
    }

    // ---- createSyntheticErrorResponse -------------------------------------------------------------

    /**
     * Structured stand-in for the web {@code Response} returned by {@code createSyntheticErrorResponse}:
     * the load-bearing bytes (SSE {@code body}), the {@code status} and the {@code headers} the host
     * reads. A {@code Response} object is not TeaVM-portable; this record-like holder carries the same
     * data. NOTE: the header keys are the literal-cased strings below; the browser fetch {@code
     * Response} lowercases them on read. The SSE body/status are the byte-exact contract.
     */
    public static final class SyntheticResponse {
        public final int status;
        public final Map<String, String> headers;
        public final String body;

        SyntheticResponse(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    /**
     * Builds a fake-success (200) Anthropic SSE stream that carries {@code errorMessage} as assistant
     * text, so the host session is not locked by a raw 400/500. The synthetic message id's
     * {@code Date.now()} is sourced from the injected {@link Clock}.
     */
    public static SyntheticResponse createSyntheticErrorResponse(
            JsonCodec json, Clock clock, String errorMessage, String requestedModel) {

        String model = requestedModel != null ? requestedModel : "unknown";
        String messageId = "msg_synthetic_" + clock.now();

        StringBuilder body = new StringBuilder();

        Map<String, Object> messageStart = new LinkedHashMap<>();
        messageStart.put("type", "message_start");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", messageId);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("content", new ArrayList<>());
        message.put("model", model);
        message.put("stop_reason", null);
        message.put("stop_sequence", null);
        Map<String, Object> startUsage = new LinkedHashMap<>();
        startUsage.put("input_tokens", 0L);
        startUsage.put("output_tokens", 0L);
        message.put("usage", startUsage);
        messageStart.put("message", message);
        appendEvent(body, "message_start", json.stringify(messageStart));

        Map<String, Object> contentBlockStart = new LinkedHashMap<>();
        contentBlockStart.put("type", "content_block_start");
        contentBlockStart.put("index", 0L);
        Map<String, Object> contentBlock = new LinkedHashMap<>();
        contentBlock.put("type", "text");
        contentBlock.put("text", "");
        contentBlockStart.put("content_block", contentBlock);
        appendEvent(body, "content_block_start", json.stringify(contentBlockStart));

        Map<String, Object> contentBlockDelta = new LinkedHashMap<>();
        contentBlockDelta.put("type", "content_block_delta");
        contentBlockDelta.put("index", 0L);
        Map<String, Object> textDelta = new LinkedHashMap<>();
        textDelta.put("type", "text_delta");
        textDelta.put("text", errorMessage);
        contentBlockDelta.put("delta", textDelta);
        appendEvent(body, "content_block_delta", json.stringify(contentBlockDelta));

        Map<String, Object> contentBlockStop = new LinkedHashMap<>();
        contentBlockStop.put("type", "content_block_stop");
        contentBlockStop.put("index", 0L);
        appendEvent(body, "content_block_stop", json.stringify(contentBlockStop));

        Map<String, Object> messageDelta = new LinkedHashMap<>();
        messageDelta.put("type", "message_delta");
        Map<String, Object> stopDelta = new LinkedHashMap<>();
        stopDelta.put("stop_reason", "end_turn");
        stopDelta.put("stop_sequence", null);
        messageDelta.put("delta", stopDelta);
        Map<String, Object> deltaUsage = new LinkedHashMap<>();
        deltaUsage.put("output_tokens", (long) Math.ceil(errorMessage.length() / 4.0));
        messageDelta.put("usage", deltaUsage);
        appendEvent(body, "message_delta", json.stringify(messageDelta));

        Map<String, Object> messageStop = new LinkedHashMap<>();
        messageStop.put("type", "message_stop");
        appendEvent(body, "message_stop", json.stringify(messageStop));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/event-stream");
        headers.put("Cache-Control", "no-cache");
        headers.put("Connection", "keep-alive");
        headers.put("X-Antigravity-Synthetic", "true");
        headers.put("X-Antigravity-Error-Type", "prompt_too_long");

        return new SyntheticResponse(200, headers, body.toString());
    }

    // Each event is `event: <name>\ndata: <json>\n\n` (trailing blank line).
    private static void appendEvent(StringBuilder sb, String eventName, String dataJson) {
        sb.append("event: ").append(eventName).append("\ndata: ").append(dataJson).append("\n\n");
    }

    // ---- detectErrorType --------------------------------------------------------------------------

    /**
     * Classifies an already-extracted upstream error message into a recoverable error type
     * ({@code tool_result_missing} / {@code thinking_block_order} / {@code thinking_disabled_violation}),
     * or {@code null}. The polymorphic {@code getErrorMessage} unwrapping (error-object/{@code .data}/
     * {@code .error} nesting, {@code JSON.stringify} fallback) is not reproduced: the one call site
     * always passes the already-extracted message string.
     */
    public static String detectErrorType(String message) {
        String m = message != null ? message.toLowerCase() : "";
        boolean hasExpectedFoundThinkingOrder =
                (m.contains("expected thinking") || m.contains("expected a thinking")) && m.contains("found");

        if (m.contains("tool_use") && m.contains("tool_result")) {
            return "tool_result_missing";
        }

        if (m.contains("thinking")
                && (m.contains("first block") || m.contains("must start with")
                || m.contains("preceeding") || m.contains("preceding") || hasExpectedFoundThinkingOrder)) {
            return "thinking_block_order";
        }

        if (m.contains("thinking is disabled") && m.contains("cannot contain")) {
            return "thinking_disabled_violation";
        }

        return null;
    }
}
