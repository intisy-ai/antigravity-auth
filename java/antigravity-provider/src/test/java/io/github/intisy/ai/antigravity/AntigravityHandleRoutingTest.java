package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure helpers of {@link AntigravityHandleRouting} and {@code driftAccountVersions},
 * asserted directly (they are also exercised end-to-end by {@link AntigravityHandleOrchestratorTest}
 * via the real handle/attemptModel path).
 */
class AntigravityHandleRoutingTest {

    private static final JsonCodec JSON = new TestJsonCodec();
    private static final Logger NOOP = m -> { };

    @Test
    void isRateLimitStatus() {
        assertTrue(AntigravityHandleRouting.isRateLimitStatus(429));
        assertTrue(AntigravityHandleRouting.isRateLimitStatus(503));
        assertTrue(AntigravityHandleRouting.isRateLimitStatus(529));
        assertFalse(AntigravityHandleRouting.isRateLimitStatus(200));
        assertFalse(AntigravityHandleRouting.isRateLimitStatus(403));
    }

    @Test
    void isAutoModel() {
        assertTrue(AntigravityHandleRouting.isAutoModel("antigravity-auto"));
        assertTrue(AntigravityHandleRouting.isAutoModel("auto"));
        assertTrue(AntigravityHandleRouting.isAutoModel("Antigravity-auto-fast"));
        assertTrue(AntigravityHandleRouting.isAutoModel("auto-cheap"));
        assertFalse(AntigravityHandleRouting.isAutoModel("antigravity-claude-sonnet-4-6"));
        assertFalse(AntigravityHandleRouting.isAutoModel(null));
    }

    @Test
    void rewriteModelInUrl() {
        assertEquals("https://x/v1internal/models/m2:generateContent",
                AntigravityHandleRouting.rewriteModelInUrl("https://x/v1internal/models/m1:generateContent", "m2"));
        // only the FIRST /models/ segment is rewritten
        assertEquals("https://x/models/new/models/old",
                AntigravityHandleRouting.rewriteModelInUrl("https://x/models/a/models/old", "new"));
    }

    @Test
    void modelFromRequest() {
        assertEquals("ctx-model", AntigravityHandleRouting.modelFromRequest("https://x/models/url-model:gen", "{}", "ctx-model", JSON));
        assertEquals("url-model", AntigravityHandleRouting.modelFromRequest("https://x/models/url-model:gen", "{}", null, JSON));
        assertEquals("body-model", AntigravityHandleRouting.modelFromRequest("https://x/nomatch", "{\"model\":\"body-model\"}", null, JSON));
        assertEquals("antigravity-auto", AntigravityHandleRouting.modelFromRequest("https://x/nomatch", "{}", null, JSON));
    }

    @Test
    void requestedThinkingLevel() {
        assertEquals("high", AntigravityHandleRouting.requestedThinkingLevel(
                "{\"providerOptions\":{\"google\":{\"thinkingLevel\":\"high\"}}}", JSON));
        assertEquals("medium", AntigravityHandleRouting.requestedThinkingLevel(
                "{\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":16384}}}", JSON));
        assertEquals("high", AntigravityHandleRouting.requestedThinkingLevel(
                "{\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":20000}}}", JSON));
        // wrapped body (parsed.request) is unwrapped first
        assertEquals("low", AntigravityHandleRouting.requestedThinkingLevel(
                "{\"request\":{\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":4096}}}}", JSON));
        assertEquals(null, AntigravityHandleRouting.requestedThinkingLevel("{}", JSON));
    }

    @Test
    void resolveEffortVariant() {
        Map<String, Object> variants = new LinkedHashMap<>();
        variants.put("minimal", variant("m-min"));
        variants.put("medium", variant("m-med"));
        variants.put("high", variant("m-high"));
        AntigravityHandleRouting.ModelCacheLookup cache = id -> "fam".equals(id) ? variants : null;

        // exact level
        assertEquals("m-med", AntigravityHandleRouting.resolveEffortVariant("fam",
                "{\"providerOptions\":{\"google\":{\"thinkingLevel\":\"medium\"}}}", cache, JSON, NOOP));
        // requested "low" has no exact variant -> highest available NOT above "low" -> minimal
        assertEquals("m-min", AntigravityHandleRouting.resolveEffortVariant("fam",
                "{\"providerOptions\":{\"google\":{\"thinkingLevel\":\"low\"}}}", cache, JSON, NOOP));
        // no variants for the model -> unchanged
        assertEquals("other", AntigravityHandleRouting.resolveEffortVariant("other",
                "{\"providerOptions\":{\"google\":{\"thinkingLevel\":\"high\"}}}", cache, JSON, NOOP));
        // no requested level -> unchanged
        assertEquals("fam", AntigravityHandleRouting.resolveEffortVariant("fam", "{}", cache, JSON, NOOP));
    }

    @Test
    void endpointsFor() {
        assertEquals(Arrays.asList(AntigravityHandleRouting.ANTIGRAVITY_ENDPOINT_PROD),
                AntigravityHandleRouting.endpointsFor("gemini-cli"));
        assertEquals(Arrays.asList(AntigravityHandleRouting.ANTIGRAVITY_ENDPOINT_PROD,
                AntigravityHandleRouting.ANTIGRAVITY_ENDPOINT_DAILY, AntigravityHandleRouting.ANTIGRAVITY_ENDPOINT_AUTOPUSH),
                AntigravityHandleRouting.endpointsFor("antigravity"));
    }

    @Test
    void retryAfterMsFromMessage() {
        assertEquals(30000L, AntigravityHandleRouting.retryAfterMsFromMessage("quota reached, resets after 30s"));
        assertEquals(5000L, AntigravityHandleRouting.retryAfterMsFromMessage("resets in 5 s"));
        assertEquals(0L, AntigravityHandleRouting.retryAfterMsFromMessage("no reset here"));
        assertEquals(0L, AntigravityHandleRouting.retryAfterMsFromMessage(null));
    }

    @Test
    void buildAuth() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("managedProjectId", "mp1");
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("refresh", "rt1");
        account.put("expires", 123L);
        account.put("meta", meta);
        Map<String, Object> auth = AntigravityHandleRouting.buildAuth(account, "at1");
        assertEquals("oauth", auth.get("type"));
        assertEquals("at1", auth.get("access"));
        assertEquals(123L, auth.get("expires"));
        assertEquals("rt1||mp1", auth.get("refresh"));
    }

    @Test
    void soonestQuotaReset_snapshot() {
        // one exhausted pool, resetTime 2023-11-14T23:00:00Z -> 1700002800000.
        List<Map<String, Object>> accounts = new ArrayList<>();
        accounts.add(accountWithQuota("2023-11-14T23:00:00Z", 0));
        assertEquals(1700002800000L, AntigravityHandleRouting.soonestQuotaReset(accounts));
        // a pool with remaining quota is NOT counted
        assertEquals(0L, AntigravityHandleRouting.soonestQuotaReset(
                Arrays.asList(accountWithQuota("2023-11-14T23:00:00Z", 0.5))));
    }

    @Test
    void driftAccountVersions_snapshot() {
        // Transcribed from fixtures.json "driftAccountVersions" (now=FIXED_NOW, random=0.5, FALLBACK pool).
        Map<String, Object> firstSight = account("d1", fingerprint("antigravity/1.18.3 (x)", "1.18.3", null));
        Map<String, Object> due = account("d2", fingerprint("antigravity/1.18.3 (x)", "1.18.3", 1700000000000L - 1000L));
        Map<String, Object> noUa = account("d3", new LinkedHashMap<>());
        List<Map<String, Object>> accounts = Arrays.asList(firstSight, due, noUa);

        List<AntigravityHandleRouting.VersionDrift> drifts = AntigravityHandleRouting.driftAccountVersions(
                accounts, 1700000000000L, () -> 0.5, AntigravityVersions.FALLBACK_VERSIONS);

        assertEquals(2, drifts.size());
        AntigravityHandleRouting.VersionDrift d1 = drifts.get(0);
        assertEquals("d1", d1.accountId);
        assertTrue(d1.scheduleOnly);
        assertEquals(1702116800000L, d1.nextVersionDriftAt);

        AntigravityHandleRouting.VersionDrift d2 = drifts.get(1);
        assertEquals("d2", d2.accountId);
        assertFalse(d2.scheduleOnly);
        assertEquals("antigravity/2.0.4 (x)", d2.userAgent);
        assertEquals("2.0.4", d2.version);
        assertEquals(Long.valueOf(1700000000000L), d2.versionPickedAt);
        assertEquals(1702116800000L, d2.nextVersionDriftAt);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static Map<String, Object> variant(String model) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("model", model);
        return v;
    }

    private static Map<String, Object> accountWithQuota(String resetTime, double remainingFraction) {
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("remainingFraction", remainingFraction);
        pool.put("resetTime", resetTime);
        Map<String, Object> cq = new LinkedHashMap<>();
        cq.put("Claude", pool);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("cachedQuota", cq);
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("meta", meta);
        return account;
    }

    private static Map<String, Object> fingerprint(String userAgent, String version, Long nextVersionDriftAt) {
        Map<String, Object> fp = new LinkedHashMap<>();
        fp.put("userAgent", userAgent);
        if (version != null) fp.put("version", version);
        if (nextVersionDriftAt != null) fp.put("nextVersionDriftAt", nextVersionDriftAt);
        return fp;
    }

    private static Map<String, Object> account(String id, Map<String, Object> fingerprint) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fingerprint", fingerprint);
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", id);
        account.put("meta", meta);
        return account;
    }
}
