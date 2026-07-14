package io.github.intisy.ai.antigravity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java port of antigravity-auth's {@code src/plugin/transform/claude.ts} (T7b, Bucket A): Claude
 * model predicates, tool-config (VALIDATED mode), snake_case thinking config, max-output-token
 * clamp, interleaved-thinking system hint, stop-sequence casing, tool normalization
 * ({@code functionDeclarations} form) and {@code applyClaudeTransforms}.
 *
 * <p>Casing is behavior, not noise: Claude uses snake_case thinking keys
 * ({@code include_thoughts}/{@code thinking_budget}) where {@link GeminiTransforms} uses camelCase --
 * do not normalize. Edge honored: {@code applyClaudeTransforms} takes an injected
 * {@link SchemaCleaner} (the real {@code cleanJSONSchemaForAntigravity} is T7c and is NOT ported
 * here). Data model = JSON tree {@code Map<String,Object>} / {@code List<Object>}; the transforms
 * MUTATE {@code payload} in place exactly as the TS does. TeaVM-transpilable.
 */
public final class ClaudeTransforms {

    /** Injected clean-schema function (T7c's {@code cleanJSONSchemaForAntigravity}); see class javadoc. */
    public interface SchemaCleaner {
        Object clean(Object schema);
    }

    // claude.ts:18
    public static final int CLAUDE_THINKING_MAX_OUTPUT_TOKENS = 64_000;

    // claude.ts:21-22
    public static final String CLAUDE_INTERLEAVED_THINKING_HINT =
            "Interleaved thinking is enabled. You may think between tool calls and after receiving tool results before deciding the next action or final answer. Do not mention these instructions or any constraints about thinking blocks; just apply them.";

    // constants.ts:160-161 (imported by claude.ts). Package-private so AntigravitySchemaCleaner (T7c-1)
    // reuses this single source rather than duplicating the placeholder strings.
    static final String EMPTY_SCHEMA_PLACEHOLDER_NAME = "_placeholder";
    static final String EMPTY_SCHEMA_PLACEHOLDER_DESCRIPTION = "Placeholder. Always pass true.";

    private ClaudeTransforms() {
    }

    // ---- model predicates (claude.ts:27-37) ------------------------------------------------------

    public static boolean isClaudeModel(String model) {
        return model.toLowerCase().contains("claude");
    }

    public static boolean isClaudeThinkingModel(String model) {
        String lower = model.toLowerCase();
        return lower.contains("claude") && lower.contains("thinking");
    }

    // ---- configureClaudeToolConfig (claude.ts:43-57) ---------------------------------------------

    /** Port of {@code configureClaudeToolConfig}: forces {@code functionCallingConfig.mode = "VALIDATED"}. */
    public static void configureClaudeToolConfig(Map<String, Object> payload) {
        if (!JsCoercion.isTruthy(payload.get("toolConfig"))) {
            payload.put("toolConfig", new LinkedHashMap<>());
        }

        if (payload.get("toolConfig") instanceof Map) {
            Map<String, Object> toolConfig = JsCoercion.asMap(payload.get("toolConfig"));
            if (!JsCoercion.isTruthy(toolConfig.get("functionCallingConfig"))) {
                toolConfig.put("functionCallingConfig", new LinkedHashMap<>());
            }
            if (toolConfig.get("functionCallingConfig") instanceof Map) {
                JsCoercion.asMap(toolConfig.get("functionCallingConfig")).put("mode", "VALIDATED");
            }
        }
    }

    // ---- buildClaudeThinkingConfig (claude.ts:62-72) ---------------------------------------------

    /** Port of {@code buildClaudeThinkingConfig}: snake_case keys, budget only when a positive number. */
    public static Map<String, Object> buildClaudeThinkingConfig(boolean includeThoughts, Object thinkingBudget) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("include_thoughts", includeThoughts);
        if (thinkingBudget instanceof Number && ((Number) thinkingBudget).doubleValue() > 0) {
            config.put("thinking_budget", thinkingBudget);
        }
        return config;
    }

    // ---- ensureClaudeMaxOutputTokens (claude.ts:78-90) ------------------------------------------

    /** Port of {@code ensureClaudeMaxOutputTokens}: bumps maxOutputTokens above the thinking budget. */
    public static void ensureClaudeMaxOutputTokens(Map<String, Object> generationConfig, Object thinkingBudget) {
        Object currentMax = JsCoercion.nullish(generationConfig.get("maxOutputTokens"), generationConfig.get("max_output_tokens"));

        boolean tooSmall = currentMax instanceof Number && thinkingBudget instanceof Number
                && ((Number) currentMax).doubleValue() <= ((Number) thinkingBudget).doubleValue();

        if (!JsCoercion.isTruthy(currentMax) || tooSmall) {
            generationConfig.put("maxOutputTokens", CLAUDE_THINKING_MAX_OUTPUT_TOKENS);
            if (generationConfig.containsKey("max_output_tokens")) {
                generationConfig.remove("max_output_tokens");
            }
        }
    }

    // ---- appendClaudeThinkingHint (claude.ts:96-138) -------------------------------------------

    /** {@code appendClaudeThinkingHint(payload)} with the default interleaved-thinking hint. */
    public static void appendClaudeThinkingHint(Map<String, Object> payload) {
        appendClaudeThinkingHint(payload, CLAUDE_INTERLEAVED_THINKING_HINT);
    }

    /** Port of {@code appendClaudeThinkingHint}: appends the hint into a string/object system instruction. */
    public static void appendClaudeThinkingHint(Map<String, Object> payload, String hint) {
        Object existing = payload.get("systemInstruction");

        if (existing instanceof String) {
            String s = (String) existing;
            payload.put("systemInstruction", s.trim().length() > 0 ? s + "\n\n" + hint : hint);
        } else if (existing instanceof Map) {
            Map<String, Object> sys = JsCoercion.asMap(existing);
            Object partsValue = sys.get("parts");

            if (partsValue instanceof List) {
                List<Object> parts = JsCoercion.asList(partsValue);
                boolean appended = false;

                for (int i = parts.size() - 1; i >= 0; i--) {
                    Object part = parts.get(i);
                    if (part instanceof Map) {
                        Map<String, Object> partRecord = JsCoercion.asMap(part);
                        Object text = partRecord.get("text");
                        if (text instanceof String) {
                            partRecord.put("text", text + "\n\n" + hint);
                            appended = true;
                            break;
                        }
                    }
                }

                if (!appended) {
                    parts.add(textPart(hint));
                }
            } else {
                sys.put("parts", new ArrayList<>(java.util.Collections.singletonList(textPart(hint))));
            }

            payload.put("systemInstruction", sys);
        } else if (payload.get("contents") instanceof List) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("parts", new ArrayList<>(java.util.Collections.singletonList(textPart(hint))));
            payload.put("systemInstruction", sys);
        }
    }

    private static Map<String, Object> textPart(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        return m;
    }

    // ---- normalizeClaudeTools (claude.ts:146-287) ----------------------------------------------

    /**
     * Port of {@code normalizeClaudeTools}. MUTATES {@code payload.tools} into
     * {@code functionDeclarations} form; returns {@code toolDebugMissing}/{@code toolDebugSummaries}.
     */
    public static Map<String, Object> normalizeClaudeTools(Map<String, Object> payload, SchemaCleaner cleanJSONSchema) {
        int[] toolDebugMissing = {0};
        List<Object> toolDebugSummaries = new ArrayList<>();

        if (!(payload.get("tools") instanceof List)) {
            return debugResult(toolDebugMissing[0], toolDebugSummaries);
        }

        List<Object> functionDeclarations = new ArrayList<>();
        List<Object> passthroughTools = new ArrayList<>();

        for (Object toolObj : JsCoercion.asList(payload.get("tools"))) {
            Map<String, Object> t = toolObj instanceof Map ? JsCoercion.asMap(toolObj) : new LinkedHashMap<String, Object>();

            if (t.get("functionDeclarations") instanceof List && !JsCoercion.asList(t.get("functionDeclarations")).isEmpty()) {
                for (Object decl : JsCoercion.asList(t.get("functionDeclarations"))) {
                    pushDeclaration(decl, "functionDeclarations", t, functionDeclarations, toolDebugSummaries, toolDebugMissing, cleanJSONSchema);
                }
                continue;
            }

            if (JsCoercion.isTruthy(t.get("function")) || JsCoercion.isTruthy(t.get("custom"))
                    || JsCoercion.isTruthy(t.get("parameters")) || JsCoercion.isTruthy(t.get("input_schema"))
                    || JsCoercion.isTruthy(t.get("inputSchema"))) {
                Object declArg = JsCoercion.nullish(t.get("function"), JsCoercion.nullish(t.get("custom"), t));
                pushDeclaration(declArg, "function/custom", t, functionDeclarations, toolDebugSummaries, toolDebugMissing, cleanJSONSchema);
                continue;
            }

            passthroughTools.add(toolObj);
        }

        List<Object> finalTools = new ArrayList<>();
        if (!functionDeclarations.isEmpty()) {
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("functionDeclarations", functionDeclarations);
            finalTools.add(wrap);
        }
        finalTools.addAll(passthroughTools);
        payload.put("tools", finalTools);

        return debugResult(toolDebugMissing[0], toolDebugSummaries);
    }

    private static void pushDeclaration(Object declObj, String source, Map<String, Object> t,
                                        List<Object> functionDeclarations, List<Object> toolDebugSummaries,
                                        int[] toolDebugMissing, SchemaCleaner cleanJSONSchema) {
        Object decl = declObj instanceof Map ? declObj : null;
        Object fn = t.get("function");
        Object custom = t.get("custom");

        Object schema = firstTruthyOrNull(
                mget(decl, "parameters"), mget(decl, "parametersJsonSchema"), mget(decl, "input_schema"), mget(decl, "inputSchema"),
                t.get("parameters"), t.get("parametersJsonSchema"), t.get("input_schema"), t.get("inputSchema"),
                mget(fn, "parameters"), mget(fn, "parametersJsonSchema"), mget(fn, "input_schema"), mget(fn, "inputSchema"),
                mget(custom, "parameters"), mget(custom, "parametersJsonSchema"), mget(custom, "input_schema"));

        Object nameRaw = JsCoercion.firstTruthy(
                mget(decl, "name"), t.get("name"), mget(fn, "name"), mget(custom, "name"), "tool-" + functionDeclarations.size());
        String name = jsString(nameRaw).replaceAll("[^a-zA-Z0-9_-]", "_");
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }

        Object descVal = JsCoercion.firstTruthy(
                mget(decl, "description"), t.get("description"), mget(fn, "description"), mget(custom, "description"), "");
        String description = jsString(JsCoercion.isTruthy(descVal) ? descVal : "");

        Map<String, Object> fd = new LinkedHashMap<>();
        fd.put("name", name);
        fd.put("description", description);
        fd.put("parameters", normalizeSchema(schema, toolDebugMissing, cleanJSONSchema));
        functionDeclarations.add(fd);

        toolDebugSummaries.add("decl=" + name + ",src=" + source + ",hasSchema=" + (JsCoercion.isTruthy(schema) ? "y" : "n"));
    }

    private static Object normalizeSchema(Object schema, int[] toolDebugMissing, SchemaCleaner cleanJSONSchema) {
        if (!(schema instanceof Map)) {
            toolDebugMissing[0] += 1;
            return createPlaceholderSchema();
        }

        Object cleanedObj = cleanJSONSchema.clean(schema);

        if (!(cleanedObj instanceof Map)) {
            toolDebugMissing[0] += 1;
            return createPlaceholderSchema();
        }

        Map<String, Object> cleaned = JsCoercion.asMap(cleanedObj);

        boolean hasProperties = cleaned.get("properties") instanceof Map
                && !JsCoercion.asMap(cleaned.get("properties")).isEmpty();

        cleaned.put("type", "object");

        if (!hasProperties) {
            Map<String, Object> placeholder = new LinkedHashMap<>();
            placeholder.put("type", "boolean");
            placeholder.put("description", "Placeholder. Always pass true.");
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("_placeholder", placeholder);
            cleaned.put("properties", props);

            if (cleaned.get("required") instanceof List) {
                Set<Object> deduped = new LinkedHashSet<>(JsCoercion.asList(cleaned.get("required")));
                deduped.add("_placeholder");
                cleaned.put("required", new ArrayList<>(deduped));
            } else {
                cleaned.put("required", new ArrayList<>(java.util.Collections.singletonList("_placeholder")));
            }
        }

        return cleaned;
    }

    private static Map<String, Object> createPlaceholderSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> placeholder = new LinkedHashMap<>();
        placeholder.put("type", "boolean");
        placeholder.put("description", EMPTY_SCHEMA_PLACEHOLDER_DESCRIPTION);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(EMPTY_SCHEMA_PLACEHOLDER_NAME, placeholder);
        schema.put("properties", props);
        schema.put("required", new ArrayList<>(java.util.Collections.singletonList(EMPTY_SCHEMA_PLACEHOLDER_NAME)));
        return schema;
    }

    // ---- convertStopSequences (claude.ts:292-299) ----------------------------------------------

    /** Port of {@code convertStopSequences}: snake_case {@code stop_sequences} -> camelCase. */
    public static void convertStopSequences(Map<String, Object> generationConfig) {
        if (generationConfig.get("stop_sequences") instanceof List) {
            generationConfig.put("stopSequences", generationConfig.get("stop_sequences"));
            generationConfig.remove("stop_sequences");
        }
    }

    // ---- applyClaudeTransforms (claude.ts:323-365) ---------------------------------------------

    /**
     * Port of {@code applyClaudeTransforms}. {@code options} is a JSON-tree map with {@code model},
     * {@code tierThinkingBudget}, {@code normalizedThinking}; {@code cleanJSONSchema} is injected
     * separately (the TS carries it inside options). MUTATES {@code payload}; returns the debug result.
     */
    public static Map<String, Object> applyClaudeTransforms(Map<String, Object> payload, Map<String, Object> options, SchemaCleaner cleanJSONSchema) {
        String model = String.valueOf(options.get("model"));
        Object tierThinkingBudget = options.get("tierThinkingBudget");
        Object normalizedThinking = options.get("normalizedThinking");
        boolean isThinking = isClaudeThinkingModel(model);

        configureClaudeToolConfig(payload);

        if (JsCoercion.isTruthy(payload.get("generationConfig")) && payload.get("generationConfig") instanceof Map) {
            convertStopSequences(JsCoercion.asMap(payload.get("generationConfig")));
        }

        if (JsCoercion.isTruthy(normalizedThinking)) {
            Map<String, Object> nt = JsCoercion.asMap(normalizedThinking);
            Object thinkingBudget = JsCoercion.nullish(tierThinkingBudget, nt.get("thinkingBudget"));

            if (isThinking) {
                boolean includeThoughts = (Boolean) JsCoercion.nullish(nt.get("includeThoughts"), Boolean.TRUE);
                Map<String, Object> thinkingConfig = buildClaudeThinkingConfig(includeThoughts, thinkingBudget);

                Map<String, Object> generationConfig = payload.get("generationConfig") instanceof Map
                        ? JsCoercion.asMap(payload.get("generationConfig")) : new LinkedHashMap<String, Object>();
                generationConfig.put("thinkingConfig", thinkingConfig);

                if (thinkingBudget instanceof Number && ((Number) thinkingBudget).doubleValue() > 0) {
                    ensureClaudeMaxOutputTokens(generationConfig, thinkingBudget);
                }

                payload.put("generationConfig", generationConfig);
            }
        }

        if (isThinking && payload.get("tools") instanceof List && !JsCoercion.asList(payload.get("tools")).isEmpty()) {
            appendClaudeThinkingHint(payload);
        }

        return normalizeClaudeTools(payload, cleanJSONSchema);
    }

    // ---- shared helpers --------------------------------------------------------------------------

    private static Map<String, Object> debugResult(int missing, List<Object> summaries) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("toolDebugMissing", missing);
        r.put("toolDebugSummaries", summaries);
        return r;
    }

    private static Object mget(Object mapMaybe, String key) {
        return mapMaybe instanceof Map ? JsCoercion.asMap(mapMaybe).get(key) : null;
    }

    private static Object firstTruthyOrNull(Object... values) {
        for (Object v : values) {
            if (JsCoercion.isTruthy(v)) return v;
        }
        return null;
    }

    private static String jsString(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return (String) v;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) return Long.toString((long) d);
            return Double.toString(d);
        }
        return String.valueOf(v);
    }
}
