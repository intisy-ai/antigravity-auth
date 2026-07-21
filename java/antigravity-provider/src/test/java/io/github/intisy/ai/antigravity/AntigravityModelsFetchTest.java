package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.ModelInfo;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model discovery is served by the typed {@link AntigravityProvider#models} capability directly
 * (backed by {@link AntigravityModelsFetch#models}).
 */
class AntigravityModelsFetchTest {

    @Test
    void happyPath_mapsUpstreamCatalog_withManagedProjectId(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"models\":{"
                        + "\"gemini-3-pro-agent\":{\"displayName\":\"Gemini 3 Pro\",\"maxTokens\":1000000,\"maxOutputTokens\":32000}"
                        + "},\"agentModelSorts\":[{\"groups\":[{\"modelIds\":[\"gemini-3-pro-agent\"]}]}],"
                        + "\"defaultAgentModelId\":\"gemini-3-pro-agent\"}");

        registerTestBackend(configDir, http).accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acct-x", "proj-x"));

        List<ModelInfo> models = callModels(configDir);

        ModelInfo ranked = findById(models, "antigravity-gemini-3-pro-agent");
        assertEquals("Gemini 3 Pro (Antigravity)", ranked.name);
        assertEquals(1000000, ranked.context);
        assertEquals(32000, ranked.output);
        // The fixed Auto entry and the Gemini CLI free pool are always present alongside the
        // ranked agent models (AntigravityCatalog.buildAntigravityCatalog's catalog map).
        assertTrue(models.stream().anyMatch(m -> "antigravity-auto".equals(m.id)));
        assertTrue(models.stream().anyMatch(m -> "gemini-2.5-flash".equals(m.id)));

        assertEquals(1, http.requests.size());
        HttpRequest sent = http.requests.get(0);
        assertEquals("POST", sent.method);
        assertTrue(sent.url.endsWith("/v1internal:fetchAvailableModels"), "unexpected url: " + sent.url);
        assertEquals("Bearer access-acct-x", sent.headers.get("Authorization"));
        assertEquals("{\"project\":\"proj-x\"}", sent.body);
    }

    @Test
    void noEnabledAccount_returnsEmptyList_withoutCallingUpstream(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        registerTestBackend(configDir, http);
        // No accounts seeded at all -> zero enabled accounts.

        List<ModelInfo> models = callModels(configDir);

        assertTrue(models.isEmpty());
        assertTrue(http.requests.isEmpty(), "no HTTP call should be attempted with no enabled account");
    }

    @Test
    void disabledAccountOnly_isSkipped_treatedAsNoAccount(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        AntigravityBackend backend = registerTestBackend(configDir, http);
        Account disabled = seededAccount("acct-disabled", "proj-d");
        disabled.enabled = false;
        backend.accountStore.add(AntigravityBackend.PROVIDER_ID, disabled);

        List<ModelInfo> models = callModels(configDir);

        assertTrue(models.isEmpty());
        assertTrue(http.requests.isEmpty());
    }

    @Test
    void allEndpointsNon2xx_returnsEmptyList_notAThrow(@TempDir Path configDir) {
        int endpointCount = AntigravityHandleRouting.endpointsFor("antigravity").size();
        ScriptedHttpClient http = new ScriptedHttpClient();
        for (int i = 0; i < endpointCount; i++) {
            http.enqueueError(500, "{\"error\":{\"message\":\"boom\"}}");
        }
        registerTestBackend(configDir, http).accountStore.add(AntigravityBackend.PROVIDER_ID, seededAccount("acct-x", "proj-x"));

        List<ModelInfo> models = callModels(configDir);

        assertTrue(models.isEmpty(), "upstream failure on every endpoint must fold into an empty list, not a throw");
        assertEquals(endpointCount, http.requests.size(), "every endpoint fallback must have been tried");
    }

    @Test
    void noProjectId_sendsEmptyBody_stillSucceeds(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"models\":{},\"agentModelSorts\":[]}");
        AntigravityBackend backend = registerTestBackend(configDir, http);
        Account account = seededAccount("acct-noproj", null);
        backend.accountStore.add(AntigravityBackend.PROVIDER_ID, account);

        List<ModelInfo> models = callModels(configDir);

        // No ranked agent models in this fixture, but the fixed Auto + Gemini CLI entries remain.
        assertTrue(models.stream().anyMatch(m -> "antigravity-auto".equals(m.id)));
        assertEquals(1, http.requests.size());
        assertEquals("{}", http.requests.get(0).body);
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

    private static List<ModelInfo> callModels(Path configDir) {
        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();
        return new AntigravityProvider().models(ctx);
    }

    private static ModelInfo findById(List<ModelInfo> models, String id) {
        for (ModelInfo m : models) {
            if (id.equals(m.id)) return m;
        }
        throw new AssertionError("no model with id " + id + " in " + models);
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
