package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.seam.NoopLogger;
import io.github.intisy.ai.api.seam.Clock;
import io.github.intisy.ai.api.seam.JsonCodec;
import io.github.intisy.ai.api.seam.Logger;
import io.github.intisy.ai.api.seam.Random;

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

    /** How many account and endpoint attempts one request makes before giving up. */
    public static final int MAX_ATTEMPTS = 6;

    /** What the user is told when the free pool has nothing left for this model. */
    public static final String GEMINI_CLI_EXHAUSTED_MESSAGE =
            "The Gemini CLI free pool is exhausted for this model. Pick another model or try again later.";
    /** What comes before the reset time the host formats. */
    public static final String QUOTA_RESET_PREFIX =
            "All Antigravity accounts are rate-limited for this model. Quota resets ";
    /** What comes after it. */
    public static final String QUOTA_RESET_SUFFIX = ". Try again later or pick another model.";

    private static final Logger NOOP_LOGGER = NoopLogger.INSTANCE;

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

    /**
     * One orchestrator, wired to the host seams it makes its decisions through.
     *
     * @param json the codec every parse and write goes through
     * @param clock the injected clock
     * @param random the host's entropy
     * @param ids the host's id minting
     * @param accounts the host's account rotation and reporting
     * @param preparer the host's request preparation
     * @param executor the host's transport
     * @param modelCache the host's catalog read
     * @param projectLoader the host's managed-project fetch
     * @param projectOnboarder the host's managed-project provisioning
     * @param platform the host platform and architecture
     */
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
        /**
         * Takes the next account that can serve one lane.
         *
         * @param lane the lane to serve on
         * @return the account and its access token, or {@code null} when none is free
         */
        Acquired acquire(String lane);

        /**
         * When a lane's soonest account comes back.
         *
         * @param lane the lane being asked about
         * @return the epoch-millisecond time, or {@code null} when nothing is waiting
         */
        Long nextAvailableAt(String lane);

        /**
         * One attempt failed for a reason that is not a rate limit.
         *
         * @param accountId the account that failed
         * @param lane the lane it failed on
         * @param attempt which attempt this was, counting from one
         * @param message what went wrong
         */
        void reportError(String accountId, String lane, int attempt, String message);

        /**
         * One attempt hit the upstream rate limit.
         *
         * @param accountId the account that was limited
         * @param lane the lane it was limited on
         * @param resetMs when the limit resets, in epoch milliseconds
         */
        void reportRateLimit(String accountId, String lane, long resetMs);

        /**
         * One attempt served the request.
         *
         * @param accountId the account that served it
         */
        void reportSuccess(String accountId);

        /**
         * An attempt failed in a way that implicates the outbound address rather than the account.
         *
         * @param accountId the account the attempt used, whose proxy the host knows
         * @param ipSuspected whether the address is the likely cause
         */
        void reportProxyRateLimit(String accountId, boolean ipSuspected);

        /**
         * Every account the host currently holds.
         *
         * @return the accounts, each a plain JSON tree
         */
        List<Map<String, Object>> list();

        /**
         * Persists the fields a mutator changes on one account.
         *
         * @param accountId the account to write back
         * @param mutator what to change on it
         */
        void mutate(String accountId, Mutator mutator);
    }

    /** {@code manager.mutate}'s callback -- mutates the stored account in place. */
    public interface Mutator {
        /**
         * Changes one stored account in place.
         *
         * @param account the account to change
         */
        void apply(Map<String, Object> account);
    }

    /** {@code manager.acquire()} result: {@code {account, access}}. */
    public static final class Acquired {
        /** The account that was taken. */
        public final String accountId;
        /** Its access token. */
        public final String access;
        /** The whole account, so the project resolution can read and change its metadata. */
        public final Map<String, Object> account;

        /**
         * One acquisition.
         *
         * @param accountId the account that was taken
         * @param access its access token
         * @param account the whole account
         */
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
        /**
         * One request, prepared for one account and endpoint.
         *
         * @param url the request url
         * @param bodyText the request body
         * @param method the request method
         * @param headers the caller's headers
         * @param access the account's access token
         * @param projectId the project the request is billed to
         * @param endpoint the endpoint this attempt uses
         * @param headerStyle which header set that endpoint expects
         * @param account the whole account, whose fingerprint the preparation reads
         * @return an opaque handle to what was prepared, plus the transform parameters
         * @throws RuntimeException on a prepare failure, which skips this endpoint
         */
        Prepared prepare(String url, String bodyText, String method, Map<String, String> headers,
                         String access, String projectId, String endpoint, String headerStyle,
                         Map<String, Object> account);
    }

    /** {@code prepareAntigravityRequest} result: an opaque request handle + the response-transform params. */
    public static final class Prepared {
        /** The host's own opaque handle to what it prepared; no request bytes cross here. */
        public final Object requestRef;
        /** What the response transform will need to know. */
        public final TransformParams params;

        /**
         * One prepared attempt.
         *
         * @param requestRef the host's opaque handle
         * @param params what the response transform will need
         */
        public Prepared(Object requestRef, TransformParams params) {
            this.requestRef = requestRef;
            this.params = params;
        }
    }

    /** The params {@code transformAntigravityResponse} needs -- carried on SERVE. */
    public static final class TransformParams {
        /** The model the caller asked for. */
        public final String requestedModel;
        /** The project the request was billed to. */
        public final String projectId;
        /** The endpoint it went to. */
        public final String endpoint;
        /** The model it was served as. */
        public final String effectiveModel;
        /** The key this session's signatures are stored under. */
        public final String sessionId;
        /** Whether the upstream streamed its answer. */
        public final boolean streaming;

        /**
         * What one served response's transform needs to know.
         *
         * @param requestedModel the model the caller asked for
         * @param projectId the project the request was billed to
         * @param endpoint the endpoint it went to
         * @param effectiveModel the model it was served as
         * @param sessionId the key this session's signatures are stored under
         * @param streaming whether the upstream streamed its answer
         */
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
        /**
         * Sends one prepared request.
         *
         * @param accountId the account it is sent for
         * @param preparedRequestRef the host's own opaque handle to what it prepared
         * @return what the upstream answered, without its bytes
         */
        AttemptResult execute(String accountId, Object preparedRequestRef);
    }

    /** Result of one {@link AttemptExecutor#execute}. */
    public static final class AttemptResult {
        /** The HTTP status the upstream answered with, or 0 when it never answered. */
        public final int status;
        /** Whether the answer was a success. */
        public final boolean ok;
        /** Host exhausted proxy + direct fetch for this endpoint -&gt; skip endpoint. */
        public final boolean transportFailed;
        /** The host's own opaque handle to the retained response. */
        public final Object attemptRef;
        /** cloudcode-pa {@code error.message} (rate-limit body), or {@code null}. */
        public final String errorMessage;
        /** cloudcode-pa {@code error.status || error.reason}, or {@code null}. */
        public final String errorReason;
        /** {@code !!proxyUrl} -- the host selected a proxy for this account/attempt. */
        public final boolean proxyUsed;

        /**
         * What one attempt came back with.
         *
         * @param status the HTTP status, or 0 when the upstream never answered
         * @param ok whether it was a success
         * @param transportFailed whether the request never reached the upstream at all
         * @param attemptRef the host's opaque handle to the retained response
         * @param errorMessage what the upstream said went wrong, or {@code null}
         * @param errorReason how the upstream classified it, or {@code null}
         * @param proxyUsed whether the host sent this attempt through a proxy
         */
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
        /** Which of the five answers this decision is. */
        public enum Kind {
            /** Serve a successful upstream response through the transform. */
            SERVE,
            /** Serve a retained upstream response verbatim. */
            SERVE_RAW,
            /** Answer with a response the host builds itself. */
            SYNTHETIC,
            /** Answer with the lane-accurate exhaustion error. */
            TERMINAL_ERROR,
            /** Pipe the response through the Anthropic bridge. */
            BRIDGE_STREAM
        }

        /** Which answer this is. */
        public final Kind kind;
        /** The status to answer with. */
        public final int status;
        /** The host's opaque handle to the retained response, for the two serving kinds. */
        public final Object attemptRef;
        /** What the transform needs, on the transformed serving kind. */
        public final TransformParams params;
        /** The headers to answer with, on the synthetic kind. */
        public final Map<String, String> headers;
        /** The body to answer with, on the synthetic kind. */
        public final String body;
        /** What the host must assemble, on the terminal kind. */
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

        /**
         * Serve a successful upstream response through the transform.
         *
         * @param attemptRef the host's opaque handle to it
         * @param params what the transform needs
         * @param status the status it answered with
         * @return the decision
         */
        public static HandleDecision serve(Object attemptRef, TransformParams params, int status) {
            return new HandleDecision(Kind.SERVE, status, attemptRef, params, null, null, null);
        }

        /**
         * Serve a retained upstream response verbatim.
         *
         * @param attemptRef the host's opaque handle to it
         * @param status the status it answered with
         * @return the decision
         */
        public static HandleDecision serveRaw(Object attemptRef, int status) {
            return new HandleDecision(Kind.SERVE_RAW, status, attemptRef, null, null, null, null);
        }

        /**
         * Answer with a response the host builds itself.
         *
         * @param status the status to answer with
         * @param headers the headers to answer with
         * @param body the body to answer with
         * @return the decision
         */
        public static HandleDecision synthetic(int status, Map<String, String> headers, String body) {
            return new HandleDecision(Kind.SYNTHETIC, status, null, null, headers, body, null);
        }

        /**
         * Answer with the lane-accurate exhaustion error.
         *
         * @param terminal what the host must assemble
         * @return the decision
         */
        public static HandleDecision terminalError(TerminalError terminal) {
            return new HandleDecision(Kind.TERMINAL_ERROR, terminal.status, null, null, null, null, terminal);
        }

        /**
         * Pipe the response through the Anthropic bridge.
         *
         * @param attemptRef the host's opaque handle to it
         * @return the decision
         */
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
        /** Which of the two exhaustion paths this is. */
        public enum Kind {
            /** The free pool has nothing left for this model. */
            GEMINI_CLI_EXHAUSTED,
            /** Every metered account is rate-limited for this model. */
            ANTIGRAVITY_QUOTA_RESET
        }

        /** Which exhaustion path this is. */
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
        /** The request url. */
        public String url;
        /** The request method. */
        public String method;
        /** The caller's headers. */
        public Map<String, String> headers;
        /** The request body. */
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
     *
     * @param in the request and what the router resolved about it
     * @return what the host should answer with
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
                log.info("auto: " + model + " rate-limited (" + outcome.status + "); trying next candidate");
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
     *
     * @param model the model to try
     * @param url the request url, already rewritten to name that model
     * @param in the request and what the router resolved about it
     * @param log where the attempt loop reports what it did
     * @return what the host should answer with, or the rate-limit outcome to fall through on
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
                        ? lane + " quota exhausted, resets in ~" + secs
                            + "s. Pick another model or use Auto (it falls through to a free pool)."
                        : "No available antigravity account for lane " + lane + ".";
                return HandleDecision.synthetic(503, jsonHeaders(), errorBody(msg));
            }

            String accountId = acquired.accountId;
            String access = acquired.access;
            Map<String, Object> account = acquired.account;
            if (!JsCoercion.isTruthy(access)) {
                accounts.reportError(accountId, lane, attempt, "missing access token");
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
                    log.error("prepare failed: " + e);
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
     *
     * @param account the acquired account, whose metadata is written back
     * @param access its access token
     * @param log where the resolution reports what it did
     * @return the project the account's requests are billed to
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
            log.error("ensureProjectContext failed: " + error);
        }
        return JsCoercion.isTruthy(projectId) ? projectId : fallbackProjectId;
    }

    /**
     * A stable per-account synthetic project id (so accounts without a discovered managed project
     * never share the same {@code x-goog-user-project}). Generates + persists one via {@link
     * AccountOps#mutate} on first use.
     *
     * @param account the account, whose metadata is written back on first use
     * @return its stable synthetic project id
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
     *
     * @param r what the inner Gemini path came back with
     * @return what the host should answer with
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
