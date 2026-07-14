package io.github.intisy.ai.antigravity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of antigravity-auth's thinking-block filtering + response-transform helpers from
 * {@code src/plugin/request-helpers.ts} (T7c-2): the exported {@code filterUnsignedThinkingBlocks}
 * (:1268), {@code filterMessagesThinkingBlocks} (:1326), {@code deepFilterThinkingBlocks} (:1362) and
 * {@code transformThinkingParts} (:1522), plus their full private-helper closure --
 * {@code isThinkingPart} (:915), {@code hasSignatureField} (:927), {@code isToolBlock} (:939),
 * {@code stripAllThinkingBlocks} (:956), {@code removeTrailingThinkingBlocks} (:971),
 * {@code hasValidSignature} (:996), {@code getSignature} (:1004), {@code isOurCachedSignature}
 * (:1014), {@code getThinkingText} (:1040), {@code stripCacheControlRecursively} (:1061),
 * {@code sanitizeThinkingPart} (:1079), {@code findLastAssistantIndex} (:1141),
 * {@code filterContentArray} (:1151) and {@code transformGeminiCandidate} (:1418). The thinking-config
 * helpers live in {@link AntigravityThinkingConfig}.
 *
 * <p>Signature gating (casing IS behavior): a "real" signature is a string of length >=
 * {@link CrossModelSanitizer#MIN_SIGNATURE_LENGTH} (=50, REUSED not redefined). Gemini thought parts
 * carry {@code thoughtSignature}; Claude/reasoning parts carry {@code signature}. The
 * {@link #SKIP_THOUGHT_SIGNATURE} sentinel (constants.ts:176) is injected in place of a foreign/
 * unsigned block's signature so Antigravity's validator lets it through.
 *
 * <h2>Injected edges (A*; Bucket C stays in TS)</h2>
 * <ul>
 *   <li>{@code getKeepThinking()} (a global config read in the TS {@code filterContentArray}) becomes
 *       the explicit {@code boolean keepThinking} PARAMETER threaded through the three filter
 *       functions -- never a global read.</li>
 *   <li>{@code getCachedSignatureFn} (the signature-cache getter) is the {@link CachedSignatureLookup}
 *       functional interface -- exactly the shape the TS already injects
 *       ({@code (sessionId, text) => string | undefined}). The named globals
 *       {@code getCachedSignature}/{@code cacheSignature} are NOT reached by this slice (no Store SPI
 *       introduced -- they belong to T7e); the lookup is a read-only getter callback here, and nothing
 *       WRITES the cache.</li>
 *   <li>{@code processImageData} (the fs-write branch inside {@code transformGeminiCandidate}, Bucket
 *       C) becomes the {@link ImageSink} functional interface -- this Java owns the DECISION (read
 *       {@code inlineData.mimeType}/{@code data}, call the sink, and if it returns a truthy string
 *       replace the part with {@code {text: result}}); the actual fs write stays in TS.</li>
 *   <li>{@code recursivelyParseJsonStrings} (the tool-args re-parse in the {@code functionCall} branch)
 *       is beyond this slice's boundary -- it lives at request-helpers.ts:1927, past
 *       {@code parseAntigravityApiBody} (T7c-3) -- so it is INJECTED as the {@link JsonStringParser}
 *       seam rather than duplicated here. This Java owns the DECISION (only call it when
 *       {@code functionCall.args} is truthy, else {@code {}}).</li>
 * </ul>
 *
 * <p>Copy-vs-mutate fidelity: {@code filter*} return NEW arrays/maps (the TS {@code .map()} +
 * {@code {...spread}}); {@code deepFilterThinkingBlocks} MUTATES the payload in place and returns the
 * same reference; {@code transformThinkingParts}/{@code transformGeminiCandidate} return new trees.
 *
 * <p>Fidelity deviations (disclosed; none reachable by valid payloads, exercised by no fixture):
 * (1) the single {@code log.debug} line inside {@code filterContentArray} (last-message foreign-
 * signature diagnostic) is omitted -- it has no bearing on the returned data and this slice was not
 * given a Logger edge. (2) Wherever the TS reads a nested object via {@code typeof x === "object"}
 * (a {@code functionCall}/{@code inlineData}/{@code content}/{@code text}/{@code thinking} container),
 * this port requires a {@link Map}; a JSON array in that exact slot -- invalid -- is treated as
 * absent, matching the (all-undefined) result the TS array-property reads would produce. TeaVM-
 * transpilable.
 */
public final class AntigravityThinkingBlocks {

    /** constants.ts:176 -- sentinel signature that bypasses Antigravity's thinking-block validation. */
    public static final String SKIP_THOUGHT_SIGNATURE = "skip_thought_signature_validator";

    /** request-helpers.ts:974 -- the signature-cache getter ({@code (sessionId, text) => string | undefined}). */
    public interface CachedSignatureLookup {
        String get(String sessionId, String text);
    }

    /** Injected {@code recursivelyParseJsonStrings} (T7c-3, request-helpers.ts:1927); see class javadoc. */
    public interface JsonStringParser {
        Object parse(Object value);
    }

    /** Injected {@code processImageData} (Bucket C fs write); returns the replacement text or null. */
    public interface ImageSink {
        String process(Object mimeType, Object data);
    }

    // Distinguishes JS `undefined` (absent map key) from a present `null`/other value inside
    // sanitizeThinkingPart, exactly as the TS `!== undefined` checks require.
    private static final Object UNDEFINED = new Object();

    private AntigravityThinkingBlocks() {
    }

    // ---- part predicates (request-helpers.ts:915-949) --------------------------------------------

    // isThinkingPart: `!== undefined` maps to containsKey (a present null still counts); `=== true`
    // is a strict boolean check.
    private static boolean isThinkingPart(Map<String, Object> part) {
        return "thinking".equals(part.get("type"))
                || "redacted_thinking".equals(part.get("type"))
                || "reasoning".equals(part.get("type"))
                || part.containsKey("thinking")
                || Boolean.TRUE.equals(part.get("thought"));
    }

    private static boolean hasSignatureField(Map<String, Object> part) {
        return part.containsKey("signature") || part.containsKey("thoughtSignature");
    }

    private static boolean isToolBlock(Map<String, Object> part) {
        return "tool_use".equals(part.get("type"))
                || "tool_result".equals(part.get("type"))
                || part.containsKey("tool_use_id")
                || part.containsKey("tool_call_id")
                || part.containsKey("tool_result")
                || part.containsKey("tool_use")
                || part.containsKey("toolUse")
                || part.containsKey("functionCall")
                || part.containsKey("functionResponse");
    }

    // ---- stripAllThinkingBlocks (request-helpers.ts:956-964) -------------------------------------

    private static List<Object> stripAllThinkingBlocks(List<Object> contentArray) {
        List<Object> out = new ArrayList<>();
        for (Object item : contentArray) {
            // TS `!item || typeof item !== "object"` keeps primitives; a non-tool/non-thinking object
            // (or an array item, whose property reads are all undefined) is likewise kept.
            if (!(item instanceof Map)) {
                out.add(item);
                continue;
            }
            Map<String, Object> m = JsCoercion.asMap(item);
            if (isToolBlock(m)) {
                out.add(item);
                continue;
            }
            if (isThinkingPart(m)) {
                continue;
            }
            if (hasSignatureField(m)) {
                continue;
            }
            out.add(item);
        }
        return out;
    }

    // ---- removeTrailingThinkingBlocks (request-helpers.ts:971-990) -------------------------------

    private static List<Object> removeTrailingThinkingBlocks(List<Object> contentArray, String sessionId, CachedSignatureLookup lookup) {
        List<Object> result = new ArrayList<>(contentArray);

        while (!result.isEmpty() && isThinkingPartLoose(result.get(result.size() - 1))) {
            Map<String, Object> part = JsCoercion.asMap(result.get(result.size() - 1));
            boolean isValid = (JsCoercion.isTruthy(sessionId) && lookup != null)
                    ? isOurCachedSignature(part, sessionId, lookup)
                    : hasValidSignature(part);
            if (isValid) {
                break;
            }
            result.remove(result.size() - 1);
        }

        return result;
    }

    // isThinkingPart on an arbitrary trailing item: a non-Map (primitive/array) reads all-undefined
    // in the TS, so it is never a thinking part -> the while-loop stops.
    private static boolean isThinkingPartLoose(Object item) {
        return item instanceof Map && isThinkingPart(JsCoercion.asMap(item));
    }

    // ---- signature helpers (request-helpers.ts:996-1054) ----------------------------------------

    private static boolean hasValidSignature(Map<String, Object> part) {
        Object signature = Boolean.TRUE.equals(part.get("thought")) ? part.get("thoughtSignature") : part.get("signature");
        return signature instanceof String && ((String) signature).length() >= CrossModelSanitizer.MIN_SIGNATURE_LENGTH;
    }

    private static String getSignature(Map<String, Object> part) {
        Object signature = Boolean.TRUE.equals(part.get("thought")) ? part.get("thoughtSignature") : part.get("signature");
        return signature instanceof String ? (String) signature : null;
    }

    private static boolean isOurCachedSignature(Map<String, Object> part, String sessionId, CachedSignatureLookup lookup) {
        if (!JsCoercion.isTruthy(sessionId) || lookup == null) {
            return false;
        }
        String text = getThinkingText(part);
        if (text.isEmpty()) {
            return false;
        }
        String partSignature = getSignature(part);
        if (partSignature == null) {
            return false;
        }
        String cachedSignature = lookup.get(sessionId, text);
        return partSignature.equals(cachedSignature);
    }

    private static String getThinkingText(Map<String, Object> part) {
        if (part.get("text") instanceof String) return (String) part.get("text");
        if (part.get("thinking") instanceof String) return (String) part.get("thinking");

        if (JsCoercion.isTruthy(part.get("text")) && part.get("text") instanceof Map) {
            Object maybeText = JsCoercion.asMap(part.get("text")).get("text");
            if (maybeText instanceof String) return (String) maybeText;
        }

        if (JsCoercion.isTruthy(part.get("thinking")) && part.get("thinking") instanceof Map) {
            Map<String, Object> t = JsCoercion.asMap(part.get("thinking"));
            Object maybeText = JsCoercion.nullish(t.get("text"), t.get("thinking"));
            if (maybeText instanceof String) return (String) maybeText;
        }

        return "";
    }

    // ---- stripCacheControlRecursively (request-helpers.ts:1061-1072) -----------------------------

    private static Object stripCacheControlRecursively(Object obj) {
        if (obj == null) return null;
        if (!(obj instanceof Map) && !(obj instanceof List)) return obj;
        if (obj instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : JsCoercion.asList(obj)) {
                out.add(stripCacheControlRecursively(item));
            }
            return out;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : JsCoercion.asMap(obj).entrySet()) {
            if ("cache_control".equals(e.getKey()) || "providerOptions".equals(e.getKey())) continue;
            result.put(e.getKey(), stripCacheControlRecursively(e.getValue()));
        }
        return result;
    }

    // ---- sanitizeThinkingPart (request-helpers.ts:1079-1139) ------------------------------------

    private static Map<String, Object> sanitizeThinkingPart(Map<String, Object> part) {
        // Gemini-style thought blocks: { thought: true, text, thoughtSignature }
        if (Boolean.TRUE.equals(part.get("thought"))) {
            Object textContent = coerceNestedText(part.containsKey("text") ? part.get("text") : UNDEFINED, "text");
            boolean hasContent = textContent instanceof String && !((String) textContent).trim().isEmpty();
            if (!hasContent && !JsCoercion.isTruthy(part.get("thoughtSignature"))) {
                return null;
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            sanitized.put("thought", true);
            if (textContent != UNDEFINED) sanitized.put("text", textContent);
            if (part.containsKey("thoughtSignature")) sanitized.put("thoughtSignature", part.get("thoughtSignature"));
            return sanitized;
        }

        // Anthropic-style thinking/redacted_thinking blocks
        if ("thinking".equals(part.get("type")) || "redacted_thinking".equals(part.get("type")) || part.containsKey("thinking")) {
            Object raw = JsCoercion.nullish(
                    part.containsKey("thinking") ? part.get("thinking") : UNDEFINED,
                    part.containsKey("text") ? part.get("text") : UNDEFINED);
            // TS: `part.thinking ?? part.text` -- a present-null `thinking` still nullish-falls to text.
            Object thinkingContent = coerceNestedThinking(raw);
            boolean hasContent = thinkingContent instanceof String && !((String) thinkingContent).trim().isEmpty();
            if (!hasContent && !JsCoercion.isTruthy(part.get("signature"))) {
                return null;
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            sanitized.put("type", "redacted_thinking".equals(part.get("type")) ? "redacted_thinking" : "thinking");
            if (thinkingContent != UNDEFINED) sanitized.put("thinking", thinkingContent);
            if (part.containsKey("signature")) sanitized.put("signature", part.get("signature"));
            return sanitized;
        }

        // Reasoning blocks (OpenCode format): { type: "reasoning", text, signature }
        if ("reasoning".equals(part.get("type"))) {
            Object textContent = coerceNestedText(part.containsKey("text") ? part.get("text") : UNDEFINED, "text");
            boolean hasContent = textContent instanceof String && !((String) textContent).trim().isEmpty();
            if (!hasContent && !JsCoercion.isTruthy(part.get("signature"))) {
                return null;
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            sanitized.put("type", "reasoning");
            if (textContent != UNDEFINED) sanitized.put("text", textContent);
            if (part.containsKey("signature")) sanitized.put("signature", part.get("signature"));
            return sanitized;
        }

        // Fallback: strip cache_control recursively.
        return (Map<String, Object>) stripCacheControlRecursively(part);
    }

    // TS: `if (typeof x === "object" && x !== null) { const m = x[key]; x = typeof m === "string" ? m : undefined }`.
    // A Map reads its key (string or UNDEFINED); a List (also typeof object) reads undefined -> UNDEFINED;
    // a null/primitive is left as-is.
    private static Object coerceNestedText(Object value, String key) {
        if (value instanceof Map) {
            Object maybe = JsCoercion.asMap(value).get(key);
            return maybe instanceof String ? maybe : UNDEFINED;
        }
        if (value instanceof List) {
            return UNDEFINED;
        }
        return value;
    }

    // sanitizeThinkingPart's Anthropic branch: `const m = x.text ?? x.thinking`.
    private static Object coerceNestedThinking(Object value) {
        if (value == UNDEFINED) return UNDEFINED;
        if (value instanceof Map) {
            Map<String, Object> m = JsCoercion.asMap(value);
            Object maybe = JsCoercion.nullish(m.get("text"), m.get("thinking"));
            return maybe instanceof String ? maybe : UNDEFINED;
        }
        if (value instanceof List) {
            return UNDEFINED;
        }
        return value;
    }

    // ---- findLastAssistantIndex (request-helpers.ts:1141-1149) ----------------------------------

    private static int findLastAssistantIndex(List<Object> contents, String roleValue) {
        for (int i = contents.size() - 1; i >= 0; i--) {
            Object content = contents.get(i);
            if (content instanceof Map && roleValue.equals(JsCoercion.asMap(content).get("role"))) {
                return i;
            }
        }
        return -1;
    }

    // ---- filterContentArray (request-helpers.ts:1151-1258) --------------------------------------

    private static List<Object> filterContentArray(
            List<Object> contentArray, String sessionId, CachedSignatureLookup lookup,
            boolean isClaudeModel, boolean isLastAssistantMessage, boolean keepThinking) {

        if (isClaudeModel && !keepThinking) {
            return stripAllThinkingBlocks(contentArray);
        }

        List<Object> filtered = new ArrayList<>();

        for (Object item : contentArray) {
            if (!(item instanceof Map)) {
                filtered.add(item);
                continue;
            }
            Map<String, Object> m = JsCoercion.asMap(item);

            if (isToolBlock(m)) {
                if (!isClaudeModel) {
                    filtered.add(item);
                    continue;
                }
                Map<String, Object> sanitizedToolBlock = new LinkedHashMap<>(m);
                sanitizedToolBlock.remove("signature");
                sanitizedToolBlock.remove("thoughtSignature");
                sanitizedToolBlock.remove("thought_signature");
                sanitizedToolBlock.remove("thought");
                filtered.add(sanitizedToolBlock);
                continue;
            }

            boolean isThinking = isThinkingPart(m);
            boolean hasSignature = hasSignatureField(m);

            if (!isThinking && !hasSignature) {
                filtered.add(item);
                continue;
            }

            if (isClaudeModel) {
                String thinkingText = getThinkingText(m);
                Map<String, Object> sentinelPart = new LinkedHashMap<>();
                sentinelPart.put("type", "redacted_thinking".equals(m.get("type")) ? "redacted_thinking" : "thinking");
                sentinelPart.put("thinking", thinkingText);
                sentinelPart.put("signature", SKIP_THOUGHT_SIGNATURE);
                filtered.add(sentinelPart);
                continue;
            }

            if (isLastAssistantMessage) {
                if (isOurCachedSignature(m, sessionId, lookup)) {
                    Map<String, Object> sanitized = sanitizeThinkingPart(m);
                    if (sanitized != null) filtered.add(sanitized);
                    continue;
                }
                String thinkingText = getThinkingText(m);
                // log.debug diagnostic omitted (no data effect; see class javadoc).
                Map<String, Object> sentinelPart = new LinkedHashMap<>();
                sentinelPart.put("type", JsCoercion.firstTruthy(m.get("type"), "thinking"));
                sentinelPart.put("thinking", thinkingText);
                sentinelPart.put("signature", SKIP_THOUGHT_SIGNATURE);
                filtered.add(sentinelPart);
                continue;
            }

            if (isOurCachedSignature(m, sessionId, lookup)) {
                Map<String, Object> sanitized = sanitizeThinkingPart(m);
                if (sanitized != null) filtered.add(sanitized);
                continue;
            }

            if (JsCoercion.isTruthy(sessionId) && lookup != null) {
                String text = getThinkingText(m);
                if (!text.isEmpty()) {
                    String cachedSignature = lookup.get(sessionId, text);
                    if (cachedSignature != null && cachedSignature.length() >= CrossModelSanitizer.MIN_SIGNATURE_LENGTH) {
                        Map<String, Object> restoredPart = new LinkedHashMap<>(m);
                        if (Boolean.TRUE.equals(m.get("thought"))) {
                            restoredPart.put("thoughtSignature", cachedSignature);
                        } else {
                            restoredPart.put("signature", cachedSignature);
                        }
                        Map<String, Object> sanitized = sanitizeThinkingPart(restoredPart);
                        if (sanitized != null) filtered.add(sanitized);
                        continue;
                    }
                }
            }
            // Unsigned, uncached, non-last thinking block: dropped.
        }

        return filtered;
    }

    // ---- filterUnsignedThinkingBlocks (request-helpers.ts:1268-1321) ----------------------------

    /** Port of {@code filterUnsignedThinkingBlocks} (Gemini {@code parts} + Anthropic {@code content} shapes). */
    public static List<Object> filterUnsignedThinkingBlocks(
            List<Object> contents, String sessionId, CachedSignatureLookup lookup, boolean isClaudeModel, boolean keepThinking) {

        int lastAssistantIdx = findLastAssistantIndex(contents, "model");

        List<Object> out = new ArrayList<>();
        for (int idx = 0; idx < contents.size(); idx++) {
            Object contentObj = contents.get(idx);
            if (!(contentObj instanceof Map)) {
                out.add(contentObj);
                continue;
            }
            Map<String, Object> content = JsCoercion.asMap(contentObj);
            boolean isLastAssistant = idx == lastAssistantIdx;

            if (content.get("parts") instanceof List) {
                List<Object> filteredParts = filterContentArray(
                        JsCoercion.asList(content.get("parts")), sessionId, lookup, isClaudeModel, isLastAssistant, keepThinking);
                List<Object> trimmedParts = ("model".equals(content.get("role")) && !isClaudeModel)
                        ? removeTrailingThinkingBlocks(filteredParts, sessionId, lookup)
                        : filteredParts;
                Map<String, Object> newContent = new LinkedHashMap<>(content);
                newContent.put("parts", trimmedParts);
                out.add(newContent);
                continue;
            }

            if (content.get("content") instanceof List) {
                boolean isAssistantRole = "assistant".equals(content.get("role"));
                boolean isLastAssistantContent = idx == lastAssistantIdx
                        || (isAssistantRole && idx == findLastAssistantIndex(contents, "assistant"));
                List<Object> filteredContent = filterContentArray(
                        JsCoercion.asList(content.get("content")), sessionId, lookup, isClaudeModel, isLastAssistantContent, keepThinking);
                List<Object> trimmedContent = (isAssistantRole && !isClaudeModel)
                        ? removeTrailingThinkingBlocks(filteredContent, sessionId, lookup)
                        : filteredContent;
                Map<String, Object> newContent = new LinkedHashMap<>(content);
                newContent.put("content", trimmedContent);
                out.add(newContent);
                continue;
            }

            out.add(contentObj);
        }
        return out;
    }

    // ---- filterMessagesThinkingBlocks (request-helpers.ts:1326-1360) ----------------------------

    /** Port of {@code filterMessagesThinkingBlocks} (Anthropic {@code messages[]} payloads). */
    public static List<Object> filterMessagesThinkingBlocks(
            List<Object> messages, String sessionId, CachedSignatureLookup lookup, boolean isClaudeModel, boolean keepThinking) {

        int lastAssistantIdx = findLastAssistantIndex(messages, "assistant");

        List<Object> out = new ArrayList<>();
        for (int idx = 0; idx < messages.size(); idx++) {
            Object messageObj = messages.get(idx);
            if (!(messageObj instanceof Map)) {
                out.add(messageObj);
                continue;
            }
            Map<String, Object> message = JsCoercion.asMap(messageObj);

            if (message.get("content") instanceof List) {
                boolean isAssistantRole = "assistant".equals(message.get("role"));
                boolean isLastAssistant = isAssistantRole && idx == lastAssistantIdx;
                List<Object> filteredContent = filterContentArray(
                        JsCoercion.asList(message.get("content")), sessionId, lookup, isClaudeModel, isLastAssistant, keepThinking);
                List<Object> trimmedContent = (isAssistantRole && !isClaudeModel)
                        ? removeTrailingThinkingBlocks(filteredContent, sessionId, lookup)
                        : filteredContent;
                Map<String, Object> newMessage = new LinkedHashMap<>(message);
                newMessage.put("content", trimmedContent);
                out.add(newMessage);
                continue;
            }

            out.add(messageObj);
        }
        return out;
    }

    // ---- deepFilterThinkingBlocks (request-helpers.ts:1362-1411) --------------------------------

    /** Port of {@code deepFilterThinkingBlocks}: walks the payload, MUTATING {@code contents}/{@code messages} in place. */
    public static Object deepFilterThinkingBlocks(
            Object payload, String sessionId, CachedSignatureLookup lookup, boolean isClaudeModel, boolean keepThinking) {
        walk(payload, sessionId, lookup, isClaudeModel, keepThinking, new ArrayList<>());
        return payload;
    }

    private static void walk(Object value, String sessionId, CachedSignatureLookup lookup, boolean isClaudeModel, boolean keepThinking, List<Object> visited) {
        if (!(value instanceof Map) && !(value instanceof List)) {
            return;
        }
        if (containsIdentity(visited, value)) {
            return;
        }
        visited.add(value);

        if (value instanceof List) {
            for (Object item : JsCoercion.asList(value)) {
                walk(item, sessionId, lookup, isClaudeModel, keepThinking, visited);
            }
            return;
        }

        Map<String, Object> obj = JsCoercion.asMap(value);

        if (obj.get("contents") instanceof List) {
            obj.put("contents", filterUnsignedThinkingBlocks(JsCoercion.asList(obj.get("contents")), sessionId, lookup, isClaudeModel, keepThinking));
        }
        if (obj.get("messages") instanceof List) {
            obj.put("messages", filterMessagesThinkingBlocks(JsCoercion.asList(obj.get("messages")), sessionId, lookup, isClaudeModel, keepThinking));
        }

        for (String key : new ArrayList<>(obj.keySet())) {
            walk(obj.get(key), sessionId, lookup, isClaudeModel, keepThinking, visited);
        }
    }

    // JS WeakSet: identity membership.
    private static boolean containsIdentity(List<Object> visited, Object value) {
        for (Object v : visited) {
            if (v == value) return true;
        }
        return false;
    }

    // ---- transformThinkingParts (request-helpers.ts:1522-1573) ----------------------------------

    /**
     * Port of {@code transformThinkingParts}: converts Anthropic {@code content[]} + Gemini
     * {@code candidates[]} thinking blocks into reasoning format and aggregates {@code reasoning_content}.
     * {@code parser}/{@code imageSink} are the injected {@code functionCall}/{@code inlineData} seams.
     */
    public static Object transformThinkingParts(Object response, JsonStringParser parser, ImageSink imageSink) {
        if (!(response instanceof Map)) {
            return response;
        }

        Map<String, Object> resp = JsCoercion.asMap(response);
        Map<String, Object> result = new LinkedHashMap<>(resp);
        List<String> reasoningTexts = new ArrayList<>();

        if (resp.get("content") instanceof List) {
            List<Object> transformedContent = new ArrayList<>();
            for (Object blockObj : JsCoercion.asList(resp.get("content"))) {
                if (blockObj instanceof Map && "thinking".equals(JsCoercion.asMap(blockObj).get("type"))) {
                    Map<String, Object> block = JsCoercion.asMap(blockObj);
                    String thinkingText = jsStr(JsCoercion.firstTruthy(block.get("thinking"), block.get("text"), ""));
                    reasoningTexts.add(thinkingText);
                    Map<String, Object> transformed = new LinkedHashMap<>(block);
                    transformed.put("type", "reasoning");
                    transformed.put("text", thinkingText);
                    transformed.put("thought", true);
                    applySignatureMetadata(transformed, block);
                    transformedContent.add(transformed);
                } else {
                    transformedContent.add(blockObj);
                }
            }
            result.put("content", transformedContent);
        }

        if (resp.get("candidates") instanceof List) {
            List<Object> candidates = new ArrayList<>();
            for (Object candidate : JsCoercion.asList(resp.get("candidates"))) {
                candidates.add(transformGeminiCandidate(candidate, parser, imageSink));
            }
            result.put("candidates", candidates);
        }

        if (!reasoningTexts.isEmpty() && !JsCoercion.isTruthy(result.get("reasoning_content"))) {
            result.put("reasoning_content", join(reasoningTexts));
        }

        return result;
    }

    // ---- transformGeminiCandidate (request-helpers.ts:1418-1515) --------------------------------

    private static Object transformGeminiCandidate(Object candidateObj, JsonStringParser parser, ImageSink imageSink) {
        if (!(candidateObj instanceof Map)) {
            return candidateObj;
        }
        Map<String, Object> candidate = JsCoercion.asMap(candidateObj);

        Object contentObj = candidate.get("content");
        if (!(contentObj instanceof Map) || !(JsCoercion.asMap(contentObj).get("parts") instanceof List)) {
            return candidateObj;
        }
        Map<String, Object> content = JsCoercion.asMap(contentObj);

        List<String> thinkingTexts = new ArrayList<>();
        List<Object> transformedParts = new ArrayList<>();

        for (Object partObj : JsCoercion.asList(content.get("parts"))) {
            if (!(partObj instanceof Map)) {
                transformedParts.add(partObj);
                continue;
            }
            Map<String, Object> part = JsCoercion.asMap(partObj);

            if (Boolean.TRUE.equals(part.get("thought"))) {
                String thinkingText = jsStr(JsCoercion.firstTruthy(part.get("text"), ""));
                thinkingTexts.add(thinkingText);
                Map<String, Object> transformed = new LinkedHashMap<>(part);
                transformed.put("type", "reasoning");
                if (JsCoercion.isTruthy(part.get("cache_control"))) transformed.put("cache_control", part.get("cache_control"));
                applySignatureMetadata(transformed, part);
                transformedParts.add(transformed);
                continue;
            }

            if ("thinking".equals(part.get("type"))) {
                String thinkingText = jsStr(JsCoercion.firstTruthy(part.get("thinking"), part.get("text"), ""));
                thinkingTexts.add(thinkingText);
                Map<String, Object> transformed = new LinkedHashMap<>(part);
                transformed.put("type", "reasoning");
                transformed.put("text", thinkingText);
                transformed.put("thought", true);
                if (JsCoercion.isTruthy(part.get("cache_control"))) transformed.put("cache_control", part.get("cache_control"));
                applySignatureMetadata(transformed, part);
                transformedParts.add(transformed);
                continue;
            }

            if (JsCoercion.isTruthy(part.get("functionCall"))) {
                Map<String, Object> fc = part.get("functionCall") instanceof Map
                        ? JsCoercion.asMap(part.get("functionCall")) : new LinkedHashMap<String, Object>();
                Object parsedArgs = JsCoercion.isTruthy(fc.get("args")) ? parser.parse(fc.get("args")) : new LinkedHashMap<String, Object>();
                Map<String, Object> newFc = new LinkedHashMap<>(fc);
                newFc.put("args", parsedArgs);
                Map<String, Object> transformed = new LinkedHashMap<>(part);
                transformed.put("functionCall", newFc);
                transformedParts.add(transformed);
                continue;
            }

            if (JsCoercion.isTruthy(part.get("inlineData"))) {
                Map<String, Object> inline = part.get("inlineData") instanceof Map ? JsCoercion.asMap(part.get("inlineData")) : null;
                Object mimeType = inline != null ? inline.get("mimeType") : null;
                Object data = inline != null ? inline.get("data") : null;
                String replacement = imageSink.process(mimeType, data);
                if (JsCoercion.isTruthy(replacement)) {
                    Map<String, Object> textPart = new LinkedHashMap<>();
                    textPart.put("text", replacement);
                    transformedParts.add(textPart);
                    continue;
                }
            }

            transformedParts.add(partObj);
        }

        Map<String, Object> newContent = new LinkedHashMap<>(content);
        newContent.put("parts", transformedParts);
        Map<String, Object> result = new LinkedHashMap<>(candidate);
        result.put("content", newContent);
        if (!thinkingTexts.isEmpty()) {
            result.put("reasoning_content", join(thinkingTexts));
        }
        return result;
    }

    // Shared: `const sig = x.signature || x.thoughtSignature; if (sig) { x.providerMetadata =
    // {anthropic:{signature:sig}}; delete x.signature; delete x.thoughtSignature; }`.
    private static void applySignatureMetadata(Map<String, Object> transformed, Map<String, Object> source) {
        Object sig = JsCoercion.firstTruthy(source.get("signature"), source.get("thoughtSignature"), null);
        if (JsCoercion.isTruthy(sig)) {
            Map<String, Object> anthropic = new LinkedHashMap<>();
            anthropic.put("signature", sig);
            Map<String, Object> providerMetadata = new LinkedHashMap<>();
            providerMetadata.put("anthropic", anthropic);
            transformed.put("providerMetadata", providerMetadata);
            transformed.remove("signature");
            transformed.remove("thoughtSignature");
        }
    }

    // JS `arr.join("\n\n")`.
    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // The `|| ""` fallbacks yield a string; a truthy non-string thinking value is invalid input and
    // appears in no fixture, but String(x) keeps the port total.
    private static String jsStr(Object v) {
        return v instanceof String ? (String) v : String.valueOf(v);
    }
}
