package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 3a of the JVM {@code AntigravityProvider} (see
 * {@code .superpowers/sdd/phase-3a-brief.md}): {@link #handle} now runs every request through the
 * fully-ported {@link AntigravityHandleOrchestrator} instead of the Phase 2 ad-hoc single POST --
 * retry/account-rotation/Auto-walk/terminal-error/synthetic-response all come from the
 * orchestrator's DECISION loop. This class only (1) builds/memoizes one orchestrator per {@link
 * AntigravityBackend} (wiring the seven host seams from {@link AntigravityHostSeams}), (2) builds
 * the orchestrator's {@code RequestInputs} from the incoming {@code HttpRequest}/{@code
 * HandlerCtx}, and (3) materializes the returned {@code HandleDecision} into an
 * {@code HttpResponse}. The Anthropic&harr;Gemini bridge + {@code AntigravityRequestPrep} spine
 * that Phase 2 ran inline here now live in {@link AntigravityHostSeams.HostRequestPreparer} (moved,
 * not duplicated). Response-body transform (Gemini-&gt;Anthropic) for a {@code SERVE} decision is
 * ported in Phase 3b via {@link AntigravityResponseTransform#transformServe}; {@code SERVE_RAW} and
 * {@code BRIDGE_STREAM} still return the retained upstream response verbatim (streaming/SSE is
 * Phase 4).
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
    static final String PLUGIN_SESSION_ID = "-" + UUID.randomUUID();

    // One orchestrator per backend (memoized): the orchestrator itself is stateless across
    // requests (RequestPreparer/AttemptExecutor take every per-request value as a method
    // parameter -- see AntigravityHostSeams), EXCEPT the Logger baked into HostRequestPreparer at
    // construction time, which is fixed to whichever ctx built the FIRST request for this backend
    // (a known Phase 3a limitation of the "memoize the whole orchestrator" design the brief
    // specifies; a later request's own ctx.log is still used for the orchestrator's OWN
    // RequestInputs.log, just not for AntigravityRequestPrep's internal warn sink).
    private static final ConcurrentHashMap<AntigravityBackend, AntigravityHandleOrchestrator> ORCHESTRATORS =
            new ConcurrentHashMap<>();

    // ---- seam singletons (reused by AntigravityHostSeams; see AntigravityRequestPrep.Deps javadoc) --

    static final AntigravityRequestPrep.IdGenerator ID_GENERATOR = () -> UUID.randomUUID().toString();

    static final AntigravityRequestKeys.Hasher SHA256_HASHER = AntigravityProvider::sha256Hex;

    // No real signature cache yet (Bucket C, deferred past Phase 2): every lookup is a cache miss,
    // matching the "ship a no-op to start" guidance for ThinkingRecovery/SignatureStore.
    static final AntigravityThinkingBlocks.CachedSignatureLookup NO_CACHED_SIGNATURE =
            (sessionId, text) -> null;

    static final AntigravityRequestSignatures.SignatureStore NOOP_SIGNATURE_STORE =
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

    static final AntigravityRequestPrep.ThinkingRecovery NOOP_THINKING_RECOVERY =
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
        AntigravityBackend backend = AntigravityBackend.forConfigDir(ctx != null ? ctx.configDir : null);
        Logger log = loggerFor(ctx);
        AntigravityHandleOrchestrator orchestrator = orchestratorFor(backend, log);

        AntigravityHandleOrchestrator.RequestInputs in = new AntigravityHandleOrchestrator.RequestInputs();
        in.url = "https://cloudcode-pa.googleapis.com/v1internal/models/" + model + ":generateContent";
        in.method = "POST";
        in.headers = new LinkedHashMap<>();
        in.headers.put("content-type", "application/json");
        in.bodyText = request != null ? request.body : null;
        in.ctxModel = model;
        in.autoCandidates = Collections.emptyList(); // TODO Phase 4: real Auto leaderboard candidates
        in.log = log;

        try {
            return materialize(orchestrator.handle(in), backend.json);
        } catch (RuntimeException e) {
            return errorResponse(502, "api_error", "antigravity request failed: " + e.getMessage());
        }
    }

    /**
     * One {@link AntigravityHandleOrchestrator} per backend, wiring the seven host seams from
     * {@link AntigravityHostSeams}. See the {@link #ORCHESTRATORS} javadoc for the one caveat
     * (the {@code Logger} baked into {@code HostRequestPreparer} is fixed at first construction).
     */
    private static AntigravityHandleOrchestrator orchestratorFor(AntigravityBackend backend, Logger log) {
        return ORCHESTRATORS.computeIfAbsent(backend, b -> new AntigravityHandleOrchestrator(
                b.json, b.clock, b.random, ID_GENERATOR,
                new AntigravityHostSeams.HostAccountOps(b),
                new AntigravityHostSeams.HostRequestPreparer(b, log),
                new AntigravityHostSeams.HostAttemptExecutor(b),
                new AntigravityHostSeams.HostModelCacheLookup(b),
                new AntigravityHostSeams.HostProjectLoader(b),
                new AntigravityHostSeams.HostProjectOnboarder(b),
                new AntigravityHostSeams.HostPlatform()));
    }

    // ---- HandleDecision materialization (brief step 6) -------------------------------------------

    private static HttpResponse materialize(AntigravityHandleOrchestrator.HandleDecision d, JsonCodec json) {
        switch (d.kind) {
            case SERVE:
                if (d.attemptRef instanceof HttpResponse) {
                    return AntigravityResponseTransform.transformServe(json, (HttpResponse) d.attemptRef, d.params);
                }
                return errorResponse(502, "api_error", "antigravity upstream response missing");
            case SERVE_RAW:
                // Phase 4: SERVE_RAW is untransformed by design (raw passthrough decisions from the
                // orchestrator) -- returns the retained upstream HttpResponse verbatim.
                return upstreamResponseOrError(d.attemptRef);
            case SYNTHETIC:
                HttpResponse synthetic = new HttpResponse();
                synthetic.status = d.status;
                synthetic.headers = new LinkedHashMap<>(d.headers != null ? d.headers : Collections.emptyMap());
                synthetic.body = d.body;
                return synthetic;
            case TERMINAL_ERROR:
                return materializeTerminal(d.terminal);
            case BRIDGE_STREAM:
                // TODO Phase 4: pipe the retained response through a Gemini->Anthropic SSE bridge
                // (geminiToAnthropicStream). 3a returns the retained upstream body verbatim.
                return upstreamResponseOrError(d.attemptRef);
            default:
                return errorResponse(502, "api_error", "unrecognized antigravity decision: " + d.kind);
        }
    }

    private static HttpResponse upstreamResponseOrError(Object attemptRef) {
        if (attemptRef instanceof HttpResponse) {
            return (HttpResponse) attemptRef;
        }
        return errorResponse(502, "api_error", "antigravity upstream response missing");
    }

    // The two lane-accurate exhaustion messages (index.ts:432,439-440, mirrored by
    // AntigravityHandleOrchestrator.TerminalError): GEMINI_CLI_EXHAUSTED carries its full message
    // as messagePrefix; ANTIGRAVITY_QUOTA_RESET splits the message around the host-formatted date.
    private static HttpResponse materializeTerminal(AntigravityHandleOrchestrator.TerminalError terminal) {
        String message = terminal.kind == AntigravityHandleOrchestrator.TerminalError.Kind.ANTIGRAVITY_QUOTA_RESET
                ? terminal.messagePrefix + formatResetInstant(terminal.resetEpochMs) + terminal.messageSuffix
                : terminal.messagePrefix;

        HttpResponse response = new HttpResponse();
        response.status = terminal.status > 0 ? terminal.status : 400;
        response.headers = new LinkedHashMap<>();
        response.headers.put("content-type", "application/json");
        if (terminal.retryAfterMs > 0) {
            response.headers.put("Retry-After", String.valueOf(Math.max(0, terminal.retryAfterMs / 1000)));
        }
        response.body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":"
                + quote(message) + "}}";
        return response;
    }

    // index.ts:459's `toLocaleString(undefined, {month:"short",day:"numeric",hour:"numeric",
    // minute:"2-digit"})` -- a JVM-side, locale-formatted stand-in (not byte-exact; disclosed
    // deviation, no fixture asserts the exact date rendering, only that the reset wording is present).
    private static String formatResetInstant(long resetEpochMs) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.getDefault());
        return Instant.ofEpochMilli(resetEpochMs).atZone(ZoneId.systemDefault()).format(formatter);
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
    static Map<String, Object> parseAnthropicBody(io.github.intisy.ai.shared.spi.JsonCodec json, String body) {
        if (body == null || body.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = json.parse(body);
            return parsed instanceof Map ? (Map<String, Object>) parsed : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fingerprintFromMeta(Map<String, Object> meta) {
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
    static Map<String, Object> defaultSelectedHeaders() {
        String version = AntigravityVersions.FALLBACK_VERSIONS.isEmpty()
                ? "1.0.0" : AntigravityVersions.FALLBACK_VERSIONS.get(0);
        String platform = detectPlatform();
        String arch = System.getProperty("os.arch", "x64");
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "antigravity/" + version + " " + platform + "/" + arch);
        return headers;
    }

    static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac")) {
            return "darwin";
        }
        return "linux";
    }

    static Map<String, String> stringifyHeaders(Map<String, Object> headers) {
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

    static HttpResponse errorResponse(int status, String errorType, String message) {
        HttpResponse response = new HttpResponse();
        response.status = status;
        response.headers = new LinkedHashMap<>();
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
