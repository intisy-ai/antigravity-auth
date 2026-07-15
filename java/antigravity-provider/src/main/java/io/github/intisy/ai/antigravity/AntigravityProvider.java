package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.manager.Acquired;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 2 of the JVM {@code AntigravityProvider} (see
 * {@code docs/superpowers/plans/2026-07-15-jvm-antigravity-claude-providers.md}): {@link #handle}
 * now self-assembles a real backend ({@link AntigravityBackend}, plan DECISION FLAG A),
 * acquires an antigravity account via its {@code AccountManager}, prepares the outbound request
 * with the already-ported {@link AntigravityRequestPrep}, and does ONE non-streaming POST via the
 * {@link io.github.intisy.ai.shared.spi.HttpClient} SPI -- returning the raw upstream response
 * (status/headers/body) verbatim. There is no retry/rotation loop yet and no response transform
 * back to Anthropic shape (both are {@code AntigravityHandleOrchestrator} + a new
 * {@code AntigravityResponseTransform}, Phase 3); a request with no account configured returns a
 * clear Anthropic-shaped auth error rather than attempting any network call.
 *
 * <p>The incoming {@code HttpRequest} is the host's Anthropic {@code /v1/messages} request (same
 * shape the Phase 1 skeleton answered with a canned body); it is bridged to a Gemini
 * {@code generateContent} body via the already-ported {@link AntigravityFormatBridge} before being
 * run through {@link AntigravityRequestPrep}, mirroring how {@code src/driver/index.ts} bridges
 * Anthropic requests through the same Gemini decision path.
 *
 * <p>Shape discipline: {@code compileOnly project(":routing")} + {@code compileOnly
 * "io.github.intisy:jvm:0.1.0"} keep this module's own jar THIN (no {@code :routing}/{@code :jvm}
 * classes bundled -- the host's {@code ProviderRegistry} classloader already has them); only the
 * seam glue lives here, no {@code AntigravityRequestPrep}/{@code AntigravityHandleOrchestrator}
 * logic is duplicated.
 */
public final class AntigravityProvider implements Provider {

    /** The provider id this instance serves; matches the {@code provider} field in a model-map assignment. */
    public static final String ID = "antigravity";

    private static final String DEFAULT_MODEL_FALLBACK = "antigravity-default";

    // request.ts:77 -- PLUGIN_SESSION_ID is a TS module-load-once constant; mirrored here as a
    // static field initialized once per JVM (this class is loaded once per host process).
    private static final String PLUGIN_SESSION_ID = "-" + UUID.randomUUID();

    // ---- seam singletons (Phase 2 minimal impls; see AntigravityRequestPrep.Deps javadoc) --------

    private static final AntigravityRequestPrep.IdGenerator ID_GENERATOR = () -> UUID.randomUUID().toString();

    private static final AntigravityRequestKeys.Hasher SHA256_HASHER = AntigravityProvider::sha256Hex;

    // No real signature cache yet (Bucket C, deferred past Phase 2): every lookup is a cache miss,
    // matching the "ship a no-op to start" guidance for ThinkingRecovery/SignatureStore.
    private static final AntigravityThinkingBlocks.CachedSignatureLookup NO_CACHED_SIGNATURE =
            (sessionId, text) -> null;

    private static final AntigravityRequestSignatures.SignatureStore NOOP_SIGNATURE_STORE =
            new AntigravityRequestSignatures.SignatureStore() {
                @Override
                public Map<String, Object> get(String key) {
                    return null;
                }

                @Override
                public boolean has(String key) {
                    return false;
                }

                @Override
                public void delete(String key) {
                    // no-op
                }
            };

    private static final AntigravityRequestPrep.ThinkingRecovery NOOP_THINKING_RECOVERY =
            new AntigravityRequestPrep.ThinkingRecovery() {
                @Override
                public Object analyzeConversationState(List<Object> contents) {
                    return null;
                }

                @Override
                public boolean needsThinkingRecovery(Object state) {
                    return false;
                }

                @Override
                public List<Object> closeToolLoopForThinking(List<Object> contents) {
                    return contents;
                }
            };

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HttpResponse handle(HttpRequest request, HandlerCtx ctx) {
        String model = resolveModel(ctx);
        String lane = AntigravityLanes.laneFor(model);
        String headerStyle = AntigravityLanes.headerStyleFor(model);

        AntigravityBackend backend = AntigravityBackend.forConfigDir(ctx != null ? ctx.configDir : null);

        Acquired acquired;
        try {
            acquired = backend.accounts.acquire(lane);
        } catch (RuntimeException e) {
            return errorResponse(502, "api_error", "antigravity account refresh failed: " + e.getMessage());
        }
        if (acquired == null || acquired.account == null) {
            return noAccountError(model);
        }

        Map<String, Object> meta = acquired.account.meta;
        String projectId = projectIdFromMeta(meta);
        Map<String, Object> fingerprint = fingerprintFromMeta(meta);

        Map<String, Object> anthropicBody = parseAnthropicBody(backend, request);
        Map<String, Object> geminiBody = AntigravityFormatBridge.anthropicToGemini(
                backend.json, anthropicBody, model, AntigravitySchemaCleaner::clean);

        AntigravityRequestPrep.Input input = new AntigravityRequestPrep.Input();
        input.url = "https://cloudcode-pa.googleapis.com/v1internal/models/" + model + ":generateContent";
        input.method = "POST";
        input.headers = new LinkedHashMap<>();
        input.body = backend.json.stringify(geminiBody);
        input.accessToken = acquired.access;
        input.projectId = projectId;
        input.endpointOverride = null;
        input.headerStyle = headerStyle;
        input.forceThinkingRecovery = false;
        input.claudeToolHardening = null; // Input javadoc: null -> defaults to true, matches TS ?? true
        input.claudePromptAutoCaching = false; // config default (schema.ts claude_prompt_auto_caching)
        input.fingerprint = fingerprint;
        input.imageAspectRatio = null;

        AntigravityRequestPrep.Deps deps = new AntigravityRequestPrep.Deps();
        deps.json = backend.json;
        deps.ids = ID_GENERATOR;
        deps.random = backend.random;
        deps.hasher = SHA256_HASHER;
        deps.cachedSignatureLookup = NO_CACHED_SIGNATURE;
        deps.signatureStore = NOOP_SIGNATURE_STORE;
        deps.thinkingRecovery = NOOP_THINKING_RECOVERY;
        deps.logger = loggerFor(ctx);
        deps.keepThinking = false; // config default (schema.ts keep_thinking: false)
        deps.pluginSessionId = PLUGIN_SESSION_ID;
        deps.selectedHeaders = defaultSelectedHeaders();

        AntigravityRequestPrep.PrepareResult prepared;
        try {
            prepared = AntigravityRequestPrep.prepare(input, deps);
        } catch (RuntimeException e) {
            return errorResponse(502, "api_error", "antigravity request prepare failed: " + e.getMessage());
        }

        HttpRequest outbound = new HttpRequest();
        outbound.method = "POST";
        outbound.url = String.valueOf(prepared.request);
        outbound.headers = stringifyHeaders(prepared.headers);
        outbound.body = prepared.body != null ? String.valueOf(prepared.body) : null;

        try {
            // Phase 2: a single non-streaming attempt, no rotation/retry -- on a non-OK upstream
            // status the raw upstream error body is returned as-is (Phase 3 adds the full
            // AntigravityHandleOrchestrator decision loop + response transform).
            return backend.http.send(outbound);
        } catch (RuntimeException e) {
            return errorResponse(502, "api_error", "antigravity upstream request failed: " + e.getMessage());
        }
    }

    // ctx.model (the tier-resolved assignment) wins when present -- mirrors StubProvider/
    // EchoProvider precedence. Absent that, fall back to the first entry of the already-ported
    // AntigravityCatalog's Gemini CLI model list (cheap, self-contained, no network) rather than a
    // bare literal, so the fallback model id is at least a real antigravity-known one.
    private static String resolveModel(HandlerCtx ctx) {
        if (ctx != null && ctx.model != null && !ctx.model.isEmpty()) {
            return ctx.model;
        }
        List<AntigravityCatalog.GeminiCliModel> models = AntigravityCatalog.GEMINI_CLI_MODELS;
        if (!models.isEmpty() && models.get(0).id != null) {
            return models.get(0).id;
        }
        return DEFAULT_MODEL_FALLBACK;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseAnthropicBody(AntigravityBackend backend, HttpRequest request) {
        String body = request != null ? request.body : null;
        if (body == null || body.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = backend.json.parse(body);
            return parsed instanceof Map ? (Map<String, Object>) parsed : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    // account.meta shape (plan DECISION FLAG C): managedProjectId wins over projectId, matching
    // AntigravityHandleOrchestrator's own meta.managedProjectId/meta.projectId precedence. The full
    // loadCodeAssist/onboard discovery loop (AntigravityProjectContext) is Phase 3 -- absent both,
    // AntigravityRequestPrep.prepare falls back to generateSyntheticProjectId itself.
    private static String projectIdFromMeta(Map<String, Object> meta) {
        if (meta == null) {
            return "";
        }
        Object managed = meta.get("managedProjectId");
        if (managed instanceof String && !((String) managed).isEmpty()) {
            return (String) managed;
        }
        Object projectId = meta.get("projectId");
        return projectId instanceof String ? (String) projectId : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fingerprintFromMeta(Map<String, Object> meta) {
        if (meta == null) {
            return null;
        }
        Object fingerprint = meta.get("fingerprint");
        return fingerprint instanceof Map ? (Map<String, Object>) fingerprint : null;
    }

    private static Logger loggerFor(HandlerCtx ctx) {
        Logger log = ctx != null ? ctx.log : null;
        return log != null ? log : (msg -> { });
    }

    // fingerprint.ts:109's `antigravity/${version} ${platform}/${arch}` User-Agent, built from a
    // fixed sane default (newest curated version + real platform/arch) rather than the Bucket-C
    // session-fingerprint/version-drift singletons (deferred past Phase 2).
    private static Map<String, Object> defaultSelectedHeaders() {
        String version = AntigravityVersions.FALLBACK_VERSIONS.isEmpty()
                ? "1.0.0" : AntigravityVersions.FALLBACK_VERSIONS.get(0);
        String platform = detectPlatform();
        String arch = System.getProperty("os.arch", "x64");
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "antigravity/" + version + " " + platform + "/" + arch);
        return headers;
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac")) {
            return "darwin";
        }
        return "linux";
    }

    private static Map<String, String> stringifyHeaders(Map<String, Object> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (entry.getValue() != null) {
                out.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return out;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // The has-no-account path (Phase 2 scope): a clear Anthropic-shaped auth error instead of
    // attempting any network call. Real login/account-seeding is Phase 5.
    private static HttpResponse noAccountError(String model) {
        return errorResponse(401, "authentication_error",
                "no antigravity account available for model " + model
                        + " -- sign in (antigravity-accounts) or seed config/accounts.json");
    }

    private static HttpResponse errorResponse(int status, String errorType, String message) {
        HttpResponse response = new HttpResponse();
        response.status = status;
        response.headers = new HashMap<>();
        response.headers.put("content-type", "application/json");
        response.body = "{\"type\":\"error\",\"error\":{\"type\":" + quote(errorType) + ",\"message\":" + quote(message) + "}}";
        return response;
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
