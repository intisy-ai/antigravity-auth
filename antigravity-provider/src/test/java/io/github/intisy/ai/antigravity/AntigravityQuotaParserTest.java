package io.github.intisy.ai.antigravity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline deterministic tests for {@link AntigravityQuotaParser}, including the pure per-family
 * aggregation slice of {@code fetchQuotaFamilies}.
 */
class AntigravityQuotaParserTest {

    private static final long NOW = 1_700_000_000_000L;

    // ---- allPoolsExhausted ------------------------------------------------------------------------

    @Test
    void allPoolsExhausted_noPools_false() {
        assertFalse(AntigravityQuotaParser.allPoolsExhausted(map()));
    }

    @Test
    void allPoolsExhausted_onePoolWithCapacity_false() {
        assertFalse(AntigravityQuotaParser.allPoolsExhausted(map("Claude", map("remainingFraction", 0.2))));
    }

    @Test
    void allPoolsExhausted_everyPoolZero_true() {
        assertTrue(AntigravityQuotaParser.allPoolsExhausted(
                map("Claude", map("remainingFraction", 0.0), "Gemini", map("remainingFraction", 0.0))));
    }

    @Test
    void allPoolsExhausted_mixed_false() {
        assertFalse(AntigravityQuotaParser.allPoolsExhausted(
                map("Claude", map("remainingFraction", 0.0), "Gemini", map("remainingFraction", 0.1))));
    }

    // ---- antigravityStatus ------------------------------------------------------------------------

    @Test
    void antigravityStatus_disabled() {
        assertEquals("disabled", AntigravityQuotaParser.antigravityStatus(map("enabled", false), NOW));
    }

    @Test
    void antigravityStatus_verificationRequired() {
        assertEquals("verification-required",
                AntigravityQuotaParser.antigravityStatus(map("meta", map("verificationRequired", true)), NOW));
    }

    @Test
    void antigravityStatus_coolingDown() {
        assertEquals("cooling-down",
                AntigravityQuotaParser.antigravityStatus(map("coolingDownUntil", (double) (NOW + 5000)), NOW));
    }

    @Test
    void antigravityStatus_coolingDownExpired_active() {
        assertEquals("active",
                AntigravityQuotaParser.antigravityStatus(map("coolingDownUntil", (double) (NOW - 5000)), NOW));
    }

    @Test
    void antigravityStatus_quotaAllExhausted_rateLimited() {
        assertEquals("rate-limited", AntigravityQuotaParser.antigravityStatus(
                map("meta", map("cachedQuota", map("Claude", map("remainingFraction", 0.0)))), NOW));
    }

    @Test
    void antigravityStatus_quotaSomeRemaining_active() {
        assertEquals("active", AntigravityQuotaParser.antigravityStatus(
                map("meta", map("cachedQuota", map("Claude", map("remainingFraction", 0.4)))), NOW));
    }

    @Test
    void antigravityStatus_laneRateLimited_beforeFirstQuotaFetch() {
        assertEquals("rate-limited", AntigravityQuotaParser.antigravityStatus(
                map("rateLimitResetTimes", map("claude", (double) (NOW + 10000))), NOW));
    }

    @Test
    void antigravityStatus_laneExpired_active() {
        assertEquals("active", AntigravityQuotaParser.antigravityStatus(
                map("rateLimitResetTimes", map("claude", (double) (NOW - 10000))), NOW));
    }

    @Test
    void antigravityStatus_default_active() {
        assertEquals("active", AntigravityQuotaParser.antigravityStatus(map(), NOW));
    }

    // ---- antigravityAvailableAt -------------------------------------------------------------------

    @Test
    void antigravityAvailableAt_disabled_infinity() {
        assertEquals(Double.POSITIVE_INFINITY, AntigravityQuotaParser.antigravityAvailableAt(map("enabled", false), NOW));
    }

    @Test
    void antigravityAvailableAt_coolingDown_returnsCooldownEnd() {
        assertEquals((double) (NOW + 5000),
                AntigravityQuotaParser.antigravityAvailableAt(map("coolingDownUntil", (double) (NOW + 5000)), NOW));
    }

    @Test
    void antigravityAvailableAt_allExhaustedWithResets_returnsEarliest() {
        Map<String, Object> account = map("meta", map("cachedQuota", map(
                "Claude", map("remainingFraction", 0.0, "resetTime", "2023-11-14T22:13:20.000Z"),
                "Gemini", map("remainingFraction", 0.0, "resetTime", "2023-11-14T20:13:20.000Z"))));
        assertEquals(1_699_992_800_000.0, AntigravityQuotaParser.antigravityAvailableAt(account, NOW));
    }

    @Test
    void antigravityAvailableAt_allExhaustedNoParsableReset_returnsNow() {
        Map<String, Object> account = map("meta", map("cachedQuota", map("Claude", map("remainingFraction", 0.0))));
        assertEquals((double) NOW, AntigravityQuotaParser.antigravityAvailableAt(account, NOW));
    }

    @Test
    void antigravityAvailableAt_someRemaining_returnsNow() {
        Map<String, Object> account = map("meta", map("cachedQuota", map("Claude", map("remainingFraction", 0.5))));
        assertEquals((double) NOW, AntigravityQuotaParser.antigravityAvailableAt(account, NOW));
    }

    @Test
    void antigravityAvailableAt_noQuotaYet_returnsNow() {
        assertEquals((double) NOW, AntigravityQuotaParser.antigravityAvailableAt(map(), NOW));
    }

    // ---- antigravityQuota -------------------------------------------------------------------------

    @Test
    void antigravityQuota_present_mapsEachFamily() {
        Map<String, Object> account = map("meta", map("cachedQuota", map(
                "Claude", map("remainingFraction", 0.3, "resetTime", "2023-11-14T22:13:20.000Z"),
                "Gemini", map("remainingFraction", 0.9))));
        List<Map<String, Object>> quota = AntigravityQuotaParser.antigravityQuota(account);
        assertEquals(2, quota.size());
        assertEquals("Claude", quota.get(0).get("label"));
        assertEquals(0.3, (Double) quota.get(0).get("remainingFraction"), 1e-9);
        assertEquals("2023-11-14T22:13:20.000Z", quota.get(0).get("resetTime"));
        assertEquals("Gemini", quota.get(1).get("label"));
        assertEquals(0.9, (Double) quota.get(1).get("remainingFraction"), 1e-9);
        assertNull(quota.get(1).get("resetTime"));
    }

    @Test
    void antigravityQuota_absent_null() {
        assertNull(AntigravityQuotaParser.antigravityQuota(map()));
    }

    // ---- familyLabel ------------------------------------------------------------------------------

    @Test
    void familyLabel_claude() {
        assertEquals("Claude", AntigravityQuotaParser.familyLabel("claude-sonnet-4"));
    }

    @Test
    void familyLabel_gpt() {
        assertEquals("GPT-OSS", AntigravityQuotaParser.familyLabel("gpt-oss-120b"));
    }

    @Test
    void familyLabel_ossOnly_stillGptOss() {
        assertEquals("GPT-OSS", AntigravityQuotaParser.familyLabel("some-oss-model"));
    }

    @Test
    void familyLabel_gemini() {
        assertEquals("Gemini", AntigravityQuotaParser.familyLabel("gemini-3-pro"));
    }

    @Test
    void familyLabel_unknown_null() {
        assertNull(AntigravityQuotaParser.familyLabel("mystery-model"));
    }

    // ---- accountHasQuota --------------------------------------------------------------------------

    @Test
    void accountHasQuota_hasRemaining_true() {
        assertTrue(AntigravityQuotaParser.accountHasQuota(map("meta", map("cachedQuota", map("Claude", map("remainingFraction", 0.1))))));
    }

    @Test
    void accountHasQuota_allZero_false() {
        assertFalse(AntigravityQuotaParser.accountHasQuota(map("meta", map("cachedQuota", map("Claude", map("remainingFraction", 0.0))))));
    }

    @Test
    void accountHasQuota_noQuotaAtAll_false() {
        assertFalse(AntigravityQuotaParser.accountHasQuota(map()));
    }

    // ---- aggregateQuotaFamilies (fetchQuotaFamilies:113-127 pure slice) ---------------------------

    @Test
    @SuppressWarnings("unchecked")
    void aggregateQuotaFamilies_worstRemainingEarliestReset_exhaustedAsZero_unknownSkipped() {
        Map<String, Object> models = map(
                "claude-sonnet-4", map("quotaInfo", map("remainingFraction", 0.5, "resetTime", "2023-11-14T22:13:20.000Z")),
                "claude-opus-4", map("quotaInfo", map("remainingFraction", 0.2, "resetTime", "2023-11-14T20:13:20.000Z")),
                "gemini-3-pro", map("quotaInfo", map("remainingFraction", 0.8)),
                "unknown-model-x", map("quotaInfo", map("remainingFraction", 0.9)),
                "gemini-3-flash", map("quotaInfo", map("resetTime", "2023-11-15T00:00:00.000Z")));
        Map<String, Object> result = AntigravityQuotaParser.aggregateQuotaFamilies(models);
        assertNotNull(result);
        assertEquals(2, result.size());

        Map<String, Object> claude = (Map<String, Object>) result.get("Claude");
        assertEquals(0.2, (Double) claude.get("remainingFraction"), 1e-9);
        assertEquals("2023-11-14T20:13:20.000Z", claude.get("resetTime"));

        Map<String, Object> gemini = (Map<String, Object>) result.get("Gemini");
        assertEquals(0.0, (Double) gemini.get("remainingFraction"), 1e-9); // exhausted (resetTime only) -> 0
        assertEquals("2023-11-15T00:00:00.000Z", gemini.get("resetTime"));

        assertFalse(result.containsKey("GPT-OSS"));
    }

    @Test
    void aggregateQuotaFamilies_empty_null() {
        assertNull(AntigravityQuotaParser.aggregateQuotaFamilies(map()));
    }

    @Test
    void aggregateQuotaFamilies_onlyUnknownModels_null() {
        assertNull(AntigravityQuotaParser.aggregateQuotaFamilies(map("mystery-model", map("quotaInfo", map("remainingFraction", 0.5)))));
    }
}
