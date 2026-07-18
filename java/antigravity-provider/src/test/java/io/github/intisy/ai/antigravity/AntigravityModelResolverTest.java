package io.github.intisy.ai.antigravity;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline parity tests for {@link AntigravityModelResolver}, checked against antigravity-auth's
 * actual {@code src/plugin/transform/model-resolver.ts}: every expected value below was snapshotted
 * by running the real TS functions in a Node harness ({@code .superpowers/sdd/t7b-harness/}, node
 * v26.3.1) -- see t7b-report.md. Not hand-derived.
 */
class AntigravityModelResolverTest {

    // ---- resolveModelWithTier --------------------------------------------------------------------

    @Test
    void tier_gemini3NoTier_defaultsLow() {
        assertEquals(
                map("actualModel", "gemini-3-flash", "thinkingLevel", "low", "isThinkingModel", true,
                        "quotaPreference", "antigravity", "explicitQuota", false),
                AntigravityModelResolver.resolveModelWithTier("gemini-3-flash"));
        assertEquals(
                map("actualModel", "gemini-3-flash", "thinkingLevel", "low", "isThinkingModel", true,
                        "quotaPreference", "antigravity", "explicitQuota", true),
                AntigravityModelResolver.resolveModelWithTier("antigravity-gemini-3-flash"));
        assertEquals(
                map("actualModel", "gemini-3-pro-preview", "thinkingLevel", "low", "isThinkingModel", true,
                        "quotaPreference", "antigravity", "explicitQuota", false),
                AntigravityModelResolver.resolveModelWithTier("gemini-3-pro-preview"));
    }

    @Test
    void tier_gemini25NoTier_isThinkingButNoLevel() {
        assertEquals(
                map("actualModel", "gemini-2.5-flash", "isThinkingModel", true,
                        "quotaPreference", "antigravity", "explicitQuota", false),
                AntigravityModelResolver.resolveModelWithTier("gemini-2.5-flash"));
        // gemini-2.0-flash is NOT thinking-capable
        assertEquals(
                map("actualModel", "gemini-2.0-flash", "isThinkingModel", false,
                        "quotaPreference", "antigravity", "explicitQuota", false),
                AntigravityModelResolver.resolveModelWithTier("gemini-2.0-flash"));
    }

    @Test
    void tier_antigravityGemini3Pro_bareGetsDefaultLowSuffix() {
        assertEquals(
                map("actualModel", "gemini-3.1-pro-low", "thinkingLevel", "low", "isThinkingModel", true,
                        "quotaPreference", "antigravity", "explicitQuota", true),
                AntigravityModelResolver.resolveModelWithTier("antigravity-gemini-3.1-pro"));
    }

    @Test
    void tier_antigravityGemini3WithTier_keepsFullIdAndCarriesTier() {
        assertEquals(
                map("actualModel", "gemini-3-pro-high", "thinkingLevel", "high", "tier", "high",
                        "isThinkingModel", true, "quotaPreference", "antigravity", "explicitQuota", true),
                AntigravityModelResolver.resolveModelWithTier("antigravity-gemini-3-pro-high"));
        assertEquals(
                map("actualModel", "gemini-3-flash-medium", "thinkingLevel", "medium", "tier", "medium",
                        "isThinkingModel", true, "quotaPreference", "antigravity", "explicitQuota", true),
                AntigravityModelResolver.resolveModelWithTier("antigravity-gemini-3-flash-medium"));
    }

    @Test
    void tier_gemini3ProNonAntigravity_aliasStripsTierSuffix() {
        // non-antigravity gemini-3-pro-{low,high} alias to bare gemini-3-pro; tier still carried.
        assertEquals(
                map("actualModel", "gemini-3-pro", "thinkingLevel", "low", "tier", "low",
                        "isThinkingModel", true, "quotaPreference", "antigravity", "explicitQuota", false),
                AntigravityModelResolver.resolveModelWithTier("gemini-3-pro-low"));
    }

    @Test
    void tier_claudeThinkingNoTier_defaultHighBudget() {
        assertEquals(
                map("actualModel", "claude-opus-4-6-thinking", "thinkingBudget", 32768, "isThinkingModel", true,
                        "quotaPreference", "antigravity", "explicitQuota", true),
                AntigravityModelResolver.resolveModelWithTier("antigravity-claude-opus-4-6-thinking"));
    }

    @Test
    void tier_claudeSonnetNonThinking() {
        assertEquals(
                map("actualModel", "claude-sonnet-4-6", "isThinkingModel", false,
                        "quotaPreference", "antigravity", "explicitQuota", false),
                AntigravityModelResolver.resolveModelWithTier("claude-sonnet-4-6"));
        // gemini-claude-sonnet-4-6 alias resolves to claude-sonnet-4-6
        assertEquals(
                map("actualModel", "claude-sonnet-4-6", "isThinkingModel", false,
                        "quotaPreference", "antigravity", "explicitQuota", false),
                AntigravityModelResolver.resolveModelWithTier("gemini-claude-sonnet-4-6"));
    }

    @Test
    void tier_gemini25NumericBudgetsPerFamily() {
        assertEquals(6144, AntigravityModelResolver.resolveModelWithTier("gemini-2.5-flash-low").get("thinkingBudget"));
        assertEquals(12288, AntigravityModelResolver.resolveModelWithTier("gemini-2.5-flash-medium").get("thinkingBudget"));
        assertEquals(24576, AntigravityModelResolver.resolveModelWithTier("gemini-2.5-flash-high").get("thinkingBudget"));
        assertEquals(8192, AntigravityModelResolver.resolveModelWithTier("gemini-2.5-pro-low").get("thinkingBudget"));
        assertEquals(32768, AntigravityModelResolver.resolveModelWithTier("gemini-2.5-pro-high").get("thinkingBudget"));
    }

    @Test
    void tier_imageModel() {
        assertEquals(
                map("actualModel", "gemini-3-pro-image", "isThinkingModel", false, "isImageModel", true,
                        "quotaPreference", "antigravity", "explicitQuota", true),
                AntigravityModelResolver.resolveModelWithTier("gemini-3-pro-image"));
    }

    @Test
    void tier_gptModelSuffixNotStrippedAsTier() {
        // GPT models must NOT have -medium stripped as a thinking tier.
        assertEquals(
                map("actualModel", "gpt-oss-120b-medium", "isThinkingModel", false,
                        "quotaPreference", "antigravity", "explicitQuota", false),
                AntigravityModelResolver.resolveModelWithTier("gpt-oss-120b-medium"));
    }

    @Test
    void tier_cliFirst() {
        assertEquals("gemini-cli", AntigravityModelResolver.resolveModelWithTier("gemini-3-flash", true).get("quotaPreference"));
        assertEquals(false, AntigravityModelResolver.resolveModelWithTier("gemini-3-flash", true).get("explicitQuota"));
        // explicit antigravity prefix keeps antigravity
        assertEquals("antigravity", AntigravityModelResolver.resolveModelWithTier("antigravity-gemini-3-flash", true).get("quotaPreference"));
        // claude + image never go to gemini-cli
        assertEquals("antigravity", AntigravityModelResolver.resolveModelWithTier("claude-opus-4-6-thinking", true).get("quotaPreference"));
        assertEquals("antigravity", AntigravityModelResolver.resolveModelWithTier("gemini-3-pro-image", true).get("quotaPreference"));
    }

    // ---- resolveModelWithVariant -----------------------------------------------------------------

    @Test
    void variant_noConfig_fallsBackToTier() {
        assertEquals(
                map("actualModel", "claude-opus-4-6-thinking", "thinkingBudget", 8192, "tier", "low",
                        "isThinkingModel", true, "quotaPreference", "antigravity", "explicitQuota", false),
                AntigravityModelResolver.resolveModelWithVariant("claude-opus-4-6-thinking-low"));
        assertNull(AntigravityModelResolver.resolveModelWithVariant("gemini-3-pro-high").get("configSource"));
    }

    @Test
    void variant_claudeOverrideBudget() {
        assertEquals(
                map("actualModel", "claude-opus-4-6-thinking", "thinkingBudget", 24000, "isThinkingModel", true,
                        "quotaPreference", "antigravity", "explicitQuota", true, "configSource", "variant"),
                AntigravityModelResolver.resolveModelWithVariant("antigravity-claude-opus-4-6-thinking", map("thinkingBudget", 24000)));
    }

    @Test
    void variant_gemini3BudgetToLevel_antigravityProRewritesSuffix() {
        assertEquals(
                map("actualModel", "gemini-3-pro-low", "thinkingLevel", "low", "isThinkingModel", true,
                        "quotaPreference", "antigravity", "explicitQuota", true, "configSource", "variant"),
                AntigravityModelResolver.resolveModelWithVariant("antigravity-gemini-3-pro", map("thinkingBudget", 8000)));
        assertEquals("gemini-3-pro-medium",
                AntigravityModelResolver.resolveModelWithVariant("antigravity-gemini-3-pro", map("thinkingBudget", 8193)).get("actualModel"));
        assertEquals("gemini-3-pro-medium",
                AntigravityModelResolver.resolveModelWithVariant("antigravity-gemini-3-pro", map("thinkingBudget", 16384)).get("actualModel"));
        assertEquals("gemini-3-pro-high",
                AntigravityModelResolver.resolveModelWithVariant("antigravity-gemini-3-pro", map("thinkingBudget", 16385)).get("actualModel"));
    }

    @Test
    void variant_gemini3Flash_levelButNoSuffixRewrite() {
        // flash is NOT gemini-3-pro, so actualModel keeps its base (no -level suffix rewrite).
        assertEquals(
                map("actualModel", "gemini-3-flash", "thinkingLevel", "medium", "isThinkingModel", true,
                        "quotaPreference", "antigravity", "explicitQuota", true, "configSource", "variant"),
                AntigravityModelResolver.resolveModelWithVariant("antigravity-gemini-3-flash", map("thinkingBudget", 12000)));
    }

    @Test
    void variant_nonGemini3_budgetDirect() {
        assertEquals(
                map("actualModel", "gemini-2.5-pro", "isThinkingModel", true, "quotaPreference", "antigravity",
                        "explicitQuota", false, "thinkingBudget", 20000, "configSource", "variant"),
                AntigravityModelResolver.resolveModelWithVariant("gemini-2.5-pro", map("thinkingBudget", 20000)));
    }

    @Test
    void variant_overridesTierSuffix() {
        assertEquals(50000,
                AntigravityModelResolver.resolveModelWithVariant("claude-opus-4-6-thinking-low", map("thinkingBudget", 50000)).get("thinkingBudget"));
    }

    @Test
    void variant_googleSearchSetsConfigSource_emptyConfigNoChange() {
        Map<String, Object> gs = AntigravityModelResolver.resolveModelWithVariant("antigravity-gemini-3-pro", map("googleSearch", map("mode", "auto")));
        assertEquals(map("mode", "auto"), gs.get("googleSearch"));
        assertEquals("variant", gs.get("configSource"));
        // actualModel not rewritten to a level (early return before the gemini-3 budget path)
        assertEquals("gemini-3-pro-low", gs.get("actualModel"));
        // empty variant config -> no configSource
        assertNull(AntigravityModelResolver.resolveModelWithVariant("gemini-2.5-pro", map()).get("configSource"));
    }

    // ---- resolveModelForHeaderStyle --------------------------------------------------------------

    @Test
    void headerStyle_geminiCliToAntigravity() {
        assertEquals("gemini-3-flash", AntigravityModelResolver.resolveModelForHeaderStyle("gemini-3-flash-preview", "antigravity").get("actualModel"));
        assertEquals("gemini-3-pro-low", AntigravityModelResolver.resolveModelForHeaderStyle("gemini-3-pro-preview", "antigravity").get("actualModel"));
        assertEquals("gemini-3.1-pro-low", AntigravityModelResolver.resolveModelForHeaderStyle("gemini-3.1-pro-preview", "antigravity").get("actualModel"));
        assertEquals("gemini-3.1-pro-low", AntigravityModelResolver.resolveModelForHeaderStyle("gemini-3.1-pro-preview-customtools", "antigravity").get("actualModel"));
    }

    @Test
    void headerStyle_antigravityToGeminiCli() {
        Map<String, Object> r = AntigravityModelResolver.resolveModelForHeaderStyle("gemini-3-flash", "gemini-cli");
        assertEquals("gemini-3-flash-preview", r.get("actualModel"));
        assertEquals("gemini-cli", r.get("quotaPreference"));
        assertEquals("gemini-3-pro-preview", AntigravityModelResolver.resolveModelForHeaderStyle("gemini-3-pro-low", "gemini-cli").get("actualModel"));
        assertEquals("gemini-3.1-pro-preview", AntigravityModelResolver.resolveModelForHeaderStyle("gemini-3.1-pro-low", "gemini-cli").get("actualModel"));
        // already has -preview -> unchanged
        assertEquals("gemini-3.1-pro-preview-customtools", AntigravityModelResolver.resolveModelForHeaderStyle("gemini-3.1-pro-preview-customtools", "gemini-cli").get("actualModel"));
    }

    @Test
    void headerStyle_noTransformForNonGemini3() {
        assertEquals("gemini-2.5-flash", AntigravityModelResolver.resolveModelForHeaderStyle("gemini-2.5-flash", "antigravity").get("actualModel"));
        assertEquals("gemini-2.5-flash", AntigravityModelResolver.resolveModelForHeaderStyle("gemini-2.5-flash", "gemini-cli").get("actualModel"));
        assertEquals("claude-opus-4-6-thinking", AntigravityModelResolver.resolveModelForHeaderStyle("claude-opus-4-6-thinking", "antigravity").get("actualModel"));
    }

    // E-wiring: config.cli_first now reaches this live per-request resolve path (AntigravityRequestPrep
    // .Input.cliFirst -> resolveModelForHeaderStyle(model, headerStyle, cliFirst)), instead of every
    // call hardcoding false. Non-gemini-3 models bypass the header-style rewrite entirely and call
    // resolveModelWithTier(requestedModel, cliFirst) directly (the early return in
    // resolveModelForHeaderStyle) -- the cleanest place to observe cliFirst's effect on quotaPreference.
    @Test
    void headerStyle_threadsCliFirst_nonGemini3Model() {
        assertEquals("antigravity",
                AntigravityModelResolver.resolveModelForHeaderStyle("gemini-2.5-flash", "antigravity", false).get("quotaPreference"));
        assertEquals("gemini-cli",
                AntigravityModelResolver.resolveModelForHeaderStyle("gemini-2.5-flash", "antigravity", true).get("quotaPreference"));

        // A Claude model is never eligible for gemini-cli routing, regardless of cliFirst.
        assertEquals("antigravity",
                AntigravityModelResolver.resolveModelForHeaderStyle("claude-opus-4-6-thinking", "antigravity", true).get("quotaPreference"));

        // The 2-arg convenience overload (used by callers that don't route the config flag) still
        // defaults cliFirst to false -- the pre-wiring behavior, preserved.
        assertEquals("antigravity",
                AntigravityModelResolver.resolveModelForHeaderStyle("gemini-2.5-flash", "antigravity").get("quotaPreference"));
    }

    // ---- getModelFamily / budgetToGemini3Level ---------------------------------------------------

    @Test
    void getModelFamily_routingFamilies() {
        assertEquals("claude", AntigravityModelResolver.getModelFamily("claude-opus-4-6-thinking"));
        assertEquals("gemini-flash", AntigravityModelResolver.getModelFamily("gemini-3-flash"));
        assertEquals("gemini-flash", AntigravityModelResolver.getModelFamily("gemini-2.5-flash"));
        assertEquals("gemini-pro", AntigravityModelResolver.getModelFamily("gemini-3-pro"));
        assertEquals("gemini-pro", AntigravityModelResolver.getModelFamily("something"));
    }

    @Test
    void budgetToGemini3Level_boundaries() {
        assertEquals("low", AntigravityModelResolver.budgetToGemini3Level(8192));
        assertEquals("medium", AntigravityModelResolver.budgetToGemini3Level(8193));
        assertEquals("medium", AntigravityModelResolver.budgetToGemini3Level(16384));
        assertEquals("high", AntigravityModelResolver.budgetToGemini3Level(16385));
    }

    @Test
    void tables_portedExactly() {
        assertEquals(8192, AntigravityModelResolver.THINKING_TIER_BUDGETS.get("claude").get("low"));
        assertEquals(24576, AntigravityModelResolver.THINKING_TIER_BUDGETS.get("gemini-2.5-flash").get("high"));
        assertEquals(4096, AntigravityModelResolver.THINKING_TIER_BUDGETS.get("default").get("low"));
        assertEquals(java.util.Arrays.asList("minimal", "low", "medium", "high"), AntigravityModelResolver.GEMINI_3_THINKING_LEVELS);
        assertEquals("gemini-3.1-pro-low", AntigravityModelResolver.MODEL_ALIASES.get("auto"));
        assertEquals("claude-sonnet-4-6", AntigravityModelResolver.MODEL_ALIASES.get("gemini-claude-sonnet-4-6"));
    }
}
