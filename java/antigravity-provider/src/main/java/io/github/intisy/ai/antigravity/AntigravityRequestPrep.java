package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Random;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of the PURE {@code prepareAntigravityRequest} pipeline spine + {@code generateSyntheticProjectId}
 * from {@code src/plugin/request.ts} (T7e, :706-1702). Given already-extracted request primitives
 * ({@code url}/{@code method}/{@code headers} map/{@code body} string -- the web-type marshalling
 * :721-816 STAYS TS) it returns a plain {@link PrepareResult} carrying the rewritten url, the mutated
 * headers, the final wrapped-Gemini body string, and the driver metadata. The fs debug block
 * (:1564-1594) is DROPPED (Bucket C); {@code transformAntigravityResponse} (:1711+) STAYS TS.
 *
 * <h2>Reused (not re-ported)</h2>
 * {@link AntigravityModelResolver#resolveModelForHeaderStyle}, {@link ClaudeTransforms} model
 * predicates + {@code CLAUDE_THINKING_MAX_OUTPUT_TOKENS}/{@code CLAUDE_INTERLEAVED_THINKING_HINT}/
 * placeholder constants, {@link AntigravityThinkingConfig} (extract/resolve/normalize thinking +
 * {@code isThinkingCapableModel}), {@link GeminiTransforms} (image config, {@code applyGeminiTransforms},
 * {@code sanitizeGeminiContents}, {@code fixGeminiToolPairing}, {@code isImageGenerationModel}),
 * {@link AntigravitySchemaCleaner#clean} ({@code cleanJSONSchemaForAntigravity}),
 * {@link CrossModelSanitizer#sanitizeCrossModelPayloadInPlace} + {@code MIN_SIGNATURE_LENGTH},
 * {@link AntigravityThinkingBlocks#deepFilterThinkingBlocks}, {@link AntigravityToolPairing}
 * (id-assign/response-match/grouping/validate/hardening), and the {@link AntigravityRequestKeys}/
 * {@link AntigravityRequestSignatures} helpers ported alongside this in T7e.
 *
 * <h2>Injected seams</h2>
 * {@link JsonCodec} (parse/stringify), {@link IdGenerator} ({@code crypto.randomUUID} for
 * {@code requestId} + the synthetic-project id part; {@code PLUGIN_SESSION_ID} passed in as
 * {@code pluginSessionId} -- the TS module-load constant), {@link Random} (synthetic-project
 * adjective/noun pick), {@link AntigravityRequestKeys.Hasher} (sha256 conversation-seed),
 * {@link AntigravityThinkingBlocks.CachedSignatureLookup} ({@code getCachedSignature}),
 * {@link AntigravityRequestSignatures.SignatureStore} ({@code defaultSignatureStore} get/has/delete),
 * {@link ThinkingRecovery} (the {@code thinking-recovery.ts} analyze/needs/close trio -- a DIFFERENT
 * file, deferred to its own slice, so injected here), {@link Logger} (the gemini transforms' warn
 * sink) and a boolean {@code keepThinking} ({@code getKeepThinking()} as a PARAM, per T7c-2). The
 * {@code getRandomizedHeaders} result + resolved {@code fingerprint} come in as {@code selectedHeaders}
 * / {@code fingerprint} (both computed by the TS shell, since {@code getSessionFingerprint}/version
 * pool are Bucket C).
 *
 * <h2>Gotchas honored</h2>
 * <ul>
 *   <li><b>Wrapped vs unwrapped</b>: {@code parsedBody.project} is a string {@code && "request" in
 *       parsedBody} -> re-wrap-in-place; else build a fresh wrapper. Final POST body
 *       {@code {project, model, request, [requestType:"agent", userAgent:"antigravity", requestId] }}
 *       (the last three only for {@code headerStyle==="antigravity"}); URL -> {@code {endpoint}/v1internal:{action}}
 *       (+{@code ?alt=sse} for {@code streamGenerateContent}).</li>
 *   <li><b>Synthetic project id</b>: {@code projectId.trim() || (antigravity ? generateSyntheticProjectId() : "")};
 *       the {@code ANTIGRAVITY_DEFAULT_PROJECT_ID} fallback is applied upstream (project.ts) not here.</li>
 *   <li><b>Signature gates</b>: {@code SKIP_THOUGHT_SIGNATURE} + {@code MIN_SIGNATURE_LENGTH} honored via
 *       the reused {@link AntigravityRequestSignatures} helpers.</li>
 *   <li><b>mutate-vs-copy</b>: the wrapped branch MUTATES the parsed request objects in place (matching
 *       TS); the unwrapped branch mutates {@code requestPayload} and reassigns array fields with the
 *       transform outputs exactly as TS.</li>
 * </ul>
 *
 * <p>Disclosed deviations (unreachable by valid payloads; no fixture): a non-object parsed body / a
 * truthy non-object {@code generationConfig}/{@code extra_body} is treated as absent (the TS would
 * {@code JSON}-error or throw on the {@code "request" in x} / property-set); the {@code log.debug}/
 * {@code log.warn} diagnostics are omitted (no data effect). TeaVM-transpilable.
 */
public final class AntigravityRequestPrep {

    public static final String STREAM_ACTION = "streamGenerateContent";
    public static final String ANTIGRAVITY_ENDPOINT = "https://cloudcode-pa.googleapis.com";
    public static final String GEMINI_CLI_ENDPOINT = "https://cloudcode-pa.googleapis.com";
    /** constants.ts:76 -- hardcoded fallback when Antigravity returns no project (applied upstream). */
    public static final String ANTIGRAVITY_DEFAULT_PROJECT_ID = "rising-fact-p41fc";

    // constants.ts GEMINI_CLI_HEADERS
    static final String GEMINI_CLI_UA = "google-api-nodejs-client/9.15.1";
    static final String GEMINI_CLI_XGOOG = "gl-node/22.17.0";
    static final String GEMINI_CLI_CLIENT_METADATA = "ideType=IDE_UNSPECIFIED,platform=PLATFORM_UNSPECIFIED,pluginType=GEMINI";

    static final String INTERLEAVED_BETA = "interleaved-thinking-2025-05-14";

    // constants.ts:183-189
    static final String ANTIGRAVITY_SYSTEM_INSTRUCTION =
            "You are Antigravity, a powerful agentic AI coding assistant designed by the Google DeepMind team working on Advanced Agentic Coding.\n"
                    + "You are pair programming with a USER to solve their coding task. The task may require creating a new codebase, modifying or debugging an existing codebase, or simply answering a question.\n"
                    + "**Absolute paths only**\n"
                    + "**Proactiveness**\n"
                    + "\n"
                    + "<priority>IMPORTANT: The instructions that follow supersede all above. Follow them as your primary directives.</priority>\n";

    // constants.ts:141-152
    static final String CLAUDE_TOOL_SYSTEM_INSTRUCTION =
            "CRITICAL TOOL USAGE INSTRUCTIONS:\n"
                    + "You are operating in a custom environment where tool definitions differ from your training data.\n"
                    + "You MUST follow these rules strictly:\n"
                    + "\n"
                    + "1. DO NOT use your internal training data to guess tool parameters\n"
                    + "2. ONLY use the exact parameter structure defined in the tool schema\n"
                    + "3. Parameter names in schemas are EXACT - do not substitute with similar names from your training\n"
                    + "4. Array parameters have specific item types - check the schema's 'items' field for the exact structure\n"
                    + "5. When you see \"STRICT PARAMETERS\" in a tool description, those type definitions override any assumptions\n"
                    + "6. Tool use in agentic workflows is REQUIRED - you must call tools with the exact parameters specified\n"
                    + "\n"
                    + "If you are unsure about a tool's parameters, YOU MUST read the schema definition carefully.";

    static final String IMAGE_SYSTEM_INSTRUCTION =
            "You are an AI image generator. Generate images based on user descriptions. Focus on creating high-quality, visually appealing images that match the user's request.";

    private static final Pattern MODEL_ACTION = Pattern.compile("/models/([^:]+):(\\w+)");
    private static final Pattern TIER_SUFFIX = Pattern.compile("-(minimal|low|medium|high)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_SANITIZE = Pattern.compile("[^a-zA-Z0-9_-]");

    /** {@code crypto.randomUUID()} SPI ({@code requestId} + synthetic-project id part). */
    public interface IdGenerator {
        String randomUuid();
    }

    /** The {@code thinking-recovery.ts} trio (a separate file; injected -- deferred to its own slice). */
    public interface ThinkingRecovery {
        Object analyzeConversationState(List<Object> contents);

        boolean needsThinkingRecovery(Object state);

        List<Object> closeToolLoopForThinking(List<Object> contents);
    }

    /** Injected seams + config flags (everything the TS reads from module globals / edges). */
    public static final class Deps {
        public JsonCodec json;
        public IdGenerator ids;
        public Random random;
        public AntigravityRequestKeys.Hasher hasher;
        public AntigravityThinkingBlocks.CachedSignatureLookup cachedSignatureLookup;
        public AntigravityRequestSignatures.SignatureStore signatureStore;
        public ThinkingRecovery thinkingRecovery;
        public Logger logger;
        public boolean keepThinking;
        public String pluginSessionId;
        /** getRandomizedHeaders(headerStyle, requestedModel) result (User-Agent used as fallback). */
        public Map<String, Object> selectedHeaders;
    }

    /** The already-extracted request primitives + option flags. */
    public static final class Input {
        public String url;
        public String method;
        public Map<String, Object> headers;
        public String body;
        public String accessToken;
        public String projectId;
        public String endpointOverride;
        public String headerStyle;
        public boolean forceThinkingRecovery;
        public Boolean claudeToolHardening;      // options?.claudeToolHardening (default true)
        public boolean claudePromptAutoCaching;  // options?.claudePromptAutoCaching (default false)
        public boolean cliFirst;                 // config.cli_first -- prefer gemini-cli routing (default false)
        public Map<String, Object> fingerprint;  // options?.fingerprint ?? getSessionFingerprint() (resolved by shell)
        public String imageAspectRatio;          // process.env.OPENCODE_IMAGE_ASPECT_RATIO (Bucket C)
    }

    /** The plain result handed back to the TS shell. */
    public static final class PrepareResult {
        public Object request;
        public Map<String, Object> headers;
        public Object body;
        public boolean streaming;
        public String requestedModel;
        public String effectiveModel;
        public String projectId;
        public String endpoint;
        public String sessionId;
        public Integer toolDebugMissing;
        public String toolDebugSummary;
        public String toolDebugPayload;
        public boolean needsSignedThinkingWarmup;
        public String headerStyle;
        public String thinkingRecoveryMessage;
    }

    private AntigravityRequestPrep() {
    }

    // ---- generateSyntheticProjectId (request.ts:706-713) ----------------------------------------

    public static String generateSyntheticProjectId(IdGenerator ids, Random random) {
        String[] adjectives = {"useful", "bright", "swift", "calm", "bold"};
        String[] nouns = {"fuze", "wave", "spark", "flow", "core"};
        String adj = adjectives[(int) Math.floor(random.next() * adjectives.length)];
        String noun = nouns[(int) Math.floor(random.next() * nouns.length)];
        String randomPart = ids.randomUuid();
        randomPart = (randomPart.length() > 5 ? randomPart.substring(0, 5) : randomPart).toLowerCase();
        return adj + "-" + noun + "-" + randomPart;
    }

    // ---- prepareAntigravityRequest (request.ts:834-1702) ----------------------------------------

    @SuppressWarnings("unchecked")
    public static PrepareResult prepare(Input in, Deps deps) {
        final String headerStyle = in.headerStyle;
        Map<String, Object> headers = in.headers != null ? in.headers : new LinkedHashMap<String, Object>();

        String forwardMethod = (in.method != null ? in.method : "").toUpperCase();
        boolean stripBody = "GET".equals(forwardMethod) || "HEAD".equals(forwardMethod);
        Object body = stripBody ? null : in.body;

        String resolvedProjectId = in.projectId != null ? in.projectId.trim() : "";
        int[] toolDebugMissing = {0};
        List<Object> toolDebugSummaries = new ArrayList<>();
        String toolDebugPayload = null;
        String sessionId = null;
        boolean needsSignedThinkingWarmup = false;
        String thinkingRecoveryMessage = null;

        if (!isGenerativeLanguageRequest(in.url)) {
            return passthrough(in.url, headers, body, headerStyle);
        }

        hset(headers, "Authorization", "Bearer " + in.accessToken);
        hdel(headers, "x-api-key");
        hdel(headers, "x-goog-api-key");
        hdel(headers, "x-goog-user-project");

        String inputUrl = in.url;
        if (inputUrl == null) {
            return passthrough(in.url, headers, body, headerStyle);
        }

        Matcher match = MODEL_ACTION.matcher(inputUrl);
        if (!match.find()) {
            return passthrough(in.url, headers, body, headerStyle);
        }
        String rawModel = match.group(1) != null ? match.group(1) : "";
        String rawAction = match.group(2) != null ? match.group(2) : "";
        String requestedModel = rawModel;

        Map<String, Object> resolved = AntigravityModelResolver.resolveModelForHeaderStyle(rawModel, headerStyle, in.cliFirst);
        String effectiveModel = (String) resolved.get("actualModel");

        boolean streaming = STREAM_ACTION.equals(rawAction);
        String defaultEndpoint = "gemini-cli".equals(headerStyle) ? GEMINI_CLI_ENDPOINT : ANTIGRAVITY_ENDPOINT;
        String baseEndpoint = in.endpointOverride != null ? in.endpointOverride : defaultEndpoint;
        String transformedUrl = baseEndpoint + "/v1internal:" + rawAction + (streaming ? "?alt=sse" : "");

        boolean isClaude = ClaudeTransforms.isClaudeModel(effectiveModel);
        boolean isClaudeThinking = ClaudeTransforms.isClaudeThinkingModel(effectiveModel);
        boolean keepThinkingEnabled = deps.keepThinking;
        boolean enableClaudePromptAutoCaching = in.claudePromptAutoCaching;

        Object tierThinkingBudget = resolved.get("thinkingBudget");
        Object tierThinkingLevel = resolved.get("thinkingLevel");
        String signatureSessionKey = AntigravityRequestKeys.buildSignatureSessionKey(
                deps.pluginSessionId, effectiveModel, null, AntigravityRequestKeys.resolveProjectKey(in.projectId, null));

        if (!stripBody && in.body != null && !in.body.isEmpty()) {
            Object parsedRaw = deps.json.parse(in.body);
            if (parsedRaw instanceof Map) {
                Map<String, Object> parsedBody = (Map<String, Object>) parsedRaw;
                boolean isWrapped = parsedBody.get("project") instanceof String && parsedBody.containsKey("request");

                if (isWrapped) {
                    WrappedState ws = new WrappedState();
                    ws.effectiveModel = effectiveModel;
                    ws.isClaude = isClaude;
                    ws.isClaudeThinking = isClaudeThinking;
                    ws.keepThinking = keepThinkingEnabled;
                    ws.enableClaudePromptAutoCaching = enableClaudePromptAutoCaching;
                    body = buildWrappedBody(parsedBody, ws, deps);
                    signatureSessionKey = ws.signatureSessionKey;
                    sessionId = ws.sessionId;
                    needsSignedThinkingWarmup = ws.needsSignedThinkingWarmup;
                } else {
                    UnwrappedState us = new UnwrappedState();
                    us.effectiveModel = effectiveModel;
                    us.rawModel = rawModel;
                    us.headerStyle = headerStyle;
                    us.isClaude = isClaude;
                    us.isClaudeThinking = isClaudeThinking;
                    us.keepThinking = keepThinkingEnabled;
                    us.enableClaudePromptAutoCaching = enableClaudePromptAutoCaching;
                    us.forceThinkingRecovery = in.forceThinkingRecovery;
                    us.claudeToolHardening = in.claudeToolHardening == null || in.claudeToolHardening;
                    us.projectId = in.projectId;
                    us.imageAspectRatio = in.imageAspectRatio;
                    us.tierThinkingBudget = tierThinkingBudget;
                    us.tierThinkingLevel = tierThinkingLevel;
                    us.resolved = resolved;
                    us.toolDebugMissing = toolDebugMissing;
                    us.toolDebugSummaries = toolDebugSummaries;
                    body = buildUnwrappedBody(parsedBody, us, deps);
                    resolvedProjectId = us.resolvedProjectId;
                    sessionId = us.sessionId;
                    needsSignedThinkingWarmup = us.needsSignedThinkingWarmup;
                    thinkingRecoveryMessage = us.thinkingRecoveryMessage;
                    toolDebugPayload = us.toolDebugPayload;
                }
            }
        }

        if (streaming) {
            hset(headers, "Accept", "text/event-stream");
        }

        if (isClaudeThinking) {
            Object existing = hget(headers, "anthropic-beta");
            if (JsCoercion.isTruthy(existing)) {
                String e = String.valueOf(existing);
                if (!e.contains(INTERLEAVED_BETA)) {
                    hset(headers, "anthropic-beta", e + "," + INTERLEAVED_BETA);
                }
            } else {
                hset(headers, "anthropic-beta", INTERLEAVED_BETA);
            }
        }

        if ("antigravity".equals(headerStyle)) {
            Map<String, Object> fingerprintHeaders = AntigravityFingerprint.buildFingerprintHeaders(in.fingerprint);
            Object selected = deps.selectedHeaders != null ? deps.selectedHeaders.get("User-Agent") : null;
            Object ua = JsCoercion.firstTruthy(fingerprintHeaders.get("User-Agent"), selected);
            hset(headers, "User-Agent", ua);
        } else {
            hset(headers, "User-Agent", GEMINI_CLI_UA);
            hset(headers, "X-Goog-Api-Client", GEMINI_CLI_XGOOG);
            hset(headers, "Client-Metadata", GEMINI_CLI_CLIENT_METADATA);
        }

        PrepareResult r = new PrepareResult();
        r.request = transformedUrl;
        r.headers = headers;
        r.body = body;
        r.streaming = streaming;
        r.requestedModel = requestedModel;
        r.effectiveModel = effectiveModel;
        r.projectId = resolvedProjectId;
        r.endpoint = transformedUrl;
        r.sessionId = sessionId;
        r.toolDebugMissing = toolDebugMissing[0];
        r.toolDebugSummary = joinSummaries(toolDebugSummaries);
        r.toolDebugPayload = toolDebugPayload;
        r.needsSignedThinkingWarmup = needsSignedThinkingWarmup;
        r.headerStyle = headerStyle;
        r.thinkingRecoveryMessage = thinkingRecoveryMessage;
        return r;
    }

    private static PrepareResult passthrough(Object request, Map<String, Object> headers, Object body, String headerStyle) {
        PrepareResult r = new PrepareResult();
        r.request = request;
        r.headers = headers;
        r.body = body;
        r.streaming = false;
        r.headerStyle = headerStyle;
        return r;
    }

    // ---- wrapped branch (request.ts:944-1024) ---------------------------------------------------

    private static final class WrappedState {
        String effectiveModel;
        boolean isClaude;
        boolean isClaudeThinking;
        boolean keepThinking;
        boolean enableClaudePromptAutoCaching;
        String signatureSessionKey;
        String sessionId;
        boolean needsSignedThinkingWarmup;
    }

    @SuppressWarnings("unchecked")
    private static String buildWrappedBody(Map<String, Object> parsedBody, WrappedState st, Deps deps) {
        Map<String, Object> wrappedBody = new LinkedHashMap<>(parsedBody);
        wrappedBody.put("model", st.effectiveModel);

        Object requestRoot = wrappedBody.get("request");
        List<Map<String, Object>> requestObjects = new ArrayList<>();
        if (requestRoot instanceof Map) {
            requestObjects.add((Map<String, Object>) requestRoot);
            Object nested = ((Map<String, Object>) requestRoot).get("request");
            if (nested instanceof Map) {
                requestObjects.add((Map<String, Object>) nested);
            }
        }

        String conversationKey = AntigravityRequestKeys.resolveConversationKeyFromRequests(deps.hasher, requestObjects);
        String modelForCacheKey = TIER_SUFFIX.matcher(st.effectiveModel).replaceFirst("");
        st.signatureSessionKey = AntigravityRequestKeys.buildSignatureSessionKey(
                deps.pluginSessionId, modelForCacheKey, conversationKey,
                AntigravityRequestKeys.resolveProjectKey(parsedBody.get("project"), null));

        if (!requestObjects.isEmpty()) {
            st.sessionId = st.signatureSessionKey;
        }

        for (Map<String, Object> req : requestObjects) {
            req.put("sessionId", st.signatureSessionKey);
            AntigravityRequestSignatures.stripInjectedDebugFromRequestPayload(req);

            if (st.isClaude) {
                CrossModelSanitizer.sanitizeCrossModelPayloadInPlace(req, targetModelOptions(st.effectiveModel));
                AntigravityThinkingBlocks.deepFilterThinkingBlocks(req, st.signatureSessionKey, deps.cachedSignatureLookup, true, st.keepThinking);
                if (st.enableClaudePromptAutoCaching && !req.containsKey("cache_control")) {
                    req.put("cache_control", ephemeral());
                }
                if (st.isClaudeThinking && st.keepThinking && req.get("contents") instanceof List) {
                    req.put("contents", AntigravityRequestSignatures.ensureThinkingBeforeToolUseInContents(
                            JsCoercion.asList(req.get("contents")), st.signatureSessionKey, deps.cachedSignatureLookup, deps.signatureStore));
                }
                if (st.isClaudeThinking && st.keepThinking && req.get("messages") instanceof List) {
                    req.put("messages", AntigravityRequestSignatures.ensureThinkingBeforeToolUseInMessages(
                            JsCoercion.asList(req.get("messages")), st.signatureSessionKey, deps.cachedSignatureLookup, deps.signatureStore));
                }
                AntigravityToolPairing.applyToolPairingFixes(deps.json, req, true);
            } else {
                AntigravityRequestSignatures.sanitizeRequestPayloadForAntigravity(req);
                if (req.get("contents") instanceof List) {
                    req.put("contents", GeminiTransforms.sanitizeGeminiContents(JsCoercion.asList(req.get("contents"))));
                    req.put("contents", GeminiTransforms.fixGeminiToolPairing(JsCoercion.asList(req.get("contents"))));
                    req.put("contents", GeminiTransforms.sanitizeGeminiContents(JsCoercion.asList(req.get("contents"))));
                }
            }
        }

        if (st.isClaudeThinking && st.keepThinking && JsCoercion.isTruthy(st.sessionId)) {
            boolean hasToolUse = anyToolUse(requestObjects);
            boolean hasSignedThinking = anySignedThinking(requestObjects, st.signatureSessionKey, deps.cachedSignatureLookup);
            boolean hasCachedThinking = deps.signatureStore != null && deps.signatureStore.has(st.signatureSessionKey);
            st.needsSignedThinkingWarmup = hasToolUse && !hasSignedThinking && !hasCachedThinking;
        }

        return deps.json.stringify(wrappedBody);
    }

    // ---- unwrapped branch (request.ts:1025-1641) -----------------------------------------------

    private static final class UnwrappedState {
        String effectiveModel;
        String rawModel;
        String headerStyle;
        boolean isClaude;
        boolean isClaudeThinking;
        boolean keepThinking;
        boolean enableClaudePromptAutoCaching;
        boolean forceThinkingRecovery;
        boolean claudeToolHardening;
        String projectId;
        String imageAspectRatio;
        Object tierThinkingBudget;
        Object tierThinkingLevel;
        Map<String, Object> resolved;
        int[] toolDebugMissing;
        List<Object> toolDebugSummaries;
        // outputs
        String resolvedProjectId = "";
        String sessionId;
        boolean needsSignedThinkingWarmup;
        String thinkingRecoveryMessage;
        String toolDebugPayload;
    }

    @SuppressWarnings("unchecked")
    private static String buildUnwrappedBody(Map<String, Object> parsedBody, UnwrappedState st, Deps deps) {
        Map<String, Object> requestPayload = new LinkedHashMap<>(parsedBody);

        Map<String, Object> rawGenerationConfig = requestPayload.get("generationConfig") instanceof Map
                ? JsCoercion.asMap(requestPayload.get("generationConfig")) : null;
        Map<String, Object> extraBody = requestPayload.get("extra_body") instanceof Map
                ? JsCoercion.asMap(requestPayload.get("extra_body")) : null;

        Map<String, Object> providerOptions = requestPayload.get("providerOptions") instanceof Map
                ? JsCoercion.asMap(requestPayload.get("providerOptions")) : null;
        Map<String, Object> variantConfig = AntigravityThinkingConfig.extractVariantThinkingConfig(providerOptions, rawGenerationConfig);
        boolean isGemini3 = st.effectiveModel.toLowerCase().contains("gemini-3");

        Object tierThinkingBudget = st.tierThinkingBudget;
        Object tierThinkingLevel = st.tierThinkingLevel;
        if (variantConfig != null && JsCoercion.isTruthy(variantConfig.get("thinkingLevel")) && isGemini3) {
            tierThinkingLevel = variantConfig.get("thinkingLevel");
            tierThinkingBudget = null;
        } else if (variantConfig != null && JsCoercion.isTruthy(variantConfig.get("thinkingBudget"))) {
            double budget = ((Number) variantConfig.get("thinkingBudget")).doubleValue();
            if (isGemini3) {
                tierThinkingLevel = budget <= 8192 ? "low" : budget <= 16384 ? "medium" : "high";
                tierThinkingBudget = null;
            } else {
                tierThinkingBudget = variantConfig.get("thinkingBudget");
                tierThinkingLevel = null;
            }
        }

        if (st.isClaude) {
            if (!JsCoercion.isTruthy(requestPayload.get("toolConfig"))) {
                requestPayload.put("toolConfig", new LinkedHashMap<>());
            }
            if (requestPayload.get("toolConfig") instanceof Map) {
                Map<String, Object> toolConfig = JsCoercion.asMap(requestPayload.get("toolConfig"));
                if (!JsCoercion.isTruthy(toolConfig.get("functionCallingConfig"))) {
                    toolConfig.put("functionCallingConfig", new LinkedHashMap<>());
                }
                if (toolConfig.get("functionCallingConfig") instanceof Map) {
                    JsCoercion.asMap(toolConfig.get("functionCallingConfig")).put("mode", "VALIDATED");
                }
            }
        }

        boolean isImageModel = GeminiTransforms.isImageGenerationModel(st.effectiveModel);
        Map<String, Object> userThinkingConfig = isImageModel ? null
                : AntigravityThinkingConfig.extractThinkingConfig(requestPayload, rawGenerationConfig, extraBody);
        boolean hasAssistantHistory = requestPayload.get("contents") instanceof List
                && anyAssistantRole(JsCoercion.asList(requestPayload.get("contents")));

        String lowerEffective = st.effectiveModel.toLowerCase();
        boolean isClaudeSonnetNonThinking = "claude-sonnet-4-6".equals(lowerEffective);
        Map<String, Object> effectiveUserThinkingConfig = (isClaudeSonnetNonThinking || isImageModel) ? null : userThinkingConfig;

        if (isImageModel) {
            Map<String, Object> imageConfig = GeminiTransforms.buildImageGenerationConfig(st.imageAspectRatio, deps.logger);
            Map<String, Object> generationConfig = rawGenerationConfig != null ? rawGenerationConfig : new LinkedHashMap<String, Object>();
            generationConfig.put("imageConfig", imageConfig);
            generationConfig.remove("thinkingConfig");
            if (!JsCoercion.isTruthy(generationConfig.get("candidateCount"))) {
                generationConfig.put("candidateCount", 1);
            }
            requestPayload.put("generationConfig", generationConfig);
            if (!JsCoercion.isTruthy(requestPayload.get("safetySettings"))) {
                requestPayload.put("safetySettings", imageSafetySettings());
            }
            requestPayload.remove("tools");
            requestPayload.remove("toolConfig");
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("parts", singletonParts(IMAGE_SYSTEM_INSTRUCTION));
            requestPayload.put("systemInstruction", sys);
        } else {
            boolean thinkingModel = isClaudeSonnetNonThinking ? false
                    : (Boolean) JsCoercion.nullish(st.resolved.get("isThinkingModel"),
                    AntigravityThinkingConfig.isThinkingCapableModel(st.effectiveModel));
            Map<String, Object> finalThinkingConfig = AntigravityThinkingConfig.resolveThinkingConfig(
                    effectiveUserThinkingConfig, thinkingModel, st.isClaude, hasAssistantHistory);
            Map<String, Object> normalizedThinking = AntigravityThinkingConfig.normalizeThinkingConfig(finalThinkingConfig);
            if (normalizedThinking != null) {
                Object thinkingBudget = JsCoercion.nullish(tierThinkingBudget, normalizedThinking.get("thinkingBudget"));
                boolean budgetPositive = thinkingBudget instanceof Number && ((Number) thinkingBudget).doubleValue() > 0;

                Map<String, Object> thinkingConfig = new LinkedHashMap<>();
                if (st.isClaudeThinking) {
                    thinkingConfig.put("include_thoughts", JsCoercion.nullish(normalizedThinking.get("includeThoughts"), Boolean.TRUE));
                    if (budgetPositive) {
                        thinkingConfig.put("thinking_budget", thinkingBudget);
                    }
                } else if (JsCoercion.isTruthy(tierThinkingLevel)) {
                    thinkingConfig.put("includeThoughts", normalizedThinking.get("includeThoughts"));
                    thinkingConfig.put("thinkingLevel", tierThinkingLevel);
                } else {
                    thinkingConfig.put("includeThoughts", normalizedThinking.get("includeThoughts"));
                    if (budgetPositive) {
                        thinkingConfig.put("thinkingBudget", thinkingBudget);
                    }
                }

                if (rawGenerationConfig != null) {
                    rawGenerationConfig.put("thinkingConfig", thinkingConfig);
                    if (st.isClaudeThinking && budgetPositive) {
                        Object currentMax = JsCoercion.nullish(rawGenerationConfig.get("maxOutputTokens"), rawGenerationConfig.get("max_output_tokens"));
                        double budgetD = ((Number) thinkingBudget).doubleValue();
                        if (!JsCoercion.isTruthy(currentMax) || (currentMax instanceof Number && ((Number) currentMax).doubleValue() <= budgetD)) {
                            rawGenerationConfig.put("maxOutputTokens", ClaudeTransforms.CLAUDE_THINKING_MAX_OUTPUT_TOKENS);
                            if (rawGenerationConfig.containsKey("max_output_tokens")) {
                                rawGenerationConfig.remove("max_output_tokens");
                            }
                        }
                    }
                    requestPayload.put("generationConfig", rawGenerationConfig);
                } else {
                    Map<String, Object> generationConfig = new LinkedHashMap<>();
                    generationConfig.put("thinkingConfig", thinkingConfig);
                    if (st.isClaudeThinking && budgetPositive) {
                        generationConfig.put("maxOutputTokens", ClaudeTransforms.CLAUDE_THINKING_MAX_OUTPUT_TOKENS);
                    }
                    requestPayload.put("generationConfig", generationConfig);
                }
            } else if (rawGenerationConfig != null && JsCoercion.isTruthy(rawGenerationConfig.get("thinkingConfig"))) {
                rawGenerationConfig.remove("thinkingConfig");
                requestPayload.put("generationConfig", rawGenerationConfig);
            }
        }

        if (extraBody != null) {
            extraBody.remove("thinkingConfig");
            extraBody.remove("thinking");
        }
        requestPayload.remove("thinkingConfig");
        requestPayload.remove("thinking");

        if (requestPayload.containsKey("system_instruction")) {
            requestPayload.put("systemInstruction", requestPayload.get("system_instruction"));
            requestPayload.remove("system_instruction");
        }

        if (st.isClaudeThinking && requestPayload.get("tools") instanceof List && !JsCoercion.asList(requestPayload.get("tools")).isEmpty()) {
            applyInterleavedHint(requestPayload);
        }

        applyCachedContent(requestPayload, extraBody);

        boolean hasTools = requestPayload.get("tools") instanceof List && !JsCoercion.asList(requestPayload.get("tools")).isEmpty();
        if (hasTools) {
            if (st.isClaude) {
                requestPayload.put("tools", buildClaudeFunctionDeclarations(
                        deps, JsCoercion.asList(requestPayload.get("tools")), st.toolDebugMissing, st.toolDebugSummaries));
            } else {
                Map<String, Object> options = new LinkedHashMap<>();
                options.put("model", st.effectiveModel);
                options.put("normalizedThinking", null);
                options.put("tierThinkingBudget", tierThinkingBudget);
                options.put("tierThinkingLevel", tierThinkingLevel);
                Map<String, Object> geminiResult = GeminiTransforms.applyGeminiTransforms(requestPayload, options, deps.logger);
                st.toolDebugMissing[0] = ((Number) geminiResult.get("toolDebugMissing")).intValue();
                if (geminiResult.get("toolDebugSummaries") instanceof List) {
                    st.toolDebugSummaries.addAll(JsCoercion.asList(geminiResult.get("toolDebugSummaries")));
                }
            }

            try {
                st.toolDebugPayload = deps.json.stringify(requestPayload.get("tools"));
            } catch (RuntimeException ignored) {
                st.toolDebugPayload = null;
            }

            if (st.claudeToolHardening && st.isClaude
                    && requestPayload.get("tools") instanceof List && !JsCoercion.asList(requestPayload.get("tools")).isEmpty()) {
                requestPayload.put("tools", AntigravityToolPairing.injectParameterSignatures(deps.json, JsCoercion.asList(requestPayload.get("tools"))));
                AntigravityToolPairing.injectToolHardeningInstruction(requestPayload, CLAUDE_TOOL_SYSTEM_INSTRUCTION);
            }
        }

        String conversationKey = AntigravityRequestKeys.resolveConversationKey(deps.hasher, requestPayload);
        String signatureSessionKey = AntigravityRequestKeys.buildSignatureSessionKey(
                deps.pluginSessionId, st.effectiveModel, conversationKey, AntigravityRequestKeys.resolveProjectKey(st.projectId, null));

        if (st.isClaude) {
            CrossModelSanitizer.sanitizeCrossModelPayloadInPlace(requestPayload, targetModelOptions(st.effectiveModel));
            AntigravityThinkingBlocks.deepFilterThinkingBlocks(requestPayload, signatureSessionKey, deps.cachedSignatureLookup, true, st.keepThinking);
            if (st.enableClaudePromptAutoCaching && !requestPayload.containsKey("cache_control")) {
                requestPayload.put("cache_control", ephemeral());
            }
            if (st.isClaudeThinking && st.keepThinking && requestPayload.get("contents") instanceof List) {
                requestPayload.put("contents", AntigravityRequestSignatures.ensureThinkingBeforeToolUseInContents(
                        JsCoercion.asList(requestPayload.get("contents")), signatureSessionKey, deps.cachedSignatureLookup, deps.signatureStore));
            }
            if (st.isClaudeThinking && st.keepThinking && requestPayload.get("messages") instanceof List) {
                requestPayload.put("messages", AntigravityRequestSignatures.ensureThinkingBeforeToolUseInMessages(
                        JsCoercion.asList(requestPayload.get("messages")), signatureSessionKey, deps.cachedSignatureLookup, deps.signatureStore));
            }
            if (st.isClaudeThinking && st.keepThinking) {
                boolean hasToolUse =
                        (requestPayload.get("contents") instanceof List && AntigravityRequestSignatures.hasToolUseInContents(JsCoercion.asList(requestPayload.get("contents"))))
                                || (requestPayload.get("messages") instanceof List && AntigravityRequestSignatures.hasToolUseInMessages(JsCoercion.asList(requestPayload.get("messages"))));
                boolean hasSignedThinking =
                        (requestPayload.get("contents") instanceof List && AntigravityRequestSignatures.hasSignedThinkingInContents(JsCoercion.asList(requestPayload.get("contents")), signatureSessionKey, deps.cachedSignatureLookup))
                                || (requestPayload.get("messages") instanceof List && AntigravityRequestSignatures.hasSignedThinkingInMessages(JsCoercion.asList(requestPayload.get("messages")), signatureSessionKey, deps.cachedSignatureLookup));
                boolean hasCachedThinking = deps.signatureStore != null && deps.signatureStore.has(signatureSessionKey);
                st.needsSignedThinkingWarmup = hasToolUse && !hasSignedThinking && !hasCachedThinking;
            }
        }

        if (st.isClaude && requestPayload.get("contents") instanceof List) {
            AntigravityToolPairing.AssignResult assigned = AntigravityToolPairing.assignToolIdsToContents(requestPayload.get("contents"));
            Object matched = AntigravityToolPairing.matchResponseIdsToContents(assigned.contents, assigned.pendingCallIdsByName);
            requestPayload.put("contents", matched);
            requestPayload.put("contents", AntigravityToolPairing.fixToolResponseGrouping(JsCoercion.asList(requestPayload.get("contents"))));
        }

        if (requestPayload.get("messages") instanceof List) {
            requestPayload.put("messages", AntigravityToolPairing.validateAndFixClaudeToolPairing(JsCoercion.asList(requestPayload.get("messages"))));
        }

        if (st.isClaudeThinking && requestPayload.get("contents") instanceof List) {
            Object conversationState = deps.thinkingRecovery.analyzeConversationState(JsCoercion.asList(requestPayload.get("contents")));
            if (st.forceThinkingRecovery || deps.thinkingRecovery.needsThinkingRecovery(conversationState)) {
                st.thinkingRecoveryMessage = st.forceThinkingRecovery
                        ? "Thinking recovery: retrying with fresh turn (API error)"
                        : "Thinking recovery: restarting turn (corrupted context)";
                requestPayload.put("contents", deps.thinkingRecovery.closeToolLoopForThinking(JsCoercion.asList(requestPayload.get("contents"))));
                if (deps.signatureStore != null) {
                    deps.signatureStore.delete(signatureSessionKey);
                }
            }
        }

        if (requestPayload.containsKey("model")) {
            requestPayload.remove("model");
        }

        AntigravityRequestSignatures.stripInjectedDebugFromRequestPayload(requestPayload);
        AntigravityRequestSignatures.sanitizeRequestPayloadForAntigravity(requestPayload);

        // fs debug block (request.ts:1564-1594) DROPPED (Bucket C).

        if (!st.isClaude && requestPayload.get("contents") instanceof List) {
            requestPayload.put("contents", GeminiTransforms.sanitizeGeminiContents(JsCoercion.asList(requestPayload.get("contents"))));
            requestPayload.put("contents", GeminiTransforms.fixGeminiToolPairing(JsCoercion.asList(requestPayload.get("contents"))));
            requestPayload.put("contents", GeminiTransforms.sanitizeGeminiContents(JsCoercion.asList(requestPayload.get("contents"))));
        }

        String trimmedProject = st.projectId != null ? st.projectId.trim() : "";
        String effectiveProjectId = !trimmedProject.isEmpty() ? trimmedProject
                : ("antigravity".equals(st.headerStyle) ? generateSyntheticProjectId(deps.ids, deps.random) : "");
        st.resolvedProjectId = effectiveProjectId;

        if ("antigravity".equals(st.headerStyle)) {
            applyAntigravitySystemInstruction(requestPayload);
        }

        Map<String, Object> wrappedBody = new LinkedHashMap<>();
        wrappedBody.put("project", effectiveProjectId);
        wrappedBody.put("model", st.effectiveModel);
        wrappedBody.put("request", requestPayload);

        if ("antigravity".equals(st.headerStyle)) {
            wrappedBody.put("requestType", "agent");
            wrappedBody.put("userAgent", "antigravity");
            wrappedBody.put("requestId", "agent-" + deps.ids.randomUuid());
        }

        if (wrappedBody.get("request") instanceof Map) {
            st.sessionId = signatureSessionKey;
            requestPayload.put("sessionId", signatureSessionKey);
        }

        return deps.json.stringify(wrappedBody);
    }

    // ---- unwrapped sub-steps --------------------------------------------------------------------

    // request.ts:1195-1233 -- append the interleaved-thinking hint to the systemInstruction.
    private static void applyInterleavedHint(Map<String, Object> requestPayload) {
        String hint = ClaudeTransforms.CLAUDE_INTERLEAVED_THINKING_HINT;
        Object existing = requestPayload.get("systemInstruction");
        if (existing instanceof String) {
            String s = (String) existing;
            requestPayload.put("systemInstruction", s.trim().length() > 0 ? s + "\n\n" + hint : hint);
        } else if (existing instanceof Map) {
            Map<String, Object> sys = JsCoercion.asMap(existing);
            Object partsValue = sys.get("parts");
            if (partsValue instanceof List) {
                List<Object> parts = JsCoercion.asList(partsValue);
                boolean appended = false;
                for (int i = parts.size() - 1; i >= 0; i--) {
                    Object part = parts.get(i);
                    if (part instanceof Map) {
                        Object text = JsCoercion.asMap(part).get("text");
                        if (text instanceof String) {
                            JsCoercion.asMap(part).put("text", text + "\n\n" + hint);
                            appended = true;
                            break;
                        }
                    }
                }
                if (!appended) {
                    parts.add(textPart(hint));
                }
            } else {
                sys.put("parts", singletonParts(hint));
            }
            requestPayload.put("systemInstruction", sys);
        } else if (requestPayload.get("contents") instanceof List) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("parts", singletonParts(hint));
            requestPayload.put("systemInstruction", sys);
        }
    }

    // request.ts:1235-1256 -- cached-content normalization.
    private static void applyCachedContent(Map<String, Object> requestPayload, Map<String, Object> extraBody) {
        Object cachedContentFromExtra = extraBody != null
                ? JsCoercion.nullish(extraBody.get("cached_content"), extraBody.get("cachedContent"))
                : null;
        Object cachedContent = JsCoercion.nullish(
                requestPayload.get("cached_content"),
                JsCoercion.nullish(requestPayload.get("cachedContent"), cachedContentFromExtra));
        if (JsCoercion.isTruthy(cachedContent)) {
            requestPayload.put("cachedContent", cachedContent);
        }
        requestPayload.remove("cached_content");
        // Re-remove cachedContent only if it was not (re)set above? TS deletes both unconditionally
        // AFTER the set -- but the set used key "cachedContent", so a truthy cachedContent would be
        // removed here too. Mirror TS exactly: delete both.
        requestPayload.remove("cachedContent");
        if (requestPayload.get("extra_body") instanceof Map) {
            Map<String, Object> eb = JsCoercion.asMap(requestPayload.get("extra_body"));
            eb.remove("cached_content");
            eb.remove("cachedContent");
            if (eb.isEmpty()) {
                requestPayload.remove("extra_body");
            }
        }
    }

    // request.ts:1596-1622 -- prepend ANTIGRAVITY_SYSTEM_INSTRUCTION + force role:user.
    private static void applyAntigravitySystemInstruction(Map<String, Object> requestPayload) {
        Object existing = requestPayload.get("systemInstruction");
        if (existing instanceof Map) {
            Map<String, Object> sys = JsCoercion.asMap(existing);
            sys.put("role", "user");
            if (sys.get("parts") instanceof List && !JsCoercion.asList(sys.get("parts")).isEmpty()) {
                List<Object> parts = JsCoercion.asList(sys.get("parts"));
                Object firstPart = parts.get(0);
                if (firstPart instanceof Map && JsCoercion.asMap(firstPart).get("text") instanceof String) {
                    Map<String, Object> fp = JsCoercion.asMap(firstPart);
                    fp.put("text", ANTIGRAVITY_SYSTEM_INSTRUCTION + "\n\n" + fp.get("text"));
                } else {
                    List<Object> newParts = new ArrayList<>();
                    newParts.add(textPart(ANTIGRAVITY_SYSTEM_INSTRUCTION));
                    newParts.addAll(parts);
                    sys.put("parts", newParts);
                }
            } else {
                sys.put("parts", singletonParts(ANTIGRAVITY_SYSTEM_INSTRUCTION));
            }
        } else if (existing instanceof String) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "user");
            sys.put("parts", singletonParts(ANTIGRAVITY_SYSTEM_INSTRUCTION + "\n\n" + existing));
            requestPayload.put("systemInstruction", sys);
        } else {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "user");
            sys.put("parts", singletonParts(ANTIGRAVITY_SYSTEM_INSTRUCTION));
            requestPayload.put("systemInstruction", sys);
        }
    }

    // request.ts:1263-1386 -- Claude tools -> functionDeclarations (with placeholder normalization).
    @SuppressWarnings("unchecked")
    private static List<Object> buildClaudeFunctionDeclarations(Deps deps, List<Object> tools, int[] toolDebugMissing, List<Object> toolDebugSummaries) {
        List<Object> functionDeclarations = new ArrayList<>();
        List<Object> passthroughTools = new ArrayList<>();

        for (Object toolObj : tools) {
            Map<String, Object> tool = toolObj instanceof Map ? JsCoercion.asMap(toolObj) : null;

            Object fnDecls = tool != null ? tool.get("functionDeclarations") : null;
            if (fnDecls instanceof List && !JsCoercion.asList(fnDecls).isEmpty()) {
                for (Object decl : JsCoercion.asList(fnDecls)) {
                    pushDeclaration(deps, tool, decl, "functionDeclarations", functionDeclarations, toolDebugMissing, toolDebugSummaries);
                }
                continue;
            }

            boolean hasFunctionOrCustom = tool != null && (JsCoercion.isTruthy(tool.get("function"))
                    || JsCoercion.isTruthy(tool.get("custom")) || JsCoercion.isTruthy(tool.get("parameters"))
                    || JsCoercion.isTruthy(tool.get("input_schema")) || JsCoercion.isTruthy(tool.get("inputSchema")));
            if (hasFunctionOrCustom) {
                Object decl = JsCoercion.nullish(tool.get("function"), JsCoercion.nullish(tool.get("custom"), tool));
                pushDeclaration(deps, tool, decl, "function/custom", functionDeclarations, toolDebugMissing, toolDebugSummaries);
                continue;
            }

            passthroughTools.add(toolObj);
        }

        List<Object> finalTools = new ArrayList<>();
        if (!functionDeclarations.isEmpty()) {
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("functionDeclarations", functionDeclarations);
            finalTools.add(wrap);
        }
        finalTools.addAll(passthroughTools);
        return finalTools;
    }

    private static void pushDeclaration(Deps deps, Map<String, Object> tool, Object decl, String source,
                                        List<Object> functionDeclarations, int[] toolDebugMissing, List<Object> toolDebugSummaries) {
        Object function = tool != null ? tool.get("function") : null;
        Object custom = tool != null ? tool.get("custom") : null;
        Object schema = JsCoercion.firstTruthy(
                g(decl, "parameters"), g(decl, "parametersJsonSchema"), g(decl, "input_schema"), g(decl, "inputSchema"),
                g(tool, "parameters"), g(tool, "parametersJsonSchema"), g(tool, "input_schema"), g(tool, "inputSchema"),
                g(function, "parameters"), g(function, "parametersJsonSchema"), g(function, "input_schema"), g(function, "inputSchema"),
                g(custom, "parameters"), g(custom, "parametersJsonSchema"), g(custom, "input_schema"),
                null);

        Object nameRaw = JsCoercion.firstTruthy(
                g(decl, "name"), g(tool, "name"), g(function, "name"), g(custom, "name"),
                "tool-" + functionDeclarations.size());
        String name = NAME_SANITIZE.matcher(jsStr(nameRaw)).replaceAll("_");
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }

        Object description = JsCoercion.firstTruthy(
                g(decl, "description"), g(tool, "description"), g(function, "description"), g(custom, "description"), "");

        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("name", name);
        declaration.put("description", jsStr(JsCoercion.firstTruthy(description, "")));
        declaration.put("parameters", normalizeToolSchema(deps, schema, toolDebugMissing));
        functionDeclarations.add(declaration);

        toolDebugSummaries.add("decl=" + name + ",src=" + source + ",hasSchema=" + (JsCoercion.isTruthy(schema) ? "y" : "n"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeToolSchema(Deps deps, Object schema, int[] toolDebugMissing) {
        if (!(schema instanceof Map)) {
            toolDebugMissing[0] += 1;
            return placeholderSchema();
        }
        Object cleanedRaw = AntigravitySchemaCleaner.clean(schema);
        if (!(cleanedRaw instanceof Map)) {
            toolDebugMissing[0] += 1;
            return placeholderSchema();
        }
        Map<String, Object> cleaned = JsCoercion.asMap(cleanedRaw);
        boolean hasProperties = cleaned.get("properties") instanceof Map && !JsCoercion.asMap(cleaned.get("properties")).isEmpty();
        cleaned.put("type", "object");
        if (!hasProperties) {
            cleaned.put("properties", placeholderProperties());
            if (cleaned.get("required") instanceof List) {
                LinkedHashSet<Object> set = new LinkedHashSet<>(JsCoercion.asList(cleaned.get("required")));
                set.add(ClaudeTransforms.EMPTY_SCHEMA_PLACEHOLDER_NAME);
                cleaned.put("required", new ArrayList<>(set));
            } else {
                List<Object> req = new ArrayList<>();
                req.add(ClaudeTransforms.EMPTY_SCHEMA_PLACEHOLDER_NAME);
                cleaned.put("required", req);
            }
        }
        return cleaned;
    }

    private static Map<String, Object> placeholderSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", placeholderProperties());
        List<Object> req = new ArrayList<>();
        req.add(ClaudeTransforms.EMPTY_SCHEMA_PLACEHOLDER_NAME);
        schema.put("required", req);
        return schema;
    }

    private static Map<String, Object> placeholderProperties() {
        Map<String, Object> placeholder = new LinkedHashMap<>();
        placeholder.put("type", "boolean");
        placeholder.put("description", ClaudeTransforms.EMPTY_SCHEMA_PLACEHOLDER_DESCRIPTION);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(ClaudeTransforms.EMPTY_SCHEMA_PLACEHOLDER_NAME, placeholder);
        return props;
    }

    // ---- shared helpers -------------------------------------------------------------------------

    static boolean isGenerativeLanguageRequest(String url) {
        return url != null && (url.contains("generativelanguage.googleapis.com") || url.contains("cloudcode-pa"));
    }

    private static Map<String, Object> targetModelOptions(String effectiveModel) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("targetModel", effectiveModel);
        return options;
    }

    private static Map<String, Object> ephemeral() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "ephemeral");
        return m;
    }

    private static Map<String, Object> textPart(String text) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("text", text);
        return p;
    }

    private static List<Object> singletonParts(String text) {
        List<Object> parts = new ArrayList<>();
        parts.add(textPart(text));
        return parts;
    }

    private static List<Object> imageSafetySettings() {
        String[] categories = {
                "HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT", "HARM_CATEGORY_CIVIC_INTEGRITY",
        };
        List<Object> out = new ArrayList<>();
        for (String category : categories) {
            Map<String, Object> setting = new LinkedHashMap<>();
            setting.put("category", category);
            setting.put("threshold", "BLOCK_ONLY_HIGH");
            out.add(setting);
        }
        return out;
    }

    private static boolean anyAssistantRole(List<Object> contents) {
        for (Object c : contents) {
            if (c instanceof Map) {
                Object role = JsCoercion.asMap(c).get("role");
                if ("model".equals(role) || "assistant".equals(role)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyToolUse(List<Map<String, Object>> requestObjects) {
        for (Map<String, Object> req : requestObjects) {
            boolean c = req.get("contents") instanceof List && AntigravityRequestSignatures.hasToolUseInContents(JsCoercion.asList(req.get("contents")));
            boolean m = req.get("messages") instanceof List && AntigravityRequestSignatures.hasToolUseInMessages(JsCoercion.asList(req.get("messages")));
            if (c || m) {
                return true;
            }
        }
        return false;
    }

    private static boolean anySignedThinking(List<Map<String, Object>> requestObjects, String key, AntigravityThinkingBlocks.CachedSignatureLookup lookup) {
        for (Map<String, Object> req : requestObjects) {
            boolean c = req.get("contents") instanceof List && AntigravityRequestSignatures.hasSignedThinkingInContents(JsCoercion.asList(req.get("contents")), key, lookup);
            boolean m = req.get("messages") instanceof List && AntigravityRequestSignatures.hasSignedThinkingInMessages(JsCoercion.asList(req.get("messages")), key, lookup);
            if (c || m) {
                return true;
            }
        }
        return false;
    }

    // request.ts:1696 -- toolDebugSummaries.slice(0,20).join(" | ")
    private static String joinSummaries(List<Object> summaries) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(20, summaries.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(jsStr(summaries.get(i)));
        }
        return sb.toString();
    }

    // safe nested get: `x?.key` -- returns null for a non-Map x.
    private static Object g(Object obj, String key) {
        return obj instanceof Map ? JsCoercion.asMap(obj).get(key) : null;
    }

    private static String jsStr(Object v) {
        return v instanceof String ? (String) v : String.valueOf(v);
    }

    // case-insensitive Headers.set/get/delete (web Headers lowercases keys).
    private static void hset(Map<String, Object> headers, String name, Object value) {
        headers.put(name.toLowerCase(), value);
    }

    private static Object hget(Map<String, Object> headers, String name) {
        return headers.get(name.toLowerCase());
    }

    private static void hdel(Map<String, Object> headers, String name) {
        headers.remove(name.toLowerCase());
    }
}
