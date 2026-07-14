package io.github.intisy.ai.antigravity;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Java port of antigravity-auth's {@code src/plugin/transform/model-resolver.ts} (T7b, Bucket A):
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

    // model-resolver.ts:18-23 -- Claude & Gemini 2.5 Pro use numeric budgets; ported EXACTLY.
    public static final Map<String, Map<String, Integer>> THINKING_TIER_BUDGETS;

    // model-resolver.ts:30 -- Gemini 3 uses thinkingLevel STRINGS instead of numeric budgets.
    public static final List<String> GEMINI_3_THINKING_LEVELS =
            Collections.unmodifiableList(Arrays.asList("minimal", "low", "medium", "high"));

    // model-resolver.ts:40-64 -- user-friendly name -> API model name.
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

    // model-resolver.ts:66-77
    private static final Pattern TIER_REGEX = Pattern.compile("-(minimal|low|medium|high)$");
    private static final Pattern QUOTA_PREFIX_REGEX = Pattern.compile("^antigravity-", Pattern.CASE_INSENSITIVE);
    private static final Pattern GEMINI_3_PRO_REGEX = Pattern.compile("^gemini-3(?:\\.\\d+)?-pro", Pattern.CASE_INSENSITIVE);
    private static final Pattern GEMINI_3_FLASH_REGEX = Pattern.compile("^gemini-3(?:\\.\\d+)?-flash", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_GENERATION_MODELS = Pattern.compile("image|imagen", Pattern.CASE_INSENSITIVE);

    // resolveModelForHeaderStyle regexes (model-resolver.ts:324-347)
    private static final Pattern PREVIEW_CUSTOMTOOLS_SUFFIX = Pattern.compile("-preview-customtools$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PREVIEW_SUFFIX = Pattern.compile("-preview$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIER_SUFFIX_CI = Pattern.compile("-(low|medium|high)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HAS_PREVIEW = Pattern.compile("-preview($|-)", Pattern.CASE_INSENSITIVE);

    private AntigravityModelResolver() {
    }

    // model-resolver.ts:86-93
    private static boolean supportsThinkingTiers(String model) {
        String lower = model.toLowerCase();
        return lower.contains("gemini-3")
                || lower.contains("gemini-2.5")
                || (lower.contains("claude") && lower.contains("thinking"));
    }

    // model-resolver.ts:99-106 -- returns null when no tier suffix (or model doesn't support tiers).
    private static String extractThinkingTierFromModel(String model) {
        if (!supportsThinkingTiers(model)) return null;
        java.util.regex.Matcher m = TIER_REGEX.matcher(model);
        return m.find() ? m.group(1) : null;
    }

    // model-resolver.ts:111-122
    private static String getBudgetFamily(String model) {
        if (model.contains("claude")) return "claude";
        if (model.contains("gemini-2.5-pro")) return "gemini-2.5-pro";
        if (model.contains("gemini-2.5-flash")) return "gemini-2.5-flash";
        return "default";
    }

    // model-resolver.ts:127-134
    private static boolean isThinkingCapableModel(String model) {
        String lower = model.toLowerCase();
        return lower.contains("thinking") || lower.contains("gemini-3") || lower.contains("gemini-2.5");
    }

    private static boolean isGemini3ProModel(String model) {
        return GEMINI_3_PRO_REGEX.matcher(model).find();
    }

    private static boolean isGemini3FlashModel(String model) {
        return GEMINI_3_FLASH_REGEX.matcher(model).find();
    }

    /** {@code resolveModelWithTier(requestedModel)} with default (empty) options. */
    public static Map<String, Object> resolveModelWithTier(String requestedModel) {
        return resolveModelWithTier(requestedModel, false);
    }

    /**
     * Port of {@code resolveModelWithTier} (model-resolver.ts:164-269). {@code cliFirst} mirrors the
     * TS {@code options.cli_first} flag.
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
     * Port of {@code getModelFamily} (model-resolver.ts:274-283) -- NOTE this returns the routing
     * family {@code "claude"|"gemini-flash"|"gemini-pro"}, distinct from
     * {@link CrossModelSanitizer#getModelFamily} which returns {@code "claude"|"gemini"|"unknown"}.
     */
    public static String getModelFamily(String model) {
        String lower = model.toLowerCase();
        if (lower.contains("claude")) return "claude";
        if (lower.contains("flash")) return "gemini-flash";
        return "gemini-pro";
    }

    /** Port of {@code budgetToGemini3Level} (model-resolver.ts:297-301). */
    public static String budgetToGemini3Level(int budget) {
        if (budget <= 8192) return "low";
        if (budget <= 16384) return "medium";
        return "high";
    }

    /**
     * Port of {@code resolveModelForHeaderStyle} (model-resolver.ts:312-359). {@code headerStyle} is
     * {@code "antigravity"} or {@code "gemini-cli"}.
     */
    public static Map<String, Object> resolveModelForHeaderStyle(String requestedModel, String headerStyle) {
        String lower = requestedModel.toLowerCase();
        boolean isGemini3 = lower.contains("gemini-3");

        if (!isGemini3) {
            return resolveModelWithTier(requestedModel);
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

            return resolveModelWithTier("antigravity-" + transformedModel);
        }

        if ("gemini-cli".equals(headerStyle)) {
            String transformedModel = requestedModel;
            transformedModel = QUOTA_PREFIX_REGEX.matcher(transformedModel).replaceFirst("");
            transformedModel = TIER_SUFFIX_CI.matcher(transformedModel).replaceFirst("");

            boolean hasPreviewSuffix = HAS_PREVIEW.matcher(transformedModel).find();
            if (!hasPreviewSuffix) {
                transformedModel = transformedModel + "-preview";
            }

            Map<String, Object> r = resolveModelWithTier(transformedModel);
            r.put("quotaPreference", "gemini-cli");
            return r;
        }

        return resolveModelWithTier(requestedModel);
    }

    /** {@code resolveModelWithVariant(requestedModel)} with no variant config. */
    public static Map<String, Object> resolveModelWithVariant(String requestedModel) {
        return resolveModelWithVariant(requestedModel, null);
    }

    /**
     * Port of {@code resolveModelWithVariant} (model-resolver.ts:365-413). {@code variantConfig} is
     * a JSON-tree map (keys {@code thinkingBudget}, {@code googleSearch}) or {@code null}.
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

    // model-resolver.ts:395 uses /-(low|medium|high)$/ (case-sensitive, no i flag) for the
    // antigravity Gemini-3-Pro base-model strip in resolveModelWithVariant.
    private static final Pattern TIER_SUFFIX = Pattern.compile("-(low|medium|high)$");
}
