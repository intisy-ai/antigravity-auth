package io.github.intisy.ai.antigravity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of antigravity-auth's {@code src/plugin/transform/cross-model-sanitizer.ts} (T7b,
 * Bucket A): the signature-stripping tree walk that fixes the "Invalid {@code signature} in
 * {@code thinking} block" error when switching models mid-session. Gemini stores
 * {@code thoughtSignature} in {@code metadata.google}; Claude stores {@code signature} on top-level
 * thinking blocks -- foreign signatures fail validation on the target model and must be stripped.
 *
 * <p>Faithful copy-vs-mutate semantics: {@link #deepSanitizeCrossModelMetadata} /
 * {@link #sanitizeCrossModelPayload} return a NEW tree (the TS spreads {@code {...obj}} at every
 * level, never touching the input); {@link #sanitizeCrossModelPayloadInPlace} mutates the payload;
 * {@link #stripGeminiThinkingMetadata} / {@link #stripClaudeThinkingFields} mutate the {@code part}
 * they are handed (returning the strip count, the TS returns {@code {part, stripped}} where
 * {@code part} is the same reference). Data model = JSON tree {@code Map<String,Object>} /
 * {@code List<Object>}.
 */
public final class CrossModelSanitizer {

    private static final String[] GEMINI_SIGNATURE_FIELDS = {"thoughtSignature", "thinkingMetadata"};
    private static final String[] CLAUDE_SIGNATURE_FIELDS = {"signature"};

    // cross-model-sanitizer.ts:97 -- the ">= 50" gate on a "real" Claude signature (short
    // signature-like fields on NON-thinking parts are left alone). Named here for clarity; the TS
    // inlines the literal 50.
    public static final int MIN_SIGNATURE_LENGTH = 50;

    private CrossModelSanitizer() {
    }

    /**
     * Port of {@code getModelFamily} (cross-model-sanitizer.ts:30-34) -- {@code "claude"},
     * {@code "gemini"}, or {@code "unknown"} (distinct from {@link AntigravityModelResolver#getModelFamily}).
     */
    public static String getModelFamily(String model) {
        if (ClaudeTransforms.isClaudeModel(model)) return "claude";
        if (GeminiTransforms.isGeminiModel(model)) return "gemini";
        return "unknown";
    }

    /**
     * Port of {@code stripGeminiThinkingMetadata} (cross-model-sanitizer.ts:40-79). Mutates
     * {@code part}, returns the strip count.
     */
    public static int stripGeminiThinkingMetadata(Map<String, Object> part, boolean preserveNonSignature) {
        int stripped = 0;

        if (part.containsKey("thoughtSignature")) {
            part.remove("thoughtSignature");
            stripped++;
        }

        if (part.containsKey("thinkingMetadata")) {
            part.remove("thinkingMetadata");
            stripped++;
        }

        if (JsCoercion.isPlainObject(part.get("metadata"))) {
            Map<String, Object> metadata = JsCoercion.asMap(part.get("metadata"));
            if (JsCoercion.isPlainObject(metadata.get("google"))) {
                Map<String, Object> google = JsCoercion.asMap(metadata.get("google"));

                for (String field : GEMINI_SIGNATURE_FIELDS) {
                    if (google.containsKey(field)) {
                        google.remove(field);
                        stripped++;
                    }
                }

                if (!preserveNonSignature || google.isEmpty()) {
                    metadata.remove("google");
                }

                if (metadata.isEmpty()) {
                    part.remove("metadata");
                }
            }
        }

        return stripped;
    }

    /**
     * Port of {@code stripClaudeThinkingFields} (cross-model-sanitizer.ts:81-103). Mutates
     * {@code part}, returns the strip count.
     */
    public static int stripClaudeThinkingFields(Map<String, Object> part) {
        int stripped = 0;

        Object type = part.get("type");
        if ("thinking".equals(type) || "redacted_thinking".equals(type)) {
            for (String field : CLAUDE_SIGNATURE_FIELDS) {
                if (part.containsKey(field)) {
                    part.remove(field);
                    stripped++;
                }
            }
        }

        Object signature = part.get("signature");
        if (part.containsKey("signature") && signature instanceof String) {
            if (((String) signature).length() >= MIN_SIGNATURE_LENGTH) {
                part.remove("signature");
                stripped++;
            }
        }

        return stripped;
    }

    // sanitizePart (cross-model-sanitizer.ts:105-126) -- returns a COPY of the part with signatures
    // stripped, plus the strip count via the single-element int[] accumulator.
    private static Object sanitizePart(Object part, String targetFamily, boolean preserveNonSignature, int[] total) {
        if (!JsCoercion.isPlainObject(part)) {
            return part;
        }
        Map<String, Object> partObj = new LinkedHashMap<>(JsCoercion.asMap(part));
        if ("claude".equals(targetFamily)) {
            total[0] += stripGeminiThinkingMetadata(partObj, preserveNonSignature);
        } else if ("gemini".equals(targetFamily)) {
            total[0] += stripClaudeThinkingFields(partObj);
        }
        return partObj;
    }

    private static List<Object> sanitizeParts(List<Object> parts, String targetFamily, boolean preserveNonSignature, int[] total) {
        List<Object> out = new ArrayList<>();
        for (Object part : parts) {
            out.add(sanitizePart(part, targetFamily, preserveNonSignature, total));
        }
        return out;
    }

    // sanitizeContents (cross-model-sanitizer.ts:144-170) -- copies each content, sanitizing its
    // `parts` array.
    private static List<Object> sanitizeContents(List<Object> contents, String targetFamily, boolean preserveNonSignature, int[] total) {
        List<Object> out = new ArrayList<>();
        for (Object content : contents) {
            if (!JsCoercion.isPlainObject(content)) {
                out.add(content);
                continue;
            }
            Map<String, Object> contentObj = new LinkedHashMap<>(JsCoercion.asMap(content));
            if (contentObj.get("parts") instanceof List) {
                contentObj.put("parts", sanitizeParts(JsCoercion.asList(contentObj.get("parts")), targetFamily, preserveNonSignature, total));
            }
            out.add(contentObj);
        }
        return out;
    }

    // sanitizeMessages (cross-model-sanitizer.ts:172-198) -- copies each message, sanitizing its
    // `content` array (Anthropic format).
    private static List<Object> sanitizeMessages(List<Object> messages, String targetFamily, boolean preserveNonSignature, int[] total) {
        List<Object> out = new ArrayList<>();
        for (Object message : messages) {
            if (!JsCoercion.isPlainObject(message)) {
                out.add(message);
                continue;
            }
            Map<String, Object> messageObj = new LinkedHashMap<>(JsCoercion.asMap(message));
            if (messageObj.get("content") instanceof List) {
                messageObj.put("content", sanitizeParts(JsCoercion.asList(messageObj.get("content")), targetFamily, preserveNonSignature, total));
            }
            out.add(messageObj);
        }
        return out;
    }

    /** Result of {@link #deepSanitizeCrossModelMetadata}: the (new) tree plus strip count. */
    public static final class DeepResult {
        public final Object obj;
        public final int stripped;

        public DeepResult(Object obj, int stripped) {
            this.obj = obj;
            this.stripped = stripped;
        }
    }

    /** Port of {@code deepSanitizeCrossModelMetadata} (cross-model-sanitizer.ts:200-260). */
    public static DeepResult deepSanitizeCrossModelMetadata(Object obj, String targetFamily, boolean preserveNonSignature) {
        if (!JsCoercion.isPlainObject(obj)) {
            return new DeepResult(obj, 0);
        }

        int[] total = {0};
        Map<String, Object> result = new LinkedHashMap<>(JsCoercion.asMap(obj));

        if (result.get("contents") instanceof List) {
            result.put("contents", sanitizeContents(JsCoercion.asList(result.get("contents")), targetFamily, preserveNonSignature, total));
        }

        if (result.get("messages") instanceof List) {
            result.put("messages", sanitizeMessages(JsCoercion.asList(result.get("messages")), targetFamily, preserveNonSignature, total));
        }

        if (JsCoercion.isPlainObject(result.get("extra_body"))) {
            Map<String, Object> extraBody = new LinkedHashMap<>(JsCoercion.asMap(result.get("extra_body")));
            if (extraBody.get("messages") instanceof List) {
                extraBody.put("messages", sanitizeMessages(JsCoercion.asList(extraBody.get("messages")), targetFamily, preserveNonSignature, total));
            }
            result.put("extra_body", extraBody);
        }

        if (result.get("requests") instanceof List) {
            List<Object> sanitizedRequests = new ArrayList<>();
            for (Object req : JsCoercion.asList(result.get("requests"))) {
                DeepResult sub = deepSanitizeCrossModelMetadata(req, targetFamily, preserveNonSignature);
                total[0] += sub.stripped;
                sanitizedRequests.add(sub.obj);
            }
            result.put("requests", sanitizedRequests);
        }

        return new DeepResult(result, total[0]);
    }

    /** Result of {@link #sanitizeCrossModelPayload}. */
    public static final class SanitizationResult {
        public final Object payload;
        public final boolean modified;
        public final int signaturesStripped;

        public SanitizationResult(Object payload, boolean modified, int signaturesStripped) {
            this.payload = payload;
            this.modified = modified;
            this.signaturesStripped = signaturesStripped;
        }
    }

    /**
     * Port of {@code sanitizeCrossModelPayload} (cross-model-sanitizer.ts:262-288). {@code options}
     * is a JSON-tree map with {@code targetModel} (required), {@code sourceModel} (unused here) and
     * {@code preserveNonSignatureMetadata} (defaults to {@code true}).
     */
    public static SanitizationResult sanitizeCrossModelPayload(Object payload, Map<String, Object> options) {
        String targetFamily = getModelFamily(String.valueOf(options.get("targetModel")));

        if ("unknown".equals(targetFamily)) {
            return new SanitizationResult(payload, false, 0);
        }

        boolean preserveNonSignature = coercePreserve(options.get("preserveNonSignatureMetadata"));
        DeepResult result = deepSanitizeCrossModelMetadata(payload, targetFamily, preserveNonSignature);

        return new SanitizationResult(result.obj, result.stripped > 0, result.stripped);
    }

    // options.preserveNonSignatureMetadata ?? true (nullish default).
    private static boolean coercePreserve(Object v) {
        Object resolved = JsCoercion.nullish(v, Boolean.TRUE);
        return resolved instanceof Boolean ? (Boolean) resolved : JsCoercion.isTruthy(resolved);
    }

    /**
     * Port of {@code sanitizeCrossModelPayloadInPlace} (cross-model-sanitizer.ts:290-350). Mutates
     * {@code payload} directly, returns the strip count.
     */
    public static int sanitizeCrossModelPayloadInPlace(Map<String, Object> payload, Map<String, Object> options) {
        String targetFamily = getModelFamily(String.valueOf(options.get("targetModel")));

        if ("unknown".equals(targetFamily)) {
            return 0;
        }

        boolean preserveNonSignature = coercePreserve(options.get("preserveNonSignatureMetadata"));
        int[] total = {0};

        if (payload.get("contents") instanceof List) {
            for (Object content : JsCoercion.asList(payload.get("contents"))) {
                if (JsCoercion.isPlainObject(content) && JsCoercion.asMap(content).get("parts") instanceof List) {
                    stripPartsInPlace(JsCoercion.asList(JsCoercion.asMap(content).get("parts")), targetFamily, preserveNonSignature, total);
                }
            }
        }

        if (payload.get("messages") instanceof List) {
            for (Object message : JsCoercion.asList(payload.get("messages"))) {
                if (JsCoercion.isPlainObject(message) && JsCoercion.asMap(message).get("content") instanceof List) {
                    stripPartsInPlace(JsCoercion.asList(JsCoercion.asMap(message).get("content")), targetFamily, preserveNonSignature, total);
                }
            }
        }

        if (JsCoercion.isPlainObject(payload.get("extra_body"))) {
            Map<String, Object> extraBody = JsCoercion.asMap(payload.get("extra_body"));
            if (extraBody.get("messages") instanceof List) {
                for (Object message : JsCoercion.asList(extraBody.get("messages"))) {
                    if (JsCoercion.isPlainObject(message) && JsCoercion.asMap(message).get("content") instanceof List) {
                        stripPartsInPlace(JsCoercion.asList(JsCoercion.asMap(message).get("content")), targetFamily, preserveNonSignature, total);
                    }
                }
            }
        }

        return total[0];
    }

    private static void stripPartsInPlace(List<Object> parts, String targetFamily, boolean preserveNonSignature, int[] total) {
        for (Object part : parts) {
            if (!JsCoercion.isPlainObject(part)) continue;
            if ("claude".equals(targetFamily)) {
                total[0] += stripGeminiThinkingMetadata(JsCoercion.asMap(part), preserveNonSignature);
            } else if ("gemini".equals(targetFamily)) {
                total[0] += stripClaudeThinkingFields(JsCoercion.asMap(part));
            }
        }
    }
}
