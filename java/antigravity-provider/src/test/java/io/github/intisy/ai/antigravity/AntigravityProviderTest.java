package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.shared.routing.HandleIrException;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * T3c-2: {@link AntigravityProvider#handleIr} must throw the canonical {@link
     * HandleIrException} (not a bare RuntimeException) for the SAME no-account SYNTHETIC 503 the
     * {@code handle()} test above returns as a plain response -- carrying the exact same
     * status/body (Anthropic-shape rewrap via {@code materializeSynthetic}) so core-proxy's IR
     * front door can reconstruct an equivalent response instead of collapsing to a flat 502.
     */
    @Test
    void handleIr_noAccountConfigured_throwsHandleIrExceptionWithSameStatusAndBody(@TempDir Path configDir) {
        IrRequest request = new IrRequest();
        request.model = "antigravity-claude-sonnet-4-6";
        request.messages = new ArrayList<>();
        request.stream = false;

        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();
        ctx.model = "antigravity-claude-sonnet-4-6";

        HandleIrException thrown = assertThrows(HandleIrException.class,
                () -> new AntigravityProvider().handleIr(request, ctx));

        assertEquals(503, thrown.status);
        assertEquals("application/json", thrown.headers.get("content-type"));
        assertTrue(thrown.body.contains("\"type\":\"error\""));
        assertTrue(thrown.body.contains("No available antigravity account"));
    }
}
