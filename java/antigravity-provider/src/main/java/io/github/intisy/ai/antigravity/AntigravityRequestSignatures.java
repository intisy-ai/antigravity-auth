package io.github.intisy.ai.antigravity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of antigravity-auth's PURE payload mutators + thinking-signature helpers from
 * {@code src/plugin/request.ts} (T7e). Payload mutators (:233-426): {@code injectDebugThinking},
 * {@code stripInjectedDebugFromParts}, {@code stripInjectedDebugFromRequestPayload},
 * {@code isValidRequestPart}, {@code sanitizeRequestPayloadForAntigravity}. Signature helpers
 * (:428-697): {@code isGeminiToolUsePart}, {@code isGeminiThinkingPart}, {@code getThinkingPartText},
 * {@code hasCachedMatchingSignature}, {@code ensureThoughtSignature}, {@code hasSignedThinkingPart},
 * {@code ensureMessageThinkingSignature}, {@code hasToolUseInContents}/{@code hasSignedThinkingInContents},
 * {@code hasToolUseInMessages}/{@code hasSignedThinkingInMessages},
 * {@code ensureThinkingBeforeToolUseInContents}/{@code ensureThinkingBeforeToolUseInMessages}.
 *
 * <h2>Constants (REUSED, not redefined)</h2>
 * {@code MIN_SIGNATURE_LENGTH=50} -> {@link CrossModelSanitizer#MIN_SIGNATURE_LENGTH};
 * {@code SKIP_THOUGHT_SIGNATURE} -> {@link AntigravityThinkingBlocks#SKIP_THOUGHT_SIGNATURE}. The
 * request.ts-local {@code SENTINEL_SIGNATURE} (:441) has the SAME string value
 * ({@code "skip_thought_signature_validator"}) as {@code SKIP_THOUGHT_SIGNATURE}; both reference the
 * single reused constant.
 *
 * <h2>Injected seams</h2>
 * <ul>
 *   <li>{@link AntigravityThinkingBlocks.CachedSignatureLookup} -- REUSED for {@code getCachedSignature}
 *       ({@code hasCachedMatchingSignature}).</li>
 *   <li>{@link SignatureStore} -- the {@code defaultSignatureStore} reads
 *       ({@code get}/{@code has}/{@code delete}); {@code ensureThinkingBeforeToolUse*} uses
 *       {@code get(key).text}.</li>
 * </ul>
 *
 * <p>Copy-vs-mutate fidelity reproduced exactly: {@code ensureThinkingBeforeToolUse*} +
 * {@code injectDebugThinking} return NEW trees (TS {@code .map()}/{@code {...spread}}); the two
 * {@code sanitize}/{@code stripInjectedDebug} mutators reassign {@code payload.contents}/
 * {@code payload.messages} in place with freshly-built arrays but REUSE unchanged element refs where
 * the TS {@code .map} returns the item unchanged. The single {@code log.debug} lines inside
 * {@code ensureThinkingBeforeToolUse*} are omitted (no data effect; no Logger edge). Disclosed
 * deviation: {@code injectDebugThinking} on a non-{@link Map} (a JSON array or primitive) returns the
 * value unchanged -- matching the string fixture; a JS array (typeof "object") would instead be
 * spread, an invalid, unreachable edge exercised by no fixture. TeaVM-transpilable.
 */
public final class AntigravityRequestSignatures {

    /** {@code defaultSignatureStore} reads ({@code get} returns {@code {text,signature}} or null). */
    public interface SignatureStore {
        Map<String, Object> get(String key);

        boolean has(String key);

        void delete(String key);
    }

    static final String DEBUG_MESSAGE_PREFIX = "[opencode-antigravity-auth debug]";
    static final String SYNTHETIC_THINKING_PLACEHOLDER = "[Thinking preserved]\n";
    // request.ts:441 SENTINEL_SIGNATURE == constants SKIP_THOUGHT_SIGNATURE (same string value).
    private static final String SENTINEL_SIGNATURE = AntigravityThinkingBlocks.SKIP_THOUGHT_SIGNATURE;
    private static final int MIN_SIGNATURE_LENGTH = CrossModelSanitizer.MIN_SIGNATURE_LENGTH;

    private AntigravityRequestSignatures() {
    }

    // ---- injectDebugThinking (request.ts:233-269) -----------------------------------------------

    public static Object injectDebugThinking(Object response, String debugText) {
        if (!(response instanceof Map)) {
            return response;
        }
        Map<String, Object> resp = JsCoercion.asMap(response);

        if (resp.get("candidates") instanceof List && !JsCoercion.asList(resp.get("candidates")).isEmpty()) {
            List<Object> candidates = new ArrayList<>(JsCoercion.asList(resp.get("candidates")));
            Object first = candidates.get(0);
            if (first instanceof Map) {
                Map<String, Object> firstMap = JsCoercion.asMap(first);
                Object contentObj = firstMap.get("content");
                if (contentObj instanceof Map && JsCoercion.asMap(contentObj).get("parts") instanceof List) {
                    Map<String, Object> content = JsCoercion.asMap(contentObj);
                    List<Object> parts = new ArrayList<>();
                    parts.add(thoughtPart(debugText));
                    parts.addAll(JsCoercion.asList(content.get("parts")));
                    Map<String, Object> newContent = new LinkedHashMap<>(content);
                    newContent.put("parts", parts);
                    Map<String, Object> newFirst = new LinkedHashMap<>(firstMap);
                    newFirst.put("content", newContent);
                    candidates.set(0, newFirst);
                    Map<String, Object> out = new LinkedHashMap<>(resp);
                    out.put("candidates", candidates);
                    return out;
                }
            }
            return resp;
        }

        if (resp.get("content") instanceof List) {
            List<Object> content = new ArrayList<>();
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put("type", "thinking");
            thinking.put("thinking", debugText);
            content.add(thinking);
            content.addAll(JsCoercion.asList(resp.get("content")));
            Map<String, Object> out = new LinkedHashMap<>(resp);
            out.put("content", content);
            return out;
        }

        if (!JsCoercion.isTruthy(resp.get("reasoning_content"))) {
            Map<String, Object> out = new LinkedHashMap<>(resp);
            out.put("reasoning_content", debugText);
            return out;
        }

        return resp;
    }

    private static Map<String, Object> thoughtPart(String debugText) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("thought", true);
        p.put("text", debugText);
        return p;
    }

    // ---- stripInjectedDebugFromParts (request.ts:278-303) ---------------------------------------

    static Object stripInjectedDebugFromParts(Object parts) {
        if (!(parts instanceof List)) {
            return parts;
        }
        List<Object> out = new ArrayList<>();
        String placeholderTrimmed = SYNTHETIC_THINKING_PLACEHOLDER.trim();
        for (Object part : JsCoercion.asList(parts)) {
            if (!(part instanceof Map)) {
                out.add(part);
                continue;
            }
            Map<String, Object> record = JsCoercion.asMap(part);
            String text = record.get("text") instanceof String
                    ? (String) record.get("text")
                    : record.get("thinking") instanceof String ? (String) record.get("thinking") : null;
            if (text != null && !text.isEmpty()
                    && (text.startsWith(DEBUG_MESSAGE_PREFIX) || text.startsWith(placeholderTrimmed))) {
                continue;
            }
            out.add(part);
        }
        return out;
    }

    // ---- stripInjectedDebugFromRequestPayload (request.ts:305-339) -------------------------------

    public static void stripInjectedDebugFromRequestPayload(Map<String, Object> payload) {
        if (payload.get("contents") instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object content : JsCoercion.asList(payload.get("contents"))) {
                if (!(content instanceof Map)) {
                    out.add(content);
                    continue;
                }
                Map<String, Object> c = JsCoercion.asMap(content);
                if (c.get("parts") instanceof List) {
                    Map<String, Object> nc = new LinkedHashMap<>(c);
                    nc.put("parts", stripInjectedDebugFromParts(c.get("parts")));
                    out.add(nc);
                } else if (c.get("content") instanceof List) {
                    Map<String, Object> nc = new LinkedHashMap<>(c);
                    nc.put("content", stripInjectedDebugFromParts(c.get("content")));
                    out.add(nc);
                } else {
                    out.add(content);
                }
            }
            payload.put("contents", out);
        }

        if (payload.get("messages") instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object message : JsCoercion.asList(payload.get("messages"))) {
                if (!(message instanceof Map)) {
                    out.add(message);
                    continue;
                }
                Map<String, Object> m = JsCoercion.asMap(message);
                if (m.get("content") instanceof List) {
                    Map<String, Object> nm = new LinkedHashMap<>(m);
                    nm.put("content", stripInjectedDebugFromParts(m.get("content")));
                    out.add(nm);
                } else {
                    out.add(message);
                }
            }
            payload.put("messages", out);
        }
    }

    // ---- isValidRequestPart (request.ts:341-358) ------------------------------------------------

    public static boolean isValidRequestPart(Object part) {
        if (!(part instanceof Map)) {
            return false;
        }
        Map<String, Object> r = JsCoercion.asMap(part);
        return r.containsKey("text")
                || r.containsKey("functionCall")
                || r.containsKey("functionResponse")
                || r.containsKey("inlineData")
                || r.containsKey("fileData")
                || r.containsKey("executableCode")
                || r.containsKey("codeExecutionResult")
                || r.containsKey("thought");
    }

    // ---- sanitizeRequestPayloadForAntigravity (request.ts:360-426) ------------------------------

    public static void sanitizeRequestPayloadForAntigravity(Map<String, Object> payload) {
        if (payload.get("contents") instanceof List) {
            List<Object> newContents = new ArrayList<>();
            for (Object content : JsCoercion.asList(payload.get("contents"))) {
                Object mapped = sanitizeContent(content);
                if (mapped != null) {
                    newContents.add(mapped);
                }
            }
            payload.put("contents", newContents);
        }

        Object systemInstruction = payload.get("systemInstruction");
        if (systemInstruction instanceof Map) {
            Map<String, Object> sys = JsCoercion.asMap(systemInstruction);
            if (sys.get("parts") instanceof List) {
                List<Object> sanitizedSystemParts = new ArrayList<>();
                for (Object p : JsCoercion.asList(sys.get("parts"))) {
                    if (isValidRequestPart(p)) {
                        sanitizedSystemParts.add(p);
                    }
                }
                if (!sanitizedSystemParts.isEmpty()) {
                    sys.put("parts", sanitizedSystemParts);
                } else {
                    payload.remove("systemInstruction");
                }
            }
        }
    }

    // one `contents.map` iteration; returns null to drop (filtered out).
    private static Object sanitizeContent(Object content) {
        if (!(content instanceof Map)) {
            return null;
        }
        Map<String, Object> contentRecord = JsCoercion.asMap(content);
        List<Object> rawParts = contentRecord.get("parts") instanceof List
                ? JsCoercion.asList(contentRecord.get("parts")) : new ArrayList<Object>();

        boolean[] foundFirstFunctionCall = {false};
        List<Object> sanitizedParts = new ArrayList<>();
        for (Object part : rawParts) {
            if (!isValidRequestPart(part)) {
                continue;
            }
            sanitizedParts.add(sanitizePart(part, foundFirstFunctionCall));
        }

        if (sanitizedParts.isEmpty()) {
            return null;
        }
        Map<String, Object> newContent = new LinkedHashMap<>(contentRecord);
        newContent.put("parts", sanitizedParts);
        return newContent;
    }

    private static Object sanitizePart(Object part, boolean[] foundFirstFunctionCall) {
        if (!(part instanceof Map) || !JsCoercion.isTruthy(JsCoercion.asMap(part).get("functionCall"))) {
            return part;
        }
        Map<String, Object> p = JsCoercion.asMap(part);
        Object sig = JsCoercion.firstTruthy(p.get("thoughtSignature"), p.get("thought_signature"), null);
        if (!foundFirstFunctionCall[0]) {
            foundFirstFunctionCall[0] = true;
            // JS `!sig || sig.length < MIN`: length< only applies to a string; a truthy non-string
            // value keeps sig unchanged (its `.length` is undefined -> `undefined < MIN` is false).
            if (!JsCoercion.isTruthy(sig) || (sig instanceof String && ((String) sig).length() < MIN_SIGNATURE_LENGTH)) {
                sig = AntigravityThinkingBlocks.SKIP_THOUGHT_SIGNATURE;
            }
        } else {
            sig = null; // JS undefined
        }
        if (JsCoercion.isTruthy(sig)) {
            Map<String, Object> out = new LinkedHashMap<>(p);
            out.put("thought_signature", sig);
            out.put("thoughtSignature", sig);
            return out;
        }
        Map<String, Object> newPart = new LinkedHashMap<>(p);
        newPart.remove("thoughtSignature");
        newPart.remove("thought_signature");
        return newPart;
    }

    // ---- part predicates (request.ts:428-457) ---------------------------------------------------

    public static boolean isGeminiToolUsePart(Object part) {
        if (!(part instanceof Map)) {
            return false;
        }
        Map<String, Object> p = JsCoercion.asMap(part);
        return JsCoercion.isTruthy(p.get("functionCall")) || JsCoercion.isTruthy(p.get("tool_use")) || JsCoercion.isTruthy(p.get("toolUse"));
    }

    public static boolean isGeminiThinkingPart(Object part) {
        if (!(part instanceof Map)) {
            return false;
        }
        Map<String, Object> p = JsCoercion.asMap(part);
        return Boolean.TRUE.equals(p.get("thought")) || "thinking".equals(p.get("type")) || "reasoning".equals(p.get("type"));
    }

    static String getThinkingPartText(Map<String, Object> part) {
        if (part.get("text") instanceof String) {
            return (String) part.get("text");
        }
        if (part.get("thinking") instanceof String) {
            return (String) part.get("thinking");
        }
        return "";
    }

    // ---- hasCachedMatchingSignature (request.ts:459-479) ----------------------------------------

    public static boolean hasCachedMatchingSignature(Object part, String sessionId, AntigravityThinkingBlocks.CachedSignatureLookup lookup) {
        if (!(part instanceof Map)) {
            return false;
        }
        Map<String, Object> p = JsCoercion.asMap(part);
        String text = getThinkingPartText(p);
        if (text.isEmpty()) {
            return false;
        }
        String expectedSignature = lookup != null ? lookup.get(sessionId, text) : null;
        if (!JsCoercion.isTruthy(expectedSignature)) {
            return false;
        }
        if (Boolean.TRUE.equals(p.get("thought"))) {
            return expectedSignature.equals(p.get("thoughtSignature"));
        }
        return expectedSignature.equals(p.get("signature"));
    }

    // ---- ensureThoughtSignature (request.ts:481-504) --------------------------------------------

    public static Object ensureThoughtSignature(Object part, String sessionId) {
        if (!(part instanceof Map)) {
            return part;
        }
        if (!JsCoercion.isTruthy(sessionId)) {
            return part;
        }
        Map<String, Object> p = JsCoercion.asMap(part);
        String text = getThinkingPartText(p);
        if (text.isEmpty()) {
            return part;
        }
        if (Boolean.TRUE.equals(p.get("thought"))) {
            Map<String, Object> out = new LinkedHashMap<>(p);
            out.put("thoughtSignature", SENTINEL_SIGNATURE);
            return out;
        }
        Object type = p.get("type");
        if ("thinking".equals(type) || "reasoning".equals(type) || "redacted_thinking".equals(type)) {
            Map<String, Object> out = new LinkedHashMap<>(p);
            out.put("signature", SENTINEL_SIGNATURE);
            return out;
        }
        return part;
    }

    // ---- hasSignedThinkingPart (request.ts:506-544) ---------------------------------------------

    public static boolean hasSignedThinkingPart(Object part, String sessionId, AntigravityThinkingBlocks.CachedSignatureLookup lookup) {
        if (!(part instanceof Map)) {
            return false;
        }
        Map<String, Object> p = JsCoercion.asMap(part);

        if (Boolean.TRUE.equals(p.get("thought"))) {
            Object sig = p.get("thoughtSignature");
            if (SENTINEL_SIGNATURE.equals(sig) || AntigravityThinkingBlocks.SKIP_THOUGHT_SIGNATURE.equals(sig)) {
                return true;
            }
            if (!(sig instanceof String) || ((String) sig).length() < MIN_SIGNATURE_LENGTH) {
                return false;
            }
            if (!JsCoercion.isTruthy(sessionId)) {
                return true;
            }
            return hasCachedMatchingSignature(p, sessionId, lookup);
        }

        Object type = p.get("type");
        if ("thinking".equals(type) || "reasoning".equals(type) || "redacted_thinking".equals(type)) {
            Object sig = p.get("signature");
            if (SENTINEL_SIGNATURE.equals(sig) || AntigravityThinkingBlocks.SKIP_THOUGHT_SIGNATURE.equals(sig)) {
                return true;
            }
            if (!(sig instanceof String) || ((String) sig).length() < MIN_SIGNATURE_LENGTH) {
                return false;
            }
            if (!JsCoercion.isTruthy(sessionId)) {
                return true;
            }
            return hasCachedMatchingSignature(p, sessionId, lookup);
        }

        return false;
    }

    // ---- ensureMessageThinkingSignature (request.ts:589-608) ------------------------------------

    static Object ensureMessageThinkingSignature(Object block, String sessionId) {
        if (!(block instanceof Map)) {
            return block;
        }
        Map<String, Object> b = JsCoercion.asMap(block);
        Object type = b.get("type");
        if (!"thinking".equals(type) && !"redacted_thinking".equals(type)) {
            return block;
        }
        String text = getThinkingPartText(b);
        if (text.isEmpty()) {
            return block;
        }
        if (!JsCoercion.isTruthy(sessionId)) {
            return block;
        }
        Map<String, Object> out = new LinkedHashMap<>(b);
        out.put("signature", AntigravityThinkingBlocks.SKIP_THOUGHT_SIGNATURE);
        return out;
    }

    // ---- hasToolUse* / hasSignedThinking* (request.ts:610-646) ----------------------------------

    public static boolean hasToolUseInContents(List<Object> contents) {
        for (Object content : contents) {
            if (!(content instanceof Map) || !(JsCoercion.asMap(content).get("parts") instanceof List)) {
                continue;
            }
            for (Object part : JsCoercion.asList(JsCoercion.asMap(content).get("parts"))) {
                if (isGeminiToolUsePart(part)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasSignedThinkingInContents(List<Object> contents, String sessionId, AntigravityThinkingBlocks.CachedSignatureLookup lookup) {
        for (Object content : contents) {
            if (!(content instanceof Map) || !(JsCoercion.asMap(content).get("parts") instanceof List)) {
                continue;
            }
            for (Object part : JsCoercion.asList(JsCoercion.asMap(content).get("parts"))) {
                if (hasSignedThinkingPart(part, sessionId, lookup)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasToolUseInMessages(List<Object> messages) {
        for (Object message : messages) {
            if (!(message instanceof Map) || !(JsCoercion.asMap(message).get("content") instanceof List)) {
                continue;
            }
            for (Object block : JsCoercion.asList(JsCoercion.asMap(message).get("content"))) {
                if (block instanceof Map) {
                    Object type = JsCoercion.asMap(block).get("type");
                    if ("tool_use".equals(type) || "tool_result".equals(type)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean hasSignedThinkingInMessages(List<Object> messages, String sessionId, AntigravityThinkingBlocks.CachedSignatureLookup lookup) {
        for (Object message : messages) {
            if (!(message instanceof Map) || !(JsCoercion.asMap(message).get("content") instanceof List)) {
                continue;
            }
            for (Object block : JsCoercion.asList(JsCoercion.asMap(message).get("content"))) {
                if (hasSignedThinkingPart(block, sessionId, lookup)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- ensureThinkingBeforeToolUseInContents (request.ts:546-587) -----------------------------

    public static List<Object> ensureThinkingBeforeToolUseInContents(
            List<Object> contents, String signatureSessionKey,
            AntigravityThinkingBlocks.CachedSignatureLookup lookup, SignatureStore store) {

        List<Object> out = new ArrayList<>();
        for (Object contentObj : contents) {
            if (!(contentObj instanceof Map) || !(JsCoercion.asMap(contentObj).get("parts") instanceof List)) {
                out.add(contentObj);
                continue;
            }
            Map<String, Object> content = JsCoercion.asMap(contentObj);
            Object role = content.get("role");
            if (!"model".equals(role) && !"assistant".equals(role)) {
                out.add(contentObj);
                continue;
            }
            List<Object> parts = JsCoercion.asList(content.get("parts"));
            if (!anyToolUse(parts)) {
                out.add(contentObj);
                continue;
            }

            List<Object> thinkingParts = new ArrayList<>();
            List<Object> otherParts = new ArrayList<>();
            for (Object p : parts) {
                if (isGeminiThinkingPart(p)) {
                    thinkingParts.add(ensureThoughtSignature(p, signatureSessionKey));
                } else {
                    otherParts.add(p);
                }
            }

            boolean hasSignedThinking = anySigned(thinkingParts, signatureSessionKey, lookup);
            if (hasSignedThinking) {
                out.add(withParts(content, concat(thinkingParts, otherParts)));
                continue;
            }

            Map<String, Object> lastThinking = store != null ? store.get(signatureSessionKey) : null;
            if (lastThinking == null) {
                out.add(withParts(content, otherParts));
                continue;
            }
            Map<String, Object> injected = new LinkedHashMap<>();
            injected.put("thought", true);
            injected.put("text", lastThinking.get("text"));
            injected.put("thoughtSignature", SENTINEL_SIGNATURE);
            List<Object> merged = new ArrayList<>();
            merged.add(injected);
            merged.addAll(otherParts);
            out.add(withParts(content, merged));
        }
        return out;
    }

    // ---- ensureThinkingBeforeToolUseInMessages (request.ts:648-697) -----------------------------

    public static List<Object> ensureThinkingBeforeToolUseInMessages(
            List<Object> messages, String signatureSessionKey,
            AntigravityThinkingBlocks.CachedSignatureLookup lookup, SignatureStore store) {

        List<Object> out = new ArrayList<>();
        for (Object messageObj : messages) {
            if (!(messageObj instanceof Map) || !(JsCoercion.asMap(messageObj).get("content") instanceof List)) {
                out.add(messageObj);
                continue;
            }
            Map<String, Object> message = JsCoercion.asMap(messageObj);
            if (!"assistant".equals(message.get("role"))) {
                out.add(messageObj);
                continue;
            }
            List<Object> blocks = JsCoercion.asList(message.get("content"));
            if (!anyToolUseBlock(blocks)) {
                out.add(messageObj);
                continue;
            }

            List<Object> thinkingBlocks = new ArrayList<>();
            List<Object> otherBlocks = new ArrayList<>();
            for (Object b : blocks) {
                if (isThinkingBlock(b)) {
                    thinkingBlocks.add(ensureMessageThinkingSignature(b, signatureSessionKey));
                } else {
                    otherBlocks.add(b);
                }
            }

            boolean hasSignedThinking = anySigned(thinkingBlocks, signatureSessionKey, lookup);
            if (hasSignedThinking) {
                out.add(withContent(message, concat(thinkingBlocks, otherBlocks)));
                continue;
            }

            Map<String, Object> lastThinking = store != null ? store.get(signatureSessionKey) : null;
            if (lastThinking == null) {
                Object existingThinking = thinkingBlocks.isEmpty() ? null : thinkingBlocks.get(0);
                String thinkingText = existingBlockText(existingThinking);
                Map<String, Object> sentinelBlock = new LinkedHashMap<>();
                sentinelBlock.put("type", "thinking");
                sentinelBlock.put("thinking", thinkingText);
                sentinelBlock.put("signature", AntigravityThinkingBlocks.SKIP_THOUGHT_SIGNATURE);
                List<Object> merged = new ArrayList<>();
                merged.add(sentinelBlock);
                merged.addAll(otherBlocks);
                out.add(withContent(message, merged));
                continue;
            }
            Map<String, Object> injected = new LinkedHashMap<>();
            injected.put("type", "thinking");
            injected.put("thinking", lastThinking.get("text"));
            injected.put("signature", AntigravityThinkingBlocks.SKIP_THOUGHT_SIGNATURE);
            List<Object> merged = new ArrayList<>();
            merged.add(injected);
            merged.addAll(otherBlocks);
            out.add(withContent(message, merged));
        }
        return out;
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static boolean anyToolUse(List<Object> parts) {
        for (Object p : parts) {
            if (isGeminiToolUsePart(p)) return true;
        }
        return false;
    }

    private static boolean anySigned(List<Object> parts, String key, AntigravityThinkingBlocks.CachedSignatureLookup lookup) {
        for (Object p : parts) {
            if (hasSignedThinkingPart(p, key, lookup)) return true;
        }
        return false;
    }

    // blocks.some(b => b && typeof b === "object" && (b.type === "tool_use" || b.type === "tool_result"))
    private static boolean anyToolUseBlock(List<Object> blocks) {
        for (Object b : blocks) {
            if (b instanceof Map) {
                Object type = JsCoercion.asMap(b).get("type");
                if ("tool_use".equals(type) || "tool_result".equals(type)) return true;
            }
        }
        return false;
    }

    private static boolean isThinkingBlock(Object b) {
        if (!(b instanceof Map)) return false;
        Object type = JsCoercion.asMap(b).get("type");
        return "thinking".equals(type) || "redacted_thinking".equals(type);
    }

    // existingThinking?.thinking || existingThinking?.text || ""
    private static String existingBlockText(Object block) {
        if (!(block instanceof Map)) return "";
        Map<String, Object> b = JsCoercion.asMap(block);
        Object v = JsCoercion.firstTruthy(b.get("thinking"), b.get("text"), "");
        return v instanceof String ? (String) v : "";
    }

    private static Map<String, Object> withParts(Map<String, Object> content, List<Object> parts) {
        Map<String, Object> out = new LinkedHashMap<>(content);
        out.put("parts", parts);
        return out;
    }

    private static Map<String, Object> withContent(Map<String, Object> message, List<Object> content) {
        Map<String, Object> out = new LinkedHashMap<>(message);
        out.put("content", content);
        return out;
    }

    private static List<Object> concat(List<Object> a, List<Object> b) {
        List<Object> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
