package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.shared.routing.AccountQuota;
import io.github.intisy.ai.shared.routing.AuthorizeInfo;
import io.github.intisy.ai.shared.routing.ConfigSchema;
import io.github.intisy.ai.shared.routing.ConfigurableProvider;
import io.github.intisy.ai.shared.routing.HandleIrException;
import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.ModelCatalogProvider;
import io.github.intisy.ai.shared.routing.ModelInfo;
import io.github.intisy.ai.shared.routing.OAuthProvider;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.routing.QuotaProvider;
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
 *
 * <p>SP-E/E-D typed capability SPI: {@link #handle} now answers ONLY the messages orchestrator --
 * the former {@code GET /v1/models}/{@code GET /v1/quota} URL branches are retired in favor of
 * {@link ModelCatalogProvider#models}/{@link QuotaProvider#quota} (mechanical re-expose of the
 * existing {@link AntigravityModelsFetch}/{@link AntigravityQuotaFetch} logic as typed POJOs), and
 * {@link ConfigurableProvider}/{@link OAuthProvider} are genuinely NEW capabilities ported from the
 * TS {@code src/plugin/config/*} and {@code src/antigravity/oauth.ts} (see {@link AntigravityConfig}/
 * {@link AntigravityOAuth}). All four capabilities resolve their backend via {@link
 * AntigravityBackend#forCtx}, which serves from the host's injected {@link
 * io.github.intisy.ai.shared.spi.Store} rather than self-assembling a {@code FileStore}.
 */
public final class AntigravityProvider implements Provider, ConfigurableProvider, ModelCatalogProvider,
        QuotaProvider, OAuthProvider {

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

    // No real signature cache yet (Bucket C, deferred past Phase 2): every lookup is a cache miss.
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

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HttpResponse handle(HttpRequest request, HandlerCtx ctx) {
        // SP-E/E-D: the former GET /v1/models and GET /v1/quota URL branches are RETIRED here --
        // handle() now answers only the messages orchestrator. Discovery/quota-display is served
        // by the typed ModelCatalogProvider#models/QuotaProvider#quota capabilities below instead.
        String model = resolveModel(ctx);
        AntigravityBackend backend = AntigravityBackend.forCtx(ctx);
        Logger log = loggerFor(ctx);
        AntigravityHandleOrchestrator orchestrator = orchestratorFor(backend, log);

        AntigravityHandleOrchestrator.RequestInputs in = new AntigravityHandleOrchestrator.RequestInputs();
        // Phase 4: streamGenerateContent (SSE), not generateContent -- SERVE now bridges the
        // buffered Gemini SSE body to Anthropic (AntigravityGeminiSseBridge, SP-2). The TS bridge
        // (javaHandle.ts:358) targets generativelanguage.googleapis.com, but that host needs an API
        // key, not the OAuth account access tokens this provider's AccountOps supplies -- keep the
        // existing cloudcode-pa v1internal host (already used by every other endpoint here, see
        // AntigravityHandleRouting.endpointsFor) and only switch the verb; AntigravityRequestPrep
        // detects ":streamGenerateContent" via its own action regex and appends "?alt=sse" itself.
        in.url = "https://cloudcode-pa.googleapis.com/v1internal/models/" + model + ":streamGenerateContent";
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
     * SP-3 T2: the IR-native alternative to {@link #handle} -- receives an already app-wire-decoded
     * {@link IrRequest} (the Router's own {@code AnthropicTranslator.decodeRequest}) and runs the
     * SAME decision loop (account rotation, retry, project-context discovery, terminal-error
     * selection -- all in {@link AntigravityHandleOrchestrator}, untouched) via {@link
     * AntigravityHostSeams.HostIrRequestPreparer}, which applies antigravity's thinking-budget
     * resolution + the neutral IR-&gt;Gemini encode directly on {@code request} instead of decoding it
     * from Anthropic wire text a second time. On a SERVE decision the buffered upstream Gemini SSE is
     * decoded back into a single {@link IrResponse} via {@link
     * AntigravityGeminiSseBridge#bufferedGeminiSseToIr} -- no Anthropic re-encoding happens here, that
     * is now the caller's job. Every non-SERVE decision (rate-limited exhaustion, no-account,
     * transport failure, ...) has no IR-shaped representation to return, so it is thrown instead
     * (matching this SPI's contract: {@code handleIr} either returns a served answer or throws).
     */
    @Override
    public IrResponse handleIr(IrRequest request, HandlerCtx ctx) throws Exception {
        String model = resolveModel(ctx);
        AntigravityBackend backend = AntigravityBackend.forCtx(ctx);
        Logger log = loggerFor(ctx);

        // Not memoized (unlike orchestratorFor's per-backend ORCHESTRATORS map): the preparer here
        // closes over THIS call's `request`, which is per-call state, not per-backend state.
        AntigravityHandleOrchestrator orchestrator = buildOrchestrator(backend, log,
                new AntigravityHostSeams.HostIrRequestPreparer(backend, log, request));

        AntigravityHandleOrchestrator.RequestInputs in = new AntigravityHandleOrchestrator.RequestInputs();
        in.url = "https://cloudcode-pa.googleapis.com/v1internal/models/" + model + ":streamGenerateContent";
        in.method = "POST";
        in.headers = new LinkedHashMap<>();
        in.headers.put("content-type", "application/json");
        // No wire body to inspect here (the caller already decoded it into `request`) -- harmless:
        // modelFromRequest/resolveEffortVariant both read bodyText for signals (Gemini-native
        // generationConfig.thinkingConfig / opencode providerOptions) that never exist in the
        // Anthropic wire body handle() itself passes for this same call either, so this changes
        // nothing observable versus the legacy path.
        in.bodyText = "{}";
        in.ctxModel = model;
        in.autoCandidates = Collections.emptyList(); // TODO Phase 4: real Auto leaderboard candidates
        in.log = log;

        AntigravityHandleOrchestrator.HandleDecision decision = orchestrator.handle(in);
        switch (decision.kind) {
            case SERVE:
                if (decision.attemptRef instanceof HttpResponse) {
                    String requestedModel = decision.params != null ? decision.params.requestedModel : model;
                    return AntigravityGeminiSseBridge.bufferedGeminiSseToIr(
                            backend.json, requestedModel, (HttpResponse) decision.attemptRef);
                }
                throw asHandleIrException(errorResponse(502, "api_error", "antigravity upstream response missing"));
            case SERVE_RAW:
                // Real upstream response, verbatim status/headers/body -- same raw passthrough
                // materialize()'s own SERVE_RAW arm gives the legacy handle() path (no Anthropic
                // rewrap here either; that only happens for the SYNTHETIC/TERMINAL_ERROR cases below,
                // which are host-synthesized rather than a real upstream reply).
                throw asHandleIrException(upstreamResponseOrError(decision.attemptRef));
            case SYNTHETIC:
                // The no-account 503 / exhausted 502 the pool logic already synthesizes -- reuse
                // materializeSynthetic (same Anthropic-shape rewrap the legacy handle() path uses)
                // so the typed error carries the exact same status/headers/body.
                throw asHandleIrException(materializeSynthetic(backend.json, decision));
            case TERMINAL_ERROR:
                // The two lane-accurate exhaustion paths -- reuse materializeTerminal (same
                // Anthropic rate_limit_error body + Retry-After header as the legacy path), and
                // carry the SAME retryAfterMs the account-pool quota-reset math already computed
                // (AntigravityHandleOrchestrator.TerminalError.retryAfterMs) so the front door's
                // fallback logic gets an accurate reset hint.
                throw asHandleIrException(materializeTerminal(decision.terminal),
                        decision.terminal != null && decision.terminal.retryAfterMs > 0
                                ? decision.terminal.retryAfterMs : null);
            case BRIDGE_STREAM:
                // See materialize's BRIDGE_STREAM comment -- unreachable in practice, defensive only.
                throw asHandleIrException(upstreamResponseOrError(decision.attemptRef));
            default:
                throw asHandleIrException(errorResponse(502, "api_error", "unrecognized antigravity decision: " + decision.kind));
        }
    }

    // T3c-2: wraps an already-built HttpResponse (status/headers/body) as the canonical typed
    // transport error core-proxy's Router.route reconstructs a real HttpResponse from, instead of
    // collapsing every handleIr throw to a flat 502 (which lost status fidelity and broke
    // rate-limit fallback). Reuses the SAME response builders (materializeSynthetic/
    // materializeTerminal/upstreamResponseOrError/errorResponse) the legacy handle() path already
    // uses, so handleIr's error responses stay byte-identical to handle()'s.
    private static HandleIrException asHandleIrException(HttpResponse response) {
        return asHandleIrException(response, null);
    }

    private static HandleIrException asHandleIrException(HttpResponse response, Long retryAfterMs) {
        return new HandleIrException(response.status, response.headers, response.body, retryAfterMs);
    }

    // ---- ModelCatalogProvider / QuotaProvider: mechanical re-expose of the retired /v1/models
    // and /v1/quota JSON builders as typed POJOs -- AntigravityModelsFetch/AntigravityQuotaFetch
    // hold the only copy of the fetch/aggregate logic; these are thin conversions. ----------------

    @Override
    public List<ModelInfo> models(HandlerCtx ctx) {
        return AntigravityModelsFetch.models(AntigravityBackend.forCtx(ctx));
    }

    @Override
    public List<AccountQuota> quota(HandlerCtx ctx) {
        return AntigravityQuotaFetch.quota(AntigravityBackend.forCtx(ctx));
    }

    // ---- ConfigurableProvider: genuinely new capability, ported from src/plugin/config/*.ts ------

    @Override
    public ConfigSchema configSchema(HandlerCtx ctx) {
        return AntigravityConfig.schema();
    }

    @Override
    public Map<String, Object> getConfigValues(HandlerCtx ctx) {
        return AntigravityConfig.getValues(AntigravityBackend.forCtx(ctx));
    }

    @Override
    public Map<String, Object> putConfigValues(HandlerCtx ctx, Map<String, Object> values) {
        return AntigravityConfig.putValues(AntigravityBackend.forCtx(ctx), values);
    }

    // ---- OAuthProvider: genuinely new capability, ported from src/antigravity/oauth.ts -----------

    @Override
    public AuthorizeInfo authorize(HandlerCtx ctx) {
        return AntigravityOAuth.authorize();
    }

    @Override
    public Map<String, Object> exchange(HandlerCtx ctx, String body) {
        return AntigravityOAuth.exchange(AntigravityBackend.forCtx(ctx), body);
    }

    /**
     * One {@link AntigravityHandleOrchestrator} per backend, wiring the seven host seams from
     * {@link AntigravityHostSeams}. See the {@link #ORCHESTRATORS} javadoc for the one caveat
     * (the {@code Logger} baked into {@code HostRequestPreparer} is fixed at first construction).
     */
    private static AntigravityHandleOrchestrator orchestratorFor(AntigravityBackend backend, Logger log) {
        return ORCHESTRATORS.computeIfAbsent(backend,
                b -> buildOrchestrator(b, log, new AntigravityHostSeams.HostRequestPreparer(b, log)));
    }

    /**
     * Wires the six backend-scoped host seams (every seam but the {@link
     * AntigravityHandleOrchestrator.RequestPreparer}, which differs between the legacy Anthropic-wire
     * path and the IR-native path -- see {@link #handleIr}) around a caller-supplied preparer.
     */
    private static AntigravityHandleOrchestrator buildOrchestrator(AntigravityBackend backend, Logger log,
            AntigravityHandleOrchestrator.RequestPreparer preparer) {
        return new AntigravityHandleOrchestrator(
                backend.json, backend.clock, backend.random, ID_GENERATOR,
                new AntigravityHostSeams.HostAccountOps(backend),
                preparer,
                new AntigravityHostSeams.HostAttemptExecutor(backend),
                new AntigravityHostSeams.HostModelCacheLookup(backend),
                new AntigravityHostSeams.HostProjectLoader(backend),
                new AntigravityHostSeams.HostProjectOnboarder(backend),
                new AntigravityHostSeams.HostPlatform());
    }

    // ---- HandleDecision materialization (brief step 6) -------------------------------------------

    private static HttpResponse materialize(AntigravityHandleOrchestrator.HandleDecision d, JsonCodec json) {
        switch (d.kind) {
            case SERVE:
                // Phase 4 / SP-2: the example-server speaks Anthropic /v1/messages, so SERVE bridges
                // the buffered Gemini SSE upstream to Anthropic-shaped SSE via core-ir's translators
                // (AntigravityGeminiSseBridge, replacing the deleted AntigravityAnthropicBridge).
                // AntigravityResponseTransform stays in the tree untouched -- it is the Gemini-format
                // serve building block a future native-Gemini consumer may still want, just no
                // longer what SERVE itself returns.
                if (d.attemptRef instanceof HttpResponse) {
                    String requestedModel = d.params != null ? d.params.requestedModel : null;
                    return AntigravityGeminiSseBridge.bufferedGeminiSseToAnthropic(json, requestedModel, (HttpResponse) d.attemptRef);
                }
                return errorResponse(502, "api_error", "antigravity upstream response missing");
            case SERVE_RAW:
                // Phase 4: SERVE_RAW is untransformed by design (raw passthrough decisions from the
                // orchestrator) -- returns the retained upstream HttpResponse verbatim.
                return upstreamResponseOrError(d.attemptRef);
            case SYNTHETIC:
                return materializeSynthetic(json, d);
            case TERMINAL_ERROR:
                return materializeTerminal(d.terminal);
            case BRIDGE_STREAM:
                // Phase 4 shipped the Gemini->Anthropic SSE bridge (AntigravityGeminiSseBridge since
                // SP-2, wired into the SERVE case above); the orchestrator no longer routes here in
                // practice -- this arm stays as unreachable-defensive handling, matching SERVE_RAW's
                // verbatim-passthrough contract if it ever is reached.
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
        String message = terminalErrorMessage(terminal);

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

    private static String terminalErrorMessage(AntigravityHandleOrchestrator.TerminalError terminal) {
        return terminal.kind == AntigravityHandleOrchestrator.TerminalError.Kind.ANTIGRAVITY_QUOTA_RESET
                ? terminal.messagePrefix + formatResetInstant(terminal.resetEpochMs) + terminal.messageSuffix
                : terminal.messagePrefix;
    }

    // Phase 4 (handleAnthropicMessagesViaJava L361-383): a SYNTHETIC decision from the Gemini-path
    // orchestrator (attemptModel's no-account/exhausted-attempts 503/502, or handle's
    // all-Auto-candidates-exhausted 502) carries a plain Gemini-shaped {"error":{"message"}} body --
    // rewrap it as Anthropic error shape so an Anthropic client never sees the Gemini shape. A
    // rate-limit-classified status (429/503/529, AntigravityHandleRouting#isRateLimitStatus) becomes
    // rate_limit_error; anything else becomes api_error. The orchestrator's own status/headers are
    // preserved verbatim (brief: "Preserve the status the orchestrator chose").
    private static HttpResponse materializeSynthetic(JsonCodec json, AntigravityHandleOrchestrator.HandleDecision d) {
        String message = extractGeminiErrorMessage(json, d.body);
        String errorType = AntigravityHandleRouting.isRateLimitStatus(d.status) ? "rate_limit_error" : "api_error";
        HttpResponse response = new HttpResponse();
        response.status = d.status;
        response.headers = new LinkedHashMap<>(d.headers != null ? d.headers : Collections.<String, String>emptyMap());
        response.headers.put("content-type", "application/json");
        response.body = "{\"type\":\"error\",\"error\":{\"type\":" + quote(errorType) + ",\"message\":" + quote(message) + "}}";
        return response;
    }

    private static String extractGeminiErrorMessage(JsonCodec json, String body) {
        if (body == null || body.isEmpty()) {
            return "antigravity request failed";
        }
        try {
            Object parsed = json.parse(body);
            if (parsed instanceof Map) {
                Object err = ((Map<?, ?>) parsed).get("error");
                if (err instanceof Map) {
                    Object msg = ((Map<?, ?>) err).get("message");
                    if (msg instanceof String) {
                        return (String) msg;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // fall through -- treat an unparsable body as the message itself
        }
        return body;
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
