package io.github.intisy.ai.antigravity;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline parity tests for {@link AntigravityThinkingConfig}, expected values snapshotted from the
 * REAL thinking-config helpers (src/plugin/request-helpers.ts) via the Node harness
 * ({@code .superpowers/sdd/t7c2-harness/}, fixtures.json {@code keepFalse.*}) -- never hand-derived.
 */
class AntigravityThinkingConfigTest {

    private static final int DEF = 16000;

    // ---- isThinkingCapableModel ------------------------------------------------------------------

    @Test
    void isThinkingCapable() {
        assertFalse(AntigravityThinkingConfig.isThinkingCapableModel("gemini-2.5-pro"));
        assertTrue(AntigravityThinkingConfig.isThinkingCapableModel("claude-opus-4"));
        assertTrue(AntigravityThinkingConfig.isThinkingCapableModel("claude-sonnet-4-thinking"));
        assertTrue(AntigravityThinkingConfig.isThinkingCapableModel("gemini-3-pro"));
        assertFalse(AntigravityThinkingConfig.isThinkingCapableModel("gpt-4o"));
        assertTrue(AntigravityThinkingConfig.isThinkingCapableModel("OPUS-Thinking"));
        assertFalse(AntigravityThinkingConfig.isThinkingCapableModel("gemini-2.0-flash"));
    }

    // ---- extractThinkingConfig -------------------------------------------------------------------

    private static Map<String, Object> extract(Map<String, Object> rp, Map<String, Object> gc, Map<String, Object> eb) {
        return AntigravityThinkingConfig.extractThinkingConfig(rp, gc, eb);
    }

    @Test
    void extractThinkingConfig_gemini() {
        assertEquals(map("includeThoughts", true, "thinkingBudget", 8000),
                extract(map(), map("thinkingConfig", map("includeThoughts", true, "thinkingBudget", 8000)), null));
        assertEquals(map("includeThoughts", false, "thinkingBudget", 4000),
                extract(map(), null, map("thinkingConfig", map("includeThoughts", false, "thinkingBudget", 4000))));
        assertEquals(map("includeThoughts", true, "thinkingBudget", 3000),
                extract(map("thinkingConfig", map("includeThoughts", true, "thinkingBudget", 3000)), null, null));
        // non-number budget -> DEFAULT; includeThoughts absent -> Boolean(undefined)=false
        assertEquals(map("includeThoughts", false, "thinkingBudget", DEF),
                extract(map(), map("thinkingConfig", map("thinkingBudget", "notnum")), null));
        // includeThoughts:0 -> Boolean(0)=false; budget absent -> DEFAULT
        assertEquals(map("includeThoughts", false, "thinkingBudget", DEF),
                extract(map(), map("thinkingConfig", map("includeThoughts", 0)), null));
        assertEquals(map("includeThoughts", false, "thinkingBudget", DEF),
                extract(map(), map("thinkingConfig", map()), null));
        // precedence: generationConfig wins over extraBody wins over requestPayload
        assertEquals(map("includeThoughts", false, "thinkingBudget", 2),
                extract(map("thinkingConfig", map("thinkingBudget", 1)),
                        map("thinkingConfig", map("thinkingBudget", 2)),
                        map("thinkingConfig", map("thinkingBudget", 3))));
    }

    @Test
    void extractThinkingConfig_anthropic() {
        assertEquals(map("includeThoughts", true, "thinkingBudget", DEF),
                extract(map(), null, map("thinking", map("type", "enabled"))));
        assertEquals(map("includeThoughts", true, "thinkingBudget", 12000),
                extract(map(), null, map("thinking", map("budgetTokens", 12000))));
        assertEquals(map("includeThoughts", true, "thinkingBudget", 5000),
                extract(map("thinking", map("type", "enabled", "budgetTokens", 5000)), null, null));
        // type not enabled + no truthy budgetTokens -> undefined
        assertNull(extract(map(), null, map("thinking", map("type", "disabled"))));
        assertNull(extract(map(), null, map("thinking", map("type", "disabled", "budgetTokens", 0))));
        assertNull(extract(map(), null, null));
    }

    // ---- extractVariantThinkingConfig ------------------------------------------------------------

    private static Map<String, Object> variant(Map<String, Object> po, Map<String, Object> gc) {
        return AntigravityThinkingConfig.extractVariantThinkingConfig(po, gc);
    }

    @Test
    void extractVariantThinkingConfig_google() {
        assertEquals(map("thinkingLevel", "high", "includeThoughts", true),
                variant(map("google", map("thinkingLevel", "high", "includeThoughts", true)), null));
        assertEquals(map("thinkingLevel", "low"),
                variant(map("google", map("thinkingLevel", "low")), null));
        // includeThoughts not a boolean -> key absent (TS undefined, JSON-omitted)
        assertEquals(map("thinkingLevel", "medium"),
                variant(map("google", map("thinkingLevel", "medium", "includeThoughts", "yes")), null));
        assertEquals(map("thinkingBudget", 32000),
                variant(map("google", map("thinkingConfig", map("thinkingBudget", 32000))), null));
        // thinkingConfig present but budget not a number, no fallback -> null
        assertNull(variant(map("google", map("thinkingConfig", map("thinkingBudget", "x"))), null));
    }

    @Test
    void extractVariantThinkingConfig_googleSearch() {
        assertEquals(map("googleSearch", map("mode", "auto", "threshold", 0.5)),
                variant(map("google", map("googleSearch", map("mode", "auto", "threshold", 0.5))), null));
        assertEquals(map("googleSearch", map("mode", "off")),
                variant(map("google", map("googleSearch", map("mode", "off"))), null));
        // invalid mode + non-number threshold -> both omitted -> empty googleSearch map (still non-empty result)
        assertEquals(map("googleSearch", map()),
                variant(map("google", map("googleSearch", map("mode", "invalid", "threshold", "x"))), null));
        assertEquals(map("thinkingLevel", "high", "googleSearch", map("mode", "auto")),
                variant(map("google", map("thinkingLevel", "high", "googleSearch", map("mode", "auto"))), null));
    }

    @Test
    void extractVariantThinkingConfig_generationConfigFallback() {
        assertEquals(map("thinkingBudget", 8192),
                variant(null, map("thinkingConfig", map("thinkingBudget", 8192))));
        assertEquals(map("thinkingLevel", "medium", "includeThoughts", false),
                variant(null, map("thinkingConfig", map("thinkingLevel", "medium", "includeThoughts", false))));
        assertEquals(map("thinkingBudget", 100),
                variant(map(), map("thinkingConfig", map("thinkingBudget", 100))));
        // google budget present -> fallback skipped (generationConfig ignored)
        assertEquals(map("thinkingBudget", 5),
                variant(map("google", map("thinkingConfig", map("thinkingBudget", 5))), map("thinkingConfig", map("thinkingBudget", 999))));
        assertNull(variant(null, null));
        assertNull(variant(map("google", map()), null));
    }

    // ---- resolveThinkingConfig -------------------------------------------------------------------

    @Test
    void resolveThinkingConfig() {
        assertEquals(map("includeThoughts", true, "thinkingBudget", DEF),
                AntigravityThinkingConfig.resolveThinkingConfig(null, true, false, false));
        Map<String, Object> uc = map("thinkingBudget", 5000, "includeThoughts", true);
        assertEquals(uc, AntigravityThinkingConfig.resolveThinkingConfig(uc, true, true, true));
        assertNull(AntigravityThinkingConfig.resolveThinkingConfig(null, false, false, false));
        assertEquals(map("thinkingBudget", 100),
                AntigravityThinkingConfig.resolveThinkingConfig(map("thinkingBudget", 100), false, true, false));
        assertEquals(map("includeThoughts", true, "thinkingBudget", DEF),
                AntigravityThinkingConfig.resolveThinkingConfig(null, true, true, true));
    }

    // ---- normalizeThinkingConfig -----------------------------------------------------------------

    @Test
    void normalizeThinkingConfig() {
        assertEquals(map("thinkingBudget", 8000, "includeThoughts", true),
                AntigravityThinkingConfig.normalizeThinkingConfig(map("thinkingBudget", 8000, "includeThoughts", true)));
        // snake_case read, camelCase emit
        assertEquals(map("thinkingBudget", 8000, "includeThoughts", true),
                AntigravityThinkingConfig.normalizeThinkingConfig(map("thinking_budget", 8000, "include_thoughts", true)));
        // budget 0 -> enableThinking false, include forced false, but budget defined so kept
        assertEquals(map("thinkingBudget", 0, "includeThoughts", false),
                AntigravityThinkingConfig.normalizeThinkingConfig(map("thinkingBudget", 0)));
        // includeThoughts only (no budget) -> include forced false, budget omitted
        assertEquals(map("includeThoughts", false),
                AntigravityThinkingConfig.normalizeThinkingConfig(map("includeThoughts", true)));
        assertNull(AntigravityThinkingConfig.normalizeThinkingConfig(map()));
        assertNull(AntigravityThinkingConfig.normalizeThinkingConfig(map("thinkingBudget", "x")));
        assertNull(AntigravityThinkingConfig.normalizeThinkingConfig(null));
        assertEquals(map("thinkingBudget", 16000, "includeThoughts", false),
                AntigravityThinkingConfig.normalizeThinkingConfig(map("thinkingBudget", 16000, "includeThoughts", false)));
        // negative budget: not > 0 -> not enabled, but defined so kept; include forced false
        assertEquals(map("thinkingBudget", -5, "includeThoughts", false),
                AntigravityThinkingConfig.normalizeThinkingConfig(map("thinkingBudget", -5)));
        assertEquals(map("thinkingBudget", 12000, "includeThoughts", false),
                AntigravityThinkingConfig.normalizeThinkingConfig(map("thinkingBudget", 12000)));
        assertEquals(map("thinkingBudget", 4000, "includeThoughts", true),
                AntigravityThinkingConfig.normalizeThinkingConfig(map("thinking_budget", 4000, "includeThoughts", true)));
    }

    // ---- constants -------------------------------------------------------------------------------

    @Test
    void defaultBudget() {
        assertEquals(16000, AntigravityThinkingConfig.DEFAULT_THINKING_BUDGET);
    }
}
