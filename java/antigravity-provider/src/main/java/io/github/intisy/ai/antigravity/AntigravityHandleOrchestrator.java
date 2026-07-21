package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Random;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The DECISION state machine for serving a request. <b>Java owns every branch/retry/rotation/
 * fall-through decision; the TS host owns raw
 * transport</b> -- {@code fetch}, the IP-proxy pool (select/fallback/{@code reportResult}), the SSE
 * byte-stream, {@code transformAntigravityResponse}, the {@code geminiToAnthropicStream} pipe, {@code
 * chatError} + its locale date-formatting, {@code getAutoCandidates} and the version-feed refresh --
 * all injected or done host-side. <b>NO response BYTES ever cross into this class</b>: on a servable
 * outcome it returns only the host's opaque {@code attemptRef}; the host streams that attempt's
 * retained live response back verbatim.
 *
 * <p>Reuses {@link AntigravityLanes} (lane/header-style/rate-limit-reason/reset-time),
 * {@link AntigravityHandleRouting} (pure helpers), {@link AntigravityQuotaParser#accountHasQuota}
 * (proxy ip-suspected signal), {@link AntigravityProjectContext} (project discovery),
 * {@link AntigravityRequestPrep#generateSyntheticProjectId} and {@link AntigravityAuth}. Uses only
 * {@link JsonCodec}/{@link Clock}/{@link Random} + an {@link AntigravityRequestPrep.IdGenerator};
 * no gson/java.net/java.nio/reflection/threads/{@code System.currentTimeMillis}/locale APIs --
 * TeaVM-transpilable.
 */
public final class AntigravityHandleOrchestrator {

    // Total account/endpoint attempts before giving up.
    public static final int MAX_ATTEMPTS = 6;

    // Exact gemini-cli terminal wording (host wraps in chatError).
    public static final String GEMINI_CLI_EXHAUSTED_MESSAGE =
            "The Gemini CLI free pool is exhausted for this model. Pick another model or try again later.";
    // The antigravity quota-reset message, split around the host-formatted date.
    public static final String QUOTA_RESET_PREFIX =
            "All Antigravity accounts are rate-limited for this model. Quota resets ";
    public static final String QUOTA_RESET_SUFFIX = ". Try again later or pick another model.";

    private static final Logger NOOP_LOGGER = msg -> { };

    private final JsonCodec json;
    private final Clock clock;
    private final Random random;
    private final AntigravityRequestPrep.IdGenerator ids;
    private final AccountOps accounts;
    private final RequestPreparer preparer;
    private final AttemptExecutor executor;
    private final AntigravityHandleRouting.ModelCacheLookup modelCache;
    private final AntigravityProjectContext.ProjectLoader projectLoader;
    private final AntigravityProjectContext.ProjectOnboarder projectOnboarder;
    private final AntigravityProjectContext.Platform platform;

    public AntigravityHandleOrchestrator(JsonCodec json, Clock clock, Random random,
                                         AntigravityRequestPrep.IdGenerator ids,
                                         AccountOps accounts, RequestPreparer preparer, AttemptExecutor executor,
                                         AntigravityHandleRouting.ModelCacheLookup modelCache,
                                         AntigravityProjectContext.ProjectLoader projectLoader,
                                         AntigravityProjectContext.ProjectOnboarder projectOnboarder,
                                         AntigravityProjectContext.Platform platform) {
        this.json = json;
        this.clock = clock;
        this.random = random;
        this.ids = ids;
        this.accounts = accounts;
        this.preparer = preparer;
        this.executor = executor;
        this.modelCache = modelCache;
        this.projectLoader = projectLoader;
        this.projectOnboarder = projectOnboarder;
        this.platform = platform;
    }

    // ---- injected interfaces (host implements) --------------------------------------------------

    /**
     * core-auth's {@code AccountManager} account rotation/reporting, reduced to the calls
     * {@code attemptModel}/{@code resolveProjectId} make. Proxy selection + {@code
     * proxyManager.reportResult} are host-internal (inside {@link AttemptExecutor}); Java surfaces
     * only the {@code proxyManager.reportRateLimit(url,{ipSuspected})} DECISION via {@link
     * #reportProxyRateLimit} (the host owns the proxy URL it selected for this account).
     */
    public interface AccountOps {
        /** {@code manager.acquire(lane)}. {@code null}/blank id &lt;=&gt; {@code !acquired||!acquired.account}. */
        Acquired acquire(String lane);

        /** {@code manager.nextAvailableAt(lane)} -- epoch ms, or {@code null}. */
        Long nextAvailableAt(String lane);

        void reportError(String accountId, int attempt, String message);

        void reportRateLimit(String accountId, String lane, long resetMs);

        void reportSuccess(String accountId);

        /** {@code proxyManager.reportRateLimit(proxyUrl,{ipSuspected})} -- host owns the URL. */
        void reportProxyRateLimit(String accountId, boolean ipSuspected);

        /** {@code manager.list()} -- the stored accounts (each a plain JSON {@code Map}). */
        List<Map<String, Object>> list();

        /** {@code manager.mutate(accountId, mutator)}. */
        void mutate(String accountId, Mutator mutator);
    }

    /** {@code manager.mutate}'s callback -- mutates the stored account in place. */
    public interface Mutator {
        void apply(Map<String, Object> account);
    }

    /** {@code manager.acquire()} result: {@code {account, access}}. */
    public static final class Acquired {
        public final String accountId;
        public final String access;
        public final Map<String, Object> account;

        public Acquired(String accountId, String access, Map<String, Object> account) {
            this.accountId = accountId;
            this.access = access;
            this.account = account;
        }
    }

    /**
     * Builds the outbound request via the reused {@link AntigravityRequestPrep} spine -- kept
     * a host seam because {@code prepareAntigravityRequest}'s inputs include per-session singletons
     * ({@code getSessionFingerprint}, the {@code getRandomizedHeaders} version pool) whose
     * resolution must not leak into the DECISION orchestrator. The "prepare threw -&gt; skip this
     * endpoint" DECISION stays in Java (this is called in a try/catch).
     */
    public interface RequestPreparer {
        /** @throws RuntimeException on a prepare failure (Java catches it and skips the endpoint). */
        Prepared prepare(String url, String bodyText, String method, Map<String, String> headers,
                         String access, String projectId, String endpoint, String headerStyle,
                         Map<String, Object> account);
    }

    /** {@code prepareAntigravityRequest} result: an opaque request handle + the response-transform params. */
    public static final class Prepared {
        public final Object requestRef;
        public final TransformParams params;

        public Prepared(Object requestRef, TransformParams params) {
            this.requestRef = requestRef;
            this.params = params;
        }
    }

    /** The params {@code transformAntigravityResponse} needs -- carried on SERVE. */
    public static final class TransformParams {
        public final String requestedModel;
        public final String projectId;
        public final String endpoint;
        public final String effectiveModel;
        public final String sessionId;
        public final boolean streaming;

        public TransformParams(String requestedModel, String projectId, String endpoint,
                               String effectiveModel, String sessionId, boolean streaming) {
            this.requestedModel = requestedModel;
            this.projectId = projectId;
            this.endpoint = endpoint;
            this.effectiveModel = effectiveModel;
            this.sessionId = sessionId;
            this.streaming = streaming;
        }
    }

    /**
     * Executes ONE prepared request: {@code fetch} + IP-proxy select/fallback/{@code reportResult}
     * are ALL host-internal. Only the decision-relevant summary comes back --
     * the host extracts the rate-limit classification strings ({@code errorMessage}/{@code
     * errorReason}) from the body (unwrapping the cloudcode-pa {@code [{error}]} array); NO body
     * bytes cross into Java.
     */
    public interface AttemptExecutor {
        AttemptResult execute(String accountId, Object preparedRequestRef);
    }

    /** Result of one {@link AttemptExecutor#execute}. */
    public static final class AttemptResult {
        public final int status;
        public final boolean ok;
        /** Host exhausted proxy + direct fetch for this endpoint -&gt; skip endpoint. */
        public final boolean transportFailed;
        public final Object attemptRef;
        /** cloudcode-pa {@code error.message} (rate-limit body), or {@code null}. */
        public final String errorMessage;
        /** cloudcode-pa {@code error.status || error.reason}, or {@code null}. */
        public final String errorReason;
        /** {@code !!proxyUrl} -- the host selected a proxy for this account/attempt. */
        public final boolean proxyUsed;

        public AttemptResult(int status, boolean ok, boolean transportFailed, Object attemptRef,
                             String errorMessage, String errorReason, boolean proxyUsed) {
            this.status = status;
            this.ok = ok;
            this.transportFailed = transportFailed;
            this.attemptRef = attemptRef;
            this.errorMessage = errorMessage;
            this.errorReason = errorReason;
            this.proxyUsed = proxyUsed;
        }
    }

    // ---- HandleDecision -------------------------------------------------------------------------

    /**
     * The orchestrator's only output. {@link Kind#SERVE} = serve an ok upstream response through
     * {@code transformAntigravityResponse} (carries the transform params); {@link Kind#SERVE_RAW} =
     * serve a retained upstream response verbatim (a real 429/non-ok, or the transient-limit
     * passthrough); {@link Kind#SYNTHETIC} = a host-built {@code errorResponse} (the no-account 503 /
     * exhausted 502); {@link Kind#TERMINAL_ERROR} = the host assembles a {@code chatError} (the two
     * lane-accurate exhaustion paths); {@link Kind#BRIDGE_STREAM} = the Anthropic-bridge ok path, the
     * host pipes the response through {@code geminiToAnthropicStream}. No response BYTES ever flow
     * through this type -- {@link #attemptRef} is the host's own opaque handle.
     */
    public static final class HandleDecision {
        public enum Kind { SERVE, SERVE_RAW, SYNTHETIC, TERMINAL_ERROR, BRIDGE_STREAM }

        public final Kind kind;
        public final int status;
        public final Object attemptRef;
        public final TransformParams params;
        public final Map<String, String> headers;
        public final String body;
        public final TerminalError terminal;

        private HandleDecision(Kind kind, int status, Object attemptRef, TransformParams params,
                               Map<String, String> headers, String body, TerminalError terminal) {
            this.kind = kind;
            this.status = status;
            this.attemptRef = attemptRef;
            this.params = params;
            this.headers = headers;
            this.body = body;
            this.terminal = terminal;
        }

        public static HandleDecision serve(Object attemptRef, TransformParams params, int status) {
            return new HandleDecision(Kind.SERVE, status, attemptRef, params, null, null, null);
        }

        public static HandleDecision serveRaw(Object attemptRef, int status) {
            return new HandleDecision(Kind.SERVE_RAW, status, attemptRef, null, null, null, null);
        }

        public static HandleDecision synthetic(int status, Map<String, String> headers, String body) {
            return new HandleDecision(Kind.SYNTHETIC, status, null, null, headers, body, null);
        }

        public static HandleDecision terminalError(TerminalError terminal) {
            return new HandleDecision(Kind.TERMINAL_ERROR, terminal.status, null, null, null, null, terminal);
        }

        public static HandleDecision bridgeStream(Object attemptRef) {
            return new HandleDecision(Kind.BRIDGE_STREAM, 200, attemptRef, null, null, null, null);
        }
    }

    /**
     * A terminal {@code chatError} the host must assemble (Java owns the branch + the static message
     * text + the epoch; the host owns the {@code Date.toLocaleString} date-formatting + the {@code
     * chatError} call). Both variants pass {@code {format:"gemini", rateLimited:true}}.
     */
    public static final class TerminalError {
        public enum Kind { GEMINI_CLI_EXHAUSTED, ANTIGRAVITY_QUOTA_RESET }

        public final Kind kind;
        /** HTTP status for the chatError (400 default; the gemini format keeps 400 unless overridden). */
        public final int status;
        /** GEMINI_CLI_EXHAUSTED: the full message. ANTIGRAVITY_QUOTA_RESET: {@link #QUOTA_RESET_PREFIX}. */
        public final String messagePrefix;
        /** ANTIGRAVITY_QUOTA_RESET only: {@link #QUOTA_RESET_SUFFIX} (host inserts the formatted date between). */
        public final String messageSuffix;
        /** ANTIGRAVITY_QUOTA_RESET only: the epoch-ms the host formats via {@code toLocaleString}. */
        public final long resetEpochMs;
        /** ANTIGRAVITY_QUOTA_RESET only: {@code reset - Date.now()} for the {@code x-hub-retry-after-ms} header. */
        public final long retryAfterMs;

        private TerminalError(Kind kind, int status, String messagePrefix, String messageSuffix,
                              long resetEpochMs, long retryAfterMs) {
            this.kind = kind;
            this.status = status;
            this.messagePrefix = messagePrefix;
            this.messageSuffix = messageSuffix;
            this.resetEpochMs = resetEpochMs;
            this.retryAfterMs = retryAfterMs;
        }

        static TerminalError geminiCli() {
            return new TerminalError(Kind.GEMINI_CLI_EXHAUSTED, 400, GEMINI_CLI_EXHAUSTED_MESSAGE, null, 0, 0);
        }

        static TerminalError quotaReset(long resetEpochMs, long retryAfterMs) {
            return new TerminalError(Kind.ANTIGRAVITY_QUOTA_RESET, 400,
                    QUOTA_RESET_PREFIX, QUOTA_RESET_SUFFIX, resetEpochMs, retryAfterMs);
        }
    }

    /** Everything {@code handle} reads from the inbound request + router ctx (the Gemini path). */
    public static final class RequestInputs {
        public String url;
        public String method;
        public Map<String, String> headers;
        public String bodyText;
        /** {@code ctx.model} -- the router's assigned model. */
        public String ctxModel;
        /** {@code getAutoCandidates(PROVIDER_ID)} -- leaderboard stays host, PASSED IN. */
        public List<String> autoCandidates;
        /** {@code ctx.log}; {@code null} defaults to a no-op. */
        public Logger log;
    }

    // ---- handle --------------------------------------------------------------------------------

    /**
     * The Gemini-path {@code handle} (the {@code isAnthropicMessages} route + {@code
     * maybeMaintainVersions} fire-and-forget both stay host, see {@link #classifyAnthropicResult}):
     * resolve the requested model, build the Auto candidate walk, run each candidate through {@link
     * #attemptModel} falling through on a rate-limit, then produce the lane-accurate terminal error.
     */
    public HandleDecision handle(RequestInputs in) {
        Logger log = in.log != null ? in.log : NOOP_LOGGER;
        String requestedModel = AntigravityHandleRouting.modelFromRequest(in.url, in.bodyText, in.ctxModel, json);

        List<String> candidates = new ArrayList<>();
        candidates.add(requestedModel);
        if (AntigravityHandleRouting.isAutoModel(requestedModel)
                && in.autoCandidates != null && !in.autoCandidates.isEmpty()) {
            candidates = new ArrayList<>(in.autoCandidates);
        }

        HandleDecision lastOutcome = null;
        String lastModel = null;
        for (String model : candidates) {
            String effective = AntigravityHandleRouting.resolveEffortVariant(model, in.bodyText, modelCache, json, log);
            lastModel = effective;
            String candidateUrl = (!effective.equals(requestedModel) || candidates.size() > 1)
                    ? AntigravityHandleRouting.rewriteModelInUrl(in.url, effective) : in.url;
            HandleDecision outcome = attemptModel(effective, candidateUrl, in, log);
            lastOutcome = outcome;
            if (outcome == null || !AntigravityHandleRouting.isRateLimitStatus(outcome.status)) {
                return outcome; // success / non-retryable
            }
            if (candidates.size() > 1) {
                log.log("auto: " + model + " rate-limited (" + outcome.status + "); trying next candidate");
            }
        }

        // Everything rate-limited -- surface a lane-accurate TERMINAL error.
        if (lastOutcome != null && AntigravityHandleRouting.isRateLimitStatus(lastOutcome.status)) {
            String lane = AntigravityLanes.laneFor(lastModel != null ? lastModel : requestedModel);
            if ("gemini-cli".equals(lane)) {
                return HandleDecision.terminalError(TerminalError.geminiCli());
            }
            long reset = AntigravityHandleRouting.soonestQuotaReset(accounts.list());
            if (reset > 0) {
                return HandleDecision.terminalError(TerminalError.quotaReset(reset, reset - clock.now()));
            }
            return lastOutcome; // transient limit -- let the host retry with backoff
        }
        return lastOutcome != null ? lastOutcome
                : HandleDecision.synthetic(502, jsonHeaders(), errorBody("all antigravity Auto candidates exhausted"));
    }

    // ---- attemptModel ----------------------------------------------------------------------------

    /**
     * Runs ONE model through the account/endpoint attempt loop. A rate-limit outcome means "all
     * accounts for this model's lane are spent" so the Auto caller can fall through to the next.
     * Branch order: no-account 503, missing-access, per-endpoint prepare(catch skip),
     * execute, transport-failed skip, rate-limit (classify+report+proxy report), ok (success+SERVE),
     * non-ok fallback (never masking a real rate-limit).
     */
    public HandleDecision attemptModel(String model, String url, RequestInputs in, Logger log) {
        String lane = AntigravityLanes.laneFor(model);
        String headerStyle = AntigravityLanes.headerStyleFor(model);
        HandleDecision lastOutcome = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Acquired acquired = accounts.acquire(lane);
            if (acquired == null || acquired.accountId == null || acquired.accountId.isEmpty()) {
                Long next = accounts.nextAvailableAt(lane);
                long secs = next != null ? Math.max(0, Math.round((next - clock.now()) / 1000.0)) : 0;
                String msg = secs > 0
                        ? lane + " quota exhausted — resets in ~" + secs
                            + "s. Pick another model or use Auto (it falls through to a free pool)."
                        : "No available antigravity account for lane " + lane + ".";
                return HandleDecision.synthetic(503, jsonHeaders(), errorBody(msg));
            }

            String accountId = acquired.accountId;
            String access = acquired.access;
            Map<String, Object> account = acquired.account;
            if (!JsCoercion.isTruthy(access)) {
                accounts.reportError(accountId, attempt, "missing access token");
                continue;
            }

            String projectId = resolveProjectId(account, access, log);

            boolean rateLimited = false;
            for (String endpoint : AntigravityHandleRouting.endpointsFor(headerStyle)) {
                Prepared prepared;
                try {
                    prepared = preparer.prepare(url, in.bodyText, in.method, in.headers,
                            access, projectId, endpoint, headerStyle, account);
                } catch (RuntimeException e) {
                    log.log("prepare failed: " + e);
                    continue;
                }

                AttemptResult result = executor.execute(accountId, prepared.requestRef);
                if (result.transportFailed) {
                    continue; // host already tried proxy + direct; nothing to serve for this endpoint
                }

                if (AntigravityHandleRouting.isRateLimitStatus(result.status)) {
                    rateLimited = true;
                    lastOutcome = HandleDecision.serveRaw(result.attemptRef, result.status);
                    String parsed = AntigravityLanes.parseRateLimitReason(result.errorReason, result.errorMessage, result.status);
                    long retryAfterMs = AntigravityHandleRouting.retryAfterMsFromMessage(result.errorMessage);
                    long resetMs = AntigravityLanes.resetTimeFor(parsed, attempt, retryAfterMs, random, clock);
                    accounts.reportRateLimit(accountId, lane, resetMs);
                    if (result.proxyUsed) { // only when a proxy was selected
                        Map<String, Object> fresh = findAccount(accountId, account);
                        accounts.reportProxyRateLimit(accountId, AntigravityQuotaParser.accountHasQuota(fresh));
                    }
                    continue; // next endpoint, then rotate account
                }

                if (result.ok) {
                    accounts.reportSuccess(accountId);
                    return HandleDecision.serve(result.attemptRef, prepared.params, result.status);
                }

                // Non-ok, non-rate-limit (e.g. sandbox 403): keep as fallback, but NEVER overwrite a
                // real rate-limit lastResponse.
                if (lastOutcome == null || !AntigravityHandleRouting.isRateLimitStatus(lastOutcome.status)) {
                    lastOutcome = HandleDecision.serveRaw(result.attemptRef, result.status);
                }
            }

            if (!rateLimited) break;
        }

        return lastOutcome != null ? lastOutcome
                : HandleDecision.synthetic(502, jsonHeaders(),
                        errorBody("antigravity request failed after " + MAX_ATTEMPTS + " attempts"));
    }

    // ---- resolveProjectId / syntheticProjectFor -------------------------------------------------

    /**
     * Resolves the effective project id for an acquired account, persisting a newly discovered
     * managed id (via {@link AccountOps#mutate}). Proxy is host-internal to the {@link
     * AntigravityProjectContext.ProjectLoader}/{@link AntigravityProjectContext.ProjectOnboarder}
     * seams (they select the account's proxy), so {@code null} is passed here (disclosed). On any
     * failure the synthetic fallback project id is returned.
     */
    @SuppressWarnings("unchecked")
    public String resolveProjectId(Map<String, Object> account, String access, Logger log) {
        Object metaObj = account.get("meta");
        Map<String, Object> meta = metaObj instanceof Map ? (Map<String, Object>) metaObj : new LinkedHashMap<>();
        String fallbackProjectId = syntheticProjectFor(account);
        String projectId = String.valueOf(JsCoercion.firstTruthy(
                meta.get("managedProjectId"), meta.get("projectId"), ""));
        try {
            Map<String, Object> auth = AntigravityHandleRouting.buildAuth(account, access);
            AntigravityProjectContext.ProjectContextResult result = AntigravityProjectContext.ensureProjectContext(
                    auth, null, fallbackProjectId, projectLoader, projectOnboarder, platform);
            if (result != null && JsCoercion.isTruthy(result.effectiveProjectId)) {
                projectId = result.effectiveProjectId;
            }
            Object refresh = result != null && result.auth != null ? result.auth.get("refresh") : null;
            String discovered = AntigravityAuth.parseRefreshParts(
                    refresh != null ? String.valueOf(refresh) : null).managedProjectId;
            if (JsCoercion.isTruthy(discovered) && !discovered.equals(meta.get("managedProjectId"))) {
                final String discoveredId = discovered;
                accounts.mutate(String.valueOf(account.get("id")), a -> {
                    Object m = a.get("meta");
                    Map<String, Object> mm;
                    if (m instanceof Map) {
                        mm = (Map<String, Object>) m;
                    } else {
                        mm = new LinkedHashMap<>();
                        a.put("meta", mm);
                    }
                    mm.put("managedProjectId", discoveredId);
                });
            }
        } catch (RuntimeException error) {
            log.log("ensureProjectContext failed: " + error);
        }
        return JsCoercion.isTruthy(projectId) ? projectId : fallbackProjectId;
    }

    /**
     * A stable per-account synthetic project id (so accounts without a discovered managed project
     * never share the same {@code x-goog-user-project}). Generates + persists one via {@link
     * AccountOps#mutate} on first use.
     */
    @SuppressWarnings("unchecked")
    public String syntheticProjectFor(Map<String, Object> account) {
        Object metaObj = account.get("meta");
        Map<String, Object> meta = metaObj instanceof Map ? (Map<String, Object>) metaObj : null;
        Object existing = meta != null ? meta.get("syntheticProjectId") : null;
        if (JsCoercion.isTruthy(existing)) return String.valueOf(existing);
        String synthetic = AntigravityRequestPrep.generateSyntheticProjectId(ids, random);
        accounts.mutate(String.valueOf(account.get("id")), a -> {
            Object m = a.get("meta");
            Map<String, Object> mm;
            if (m instanceof Map) {
                mm = (Map<String, Object>) m;
            } else {
                mm = new LinkedHashMap<>();
                a.put("meta", mm);
            }
            mm.put("syntheticProjectId", synthetic);
        });
        return synthetic;
    }

    // ---- inner-result classification ------------------------------

    /**
     * The inner result the host extracts for classification. The host builds the Gemini request
     * (via {@link AntigravityIrBridge}) and drives transport; Java only classifies the outcome.
     */
    public static final class AnthropicInnerResult {
        /** {@code geminiRes != null}. */
        public boolean present;
        /** {@code geminiRes.ok}. */
        public boolean ok;
        /** {@code geminiRes.body} truthy. */
        public boolean hasBody;
        /** {@code geminiRes.status}. */
        public int status;
        /** {@code geminiRes.headers.get("x-hub-chat-error")} present. */
        public boolean chatError;
        /** {@code geminiRes.headers.get("x-hub-rate-limited") === "1"}. */
        public boolean rateLimited;
        /** {@code geminiRes.headers.get("x-hub-retry-after-ms")}, or {@code null}. */
        public String retryAfterMs;
        /** {@code error.message} parsed from the chatError body (host-extracted), or {@code null}. */
        public String extractedErrorMessage;
        /** the sliced upstream error text for the api_error path, or {@code null}. */
        public String detail;
        /** opaque handle for the ok (bridge-stream) path. */
        public Object attemptRef;
    }

    /**
     * Classifies the inner Gemini result into the outbound Anthropic-shaped decision: a chatError
     * becomes a 429 rate_limit_error (carrying {@code x-hub-rate-limited}/{@code -retry-after-ms}) or
     * a {@code status||400} invalid_request_error; a missing/non-ok/bodiless result becomes a
     * {@code status||502} api_error; an ok result signals the host to pipe through {@code
     * geminiToAnthropicStream}. Byte-exact bodies + header sets.
     */
    public HandleDecision classifyAnthropicResult(AnthropicInnerResult r) {
        if (r.present && r.chatError) {
            String msg = JsCoercion.isTruthy(r.extractedErrorMessage) ? r.extractedErrorMessage : "request failed";
            if (r.rateLimited) {
                Map<String, String> headers = jsonHeaders();
                headers.put("x-hub-rate-limited", "1");
                if (JsCoercion.isTruthy(r.retryAfterMs)) headers.put("x-hub-retry-after-ms", r.retryAfterMs);
                return HandleDecision.synthetic(429, headers, anthropicErrorBody("rate_limit_error", msg));
            }
            int status = r.status != 0 ? r.status : 400;
            return HandleDecision.synthetic(status, jsonHeaders(), anthropicErrorBody("invalid_request_error", msg));
        }
        if (!r.present || !r.ok || !r.hasBody) {
            String statusStr = r.present ? String.valueOf(r.status) : "null";
            String msg = JsCoercion.isTruthy(r.detail) ? r.detail : "antigravity upstream error " + statusStr;
            int status = (r.present && r.status != 0) ? r.status : 502;
            return HandleDecision.synthetic(status, jsonHeaders(), anthropicErrorBody("api_error", msg));
        }
        return HandleDecision.bridgeStream(r.attemptRef);
    }

    // ---- synthetic bodies + helpers -------------------------------------------------------------

    private Map<String, Object> findAccount(String accountId, Map<String, Object> fallback) {
        List<Map<String, Object>> list = accounts.list();
        if (list != null) {
            for (Map<String, Object> a : list) {
                if (a != null && accountId.equals(String.valueOf(a.get("id")))) return a;
            }
        }
        return fallback;
    }

    private Map<String, String> jsonHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("content-type", "application/json");
        return h;
    }

    /** {@code errorResponse(status, message)}: {@code {error:{message}}}. */
    private String errorBody(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return json.stringify(body);
    }

    /** {@code {type:"error", error:{type, message}}}. */
    private String anthropicErrorBody(String type, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", type);
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", error);
        return json.stringify(body);
    }
}
