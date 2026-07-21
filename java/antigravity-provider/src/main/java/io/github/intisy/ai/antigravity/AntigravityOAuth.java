package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.routing.AuthorizeInfo;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A typed {@link io.github.intisy.ai.shared.routing.OAuthProvider} implementing antigravity's
 * Google OAuth flow ({@code authorizeAntigravity}/{@code exchangeAntigravity}, plus a
 * {@code fetchProjectID} helper). Mirrors claude-code-auth's {@code ClaudeOAuth} shape, but
 * antigravity is a LOOPBACK flow: the redirect URI is a local callback server, not a
 * paste-the-code flow. The OAuth client stays the SAME shared Google installed-app client
 * {@link AntigravityBackend} already uses for token refresh (see the antigravity-account-isolation
 * rule: removing/customizing it bricks refresh and worsens detection) -- {@code client_id} is
 * public and reused from {@link AntigravityBackend#ANTIGRAVITY_CLIENT_ID}; the client secret is
 * read from the {@code ANTIGRAVITY_CLIENT_SECRET} env var at call time and is NEVER logged,
 * returned, or hardcoded here.
 */
final class AntigravityOAuth {

    private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String REDIRECT_URI = "http://localhost:51121/oauth-callback";
    private static final int LOOPBACK_PORT = 51121;
    private static final String LOOPBACK_PATH = "/oauth-callback";
    private static final String[] SCOPES = {
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
    };
    // fetchProjectID/exchangeAntigravity both send this exact User-Agent, mirroring the gemini-cli client.
    private static final String GEMINI_CLI_USER_AGENT = "google-api-nodejs-client/9.15.1";

    private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RNG = new SecureRandom();

    private AntigravityOAuth() {
    }

    // ---- authorize -------------------------------------------------------------------------------

    static AuthorizeInfo authorize() {
        // PKCE S256: verifier is base64url(64 random bytes); challenge = base64url(sha256(verifier
        // UTF-8 bytes)) -- same construction as claude-code-auth's ClaudeOAuth. Any length in the
        // RFC 7636 range [43,128] chars is valid, and no fixture asserts an exact verifier length
        // since it is random on every call.
        byte[] raw = new byte[64];
        RNG.nextBytes(raw);
        String verifier = URL64.encodeToString(raw);
        String challenge = URL64.encodeToString(sha256(verifier.getBytes(StandardCharsets.UTF_8)));
        // This capability's signature carries no projectId input, so state always packs an empty
        // projectId.
        String state = encodeState(verifier);

        String url = AUTHORIZE_URL
                + "?client_id=" + enc(AntigravityBackend.ANTIGRAVITY_CLIENT_ID)
                + "&response_type=code"
                + "&redirect_uri=" + enc(REDIRECT_URI)
                + "&scope=" + enc(joinScopes())
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256"
                + "&state=" + enc(state)
                + "&access_type=offline"
                + "&prompt=consent";

        AuthorizeInfo info = new AuthorizeInfo();
        info.authorizeUrl = url;
        info.completion = "loopback";
        info.state = state;
        info.loopbackPort = LOOPBACK_PORT;
        info.loopbackPath = LOOPBACK_PATH;
        return info;
    }

    // ---- exchange --------------------------------------------------------------------------------

    /** Thin entrypoint used by the provider: parse {@code {code,state}} from the request body. */
    static Map<String, Object> exchange(AntigravityBackend backend, String requestBody) {
        Map<String, Object> body = asMap(backend.json.parse(requestBody != null ? requestBody : ""));
        String code = body != null ? stringOf(body.get("code")) : null;
        String state = body != null ? stringOf(body.get("state")) : null;
        return exchange(backend, code, state);
    }

    static Map<String, Object> exchange(AntigravityBackend backend, String code, String state) {
        StatePayload decoded;
        try {
            decoded = decodeState(state);
        } catch (RuntimeException e) {
            return errorMap("missing PKCE verifier in state");
        }

        long startTime = backend.clock.now();
        HttpRequest req = new HttpRequest();
        req.method = "POST";
        req.url = AntigravityBackend.GOOGLE_TOKEN_URL;
        req.headers = new LinkedHashMap<>();
        req.headers.put("content-type", "application/x-www-form-urlencoded;charset=UTF-8");
        req.headers.put("accept", "*/*");
        req.headers.put("user-agent", GEMINI_CLI_USER_AGENT);
        req.body = formUrlEncoded(
                "client_id", AntigravityBackend.ANTIGRAVITY_CLIENT_ID,
                "client_secret", clientSecret(),
                "code", code,
                "grant_type", "authorization_code",
                "redirect_uri", REDIRECT_URI,
                "code_verifier", decoded.verifier);

        HttpResponse resp;
        try {
            resp = backend.http.send(req);
        } catch (RuntimeException e) {
            return errorMap("antigravity token exchange failed");
        }
        if (resp.status / 100 != 2) {
            return errorMap("antigravity token endpoint returned " + resp.status);
        }

        Map<String, Object> payload = asMap(backend.json.parse(resp.body));
        String refreshToken = payload != null ? stringOf(payload.get("refresh_token")) : null;
        if (refreshToken == null) {
            return errorMap("missing refresh token in response");
        }
        String access = payload != null ? stringOf(payload.get("access_token")) : null;
        long expires = AntigravityAuth.calculateTokenExpiry(startTime, payload != null ? payload.get("expires_in") : null);

        String email = fetchEmail(backend, access);

        String projectId = decoded.projectId;
        if (projectId == null || projectId.isEmpty()) {
            projectId = fetchProjectID(backend, access);
        }

        AntigravityAuth.RefreshParts parts = new AntigravityAuth.RefreshParts();
        parts.refreshToken = refreshToken;
        parts.projectId = projectId != null ? projectId : "";
        String storedRefresh = AntigravityAuth.formatRefreshParts(parts);

        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", email != null ? email : refreshToken);
        if (email != null) account.put("email", email);
        account.put("refresh", storedRefresh);
        if (access != null) account.put("access", access);
        account.put("expires", expires);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", account);
        return result;
    }

    // ---- fetchProjectID ----------------------------------------------------------------------------

    // Never throws, never logs the access token: tries every antigravity endpoint (prod -> daily ->
    // autopush, the SAME order AntigravityHandleRouting.endpointsFor("antigravity") already uses --
    // reused rather than re-declared) and returns the first resolved project id, or "" if none.
    private static String fetchProjectID(AntigravityBackend backend, String access) {
        if (access == null) return "";
        String body = "{\"metadata\":{\"ideType\":\"ANTIGRAVITY\",\"platform\":\"" + platformTag() + "\",\"pluginType\":\"GEMINI\"}}";

        for (String endpoint : AntigravityHandleRouting.endpointsFor("antigravity")) {
            try {
                HttpRequest req = new HttpRequest();
                req.method = "POST";
                req.url = endpoint + "/v1internal:loadCodeAssist";
                req.headers = new LinkedHashMap<>();
                req.headers.put("authorization", "Bearer " + access);
                req.headers.put("content-type", "application/json");
                req.headers.put("user-agent", GEMINI_CLI_USER_AGENT);
                req.headers.put("client-metadata", "{\"ideType\":\"ANTIGRAVITY\",\"platform\":\"" + platformTag() + "\",\"pluginType\":\"GEMINI\"}");
                req.body = body;

                HttpResponse resp = backend.http.send(req);
                if (resp.status / 100 != 2) continue;
                String id = projectIdFrom(backend.json.parse(resp.body));
                if (id != null && !id.isEmpty()) return id;
            } catch (RuntimeException ignored) {
                // Try the next endpoint; never log the access token or a raw error that might echo one.
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static String projectIdFrom(Object parsed) {
        if (!(parsed instanceof Map)) return null;
        Object p = ((Map<String, Object>) parsed).get("cloudaicompanionProject");
        if (p instanceof String && !((String) p).isEmpty()) return (String) p;
        if (p instanceof Map) {
            Object id = ((Map<String, Object>) p).get("id");
            if (id instanceof String && !((String) id).isEmpty()) return (String) id;
        }
        return null;
    }

    // A real "linux" host also falls into the "MACOS" branch here: only win32 is distinguished.
    private static String platformTag() {
        return "win32".equals(AntigravityProvider.detectPlatform()) ? "WINDOWS" : "MACOS";
    }

    // ---- userinfo email lookup ---------------------------------------------------------------------

    // Never throws: a failed/absent email lookup must never fail the exchange itself.
    private static String fetchEmail(AntigravityBackend backend, String access) {
        if (access == null) return null;
        try {
            HttpRequest req = new HttpRequest();
            req.method = "GET";
            req.url = "https://www.googleapis.com/oauth2/v1/userinfo?alt=json";
            req.headers = new LinkedHashMap<>();
            req.headers.put("authorization", "Bearer " + access);
            req.headers.put("user-agent", GEMINI_CLI_USER_AGENT);
            HttpResponse resp = backend.http.send(req);
            if (resp.status / 100 != 2) return null;
            Object parsed = backend.json.parse(resp.body);
            if (parsed instanceof Map) {
                Object email = ((Map<?, ?>) parsed).get("email");
                return email instanceof String ? (String) email : null;
            }
        } catch (RuntimeException ignored) {
            // fall through -- treated the same as a non-ok userinfo response.
        }
        return null;
    }

    // ---- state encode/decode ------------------------------------------------------------------------

    private static final class StatePayload {
        final String verifier;
        final String projectId;

        StatePayload(String verifier, String projectId) {
            this.verifier = verifier;
            this.projectId = projectId;
        }
    }

    /** {@code encodeState({verifier, projectId: ""})}: base64url(JSON.stringify(...), "utf8"), no padding. */
    private static String encodeState(String verifier) {
        return URL64.encodeToString(("{\"verifier\":\"" + verifier + "\",\"projectId\":\"\"}").getBytes(StandardCharsets.UTF_8));
    }

    /** {@code decodeState()}: base64url (padded back to standard base64) -> JSON -> {verifier,projectId}. */
    private static StatePayload decodeState(String state) {
        if (state == null || state.isEmpty()) {
            throw new IllegalArgumentException("missing state");
        }
        String norm = state.replace('-', '+').replace('_', '/');
        int pad = (4 - (norm.length() % 4)) % 4;
        StringBuilder padded = new StringBuilder(norm);
        for (int i = 0; i < pad; i++) padded.append('=');
        String jsonText = new String(Base64.getDecoder().decode(padded.toString()), StandardCharsets.UTF_8);

        String verifier = extractJsonString(jsonText, "verifier");
        if (verifier == null) {
            throw new IllegalArgumentException("missing PKCE verifier in state");
        }
        String projectId = extractJsonString(jsonText, "projectId");
        return new StatePayload(verifier, projectId != null ? projectId : "");
    }

    // Minimal indexOf-based single-field string extractor (mirrors ClaudeOAuth's verifierFromState):
    // avoids a full JSON parse for this tiny, own-format-controlled payload.
    private static String extractJsonString(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int colon = json.indexOf(':', k);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        int q2 = q1 >= 0 ? json.indexOf('"', q1 + 1) : -1;
        return (q1 >= 0 && q2 > q1) ? json.substring(q1 + 1, q2) : null;
    }

    // --- misc helpers ---

    private static String joinScopes() {
        StringBuilder sb = new StringBuilder();
        for (String s : SCOPES) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
        return sb.toString();
    }

    private static String formUrlEncoded(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(kv[i])).append('=').append(enc(kv[i + 1] != null ? kv[i + 1] : ""));
        }
        return sb.toString();
    }

    // Read fresh from the environment on every call (never cached/logged) -- see class doc.
    private static String clientSecret() {
        return System.getenv("ANTIGRAVITY_CLIENT_SECRET");
    }

    private static Map<String, Object> errorMap(String message) {
        return Collections.<String, Object>singletonMap("error", message);
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String stringOf(Object o) {
        return o instanceof String ? (String) o : null;
    }
}
