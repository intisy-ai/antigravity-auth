package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.shared.routing.HandleIrException;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline test for {@link AntigravityProvider#handleIr}: the has-no-account path must throw a clear,
 * well-formed {@link HandleIrException} WITHOUT attempting any network call (no accounts.json exists
 * under the temp {@code configDir}, so {@code AccountManager#acquire} returns {@code null} and
 * {@link AntigravityHandleOrchestrator#attemptModel} materializes its own "no available account"
 * SYNTHETIC 503 decision before {@link AntigravityRequestPrep#prepare} or the {@code HttpClient}
 * SPI ever run). The full rotation/rate-limit/terminal-error paths are covered by
 * {@code AntigravityServePathTest}'s scripted-HttpClient scenarios (also via handleIr). The legacy
 * Anthropic-wire {@code handle()} override was removed in T4 (canonical-IR migration).
 */
class AntigravityProviderTest {

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
