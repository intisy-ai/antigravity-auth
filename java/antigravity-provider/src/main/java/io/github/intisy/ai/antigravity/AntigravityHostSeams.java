package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.shared.manager.Acquired;
import io.github.intisy.ai.shared.model.Account;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import io.github.intisy.ai.shared.store.ModelsCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JVM host implementations of the seven seams {@link AntigravityHandleOrchestrator} needs (Phase
 * 3a, see {@code .superpowers/sdd/phase-3a-brief.md}): adapts {@link AntigravityBackend}'s
 * core-auth {@code AccountManager}/{@code AccountStore} to {@code AccountOps}; moves the Phase 2
 * inline Anthropic-&gt;Gemini request build (SP-2: {@code AntigravityIrBridge} +
 * {@code AntigravityRequestPrep}) into {@code RequestPreparer}; runs one attempt via {@code
 * HttpClient} as {@code AttemptExecutor}; and wires the model-cache/project-context seams. Every
 * class here is small and holds only what it needs (an {@link AntigravityBackend}, sometimes a
 * fixed {@link Logger}) -- no decision logic lives here, only host I/O + shape adaptation.
 */
final class AntigravityHostSeams {

    private AntigravityHostSeams() {
    }

    // ---- Seam 1: AccountOps -----------------------------------------------------------------------

    static final class HostAccountOps implements AntigravityHandleOrchestrator.AccountOps {
        private final AntigravityBackend backend;

        HostAccountOps(AntigravityBackend backend) {
            this.backend = backend;
        }

        @Override
        public AntigravityHandleOrchestrator.Acquired acquire(String lane) {
            Acquired acquired;
            try {
                acquired = backend.accounts.acquire(lane);
            } catch (RuntimeException e) {
                // A refresh failure (e.g. a revoked refresh token) already disabled the account
                // inside AccountManager -- fold it into the orchestrator's own "no account
                // available" contract (null) rather than throwing out of the DECISION loop.
                return null;
            }
            if (acquired == null || acquired.account == null) {
                return null;
            }
            return new AntigravityHandleOrchestrator.Acquired(
                    acquired.account.id, acquired.access, accountToMap(acquired.account));
        }

        @Override
        public Long nextAvailableAt(String lane) {
            return backend.accounts.nextAvailableAt(lane);
        }

        @Override
        public void reportError(String accountId, int attempt, String message) {
            backend.accounts.reportError(accountId, attempt, message);
        }

        @Override
        public void reportRateLimit(String accountId, String lane, long resetMs) {
            backend.accounts.reportRateLimit(accountId, lane, resetMs);
        }

        @Override
        public void reportSuccess(String accountId) {
            backend.accounts.reportSuccess(accountId);
        }

        @Override
        public void reportProxyRateLimit(String accountId, boolean ipSuspected) {
            // No JVM IP-proxy pool exists yet (brief seam 1) -- no-op.
        }

        @Override
        public List<Map<String, Object>> list() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Account a : backend.accountStore.list(AntigravityBackend.PROVIDER_ID)) {
                out.add(accountToMap(a));
            }
            return out;
        }

        @Override
        public void mutate(String accountId, AntigravityHandleOrchestrator.Mutator mutator) {
            backend.accounts.mutate(accountId, account -> {
                Map<String, Object> map = accountToMap(account);
                mutator.apply(map);
                // The orchestrator only ever writes meta.* keys (managedProjectId/
                // syntheticProjectId) -- copy just `meta` back onto the stored Account.
                Object meta = map.get("meta");
                if (meta instanceof Map) {
                    account.meta = castMap(meta);
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    /** Account -&gt; plain Map, including every scalar field (brief seam 1: "for completeness"). */
    static Map<String, Object> accountToMap(Account a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("email", a.email);
        m.put("refresh", a.refresh);
        m.put("access", a.access);
        if (a.expires != null) m.put("expires", a.expires);
        if (a.addedAt != null) m.put("addedAt", a.addedAt);
        if (a.lastUsed != null) m.put("lastUsed", a.lastUsed);
        if (a.enabled != null) m.put("enabled", a.enabled);
        if (a.rateLimitResetTimes != null) m.put("rateLimitResetTimes", new LinkedHashMap<>(a.rateLimitResetTimes));
        if (a.coolingDownUntil != null) m.put("coolingDownUntil", a.coolingDownUntil);
        if (a.cooldownReason != null) m.put("cooldownReason", a.cooldownReason);
        if (a.disabledReason != null) m.put("disabledReason", a.disabledReason);
        m.put("meta", a.meta != null ? a.meta : new LinkedHashMap<String, Object>());
        return m;
    }

    // ---- Seam 2: RequestPreparer ------------------------------------------------------------------

    private static final Pattern MODEL_IN_URL = Pattern.compile("/models/([^:/?]+)");

    /**
     * The IR-native request preparer for {@link AntigravityProvider#handleIr}: it already holds an
     * {@link IrRequest} decoded by its caller (the front-door's {@code AnthropicTranslator}), so
     * this applies ONLY antigravity's own thinking-budget resolution ({@link
     * AntigravityIrBridge#resolveThinkingBudget}) + the neutral IR-&gt;Gemini encode ({@link
     * AntigravityIrBridge#encodeIrToGemini}) directly on it, then runs the {@link
     * #prepareFromGeminiBody} tail (tool-hardening, schema cleaning, signature caching, ...). One
     * instance is built per {@code handleIr} call (not memoized like {@link
     * AntigravityProvider#orchestratorFor}) because the {@link IrRequest} is per-call state.
     */
    static final class HostIrRequestPreparer implements AntigravityHandleOrchestrator.RequestPreparer {
        private final AntigravityBackend backend;
        private final Logger logger;
        private final IrRequest ir;

        HostIrRequestPreparer(AntigravityBackend backend, Logger logger, IrRequest ir) {
            this.backend = backend;
            this.logger = logger;
            this.ir = ir;
        }

        @Override
        public AntigravityHandleOrchestrator.Prepared prepare(String url, String bodyText, String method,
                Map<String, String> headers, String access, String projectId, String endpoint,
                String headerStyle, Map<String, Object> account) {
            String model = modelFromUrl(url);
            // Auto-candidate walking is not yet wired for this Provider SPI path (AntigravityProvider
            // #handle's own in.autoCandidates is likewise always empty -- a pre-existing TODO, not a
            // regression here), so `model` is fixed across attempts and re-resolving the budget/body
            // per attempt is safe and matches the legacy path's per-attempt re-derivation exactly.
            AntigravityIrBridge.resolveThinkingBudget(ir, model);
            String geminiBodyJson = AntigravityIrBridge.encodeIrToGemini(backend.json, ir);
            return prepareFromGeminiBody(backend, logger, geminiBodyJson, model, url, method, headers,
                    access, projectId, endpoint, headerStyle, account);
        }
    }

    /**
     * Tail of {@link HostIrRequestPreparer}: runs the already-Gemini-shaped body through {@link
     * AntigravityRequestPrep#prepare} (tool-hardening, schema cleaning, the real thinking-tier
     * resolution, signature caching, ...) exactly as a native-Gemini request would.
     */
    private static AntigravityHandleOrchestrator.Prepared prepareFromGeminiBody(
            AntigravityBackend backend, Logger logger, String geminiBodyJson, String model,
            String url, String method, Map<String, String> headers, String access, String projectId,
            String endpoint, String headerStyle, Map<String, Object> account) {
        try {
            AntigravityRequestPrep.Input input = new AntigravityRequestPrep.Input();
            input.url = url;
            input.method = method;
            input.headers = new LinkedHashMap<>();
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    input.headers.put(e.getKey(), e.getValue());
                }
            }
            input.headers.putIfAbsent("content-type", "application/json");
            input.body = geminiBodyJson;
            input.accessToken = access;
            input.projectId = projectId;
            input.endpointOverride = endpoint;
            input.headerStyle = headerStyle;
            input.forceThinkingRecovery = false;
            input.claudeToolHardening = null; // Input javadoc: null -> defaults to true, matches TS ?? true
            input.claudePromptAutoCaching = false; // config default (schema.ts claude_prompt_auto_caching)
            input.fingerprint = AntigravityProvider.fingerprintFromMeta(metaOf(account));
            input.imageAspectRatio = null;

            AntigravityRequestPrep.Deps deps = new AntigravityRequestPrep.Deps();
            deps.json = backend.json;
            deps.ids = AntigravityProvider.ID_GENERATOR;
            deps.random = backend.random;
            deps.hasher = AntigravityProvider.SHA256_HASHER;
            deps.cachedSignatureLookup = AntigravityProvider.NO_CACHED_SIGNATURE;
            deps.signatureStore = AntigravityProvider.NOOP_SIGNATURE_STORE;
            deps.thinkingRecovery = new AntigravityThinkingRecovery(backend.json);
            deps.logger = logger;
            deps.keepThinking = false; // config default (schema.ts keep_thinking: false)
            deps.pluginSessionId = AntigravityProvider.PLUGIN_SESSION_ID;
            deps.selectedHeaders = AntigravityProvider.defaultSelectedHeaders();

            AntigravityRequestPrep.PrepareResult prepared = AntigravityRequestPrep.prepare(input, deps);

            HttpRequest outbound = new HttpRequest();
            outbound.method = "POST";
            outbound.url = String.valueOf(prepared.request);
            outbound.headers = AntigravityProvider.stringifyHeaders(prepared.headers);
            outbound.body = prepared.body != null ? String.valueOf(prepared.body) : null;

            AntigravityHandleOrchestrator.TransformParams params = new AntigravityHandleOrchestrator.TransformParams(
                    model,
                    prepared.projectId != null ? prepared.projectId : projectId,
                    prepared.endpoint != null ? prepared.endpoint : endpoint,
                    prepared.effectiveModel != null ? prepared.effectiveModel : model,
                    prepared.sessionId,
                    prepared.streaming);
            return new AntigravityHandleOrchestrator.Prepared(outbound, params);
        } catch (RuntimeException e) {
            throw new RuntimeException("antigravity request prepare failed: " + e.getMessage(), e);
        }
    }

    private static String modelFromUrl(String url) {
        Matcher m = MODEL_IN_URL.matcher(url != null ? url : "");
        return m.find() ? m.group(1) : "antigravity-auto";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metaOf(Map<String, Object> account) {
        Object meta = account != null ? account.get("meta") : null;
        return meta instanceof Map ? (Map<String, Object>) meta : null;
    }

    // ---- Seam 3: AttemptExecutor ------------------------------------------------------------------

    /**
     * Runs ONE prepared request via {@link AntigravityBackend#http}. On a non-2xx status, unwraps
     * the cloudcode-pa error body ({@link AntigravityResponseParse#parseAntigravityApiBody}) to
     * surface {@code error.message}/{@code error.status||error.reason} for the orchestrator's
     * rate-limit classification; no response BYTES are inspected beyond that -- the retained
     * {@link HttpResponse} itself becomes the opaque {@code attemptRef} so a {@code SERVE}/{@code
     * SERVE_RAW} decision can return it verbatim.
     */
    static final class HostAttemptExecutor implements AntigravityHandleOrchestrator.AttemptExecutor {
        private final AntigravityBackend backend;

        HostAttemptExecutor(AntigravityBackend backend) {
            this.backend = backend;
        }

        @Override
        public AntigravityHandleOrchestrator.AttemptResult execute(String accountId, Object preparedRequestRef) {
            HttpRequest req = (HttpRequest) preparedRequestRef;
            HttpResponse resp;
            try {
                resp = backend.http.send(req);
            } catch (RuntimeException e) {
                return new AntigravityHandleOrchestrator.AttemptResult(0, false, true, null, null, null, false);
            }
            boolean ok = resp.status / 100 == 2;
            String errorMessage = null;
            String errorReason = null;
            if (!ok) {
                Object parsed = AntigravityResponseParse.parseAntigravityApiBody(backend.json, resp.body);
                if (parsed instanceof Map) {
                    Object err = ((Map<?, ?>) parsed).get("error");
                    if (err instanceof Map) {
                        Object msg = ((Map<?, ?>) err).get("message");
                        Object status = ((Map<?, ?>) err).get("status");
                        Object reason = ((Map<?, ?>) err).get("reason");
                        errorMessage = msg != null ? String.valueOf(msg) : null;
                        Object statusOrReason = status != null ? status : reason;
                        errorReason = statusOrReason != null ? String.valueOf(statusOrReason) : null;
                    }
                }
            }
            return new AntigravityHandleOrchestrator.AttemptResult(
                    resp.status, ok, false, resp, errorMessage, errorReason, false);
        }
    }

    // ---- Seam 4: ModelCacheLookup -----------------------------------------------------------------

    /**
     * MVP (brief seam 4): reads the shared {@code models.json} cache ({@link ModelsCache}) if the
     * antigravity catalog has been seeded there and returns the requested model's {@code variants}
     * map; otherwise (no cache entry, no such model, no variants key) returns an empty map --
     * NEVER {@code null}. Effort-variant resolution is a no-op until something populates that cache.
     */
    static final class HostModelCacheLookup implements AntigravityHandleRouting.ModelCacheLookup {
        private final AntigravityBackend backend;

        HostModelCacheLookup(AntigravityBackend backend) {
            this.backend = backend;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> variantsFor(String modelId) {
            try {
                ModelsCache.Entry entry = new ModelsCache(backend.store, backend.json)
                        .read(AntigravityBackend.PROVIDER_ID);
                if (entry == null || entry.models == null) {
                    return Collections.emptyMap();
                }
                Object modelEntry = entry.models.get(modelId);
                if (!(modelEntry instanceof Map)) {
                    return Collections.emptyMap();
                }
                Object variants = ((Map<String, Object>) modelEntry).get("variants");
                return variants instanceof Map ? (Map<String, Object>) variants : Collections.emptyMap();
            } catch (RuntimeException e) {
                return Collections.emptyMap();
            }
        }
    }

    // ---- Seams 5/6/7: ProjectLoader / ProjectOnboarder / Platform ---------------------------------

    static final class HostPlatform implements AntigravityProjectContext.Platform {
        @Override
        public String platform() {
            return AntigravityProvider.detectPlatform();
        }

        @Override
        public String arch() {
            String arch = System.getProperty("os.arch", "").toLowerCase();
            return arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64";
        }
    }

    /**
     * {@code loadManagedProject} (project.ts:121-170): POSTs {@code metadata} to {@code
     * <endpoint>/v1internal:loadCodeAssist} with a Bearer access token, trying each ANTIGRAVITY
     * fallback endpoint in turn on a non-ok status or transport error. Only reached when an
     * account has no {@code meta.projectId} (brief note); seeded/test accounts short-circuit
     * before this seam is ever called.
     */
    static final class HostProjectLoader implements AntigravityProjectContext.ProjectLoader {
        private final AntigravityBackend backend;

        HostProjectLoader(AntigravityBackend backend) {
            this.backend = backend;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> load(String accessToken, String projectId, String proxy) {
            Map<String, Object> metadata = AntigravityProjectContext.buildMetadata(projectId, new HostPlatform());
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("metadata", metadata);
            String body = backend.json.stringify(requestBody);

            for (String base : AntigravityHandleRouting.endpointsFor("antigravity")) {
                try {
                    HttpRequest req = new HttpRequest();
                    req.method = "POST";
                    req.url = base + "/v1internal:loadCodeAssist";
                    req.headers = loadHeaders(accessToken);
                    req.body = body;
                    HttpResponse resp = backend.http.send(req);
                    if (resp.status / 100 != 2) {
                        continue;
                    }
                    Object parsed = AntigravityResponseParse.parseAntigravityApiBody(backend.json, resp.body);
                    if (parsed instanceof Map) {
                        return (Map<String, Object>) parsed;
                    }
                } catch (RuntimeException e) {
                    // try the next endpoint (project.ts:163-166's catch-and-continue)
                }
            }
            return null;
        }

        // Package-visible (not private): AntigravityModelsFetch reuses this exact header set for
        // its own v1internal:fetchAvailableModels POST rather than re-deriving the User-Agent/
        // X-Goog-Api-Client strings.
        static Map<String, String> loadHeaders(String accessToken) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", "application/json");
            h.put("Authorization", "Bearer " + accessToken);
            h.put("User-Agent", "google-api-nodejs-client/9.15.1");
            h.put("X-Goog-Api-Client", "google-cloud-sdk vscode_cloudshelleditor/0.1");
            return h;
        }
    }

    /**
     * {@code onboardManagedProject} (project.ts:176-233): POSTs {@code tierId}+{@code metadata} to
     * {@code <endpoint>/v1internal:onboardUser}, polling up to 10 times per endpoint (5s apart,
     * matching the TS defaults) until {@code done} is set, then returns the provisioned managed
     * project id (or the caller's own {@code projectId} if {@code done} but no id came back).
     */
    static final class HostProjectOnboarder implements AntigravityProjectContext.ProjectOnboarder {
        private static final int ATTEMPTS = 10;
        private static final long DELAY_MS = 5000L;

        private final AntigravityBackend backend;

        HostProjectOnboarder(AntigravityBackend backend) {
            this.backend = backend;
        }

        @Override
        @SuppressWarnings("unchecked")
        public String onboard(String accessToken, String tierId, String projectId, String proxy) {
            Map<String, Object> metadata = AntigravityProjectContext.buildMetadata(projectId, new HostPlatform());
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("tierId", tierId);
            requestBody.put("metadata", metadata);
            String body = backend.json.stringify(requestBody);

            for (String base : AntigravityHandleRouting.endpointsFor("antigravity")) {
                for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                    try {
                        HttpRequest req = new HttpRequest();
                        req.method = "POST";
                        req.url = base + "/v1internal:onboardUser";
                        req.headers = onboardHeaders(accessToken);
                        req.body = body;
                        HttpResponse resp = backend.http.send(req);
                        if (resp.status / 100 != 2) {
                            break; // next endpoint (project.ts:211-213)
                        }
                        Object parsed = AntigravityResponseParse.parseAntigravityApiBody(backend.json, resp.body);
                        if (parsed instanceof Map) {
                            Map<String, Object> payload = (Map<String, Object>) parsed;
                            boolean done = Boolean.TRUE.equals(payload.get("done"));
                            String managedProjectId = extractOnboardedProjectId(payload);
                            if (done && managedProjectId != null) {
                                return managedProjectId;
                            }
                            if (done && projectId != null && !projectId.isEmpty()) {
                                return projectId;
                            }
                        }
                    } catch (RuntimeException e) {
                        break; // next endpoint (project.ts:223-225)
                    }
                    sleep(DELAY_MS);
                }
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        private static String extractOnboardedProjectId(Map<String, Object> payload) {
            Object response = payload.get("response");
            if (!(response instanceof Map)) {
                return null;
            }
            Object project = ((Map<String, Object>) response).get("cloudaicompanionProject");
            if (!(project instanceof Map)) {
                return null;
            }
            Object id = ((Map<String, Object>) project).get("id");
            return id instanceof String ? (String) id : null;
        }

        private static Map<String, String> onboardHeaders(String accessToken) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", "application/json");
            h.put("Authorization", "Bearer " + accessToken);
            return h;
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
