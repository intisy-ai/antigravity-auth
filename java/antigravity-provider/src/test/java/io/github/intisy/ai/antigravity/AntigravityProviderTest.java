package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3a offline test for {@link AntigravityProvider#handle}: the has-no-account path must
 * return a clear, well-formed error WITHOUT attempting any network call (no accounts.json exists
 * under the temp {@code configDir}, so {@code AccountManager#acquire} returns {@code null} and
 * {@link AntigravityHandleOrchestrator#attemptModel} materializes its own "no available account"
 * SYNTHETIC 503 decision before {@link AntigravityRequestPrep#prepare} or the {@code HttpClient}
 * SPI ever run). This supersedes the Phase 2 ad-hoc 401 -- the orchestrator now owns this decision
 * (see {@code .superpowers/sdd/phase-3a-brief.md}); the full rotation/rate-limit/terminal-error
 * paths are covered by {@code AntigravityServePathTest}'s scripted-HttpClient scenarios.
 */
class AntigravityProviderTest {

    @Test
    void handle_noAccountConfigured_returnsClearErrorWithoutNetworkCall(@TempDir Path configDir) {
        HttpRequest request = new HttpRequest();
        request.method = "POST";
        request.url = "/v1/messages";
        request.body = "{\"model\":\"antigravity-claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();
        ctx.model = "antigravity-claude-sonnet-4-6";

        HttpResponse response = new AntigravityProvider().handle(request, ctx);

        assertEquals(503, response.status);
        assertEquals("application/json", response.headers.get("content-type"));
        assertTrue(response.body.contains("\"error\""));
        assertTrue(response.body.contains("No available antigravity account"));
    }

    @Test
    void id_isAntigravity() {
        assertEquals("antigravity", new AntigravityProvider().id());
    }
}
