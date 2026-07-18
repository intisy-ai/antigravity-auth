package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.AccountQuota;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.QuotaBar;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
 * SP-E/E-D: {@code GET /v1/quota} is RETIRED from {@link AntigravityProvider#handle} -- this now
 * tests the typed {@link AntigravityProvider#quota} capability directly (backed by {@link
 * AntigravityQuotaFetch#quota}, returning one {@link AccountQuota} per account so an errored
 * account is still represented rather than vanishing), plus a regression test proving {@code
 * handle()} no longer intercepts a quota-shaped URL.
 */
class AntigravityQuotaFetchTest {

    @Test
    void happyPath_aggregatesFamiliesAndPersistsCachedQuota(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"models\":{"
                        + "\"claude-sonnet-4\":{\"quotaInfo\":{\"remainingFraction\":0.4,\"resetTime\":\"2025-07-16T12:00:00Z\"}},"
                        + "\"gemini-2.5-pro\":{\"quotaInfo\":{\"remainingFraction\":0.9,\"resetTime\":\"2025-07-16T13:00:00Z\"}}"
                        + "}}");

        registerTestBackend(configDir, http).accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acc1", "proj-x"));

        List<AccountQuota> accounts = callQuota(configDir);

        assertEquals(1, accounts.size());
        AccountQuota entry = accounts.get(0);
        assertEquals("acc1", entry.accountId);
        assertEquals("active", entry.accountStatus);

        assertEquals(2, entry.bars.size());
        QuotaBar claude = findByLabel(entry.bars, "Claude");
        assertEquals(0.4, claude.remainingFraction, 1e-9);
        assertEquals("2025-07-16T12:00:00Z", claude.resetTime);
        QuotaBar gemini = findByLabel(entry.bars, "Gemini");
        assertEquals(0.9, gemini.remainingFraction, 1e-9);
        assertEquals("2025-07-16T13:00:00Z", gemini.resetTime);

        assertEquals(1, http.requests.size());
        HttpRequest sent = http.requests.get(0);
        assertEquals("POST", sent.method);
        assertTrue(sent.url.endsWith("/v1internal:fetchAvailableModels"), "unexpected url: " + sent.url);
        assertEquals("Bearer access-acc1", sent.headers.get("Authorization"));

        // Persistence: re-reading the account store (backed by the real on-disk FileStore under
        // configDir) must show the aggregated per-family quota now cached under meta.cachedQuota.
        Account persisted = findAccount(configDir, http, "acc1");
        assertTrue(persisted.meta != null && persisted.meta.get("cachedQuota") instanceof Map,
                "meta.cachedQuota must be persisted");
        @SuppressWarnings("unchecked")
        Map<String, Object> cachedQuota = (Map<String, Object>) persisted.meta.get("cachedQuota");
        assertTrue(cachedQuota.containsKey("Claude") && cachedQuota.containsKey("Gemini"),
                "meta.cachedQuota must carry both families");
    }

    @Test
    void zeroEnabledAccounts_returnsEmptyList_withoutCallingUpstream(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        registerTestBackend(configDir, http);
        // No accounts seeded at all -> zero enabled accounts.

        List<AccountQuota> accounts = callQuota(configDir);

        assertTrue(accounts.isEmpty());
        assertTrue(http.requests.isEmpty(), "no HTTP call should be attempted with zero enabled accounts");
    }

    @Test
    void disabledAccountOnly_isSkipped_treatedAsZeroEnabled(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        AntigravityBackend backend = registerTestBackend(configDir, http);
        Account disabled = seededAccount("acct-disabled", "proj-d");
        disabled.enabled = false;
        backend.accountStore.add(AntigravityBackend.PROVIDER_ID, disabled);

        List<AccountQuota> accounts = callQuota(configDir);

        assertTrue(accounts.isEmpty());
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void allEndpointsFail_returnsErrorAccountQuota_withNoBars_notAThrow(@TempDir Path configDir) {
        int endpointCount = AntigravityHandleRouting.endpointsFor("antigravity").size();
        ScriptedHttpClient http = new ScriptedHttpClient();
        for (int i = 0; i < endpointCount; i++) {
            http.enqueueError(500, "{\"error\":{\"message\":\"boom\"}}");
        }
        registerTestBackend(configDir, http).accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acc1", "proj-x"));

        List<AccountQuota> accounts = callQuota(configDir);

        // The errored account is still represented (not dropped) -- exactly what AccountQuota's
        // per-account (not flattened) shape is designed to preserve.
        assertEquals(1, accounts.size());
        AccountQuota entry = accounts.get(0);
        assertEquals("acc1", entry.accountId);
        assertEquals("error", entry.accountStatus);
        assertTrue(entry.bars.isEmpty());
        assertEquals(endpointCount, http.requests.size(), "every endpoint fallback must have been tried");
    }

    @Test
    void handle_getV1Quota_noLongerIntercepted_fallsThroughToOrchestrator(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}}\n\n");
        registerTestBackend(configDir, http).accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acc1", "proj-x"));

        HttpRequest request = new HttpRequest();
        request.method = "GET";
        request.url = "/v1/quota";

        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();
        ctx.model = "antigravity-claude-sonnet-4-6";

        new AntigravityProvider().handle(request, ctx);

        assertEquals(1, http.requests.size());
        assertFalse(http.requests.get(0).url.contains("fetchAvailableModels"),
                "GET /v1/quota must no longer be specially intercepted by handle()");
    }

    @Test
    void postMessages_regression_stillRoutesThroughOrchestrator_notInterceptedByQuotaCapability(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}}\n\n");
        registerTestBackend(configDir, http).accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acc1", "proj-x"));

        HttpRequest request = new HttpRequest();
        request.method = "POST";
        request.url = "/v1/messages";
        request.body = "{\"model\":\"antigravity-claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();
        ctx.model = "antigravity-claude-sonnet-4-6";

        HttpResponse response = new AntigravityProvider().handle(request, ctx);

        assertEquals(200, response.status);
        assertEquals(1, http.requests.size());
        assertFalse(http.requests.get(0).url.contains("fetchAvailableModels"),
                "a POST /v1/messages request must still hit the messages orchestrator");
    }

    // ---- shared fixtures (mirrors AntigravityModelsFetchTest) -------------------------------------

    private static AntigravityBackend registerTestBackend(Path configDir, HttpClient http) {
        AntigravityBackend backend = AntigravityBackend.forTest(configDir.toString(), http);
        AntigravityBackend.registerForTest(configDir.toString(), backend);
        return backend;
    }

    private static Account seededAccount(String id, String managedProjectId) {
        Account a = new Account();
        a.id = id;
        a.email = id + "@example.com";
        a.refresh = "rt-" + id;
        a.access = "access-" + id; // fresh token -> AccountManager.ensureAccess never refreshes
        a.expires = System.currentTimeMillis() + 3_600_000L;
        a.enabled = true;
        Map<String, Object> meta = new LinkedHashMap<>();
        if (managedProjectId != null) {
            meta.put("managedProjectId", managedProjectId);
        }
        a.meta = meta;
        return a;
    }

    private static Account findAccount(Path configDir, HttpClient http, String id) {
        AntigravityBackend backend = AntigravityBackend.forTest(configDir.toString(), http);
        for (Account a : backend.accountStore.list(AntigravityBackend.PROVIDER_ID)) {
            if (id.equals(a.id)) return a;
        }
        return null;
    }

    private static QuotaBar findByLabel(List<QuotaBar> bars, String label) {
        for (QuotaBar bar : bars) {
            if (label.equals(bar.label)) return bar;
        }
        throw new AssertionError("no quota bar with label " + label + " in " + bars);
    }

    private static List<AccountQuota> callQuota(Path configDir) {
        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();
        return new AntigravityProvider().quota(ctx);
    }

    /** Scripted {@link HttpClient}: pops one queued response per {@link #send}, records every request. */
    private static final class ScriptedHttpClient implements HttpClient {
        private final Deque<HttpResponse> queue = new ArrayDeque<>();
        final List<HttpRequest> requests = new ArrayList<>();

        ScriptedHttpClient enqueueOk(int status, String body) {
            return enqueue(status, body);
        }

        ScriptedHttpClient enqueueError(int status, String body) {
            return enqueue(status, body);
        }

        private ScriptedHttpClient enqueue(int status, String body) {
            HttpResponse r = new HttpResponse();
            r.status = status;
            r.headers = new LinkedHashMap<>();
            r.headers.put("content-type", "application/json");
            r.body = body;
            queue.add(r);
            return this;
        }

        @Override
        public HttpResponse send(HttpRequest req) {
            requests.add(req);
            HttpResponse r = queue.poll();
            if (r == null) {
                throw new IllegalStateException("ScriptedHttpClient queue empty for " + req.url);
            }
            return r;
        }
    }
}
