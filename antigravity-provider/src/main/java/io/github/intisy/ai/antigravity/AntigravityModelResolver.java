package io.github.intisy.ai.antigravity;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Model resolution:
 * {@code resolveModelWithTier} / {@code resolveModelWithVariant} / {@code resolveModelForHeaderStyle},
 * {@code getModelFamily}, {@code budgetToGemini3Level}, and the {@code MODEL_ALIASES} /
 * {@code THINKING_TIER_BUDGETS} / {@code GEMINI_3_THINKING_LEVELS} tables.
 *
 * <p>A {@code ResolvedModel} is represented as the JSON-tree {@code Map<String,Object>} shape the
 * TS returns (keys present only when the TS object carries them -- an absent {@code thinkingBudget}
 * is an omitted key, never a {@code null} entry, matching {@code JSON.stringify} dropping
 * {@code undefined}). Numeric budgets are {@link Integer}. No java.net/nio/reflection/threads --
 * TeaVM-transpilable, see {@code :antigravity-teavm}.
 */
public final class AntigravityModelResolver {

    /** How many thinking tokens each tier gets, for the models that take a numeric budget. */
    public static final Map<String, Map<String, Integer>> THINKING_TIER_BUDGETS;

    /** The levels the newer models take in place of a numeric budget. */
    public static final List<String> GEMINI_3_THINKING_LEVELS =
            Collections.unmodifiableList(Arrays.asList("minimal", "low", "medium", "high"));

    /** What a caller may name a model, and the id the upstream knows it by. */
    public static final Map<String, String> MODEL_ALIASES;

    static {
        Map<String, Map<String, Integer>> budgets = new LinkedHashMap<>();
        budgets.put("claude", tier(8192, 16384, 32768));
        budgets.put("gemini-2.5-pro", tier(8192, 16384, 32768));
        budgets.put("gemini-2.5-flash", tier(6144, 12288, 24576));
        budgets.put("default", tier(4096, 8192, 16384));
        THINKING_TIER_BUDGETS = Collections.unmodifiableMap(budgets);

        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("auto", "gemini-3.1-pro-low");
        aliases.put("gemini-3-pro-low", "gemini-3-pro");
        aliases.put("gemini-3-pro-high", "gemini-3-pro");
        aliases.put("gemini-3.1-pro-low", "gemini-3.1-pro");
        aliases.put("gemini-3.1-pro-high", "gemini-3.1-pro");
        aliases.put("gemini-3-flash-low", "gemini-3-flash");
        aliases.put("gemini-3-flash-medium", "gemini-3-flash");
        aliases.put("gemini-3-flash-high", "gemini-3-flash");
        aliases.put("gemini-claude-opus-4-6-thinking-low", "claude-opus-4-6-thinking");
        aliases.put("gemini-claude-opus-4-6-thinking-medium", "claude-opus-4-6-thinking");
        aliases.put("gemini-claude-opus-4-6-thinking-high", "claude-opus-4-6-thinking");
        aliases.put("gemini-claude-sonnet-4-6", "claude-sonnet-4-6");
        MODEL_ALIASES = Collections.unmodifiableMap(aliases);
    }

    private static Map<String, Integer> tier(int low, int medium, int high) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("low", low);
        m.put("medium", medium);
        m.put("high", high);
        return Collections.unmodifiableMap(m);
    }

    private static final Pattern TIER_REGEX = Pattern.compile("-(minimal|low|medium|high)$");
    private static final Pattern QUOTA_PREFIX_REGEX = Pattern.compile("^antigravity-", Pattern.CASE_INSENSITIVE);
    private static final Pattern GEMINI_3_PRO_REGEX = Pattern.compile("^gemini-3(?:\\.\\d+)?-pro", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_GENERATION_MODELS = Pattern.compile("image|imagen", Pattern.CASE_INSENSITIVE);

    // resolveModelForHeaderStyle regexes
    private static final Pattern PREVIEW_CUSTOMTOOLS_SUFFIX = Pattern.compile("-preview-customtools$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREVIEW_SUFFIX = Pattern.compile("-preview$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIER_SUFFIX_CI = Pattern.compile("-(low|medium|high)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_PREVIEW = Pattern.compile("-preview($|-)", Pattern.CASE_INSENSITIVE);

    private AntigravityModelResolver() {
    }

    private static boolean supportsThinkingTiers(String model) {
        String lower = model.toLowerCase();
        return lower.contains("gemini-3")
                || lower.contains("gemini-2.5")
                || (lower.contains("claude") && lower.contains("thinking"));
    }

    // Returns null when no tier suffix (or model doesn't support tiers).
    private static String extractThinkingTierFromModel(String model) {
        if (!supportsThinkingTiers(model)) return null;
        java.util.regex.Matcher m = TIER_REGEX.matcher(model);
        return m.find() ? m.group(1) : null;
    }

    private static String getBudgetFamily(String model) {
        if (model.contains("claude")) return "claude";
        if (model.contains("gemini-2.5-pro")) return "gemini-2.5-pro";
        if (model.contains("gemini-2.5-flash")) return "gemini-2.5-flash";
        return "default";
    }

    private static boolean isThinkingCapableModel(String model) {
        String lower = model.toLowerCase();
        return lower.contains("thinking") || lower.contains("gemini-3") || lower.contains("gemini-2.5");
    }

    private static boolean isGemini3ProModel(String model) {
        return GEMINI_3_PRO_REGEX.matcher(model).find();
    }

    /**
     * The upstream model and thinking config for a requested model, with the metered lane preferred.
     *
     * @param requestedModel the model the caller asked for
     * @return the resolved model and its thinking config
     */
    public static Map<String, Object> resolveModelWithTier(String requestedModel) {
        return resolveModelWithTier(requestedModel, false);
    }

    /**
     * Resolves the actual upstream model + thinking config for a requested model name.
     * {@code cliFirst} controls whether gemini-cli quota is preferred over antigravity quota.
     *
     * @param requestedModel the model the caller asked for
     * @param cliFirst whether the free lane is preferred
     * @return the resolved model and its thinking config
     */
    public static Map<String, Object> resolveModelWithTier(String requestedModel, boolean cliFirst) {
        boolean isAntigravity = QUOTA_PREFIX_REGEX.matcher(requestedModel).find();
        String modelWithoutQuota = QUOTA_PREFIX_REGEX.matcher(requestedModel).replaceFirst("");

        String tier = extractThinkingTierFromModel(modelWithoutQuota);
        String baseName = tier != null ? TIER_REGEX.matcher(modelWithoutQuota).replaceFirst("") : modelWithoutQuota;

        boolean isImageModel = IMAGE_GENERATION_MODELS.matcher(modelWithoutQuota).find();
        boolean isClaudeModel = modelWithoutQuota.toLowerCase().contains("claude");

        boolean preferGeminiCli = cliFirst && !isAntigravity && !isImageModel && !isClaudeModel;
        String quotaPreference = preferGeminiCli ? "gemini-cli" : "antigravity";
        boolean explicitQuota = isAntigravity || isImageModel;

        boolean isGemini3 = modelWithoutQuota.toLowerCase().startsWith("gemini-3");
        boolean skipAlias = isAntigravity && isGemini3;

        boolean isGemini3Pro = isGemini3ProModel(modelWithoutQuota);

        String antigravityModel = modelWithoutQuota;
        if (skipAlias) {
            if (isGemini3Pro && tier == null && !isImageModel) {
                antigravityModel = modelWithoutQuota + "-low";
            }
        }

        String actualModel;
        if (skipAlias) {
            actualModel = antigravityModel;
        } else {
            String aliasFull = MODEL_ALIASES.get(modelWithoutQuota);
            String aliasBase = MODEL_ALIASES.get(baseName);
            actualModel = aliasFull != null ? aliasFull : (aliasBase != null ? aliasBase : baseName);
        }

        String resolvedModel = actualModel;
        boolean isThinking = isThinkingCapableModel(resolvedModel);

        if (isImageModel) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("actualModel", resolvedModel);
            r.put("isThinkingModel", false);
            r.put("isImageModel", true);
            r.put("quotaPreference", quotaPreference);
            r.put("explicitQuota", explicitQuota);
            return r;
        }

        boolean isEffectiveGemini3 = resolvedModel.toLowerCase().contains("gemini-3");
        boolean isClaudeThinking = resolvedModel.toLowerCase().contains("claude") && resolvedModel.toLowerCase().contains("thinking");

        if (tier == null) {
            if (isEffectiveGemini3) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("actualModel", resolvedModel);
                r.put("thinkingLevel", "low");
                r.put("isThinkingModel", true);
                r.put("quotaPreference", quotaPreference);
                r.put("explicitQuota", explicitQuota);
                return r;
            }
            if (isClaudeThinking) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("actualModel", resolvedModel);
                r.put("thinkingBudget", THINKING_TIER_BUDGETS.get("claude").get("high"));
                r.put("isThinkingModel", true);
                r.put("quotaPreference", quotaPreference);
                r.put("explicitQuota", explicitQuota);
                return r;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("actualModel", resolvedModel);
            r.put("isThinkingModel", isThinking);
            r.put("quotaPreference", quotaPreference);
            r.put("explicitQuota", explicitQuota);
            return r;
        }

        if (isEffectiveGemini3) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("actualModel", resolvedModel);
            r.put("thinkingLevel", tier);
            r.put("tier", tier);
            r.put("isThinkingModel", true);
            r.put("quotaPreference", quotaPreference);
            r.put("explicitQuota", explicitQuota);
            return r;
        }

        String budgetFamily = getBudgetFamily(resolvedModel);
        Integer thinkingBudget = THINKING_TIER_BUDGETS.get(budgetFamily).get(tier);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("actualModel", resolvedModel);
        r.put("thinkingBudget", thinkingBudget);
        r.put("tier", tier);
        r.put("isThinkingModel", isThinking);
        r.put("quotaPreference", quotaPreference);
        r.put("explicitQuota", explicitQuota);
        return r;
    }

    /**
     * Returns the routing family {@code "claude"|"gemini-flash"|"gemini-pro"}, distinct from
     * {@link CrossModelSanitizer#getModelFamily} which returns {@code "claude"|"gemini"|"unknown"}.
     *
     * @param model the model id
     * @return the family its routing is decided by
     */
    public static String getModelFamily(String model) {
        String lower = model.toLowerCase();
        if (lower.contains("claude")) return "claude";
        if (lower.contains("flash")) return "gemini-flash";
        return "gemini-pro";
    }

    /**
     * The thinking level closest to a numeric budget, for a model that takes a level.
     *
     * @param budget the budget in tokens
     * @return the level name
     */
    public static String budgetToGemini3Level(int budget) {
        if (budget <= 8192) return "low";
        if (budget <= 16384) return "medium";
        return "high";
    }

    /**
     * {@code resolveModelForHeaderStyle(requestedModel, headerStyle)} with {@code cliFirst} defaulted
     * to {@code false} (pre-wiring behavior, preserved for callers that don't route the config flag).
     *
     * @param requestedModel the model the caller asked for
     * @param headerStyle which endpoint's header set the request will carry
     * @return the resolved model and its thinking config
     */
    public static Map<String, Object> resolveModelForHeaderStyle(String requestedModel, String headerStyle) {
        return resolveModelForHeaderStyle(requestedModel, headerStyle, false);
    }

    /**
     * Resolves a requested model for a specific upstream header style. {@code headerStyle} is
     * {@code "antigravity"} or {@code "gemini-cli"}. {@code cliFirst} is threaded into every inner
     * {@link #resolveModelWithTier} call so gemini-cli routing preference reaches the live
     * per-request resolve path.
     *
     * @param requestedModel the model the caller asked for
     * @param headerStyle which endpoint's header set the request will carry
     * @param cliFirst whether the free lane is preferred
     * @return the resolved model and its thinking config
     */
    public static Map<String, Object> resolveModelForHeaderStyle(String requestedModel, String headerStyle, boolean cliFirst) {
        String lower = requestedModel.toLowerCase();
        boolean isGemini3 = lower.contains("gemini-3");

        if (!isGemini3) {
            return resolveModelWithTier(requestedModel, cliFirst);
        }

        if ("antigravity".equals(headerStyle)) {
            String transformedModel = requestedModel;
            transformedModel = PREVIEW_CUSTOMTOOLS_SUFFIX.matcher(transformedModel).replaceFirst("");
            transformedModel = PREVIEW_SUFFIX.matcher(transformedModel).replaceFirst("");
            transformedModel = QUOTA_PREFIX_REGEX.matcher(transformedModel).replaceFirst("");

            boolean isGemini3Pro = isGemini3ProModel(transformedModel);
            boolean hasTierSuffix = TIER_SUFFIX_CI.matcher(transformedModel).find();
            boolean isImageModel = IMAGE_GENERATION_MODELS.matcher(transformedModel).find();

            if (isGemini3Pro && !hasTierSuffix && !isImageModel) {
                transformedModel = transformedModel + "-low";
            }

            return resolveModelWithTier("antigravity-" + transformedModel, cliFirst);
        }

        if ("gemini-cli".equals(headerStyle)) {
            String transformedModel = requestedModel;
            transformedModel = QUOTA_PREFIX_REGEX.matcher(transformedModel).replaceFirst("");
            transformedModel = TIER_SUFFIX_CI.matcher(transformedModel).replaceFirst("");

            boolean hasPreviewSuffix = HAS_PREVIEW.matcher(transformedModel).find();
            if (!hasPreviewSuffix) {
                transformedModel = transformedModel + "-preview";
            }

            Map<String, Object> r = resolveModelWithTier(transformedModel, cliFirst);
            r.put("quotaPreference", "gemini-cli");
            return r;
        }

        return resolveModelWithTier(requestedModel, cliFirst);
    }

    /**
     * The resolved model with no per-variant override applied.
     *
     * @param requestedModel the model the caller asked for
     * @return the resolved model and its thinking config
     */
    public static Map<String, Object> resolveModelWithVariant(String requestedModel) {
        return resolveModelWithVariant(requestedModel, null);
    }

    /**
     * Resolves a model, applying an optional per-variant override. {@code variantConfig} is
     * a JSON-tree map (keys {@code thinkingBudget}, {@code googleSearch}) or {@code null}.
     *
     * @param requestedModel the model the caller asked for
     * @param variantConfig the override to apply, or {@code null} for none
     * @return the resolved model and its thinking config
     */
    public static Map<String, Object> resolveModelWithVariant(String requestedModel, Map<String, Object> variantConfig) {
        Map<String, Object> base = resolveModelWithTier(requestedModel);

        if (variantConfig == null) {
            return base;
        }

        Object googleSearch = variantConfig.get("googleSearch");
        if (JsCoercion.isTruthy(googleSearch)) {
            base.put("googleSearch", googleSearch);
            base.put("configSource", "variant");
        }

        Object budgetObj = variantConfig.get("thinkingBudget");
        if (!JsCoercion.isTruthy(budgetObj)) {
            return base;
        }

        int budget = ((Number) budgetObj).intValue();
        boolean isGemini3 = String.valueOf(base.get("actualModel")).toLowerCase().contains("gemini-3");

        if (isGemini3) {
            String level = budgetToGemini3Level(budget);
            boolean isAntigravityGemini3Pro = "antigravity".equals(base.get("quotaPreference"))
                    && isGemini3ProModel(String.valueOf(base.get("actualModel")));

            String actualModel = String.valueOf(base.get("actualModel"));
            if (isAntigravityGemini3Pro) {
                String baseModel = TIER_SUFFIX.matcher(actualModel).replaceFirst("");
                actualModel = baseModel + "-" + level;
            }

            Map<String, Object> r = new LinkedHashMap<>(base);
            r.put("actualModel", actualModel);
            r.put("thinkingLevel", level);
            r.remove("thinkingBudget");
            r.put("configSource", "variant");
            return r;
        }

        Map<String, Object> r = new LinkedHashMap<>(base);
        r.put("thinkingBudget", budgetObj);
        r.put("configSource", "variant");
        return r;
    }

    // Case-sensitive (no CASE_INSENSITIVE flag): the antigravity Gemini-3-Pro base-model strip in
    // resolveModelWithVariant intentionally only matches lowercase tier suffixes.
    private static final Pattern TIER_SUFFIX = Pattern.compile("-(low|medium|high)$");
}
