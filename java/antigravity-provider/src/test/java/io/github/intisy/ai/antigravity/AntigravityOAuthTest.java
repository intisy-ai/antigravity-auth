package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.routing.AuthorizeInfo;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.spi.HttpClient;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP-E/E-D: {@link AntigravityOAuth} is a GENUINELY NEW capability (no {@code /v1/oauth/*} URL
 * branch existed in the Java provider before this migration), ported from {@code
 * src/antigravity/oauth.ts}. Covers {@code authorize()}'s loopback {@link AuthorizeInfo} shape,
 * {@code exchange()}'s happy path (token exchange + userinfo email lookup, skipping the
 * loadCodeAssist project-discovery call when the state already carries a project id) and its
 * failure paths (bad state, non-2xx token endpoint, missing refresh token) -- never a throw, never
 * a token/secret in the returned error map.
 */
class AntigravityOAuthTest {

    @Test
    void authorize_returnsLoopbackFlow_withPkceStateAndSharedClientId() {
        AuthorizeInfo a = new AntigravityProvider().authorize(new HandlerCtx());

        assertEquals("loopback", a.completion);
        assertEquals(Integer.valueOf(51121), a.loopbackPort);
        assertEquals("/oauth-callback", a.loopbackPath);
        assertNotNull(a.state);
        assertTrue(a.authorizeUrl.startsWith("https://accounts.google.com/o/oauth2/v2/auth"));
        assertTrue(a.authorizeUrl.contains(AntigravityBackend.ANTIGRAVITY_CLIENT_ID),
                "must use the SAME shared Google client id as token refresh, not a custom one");
        assertTrue(a.authorizeUrl.contains("code_challenge_method=S256"));
        assertFalse(a.authorizeUrl.toLowerCase().contains("client_secret"),
                "the client secret must never appear in the authorize URL");
    }

    @Test
    void authorize_isRandomizedPerCall() {
        AuthorizeInfo a1 = new AntigravityProvider().authorize(new HandlerCtx());
        AuthorizeInfo a2 = new AntigravityProvider().authorize(new HandlerCtx());
        assertNotEquals(a1.state, a2.state, "PKCE verifier/state must be freshly randomized every call");
    }

    @Test
    void exchange_happyPath_withPreConfiguredProjectId_skipsLoadCodeAssist(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"access_token\":\"acc-tok\",\"refresh_token\":\"ref-tok\",\"expires_in\":3600}")
                .enqueueOk(200, "{\"email\":\"user@example.com\"}");
        AntigravityBackend backend = AntigravityBackend.forTest(configDir.toString(), http);

        String state = encodeStateForTest("verifier-abc", "proj-configured");
        Map<String, Object> result = AntigravityOAuth.exchange(backend, "auth-code", state);

        assertFalse(result.containsKey("error"), "unexpected error: " + result);
        @SuppressWarnings("unchecked")
        Map<String, Object> account = (Map<String, Object>) result.get("account");
        assertEquals("user@example.com", account.get("id"));
        assertEquals("user@example.com", account.get("email"));
        assertEquals("ref-tok|proj-configured", account.get("refresh"));
        assertEquals("acc-tok", account.get("access"));
        assertTrue(((Number) account.get("expires")).longValue() > System.currentTimeMillis());

        // Only the token exchange + userinfo calls -- no loadCodeAssist, since the state already
        // carried a project id.
        assertEquals(2, http.requests.size());
        assertTrue(http.requests.get(0).url.contains("oauth2.googleapis.com/token"));
        assertTrue(http.requests.get(1).url.contains("userinfo"));

        // Never logs/returns the client secret: the token request body carries whatever
        // ANTIGRAVITY_CLIENT_SECRET resolves to (usually unset/empty in a test environment), and
        // no field of the returned account/error map is named/valued from that env var.
        assertFalse(result.toString().contains("GOCSPX"), "a real client secret literal must never appear in the result");
    }

    @Test
    void exchange_missingVerifierInState_returnsErrorMap_withoutAnyHttpCall(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient();
        AntigravityBackend backend = AntigravityBackend.forTest(configDir.toString(), http);

        Map<String, Object> result = AntigravityOAuth.exchange(backend, "auth-code", "not-a-valid-state");

        assertTrue(result.containsKey("error"));
        assertTrue(http.requests.isEmpty(), "a bad state must fail before any network call");
    }

    @Test
    void exchange_tokenEndpointNon2xx_returnsErrorMap_notAThrow(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient().enqueueOk(400, "{\"error\":\"invalid_grant\"}");
        AntigravityBackend backend = AntigravityBackend.forTest(configDir.toString(), http);

        String state = encodeStateForTest("verifier-abc", "proj-x");
        Map<String, Object> result = AntigravityOAuth.exchange(backend, "auth-code", state);

        assertTrue(result.containsKey("error"));
        assertEquals(1, http.requests.size());
    }

    @Test
    void exchange_missingRefreshToken_returnsErrorMap(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"access_token\":\"acc-tok\",\"expires_in\":3600}");
        AntigravityBackend backend = AntigravityBackend.forTest(configDir.toString(), http);

        String state = encodeStateForTest("verifier-abc", "proj-x");
        Map<String, Object> result = AntigravityOAuth.exchange(backend, "auth-code", state);

        assertTrue(result.containsKey("error"));
    }

    @Test
    void exchange_thinEntrypoint_parsesCodeAndStateFromRequestBody(@TempDir Path configDir) {
        ScriptedHttpClient http = new ScriptedHttpClient()
                .enqueueOk(200, "{\"access_token\":\"acc-tok\",\"refresh_token\":\"ref-tok\",\"expires_in\":3600}")
                .enqueueOk(200, "{}");
        AntigravityBackend backend = AntigravityBackend.forTest(configDir.toString(), http);
        String state = encodeStateForTest("verifier-abc", "proj-configured");

        Map<String, Object> result = AntigravityOAuth.exchange(
                backend, "{\"code\":\"auth-code\",\"state\":" + quote(state) + "}");

        assertFalse(result.containsKey("error"));
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Mirrors AntigravityOAuth's private encodeState(), but with an explicit (non-empty)
     *  projectId so a test can exercise the "state already carries a project id" branch without
     *  a network call to loadCodeAssist. */
    private static String encodeStateForTest(String verifier, String projectId) {
        String json = "{\"verifier\":\"" + verifier + "\",\"projectId\":\"" + projectId + "\"}";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    /** Scripted {@link HttpClient}: pops one queued response per {@link #send}, records every request. */
    private static final class ScriptedHttpClient implements HttpClient {
        private final Deque<HttpResponse> queue = new ArrayDeque<>();
        final List<HttpRequest> requests = new ArrayList<>();

        ScriptedHttpClient enqueueOk(int status, String body) {
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
