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
 * Phase 2 offline test for {@link AntigravityProvider#handle}: the has-no-account path must
 * return a clear Anthropic-shaped auth error WITHOUT attempting any network call (no accounts.json
 * exists under the temp {@code configDir}, so {@code AccountManager#acquire} returns {@code null}
 * before {@link AntigravityRequestPrep#prepare} or the {@code HttpClient} SPI ever run). This is
 * the only Phase 2 path exercisable without a real upstream/network dependency -- the
 * account-found -> prepare -> single-POST path needs a real (or seeded) refresh token and hits
 * Google's OAuth endpoint via the self-assembled {@code UrlConnectionHttpClient}, so it is not
 * unit-tested here (see the Phase 2 report for the manual/self-review reasoning).
 */
class AntigravityProviderTest {

    @Test
    void handle_noAccountConfigured_returnsAnthropicAuthErrorWithoutNetworkCall(@TempDir Path configDir) {
        HttpRequest request = new HttpRequest();
        request.method = "POST";
        request.url = "/v1/messages";
        request.body = "{\"model\":\"antigravity-claude-sonnet-4-6\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

        HandlerCtx ctx = new HandlerCtx();
        ctx.configDir = configDir.toString();
        ctx.model = "antigravity-claude-sonnet-4-6";

        HttpResponse response = new AntigravityProvider().handle(request, ctx);

        assertEquals(401, response.status);
        assertEquals("application/json", response.headers.get("content-type"));
        assertTrue(response.body.contains("\"type\":\"authentication_error\""));
        assertTrue(response.body.contains("no antigravity account"));
    }

    @Test
    void id_isAntigravity() {
        assertEquals("antigravity", new AntigravityProvider().id());
    }
}
