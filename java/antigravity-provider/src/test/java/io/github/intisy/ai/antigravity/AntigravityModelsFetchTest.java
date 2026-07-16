package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.JsonCodec;
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
 * End-to-end test of {@link AntigravityProvider#handle}'s {@code GET /v1/models} branch
 * (model-map Task 2). Mirrors {@link AntigravityServePathTest}'s harness: a scripted
 * {@link HttpClient} injected via {@link AntigravityBackend#forTest}/{@link
 * AntigravityBackend#registerForTest} so the real provider is driven end-to-end without any real
 * network call.
 */
class AntigravityModelsFetchTest {

    private final JsonCodec json = new TestJsonCodec();

    @Test
    void happyPath_mapsUpstreamCatalog_withManagedProjectId(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"models\":{"
                        + "\"gemini-3-pro-agent\":{\"displayName\":\"Gemini 3 Pro\",\"maxTokens\":1000000,\"maxOutputTokens\":32000}"
                        + "},\"agentModelSorts\":[{\"groups\":[{\"modelIds\":[\"gemini-3-pro-agent\"]}]}],"
                        + "\"defaultAgentModelId\":\"gemini-3-pro-agent\"}");

        registerTestBackend(configDir, http).accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acct-x", "proj-x"));

        HttpResponse response = handleModels(configDir);

        assertEquals(200, response.status);
        assertEquals("application/json", response.headers.get("content-type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) json.parse(response.body);
        @SuppressWarnings("unchecked")
        Map<String, Object> models = (Map<String, Object>) parsed.get("models");
        assertTrue(models.containsKey("antigravity-gemini-3-pro-agent"), "the antigravity-prefixed id must survive the mapping");
        assertEquals("antigravity-gemini-3-pro-agent", parsed.get("defaultModelId"));

        assertEquals(1, http.requests.size());
        HttpRequest sent = http.requests.get(0);
        assertEquals("POST", sent.method);
        assertTrue(sent.url.endsWith("/v1internal:fetchAvailableModels"), "unexpected url: " + sent.url);
        assertEquals("Bearer access-acct-x", sent.headers.get("Authorization"));
        assertEquals("application/json", sent.headers.get("Content-Type"));
        assertEquals("{\"project\":\"proj-x\"}", sent.body);
    }

    @Test
    void noEnabledAccount_returnsNoAccountErrorShape_withoutCallingUpstream(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        registerTestBackend(configDir, http);
        // No accounts seeded at all -> zero enabled accounts.

        HttpResponse response = handleModels(configDir);

        assertEquals(400, response.status);
        assertEquals("1", response.headers.get("x-hub-chat-error"));
        assertTrue(response.body.contains("invalid_request_error"));
        assertTrue(http.requests.isEmpty(), "no HTTP call should be attempted with no enabled account");
    }

    @Test
    void disabledAccountOnly_isSkipped_treatedAsNoAccount(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        AntigravityBackend backend = registerTestBackend(configDir, http);
        Account disabled = seededAccount("acct-disabled", "proj-d");
        disabled.enabled = false;
        backend.accountStore.add(AntigravityBackend.PROVIDER_ID, disabled);

        HttpResponse response = handleModels(configDir);

        assertEquals(400, response.status);
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void allEndpointsNon2xx_returnsApiErrorShape_notAThrow(@TempDir Path configDir) {
        int endpointCount = AntigravityHandleRouting.endpointsFor("antigravity").size();
        ScriptedHttpClient http = new ScriptedHttpClient();
        for (int i = 0; i < endpointCount; i++) {
            http.enqueueError(500, "{\"error\":{\"message\":\"boom\"}}");
        }
        registerTestBackend(configDir, http).accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acct-x", "proj-x"));

        HttpResponse response = handleModels(configDir);

        assertEquals(502, response.status);
        assertTrue(response.body.contains("api_error"), "upstream failure on every endpoint must surface as an api_error shape");
        assertEquals(endpointCount, http.requests.size(), "every endpoint fallback must have been tried");
    }

    @Test
    void noProjectId_sendsEmptyBody_stillSucceeds(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"models\":{},\"agentModelSorts\":[]}");
        AntigravityBackend backend = registerTestBackend(configDir, http);
        Account account = seededAccount("acct-noproj", null);
        backend.accountStore.add(AntigravityBackend.PROVIDER_ID, account);

        HttpResponse response = handleModels(configDir);

        assertEquals(200, response.status);
        assertEquals(1, http.requests.size());
        assertEquals("{}", http.requests.get(0).body);
    }

    @Test
    void postMessages_regression_stillRoutesThroughOrchestrator_notInterceptedByModelsBranch(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}}\n\n");
        registerTestBackend(configDir, http).accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acct-x", "proj-x"));

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
                "a POST /v1/messages request must still hit the messages orchestrator, not the models branch");
    }

    // ---- shared fixtures (mirrors AntigravityServePathTest) ---------------------------------------

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

    private static HttpResponse handleModels(Path configDir) {
        HttpRequest request = new HttpRequest();
        request.method = "GET";
        request.url = "/v1/models";

        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();

        return new AntigravityProvider().handle(request, ctx);
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
