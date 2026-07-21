package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AntigravityLanesTest {

    // ---- isGeminiCliModel -------------------------------------------------------------------------

    @Test
    void isGeminiCliModel_bareGeminiId_true() {
        assertTrue(AntigravityLanes.isGeminiCliModel("gemini-2.5-flash"));
    }

    @Test
    void isGeminiCliModel_antigravityPrefixed_false() {
        assertFalse(AntigravityLanes.isGeminiCliModel("antigravity-gemini-3-pro"));
    }

    @Test
    void isGeminiCliModel_nonString_false() {
        assertFalse(AntigravityLanes.isGeminiCliModel(42));
    }

    // ---- laneFor -----------------------------------------------------------------------------------

    @Test
    void laneFor_bareGemini_isGeminiCliLane() {
        assertEquals("gemini-cli", AntigravityLanes.laneFor("gemini-2.5-pro"));
    }

    @Test
    void laneFor_antigravityClaude() {
        assertEquals("claude", AntigravityLanes.laneFor("antigravity-claude-sonnet-4"));
    }

    @Test
    void laneFor_antigravityGpt() {
        assertEquals("gpt-oss", AntigravityLanes.laneFor("antigravity-gpt-oss-120b"));
    }

    @Test
    void laneFor_antigravityFlash() {
        assertEquals("gemini-flash", AntigravityLanes.laneFor("antigravity-gemini-3.5-flash"));
    }

    @Test
    void laneFor_antigravityPro_defaultsGeminiPro() {
        assertEquals("gemini-pro", AntigravityLanes.laneFor("antigravity-gemini-3-pro"));
    }

    @Test
    void laneFor_mixedCasePrefixAndClaude() {
        assertEquals("claude", AntigravityLanes.laneFor("ANTIGRAVITY-Claude-Something"));
    }

    @Test
    void laneFor_nullModel_defaultsGeminiPro() {
        assertEquals("gemini-pro", AntigravityLanes.laneFor(null));
    }

    @Test
    void laneFor_emptyString_defaultsGeminiPro() {
        assertEquals("gemini-pro", AntigravityLanes.laneFor(""));
    }

    // ---- headerStyleFor ------------------------------------------------------------------------------

    @Test
    void headerStyleFor_bareGemini() {
        assertEquals("gemini-cli", AntigravityLanes.headerStyleFor("gemini-2.5-pro"));
    }

    @Test
    void headerStyleFor_antigravity() {
        assertEquals("antigravity", AntigravityLanes.headerStyleFor("antigravity-claude-sonnet-4"));
    }

    // ---- parseRateLimitReason -------------------------------------------------------------------------

    @Test
    void parseRateLimitReason_status529() {
        assertEquals("MODEL_CAPACITY_EXHAUSTED", AntigravityLanes.parseRateLimitReason(null, null, 529));
    }

    @Test
    void parseRateLimitReason_status503() {
        assertEquals("MODEL_CAPACITY_EXHAUSTED", AntigravityLanes.parseRateLimitReason(null, null, 503));
    }

    @Test
    void parseRateLimitReason_status500() {
        assertEquals("SERVER_ERROR", AntigravityLanes.parseRateLimitReason(null, null, 500));
    }

    @Test
    void parseRateLimitReason_reasonQuotaCaseInsensitive() {
        assertEquals("QUOTA_EXHAUSTED", AntigravityLanes.parseRateLimitReason("quota_exhausted", null, 200));
    }

    @Test
    void parseRateLimitReason_reasonRateLimit() {
        assertEquals("RATE_LIMIT_EXCEEDED", AntigravityLanes.parseRateLimitReason("RATE_LIMIT_EXCEEDED", null, null));
    }

    @Test
    void parseRateLimitReason_reasonCapacity() {
        assertEquals("MODEL_CAPACITY_EXHAUSTED", AntigravityLanes.parseRateLimitReason("model_capacity_exhausted", null, null));
    }

    @Test
    void parseRateLimitReason_unrecognizedReason_fallsThroughToMessage() {
        assertEquals("QUOTA_EXHAUSTED", AntigravityLanes.parseRateLimitReason(
                "something_else", "Resource has been exhausted (e.g. check quota).", null));
    }

    @Test
    void parseRateLimitReason_messageCapacity() {
        assertEquals("MODEL_CAPACITY_EXHAUSTED",
                AntigravityLanes.parseRateLimitReason(null, "Model is overloaded, please retry", null));
    }

    @Test
    void parseRateLimitReason_messageRateLimitPerMinute() {
        assertEquals("RATE_LIMIT_EXCEEDED", AntigravityLanes.parseRateLimitReason(
                null, "429 Too Many Requests: exceeded requests per minute quota", null));
    }

    @Test
    void parseRateLimitReason_messageQuota() {
        assertEquals("QUOTA_EXHAUSTED", AntigravityLanes.parseRateLimitReason(null, "Quota exceeded for quota metric", null));
    }

    @Test
    void parseRateLimitReason_nothing_unknown() {
        assertEquals("UNKNOWN", AntigravityLanes.parseRateLimitReason(null, null, null));
    }

    @Test
    void parseRateLimitReason_irrelevantStatus_unknown() {
        assertEquals("UNKNOWN", AntigravityLanes.parseRateLimitReason(null, null, 418));
    }

    // ---- calculateBackoffMs / resetTimeFor (Random fixed at 0.5 -> jitter() == 0) ---------------------

    private static final Random RANDOM_HALF = TestDoubles.fixedRandom(0.5);

    @Test
    void calculateBackoffMs_retryAfterHonored_flooredAtMin() {
        assertEquals(5000L, AntigravityLanes.calculateBackoffMs("QUOTA_EXHAUSTED", 0, 5000L, RANDOM_HALF));
    }

    @Test
    void calculateBackoffMs_retryAfterBelowMin_flooredUp() {
        assertEquals(2000L, AntigravityLanes.calculateBackoffMs("QUOTA_EXHAUSTED", 0, 500L, RANDOM_HALF));
    }

    @Test
    void calculateBackoffMs_retryAfterZero_ignored() {
        assertEquals(45000L, AntigravityLanes.calculateBackoffMs("RATE_LIMIT_EXCEEDED", 0, 0L, RANDOM_HALF));
    }

    @Test
    void calculateBackoffMs_quotaExhausted_index0() {
        assertEquals(60000L, AntigravityLanes.calculateBackoffMs("QUOTA_EXHAUSTED", 0, null, RANDOM_HALF));
    }

    @Test
    void calculateBackoffMs_quotaExhausted_index2() {
        assertEquals(1_800_000L, AntigravityLanes.calculateBackoffMs("QUOTA_EXHAUSTED", 2, null, RANDOM_HALF));
    }

    @Test
    void calculateBackoffMs_quotaExhausted_beyondArray_clampsToLastEntry() {
        assertEquals(7_200_000L, AntigravityLanes.calculateBackoffMs("QUOTA_EXHAUSTED", 10, null, RANDOM_HALF));
    }

    @Test
    void calculateBackoffMs_rateLimitExceeded_noFailures() {
        assertEquals(45000L, AntigravityLanes.calculateBackoffMs("RATE_LIMIT_EXCEEDED", 0, null, RANDOM_HALF));
    }

    @Test
    void calculateBackoffMs_rateLimitExceeded_threeFailures_exponential() {
        assertEquals(151875L, AntigravityLanes.calculateBackoffMs("RATE_LIMIT_EXCEEDED", 3, null, RANDOM_HALF));
    }

    @Test
    void calculateBackoffMs_modelCapacity_zeroJitterAtRandHalf() {
        assertEquals(45000L, AntigravityLanes.calculateBackoffMs("MODEL_CAPACITY_EXHAUSTED", 0, null, RANDOM_HALF));
    }

    @Test
    void calculateBackoffMs_serverError_oneFailure() {
        assertEquals(45000L, AntigravityLanes.calculateBackoffMs("SERVER_ERROR", 1, null, RANDOM_HALF));
    }

    @Test
    void calculateBackoffMs_unknownReason_noFailures() {
        assertEquals(90000L, AntigravityLanes.calculateBackoffMs("UNKNOWN", 0, null, RANDOM_HALF));
    }

    @Test
    void calculateBackoffMs_unknownReason_manyFailures_capsAtMax() {
        assertEquals(3_600_000L, AntigravityLanes.calculateBackoffMs("UNKNOWN", 20, null, RANDOM_HALF));
    }

    @Test
    void resetTimeFor_quotaExhausted() {
        Clock clock = TestDoubles.fixedClock(1_700_000_000_000L);
        assertEquals(1_700_000_300_000L,
                AntigravityLanes.resetTimeFor("QUOTA_EXHAUSTED", 1, null, RANDOM_HALF, clock));
    }

    @Test
    void resetTimeFor_rateLimitWithRetryAfter() {
        Clock clock = TestDoubles.fixedClock(1_700_000_000_000L);
        assertEquals(1_700_000_010_000L,
                AntigravityLanes.resetTimeFor("RATE_LIMIT_EXCEEDED", 0, 10_000L, RANDOM_HALF, clock));
    }
}
