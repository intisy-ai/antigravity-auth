package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the Phase 3a orchestrator-driven serve path: drives the REAL {@link
 * AntigravityProvider#handle} with a scripted {@link HttpClient} injected into the backend via
 * {@link AntigravityBackend#forTest}/{@link AntigravityBackend#registerForTest} (see
 * {@code .superpowers/sdd/phase-3a-brief.md} "Testability"), so retry/rotation/terminal-error
 * materialization is exercised end-to-end without any real network call.
 *
 * <p>Test models are deliberately split by lane/headerStyle: {@code gemini-*} ids are the
 * "gemini-cli" lane (a single endpoint per account attempt, {@link AntigravityHandleRouting
 * #endpointsFor}), used for the simple rotation/no-account scenarios; {@code antigravity-*} ids
 * are the "antigravity" lane (three endpoint fallbacks per account attempt), used for the
 * quota-reset terminal scenario, which also needs a non-"gemini-cli" lane so {@code handle}'s
 * exhaustion branch reaches {@code soonestQuotaReset} instead of the gemini-cli-specific message.
 */
class AntigravityServePathTest {

    // ---- scenario 1: rotation on 429 --------------------------------------------------------------

    @Test
    void rotatesToNextAccountOn429(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueError(429, "quota exceeded for this request")
                .enqueueOk(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi from B\"}]}}]}");

        AntigravityBackend backend = registerTestBackend(configDir, http);
        backend.accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acct-a"));
        backend.accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acct-b"));

        HttpResponse response = handle(configDir, "gemini-test-model");

        assertEquals(200, response.status);
        assertTrue(response.body.contains("hi from B"));
        assertEquals(2, http.requests.size());
        assertTrue(hasRateLimitEntry(backend, "acct-a"), "reportRateLimit must have been recorded for acct-a");
        assertFalse(hasRateLimitEntry(backend, "acct-b"), "acct-b must never have been rate-limited");
    }

    // ---- scenario 2: no account -> clear error ----------------------------------------------------

    @Test
    void noAccountConfigured_materializesClearError(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        registerTestBackend(configDir, http);
        // No accounts seeded.

        // A non-"gemini-cli" lane model: with an EMPTY pool, `acquire` returns null regardless of
        // strategy (Selection.selectIndex short-circuits on a zero-size pool even under HYBRID's
        // "soonest free" last resort), so this reaches the true SYNTHETIC 503 -- a gemini-* model
        // would instead get reclassified into the GEMINI_CLI_EXHAUSTED terminal by handle()'s own
        // lane check, which is a different (also-valid) decision covered by no test here.
        HttpResponse response = handle(configDir, "antigravity-claude-sonnet-4-6");

        assertEquals(503, response.status);
        assertEquals("application/json", response.headers.get("content-type"));
        assertTrue(response.body.contains("\"error\""));
        assertTrue(response.body.contains("No available antigravity account"));
        assertTrue(http.requests.isEmpty(), "no HTTP call should be attempted with no account");
    }

    // ---- scenario 3: all accounts rate-limited -> lane-accurate terminal message -------------------

    @Test
    void allAccountsRateLimited_materializesQuotaResetTerminal(@TempDir Path configDir) {
        // core-auth's default Strategy.HYBRID never reports "no account available" while the pool
        // is non-empty -- once nobody is CURRENTLY free it falls back to "whoever frees up
        // soonest" (Selection#soonestFree) and keeps retrying, so attemptModel only gives up after
        // MAX_ATTEMPTS account-cycles, each running every "antigravity" headerStyle endpoint
        // fallback. Every one of those calls must 429 for the exhaustion to be genuine.
        int endpointsPerAttempt = AntigravityHandleRouting.endpointsFor("antigravity").size();
        int totalCalls = AntigravityHandleOrchestrator.MAX_ATTEMPTS * endpointsPerAttempt;
        ScriptedHttpClient http = new ScriptedHttpClient();
        for (int i = 0; i < totalCalls; i++) {
            http.enqueueError(429, "Quota exceeded, exhausted for this model");
        }

        AntigravityBackend backend = registerTestBackend(configDir, http);
        String resetTime = Instant.now().plusSeconds(600).toString();
        backend.accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccountWithQuota("acct-a", resetTime));
        backend.accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acct-b"));

        HttpResponse response = handle(configDir, "antigravity-claude-sonnet-4-6");

        assertEquals(400, response.status);
        assertTrue(response.headers.containsKey("Retry-After"), "Retry-After header must be present");
        assertTrue(response.body.contains("Quota resets"));
        assertTrue(response.body.contains("Try again later or pick another model"));
        assertEquals(totalCalls, http.requests.size());
        assertTrue(hasRateLimitEntry(backend, "acct-a") || hasRateLimitEntry(backend, "acct-b"));
    }

    // ---- scenario 4: happy path, single account ---------------------------------------------------

    @Test
    void happyPath_singleAccount_returnsUpstreamBodyRaw(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}");

        AntigravityBackend backend = registerTestBackend(configDir, http);
        backend.accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acct-solo"));

        HttpResponse response = handle(configDir, "antigravity-claude-sonnet-4-6");

        assertEquals(200, response.status);
        assertTrue(response.body.contains("\"text\":\"ok\""));
        assertEquals(1, http.requests.size());
        assertFalse(hasRateLimitEntry(backend, "acct-solo"), "a successful attempt must never rate-limit its account");
    }

    // ---- shared fixtures ---------------------------------------------------------------------------

    private static AntigravityBackend registerTestBackend(Path configDir, HttpClient http) {
        AntigravityBackend backend = AntigravityBackend.forTest(configDir.toString(), http);
        AntigravityBackend.registerForTest(configDir.toString(), backend);
        return backend;
    }

    private static HttpResponse handle(Path configDir, String model) {
        HttpRequest request = new HttpRequest();
        request.method = "POST";
        request.url = "/v1/messages";
        request.body = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();
        ctx.model = model;

        return new AntigravityProvider().handle(request, ctx);
    }

    /** Quiet account: {@code meta.managedProjectId} short-circuits project discovery (no loader call). */
    private static Account seededAccount(String id) {
        Account a = new Account();
        a.id = id;
        a.email = id + "@example.com";
        a.refresh = "rt-" + id;
        a.access = "access-" + id;
        a.expires = System.currentTimeMillis() + 3_600_000L;
        a.enabled = true;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("managedProjectId", "proj-" + id);
        a.meta = meta;
        return a;
    }

    /** A quiet account whose {@code meta.cachedQuota} carries one EXHAUSTED pool with a future reset. */
    private static Account seededAccountWithQuota(String id, String resetTimeIso) {
        Account a = seededAccount(id);
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("resetTime", resetTimeIso);
        pool.put("remainingFraction", 0);
        Map<String, Object> cachedQuota = new LinkedHashMap<>();
        cachedQuota.put("pro", pool);
        a.meta.put("cachedQuota", cachedQuota);
        return a;
    }

    /** {@code reportRateLimit} is the only call that ever populates {@code rateLimitResetTimes}. */
    private static boolean hasRateLimitEntry(AntigravityBackend backend, String accountId) {
        for (Account a : backend.accountStore.list(AntigravityBackend.PROVIDER_ID)) {
            if (accountId.equals(a.id)) {
                return a.rateLimitResetTimes != null && !a.rateLimitResetTimes.isEmpty();
            }
        }
        return false;
    }

    /** Scripted {@link HttpClient}: pops one queued response per {@link #send}, records every request. */
    private static final class ScriptedHttpClient implements HttpClient {
        private final Deque<HttpResponse> queue = new ArrayDeque<>();
        final List<HttpRequest> requests = new ArrayList<>();

        ScriptedHttpClient enqueueOk(int status, String body) {
            HttpResponse r = new HttpResponse();
            r.status = status;
            r.headers = new LinkedHashMap<>();
            r.body = body;
            queue.add(r);
            return this;
        }

        ScriptedHttpClient enqueueError(int status, String message) {
            HttpResponse r = new HttpResponse();
            r.status = status;
            r.headers = new LinkedHashMap<>();
            r.body = "{\"error\":{\"message\":" + quote(message) + ",\"status\":\"RESOURCE_EXHAUSTED\"}}";
            queue.add(r);
            return this;
        }

        @Override
        public HttpResponse send(HttpRequest req) {
            requests.add(req);
            assertFalse(req.url != null && (req.url.contains("loadCodeAssist") || req.url.contains("onboardUser")),
                    "project discovery must not run for a quiet (managedProjectId-seeded) account");
            HttpResponse r = queue.poll();
            if (r == null) {
                throw new IllegalStateException("ScriptedHttpClient queue empty for " + req.url);
            }
            return r;
        }

        private static String quote(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
}
