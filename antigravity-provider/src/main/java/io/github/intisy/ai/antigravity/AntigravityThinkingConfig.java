package io.github.intisy.ai.antigravity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thinking-config helpers: {@code DEFAULT_THINKING_BUDGET}, {@code isThinkingCapableModel},
 * {@code extractThinkingConfig}, {@code extractVariantThinkingConfig}, {@code resolveThinkingConfig}
 * and {@code normalizeThinkingConfig}. Pure functions. The companion thinking-block filtering +
 * transform helpers live in {@link AntigravityThinkingBlocks}.
 *
 * <p>Casing is behavior, not noise (mirrors {@link ClaudeTransforms}/{@link GeminiTransforms}):
 * {@code normalizeThinkingConfig} reads BOTH the Gemini camelCase ({@code thinkingBudget}/
 * {@code includeThoughts}) and the Claude snake_case ({@code thinking_budget}/{@code include_thoughts})
 * keys via a nullish fallback, but always EMITS the camelCase keys. Gemini-3 variant thinking uses a
 * {@code thinkingLevel} STRING while Claude/Gemini-2.5 use a numeric {@code thinkingBudget}; both are
 * surfaced verbatim (no cross-normalization). The effort->budget / level clamp TABLES are NOT here:
 * they live in {@link AntigravityModelResolver}; these functions only READ the raw thinking fields
 * off the request.
 *
 * <p>Data model = JSON tree {@code Map<String,Object>} / {@code List<Object>}; the returned config is
 * a NEW map (or {@code null}) with only the keys that carry a value present; an unset key is left
 * ABSENT, matching what {@code JSON.stringify} emits. Numbers pass through as the caller's
 * {@link Number} object; {@link #DEFAULT_THINKING_BUDGET} is an {@code int}. Pure tree-read: no
 * SPI/Clock/Random/JSON re-parse. TeaVM-transpilable.
 *
 * <p>Fidelity deviation (unreachable by valid requests): for a config slot ({@code thinkingConfig},
 * {@code thinking}, {@code google}, a {@code googleSearch}/{@code generationConfig.thinkingConfig}
 * container, or the {@code normalizeThinkingConfig} argument) this code requires a {@link Map}, so a
 * JSON ARRAY in that exact slot (invalid input) is treated as absent rather than having its numeric
 * indices read as properties.
 */
public final class AntigravityThinkingConfig {

    /** Default token budget for thinking/reasoning. */
    public static final int DEFAULT_THINKING_BUDGET = 16000;

    private AntigravityThinkingConfig() {
    }

    // ---- isThinkingCapableModel ------------------------------------------------------------------

    /** Name contains "thinking", "gemini-3", or "opus". */
    public static boolean isThinkingCapableModel(String modelName) {
        String lower = modelName.toLowerCase();
        return lower.contains("thinking") || lower.contains("gemini-3") || lower.contains("opus");
    }

    // ---- extractThinkingConfig -------------------------------------------------------------------

    /**
     * Reads Gemini-style {@code thinkingConfig} (nullish chain generationConfig -> extraBody ->
     * requestPayload) else the Anthropic-style {@code thinking} option
     * ({@code {type:"enabled", budgetTokens}}). Returns a {@code {includeThoughts, thinkingBudget}}
     * map, or {@code null}.
     */
    public static Map<String, Object> extractThinkingConfig(
            Map<String, Object> requestPayload,
            Map<String, Object> rawGenerationConfig,
            Map<String, Object> extraBody) {

        Object thinkingConfig = JsCoercion.nullish(
                rawGenerationConfig != null ? rawGenerationConfig.get("thinkingConfig") : null,
                JsCoercion.nullish(
                        extraBody != null ? extraBody.get("thinkingConfig") : null,
                        requestPayload.get("thinkingConfig")));

        if (JsCoercion.isTruthy(thinkingConfig) && thinkingConfig instanceof Map) {
            Map<String, Object> config = JsCoercion.asMap(thinkingConfig);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("includeThoughts", JsCoercion.isTruthy(config.get("includeThoughts")));
            result.put("thinkingBudget", config.get("thinkingBudget") instanceof Number
                    ? config.get("thinkingBudget") : DEFAULT_THINKING_BUDGET);
            return result;
        }

        Object anthropicThinking = JsCoercion.nullish(
                extraBody != null ? extraBody.get("thinking") : null,
                requestPayload.get("thinking"));

        if (JsCoercion.isTruthy(anthropicThinking) && anthropicThinking instanceof Map) {
            Map<String, Object> thinking = JsCoercion.asMap(anthropicThinking);
            if ("enabled".equals(thinking.get("type")) || JsCoercion.isTruthy(thinking.get("budgetTokens"))) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("includeThoughts", true);
                result.put("thinkingBudget", thinking.get("budgetTokens") instanceof Number
                        ? thinking.get("budgetTokens") : DEFAULT_THINKING_BUDGET);
                return result;
            }
        }

        return null;
    }

    // ---- extractVariantThinkingConfig ------------------------------------------------------------

    /**
     * Reads OpenCode's {@code providerOptions.google} (Gemini-3 {@code thinkingLevel} string form OR
     * budget-based {@code thinkingConfig.thinkingBudget}), plus {@code googleSearch}, falling back to
     * {@code generationConfig.thinkingConfig}. Returns the variant map, or {@code null} when it would
     * be empty.
     */
    public static Map<String, Object> extractVariantThinkingConfig(
            Map<String, Object> providerOptions,
            Map<String, Object> generationConfig) {

        Map<String, Object> result = new LinkedHashMap<>();

        Object googleRaw = providerOptions != null ? providerOptions.get("google") : null;
        if (JsCoercion.isTruthy(googleRaw) && googleRaw instanceof Map) {
            Map<String, Object> google = JsCoercion.asMap(googleRaw);

            if (google.get("thinkingLevel") instanceof String) {
                result.put("thinkingLevel", google.get("thinkingLevel"));
                // Leave the key absent (JSON-omitted) when it is not a boolean.
                if (google.get("includeThoughts") instanceof Boolean) {
                    result.put("includeThoughts", google.get("includeThoughts"));
                }
            } else if (JsCoercion.isTruthy(google.get("thinkingConfig")) && google.get("thinkingConfig") instanceof Map) {
                Map<String, Object> tc = JsCoercion.asMap(google.get("thinkingConfig"));
                if (tc.get("thinkingBudget") instanceof Number) {
                    result.put("thinkingBudget", tc.get("thinkingBudget"));
                }
            }

            if (JsCoercion.isTruthy(google.get("googleSearch")) && google.get("googleSearch") instanceof Map) {
                Map<String, Object> search = JsCoercion.asMap(google.get("googleSearch"));
                Map<String, Object> gs = new LinkedHashMap<>();
                Object mode = search.get("mode");
                if ("auto".equals(mode) || "off".equals(mode)) {
                    gs.put("mode", mode);
                }
                if (search.get("threshold") instanceof Number) {
                    gs.put("threshold", search.get("threshold"));
                }
                result.put("googleSearch", gs);
            }
        }

        boolean noBudget = !result.containsKey("thinkingBudget");
        boolean noLevel = !JsCoercion.isTruthy(result.get("thinkingLevel"));
        if (noBudget && noLevel && generationConfig != null) {
            if (JsCoercion.isTruthy(generationConfig.get("thinkingConfig")) && generationConfig.get("thinkingConfig") instanceof Map) {
                Map<String, Object> tc = JsCoercion.asMap(generationConfig.get("thinkingConfig"));
                if (tc.get("thinkingLevel") instanceof String) {
                    result.put("thinkingLevel", tc.get("thinkingLevel"));
                    if (tc.get("includeThoughts") instanceof Boolean) {
                        result.put("includeThoughts", tc.get("includeThoughts"));
                    }
                } else if (tc.get("thinkingBudget") instanceof Number) {
                    result.put("thinkingBudget", tc.get("thinkingBudget"));
                }
            }
        }

        return result.isEmpty() ? null : result;
    }

    // ---- resolveThinkingConfig -------------------------------------------------------------------

    /**
     * A thinking model with no user config defaults to
     * {@code {includeThoughts:true, thinkingBudget:DEFAULT}}; otherwise the user config passes through.
     * {@code isClaudeModel}/{@code hasAssistantHistory} are unused here (signature validation is
     * handled by the block filters).
     */
    public static Map<String, Object> resolveThinkingConfig(
            Map<String, Object> userConfig,
            boolean isThinkingModel,
            boolean isClaudeModel,
            boolean hasAssistantHistory) {

        if (isThinkingModel && !JsCoercion.isTruthy(userConfig)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("includeThoughts", true);
            result.put("thinkingBudget", DEFAULT_THINKING_BUDGET);
            return result;
        }
        return userConfig;
    }

    // ---- normalizeThinkingConfig -----------------------------------------------------------------

    /**
     * {@code includeThoughts} is only allowed when the budget is a finite positive number. Reads
     * camelCase OR snake_case keys; emits camelCase. Returns {@code null} when neither field carries
     * a usable value.
     */
    public static Map<String, Object> normalizeThinkingConfig(Object config) {
        if (!JsCoercion.isTruthy(config) || !(config instanceof Map)) {
            return null;
        }

        Map<String, Object> record = JsCoercion.asMap(config);
        Object budgetRaw = JsCoercion.nullish(record.get("thinkingBudget"), record.get("thinking_budget"));
        Object includeRaw = JsCoercion.nullish(record.get("includeThoughts"), record.get("include_thoughts"));

        boolean hasBudget = budgetRaw instanceof Number && isFinite((Number) budgetRaw);
        Number thinkingBudget = hasBudget ? (Number) budgetRaw : null;
        Boolean includeThoughts = includeRaw instanceof Boolean ? (Boolean) includeRaw : null;

        boolean enableThinking = thinkingBudget != null && thinkingBudget.doubleValue() > 0;
        boolean finalInclude = enableThinking && (includeThoughts != null ? includeThoughts : false);

        if (!enableThinking && !finalInclude && thinkingBudget == null && includeThoughts == null) {
            return null;
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        if (thinkingBudget != null) {
            normalized.put("thinkingBudget", thinkingBudget);
        }
        // finalInclude is a boolean, so this key is always set.
        normalized.put("includeThoughts", finalInclude);
        return normalized;
    }

    // JS Number.isFinite: false for NaN/Infinity. Integers/Longs are always finite.
    private static boolean isFinite(Number n) {
        double d = n.doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }
}
