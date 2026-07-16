package io.github.intisy.ai.js;

import io.github.intisy.ai.antigravity.AntigravityAuth;
import io.github.intisy.ai.antigravity.AntigravityCatalog;
import io.github.intisy.ai.antigravity.AntigravityFingerprint;
import io.github.intisy.ai.antigravity.AntigravityFormatBridge;
import io.github.intisy.ai.antigravity.AntigravityHandleOrchestrator;
import io.github.intisy.ai.antigravity.AntigravityHandleRouting;
import io.github.intisy.ai.antigravity.AntigravityProjectContext;
import io.github.intisy.ai.antigravity.AntigravityRequestKeys;
import io.github.intisy.ai.antigravity.AntigravityRequestPrep;
import io.github.intisy.ai.antigravity.AntigravityRequestSignatures;
import io.github.intisy.ai.antigravity.AntigravityLanes;
import io.github.intisy.ai.antigravity.AntigravityModelResolver;
import io.github.intisy.ai.antigravity.AntigravityQuotaParser;
import io.github.intisy.ai.antigravity.AntigravityResponseParse;
import io.github.intisy.ai.antigravity.AntigravityResponseTransform;
import io.github.intisy.ai.antigravity.AntigravitySchemaCleaner;
import io.github.intisy.ai.antigravity.AntigravityStreamMapper;
import io.github.intisy.ai.antigravity.AntigravityStreamTransform;
import io.github.intisy.ai.antigravity.AntigravityThinkingBlocks;
import io.github.intisy.ai.antigravity.AntigravityThinkingConfig;
import io.github.intisy.ai.antigravity.AntigravityThinkingRecovery;
import io.github.intisy.ai.antigravity.AntigravityToolPairing;
import io.github.intisy.ai.antigravity.AntigravityVersions;
import io.github.intisy.ai.antigravity.ClaudeTransforms;
import io.github.intisy.ai.antigravity.CrossModelSanitizer;
import io.github.intisy.ai.antigravity.GeminiTransforms;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Random;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import org.teavm.jso.JSExport;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSArray;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSString;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TeaVM JS export surface over antigravity-auth's Java port (T7a) -- proves all five ported
 * classes ({@code AntigravityAuth}, {@code AntigravityLanes}, {@code AntigravityVersions},
 * {@code AntigravityCatalog}, {@code AntigravityQuotaParser}) are TeaVM-transpilable ({@code
 * generateJavaScript} green), mirroring claude-code-auth's {@code ClaudeProviderJs} pattern. Lives
 * in the SAME package ({@code io.github.intisy.ai.js}) as core-proxy's {@code :teavm} module (a
 * Gradle project dependency, see {@code antigravity-teavm/build.gradle}), so {@code
 * SimpleJsonCodec} is referenced unqualified exactly like {@code CoreProxyJs}/{@code
 * ClaudeProviderJs} do -- NOT duplicated here.
 *
 * <p>Every export below calls straight into the JVM-side ported classes -- ONE Java method,
 * compiled twice (javac for {@code :antigravity-provider}'s jar, TeaVM for this module) -- so this
 * is a thin touch-surface, not a reimplementation. T7a does NOT wire this JS into antigravity-
 * auth's TS runtime (that's a later task); this module only proves transpilability.
 */
public final class AntigravityProviderJs {

    private static final Clock SYSTEM_CLOCK = () -> System.currentTimeMillis();
    // Fixed 0.5 stand-in for Math.random -- this surface only proves transpilability, not
    // randomness; a deterministic value keeps the exported methods pure/reproducible.
    private static final Random FIXED_RANDOM = () -> 0.5;
    // No-op Logger stand-in for the TS `console.warn` sites (T7b transform layer). T7c-1 ported the
    // real SchemaCleaner (cleanJSONSchemaForAntigravity), so the Claude transform surface below now
    // injects it instead of the former identity double.
    private static final Logger NOOP_LOGGER = msg -> { };
    private static final ClaudeTransforms.SchemaCleaner REAL_CLEANER = AntigravitySchemaCleaner::clean;

    private AntigravityProviderJs() {
    }

    // ---- AntigravityAuth --------------------------------------------------------------------------

    /** Exercises {@link AntigravityAuth#parseRefreshParts} + {@link AntigravityAuth#formatRefreshParts} round-trip. */
    @JSExport
    public static String refreshRoundTrip(String refresh) {
        AntigravityAuth.RefreshParts parts = AntigravityAuth.parseRefreshParts(refresh);
        return AntigravityAuth.formatRefreshParts(parts);
    }

    /** Exercises {@link AntigravityAuth#isOAuthAuth} + {@link AntigravityAuth#accessTokenExpired} via the JsonCodec SPI. */
    @JSExport
    @SuppressWarnings("unchecked")
    public static boolean authExpired(String authJson) {
        JsonCodec json = new SimpleJsonCodec();
        Object parsed = authJson != null ? json.parse(authJson) : null;
        Map<String, Object> auth = parsed instanceof Map ? (Map<String, Object>) parsed : null;
        if (auth == null || !AntigravityAuth.isOAuthAuth(auth)) return true;
        return AntigravityAuth.accessTokenExpired(auth, SYSTEM_CLOCK);
    }

    // ---- AntigravityLanes -------------------------------------------------------------------------

    /** Exercises {@link AntigravityLanes#laneFor}. */
    @JSExport
    public static String laneFor(String model) {
        return AntigravityLanes.laneFor(model);
    }

    /** Exercises {@link AntigravityLanes#parseRateLimitReason} + {@link AntigravityLanes#calculateBackoffMs}. */
    @JSExport
    public static String backoffMs(String reason, int consecutiveFailures) {
        String classified = AntigravityLanes.parseRateLimitReason(reason, null, null);
        return String.valueOf(AntigravityLanes.calculateBackoffMs(classified, consecutiveFailures, null, FIXED_RANDOM));
    }

    // ---- AntigravityVersions ----------------------------------------------------------------------

    /** Exercises {@link AntigravityVersions#driftVersion} over the curated fallback pool (+ Random SPI). */
    @JSExport
    public static String driftVersion(String current) {
        List<String> pool = new ArrayList<>(AntigravityVersions.FALLBACK_VERSIONS);
        return AntigravityVersions.driftVersion(current, pool, FIXED_RANDOM);
    }

    // ---- AntigravityCatalog -----------------------------------------------------------------------

    /** Exercises {@link AntigravityCatalog#buildAntigravityCatalog} via the JsonCodec SPI (JSON in -> JSON out). */
    @JSExport
    @SuppressWarnings("unchecked")
    public static String buildCatalog(String payloadJson) {
        JsonCodec json = new SimpleJsonCodec();
        Object parsed = payloadJson != null ? json.parse(payloadJson) : null;
        Map<String, Object> payload = parsed instanceof Map ? (Map<String, Object>) parsed : new java.util.LinkedHashMap<>();
        return json.stringify(AntigravityCatalog.buildAntigravityCatalog(payload));
    }

    // ---- AntigravityQuotaParser -------------------------------------------------------------------

    /** Exercises {@link AntigravityQuotaParser#aggregateQuotaFamilies} via the JsonCodec SPI (JSON in -> JSON out). */
    @JSExport
    @SuppressWarnings("unchecked")
    public static String aggregateQuota(String modelsJson) {
        JsonCodec json = new SimpleJsonCodec();
        Object parsed = modelsJson != null ? json.parse(modelsJson) : null;
        Map<String, Object> models = parsed instanceof Map ? (Map<String, Object>) parsed : new java.util.LinkedHashMap<>();
        return json.stringify(AntigravityQuotaParser.aggregateQuotaFamilies(models));
    }

    // ---- AntigravityModelResolver (T7b) -----------------------------------------------------------

    /** Exercises {@link AntigravityModelResolver#resolveModelWithTier} (JSON out). */
    @JSExport
    public static String resolveModelWithTier(String model, boolean cliFirst) {
        return new SimpleJsonCodec().stringify(AntigravityModelResolver.resolveModelWithTier(model, cliFirst));
    }

    /** Exercises {@link AntigravityModelResolver#resolveModelForHeaderStyle} (JSON out). */
    @JSExport
    public static String resolveModelForHeaderStyle(String model, String headerStyle) {
        return new SimpleJsonCodec().stringify(AntigravityModelResolver.resolveModelForHeaderStyle(model, headerStyle));
    }

    // ---- CrossModelSanitizer (T7b) ----------------------------------------------------------------

    /** Exercises {@link CrossModelSanitizer#sanitizeCrossModelPayload} via the JsonCodec SPI. */
    @JSExport
    public static String sanitizeCrossModelPayload(String payloadJson, String targetModel) {
        JsonCodec json = new SimpleJsonCodec();
        Object payload = payloadJson != null ? json.parse(payloadJson) : null;
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("targetModel", targetModel);
        CrossModelSanitizer.SanitizationResult result = CrossModelSanitizer.sanitizeCrossModelPayload(payload, options);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("payload", result.payload);
        out.put("modified", result.modified);
        out.put("signaturesStripped", result.signaturesStripped);
        return json.stringify(out);
    }

    // ---- GeminiTransforms (T7b) -------------------------------------------------------------------

    /** Exercises {@link GeminiTransforms#toGeminiSchema} via the JsonCodec SPI. */
    @JSExport
    public static String toGeminiSchema(String schemaJson) {
        JsonCodec json = new SimpleJsonCodec();
        return json.stringify(GeminiTransforms.toGeminiSchema(schemaJson != null ? json.parse(schemaJson) : null));
    }

    /** Exercises {@link GeminiTransforms#applyGeminiTransforms} (mutates payload, no-op Logger). */
    @JSExport
    @SuppressWarnings("unchecked")
    public static String applyGeminiTransforms(String payloadJson, String optionsJson) {
        JsonCodec json = new SimpleJsonCodec();
        Map<String, Object> payload = asMap(json.parse(payloadJson));
        Map<String, Object> options = asMap(json.parse(optionsJson));
        Map<String, Object> result = GeminiTransforms.applyGeminiTransforms(payload, options, NOOP_LOGGER);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("payload", payload);
        out.put("result", result);
        return json.stringify(out);
    }

    // ---- ClaudeTransforms (T7b) -------------------------------------------------------------------

    /** Exercises {@link ClaudeTransforms#applyClaudeTransforms} (mutates payload, identity cleaner). */
    @JSExport
    public static String applyClaudeTransforms(String payloadJson, String optionsJson) {
        JsonCodec json = new SimpleJsonCodec();
        Map<String, Object> payload = asMap(json.parse(payloadJson));
        Map<String, Object> options = asMap(json.parse(optionsJson));
        Map<String, Object> result = ClaudeTransforms.applyClaudeTransforms(payload, options, REAL_CLEANER);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("payload", payload);
        out.put("result", result);
        return json.stringify(out);
    }

    // ---- AntigravitySchemaCleaner (T7c-1) ---------------------------------------------------------

    /** Exercises {@link AntigravitySchemaCleaner#clean} via the JsonCodec SPI (JSON in -> JSON out). */
    @JSExport
    public static String cleanSchema(String schemaJson) {
        JsonCodec json = new SimpleJsonCodec();
        return json.stringify(AntigravitySchemaCleaner.clean(schemaJson != null ? json.parse(schemaJson) : null));
    }

    // ---- AntigravityThinkingConfig (T7c-2) --------------------------------------------------------

    /** Exercises {@link AntigravityThinkingConfig#normalizeThinkingConfig} via the JsonCodec SPI. */
    @JSExport
    public static String normalizeThinkingConfig(String configJson) {
        JsonCodec json = new SimpleJsonCodec();
        Object cfg = configJson != null ? json.parse(configJson) : null;
        return json.stringify(AntigravityThinkingConfig.normalizeThinkingConfig(cfg));
    }

    /** Exercises {@link AntigravityThinkingConfig#extractVariantThinkingConfig} via the JsonCodec SPI. */
    @JSExport
    public static String extractVariantThinkingConfig(String providerOptionsJson, String generationConfigJson) {
        JsonCodec json = new SimpleJsonCodec();
        Map<String, Object> po = providerOptionsJson != null ? asMap(json.parse(providerOptionsJson)) : null;
        Map<String, Object> gc = generationConfigJson != null ? asMap(json.parse(generationConfigJson)) : null;
        return json.stringify(AntigravityThinkingConfig.extractVariantThinkingConfig(po, gc));
    }

    // ---- AntigravityThinkingBlocks (T7c-2) --------------------------------------------------------

    // No-op signature-cache getter -- this surface only proves transpilability, not caching.
    private static final AntigravityThinkingBlocks.CachedSignatureLookup NO_CACHE = (sessionId, text) -> null;
    // Identity JsonStringParser stand-in for recursivelyParseJsonStrings (T7c-3, injected seam).
    private static final AntigravityThinkingBlocks.JsonStringParser IDENTITY_PARSER = value -> value;
    // Deterministic ImageSink stand-in for processImageData's Bucket-C fs write (data-URL fallback).
    private static final AntigravityThinkingBlocks.ImageSink DATA_URL_SINK =
            (mimeType, data) -> data == null ? null : "data:" + mimeType + ";base64," + data;

    /** Exercises {@link AntigravityThinkingBlocks#deepFilterThinkingBlocks} (keepThinking threaded, cache getter). */
    @JSExport
    public static String deepFilterThinkingBlocks(String payloadJson, String sessionId, boolean isClaudeModel, boolean keepThinking) {
        JsonCodec json = new SimpleJsonCodec();
        Object payload = payloadJson != null ? json.parse(payloadJson) : null;
        return json.stringify(AntigravityThinkingBlocks.deepFilterThinkingBlocks(payload, sessionId, NO_CACHE, isClaudeModel, keepThinking));
    }

    /** Exercises {@link AntigravityThinkingBlocks#transformThinkingParts} with the injected parser + image sink. */
    @JSExport
    public static String transformThinkingParts(String responseJson) {
        JsonCodec json = new SimpleJsonCodec();
        Object response = responseJson != null ? json.parse(responseJson) : null;
        return json.stringify(AntigravityThinkingBlocks.transformThinkingParts(response, IDENTITY_PARSER, DATA_URL_SINK));
    }

    // ---- AntigravityResponseParse (T7c-3) ---------------------------------------------------------

    /** Exercises {@link AntigravityResponseParse#parseAntigravityApiBody} (cloudcode-pa array shape) via JsonCodec. */
    @JSExport
    public static String parseAntigravityApiBody(String rawText) {
        JsonCodec json = new SimpleJsonCodec();
        return json.stringify(AntigravityResponseParse.parseAntigravityApiBody(json, rawText));
    }

    /** Exercises {@link AntigravityResponseParse#extractUsageFromSsePayload} via JsonCodec. */
    @JSExport
    public static String extractUsageFromSsePayload(String payload) {
        JsonCodec json = new SimpleJsonCodec();
        return json.stringify(AntigravityResponseParse.extractUsageFromSsePayload(json, payload));
    }

    /** Exercises {@link AntigravityResponseParse#isEmptyResponseBody} via JsonCodec. */
    @JSExport
    public static boolean isEmptyResponseBody(String text) {
        return AntigravityResponseParse.isEmptyResponseBody(new SimpleJsonCodec(), text);
    }

    /** Exercises {@link AntigravityResponseParse#isMeaningfulSseLine} via JsonCodec. */
    @JSExport
    public static boolean isMeaningfulSseLine(String line) {
        return AntigravityResponseParse.isMeaningfulSseLine(new SimpleJsonCodec(), line);
    }

    /** Exercises {@link AntigravityResponseParse#recursivelyParseJsonStrings} via JsonCodec. */
    @JSExport
    public static String recursivelyParseJsonStrings(String objJson) {
        JsonCodec json = new SimpleJsonCodec();
        Object obj = objJson != null ? json.parse(objJson) : null;
        return json.stringify(AntigravityResponseParse.recursivelyParseJsonStrings(json, obj));
    }

    /** Exercises {@link AntigravityResponseParse#rewriteAntigravityPreviewAccessError} via JsonCodec. */
    @JSExport
    public static String rewriteAntigravityPreviewAccessError(String bodyJson, int status, String requestedModel) {
        JsonCodec json = new SimpleJsonCodec();
        return json.stringify(AntigravityResponseParse.rewriteAntigravityPreviewAccessError(
                asMap(json.parse(bodyJson)), status, requestedModel));
    }

    /** Exercises {@link AntigravityResponseParse#createSyntheticErrorResponse} (Clock-sourced id) -> JSON. */
    @JSExport
    public static String createSyntheticErrorResponse(String errorMessage, String requestedModel) {
        JsonCodec json = new SimpleJsonCodec();
        AntigravityResponseParse.SyntheticResponse res =
                AntigravityResponseParse.createSyntheticErrorResponse(json, SYSTEM_CLOCK, errorMessage, requestedModel);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("status", (long) res.status);
        out.put("headers", res.headers);
        out.put("body", res.body);
        return json.stringify(out);
    }

    // ---- AntigravityToolPairing (T7c-3) -----------------------------------------------------------

    /** Exercises {@link AntigravityToolPairing#fixToolResponseGrouping} via JsonCodec. */
    @JSExport
    @SuppressWarnings("unchecked")
    public static String fixToolResponseGrouping(String contentsJson) {
        JsonCodec json = new SimpleJsonCodec();
        Object parsed = contentsJson != null ? json.parse(contentsJson) : null;
        List<Object> contents = parsed instanceof List ? (List<Object>) parsed : null;
        return json.stringify(AntigravityToolPairing.fixToolResponseGrouping(contents));
    }

    /** Exercises {@link AntigravityToolPairing#validateAndFixClaudeToolPairing} via JsonCodec. */
    @JSExport
    @SuppressWarnings("unchecked")
    public static String validateAndFixClaudeToolPairing(String messagesJson) {
        JsonCodec json = new SimpleJsonCodec();
        Object parsed = messagesJson != null ? json.parse(messagesJson) : null;
        List<Object> messages = parsed instanceof List ? (List<Object>) parsed : null;
        return json.stringify(AntigravityToolPairing.validateAndFixClaudeToolPairing(messages));
    }

    /** Exercises {@link AntigravityToolPairing#injectParameterSignatures} via JsonCodec. */
    @JSExport
    @SuppressWarnings("unchecked")
    public static String injectParameterSignatures(String toolsJson) {
        JsonCodec json = new SimpleJsonCodec();
        Object parsed = toolsJson != null ? json.parse(toolsJson) : null;
        List<Object> tools = parsed instanceof List ? (List<Object>) parsed : null;
        return json.stringify(AntigravityToolPairing.injectParameterSignatures(json, tools));
    }

    /** Exercises {@link AntigravityToolPairing#applyToolPairingFixes} (mutates payload) via JsonCodec. */
    @JSExport
    public static String applyToolPairingFixes(String payloadJson, boolean isClaude) {
        JsonCodec json = new SimpleJsonCodec();
        Map<String, Object> payload = asMap(json.parse(payloadJson));
        AntigravityToolPairing.PairingFixResult result = AntigravityToolPairing.applyToolPairingFixes(json, payload, isClaude);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("payload", payload);
        out.put("contentsFixed", result.contentsFixed);
        out.put("messagesFixed", result.messagesFixed);
        return json.stringify(out);
    }

    // ---- AntigravityFormatBridge (T7d) ------------------------------------------------------------

    /** Exercises {@link AntigravityFormatBridge#anthropicToGemini} (real cleaner injected) via JsonCodec. */
    @JSExport
    public static String anthropicToGemini(String bodyJson, String model) {
        JsonCodec json = new SimpleJsonCodec();
        return json.stringify(AntigravityFormatBridge.anthropicToGemini(json, asMap(json.parse(bodyJson)), model, REAL_CLEANER));
    }

    /** Exercises {@link AntigravityFormatBridge#supportsThinking}. */
    @JSExport
    public static boolean supportsThinking(String model) {
        return AntigravityFormatBridge.supportsThinking(model);
    }

    /** Exercises {@link AntigravityFormatBridge#isAnthropicMessages}. */
    @JSExport
    public static boolean isAnthropicMessages(String url) {
        return AntigravityFormatBridge.isAnthropicMessages(url);
    }

    // ---- AntigravityStreamMapper (T7d) ------------------------------------------------------------

    // Deterministic id source for the transpilability proof (production ids are minted in the TS shell).
    private static final AntigravityStreamMapper.IdGenerator FIXED_IDS = new AntigravityStreamMapper.IdGenerator() {
        @Override
        public String newMessageId() {
            return "msg_js";
        }

        @Override
        public String newToolId() {
            return "toolu_js";
        }
    };

    /** Exercises the {@link AntigravityStreamMapper} state machine: feed each parsed object, then finish. */
    @JSExport
    @SuppressWarnings("unchecked")
    public static String geminiToAnthropicStream(String model, String objectsJson) {
        JsonCodec json = new SimpleJsonCodec();
        AntigravityStreamMapper mapper = new AntigravityStreamMapper(json, FIXED_IDS, model);
        StringBuilder sb = new StringBuilder();
        Object parsed = objectsJson != null ? json.parse(objectsJson) : null;
        if (parsed instanceof List) {
            for (Object obj : (List<Object>) parsed) {
                for (String ev : mapper.handle(obj)) sb.append(ev);
            }
        }
        for (String ev : mapper.finish()) sb.append(ev);
        return sb.toString();
    }

    // ---- AntigravityStreamTransform (T7d) ---------------------------------------------------------

    /** Exercises {@link AntigravityStreamTransform#hashString} (DJB2, {@code >>> 0}, base16). */
    @JSExport
    public static String hashString(String str) {
        return AntigravityStreamTransform.hashString(str != null ? str : "");
    }

    /** Exercises {@link AntigravityStreamTransform#transformStreamingPayload} (identity transform) via JsonCodec. */
    @JSExport
    public static String transformStreamingPayload(String payload) {
        return AntigravityStreamTransform.transformStreamingPayload(new SimpleJsonCodec(), payload, r -> r);
    }

    /** Exercises {@link AntigravityStreamTransform#deduplicateThinkingText} (data-URL image sink) via JsonCodec. */
    @JSExport
    public static String deduplicateThinkingText(String responseJson) {
        JsonCodec json = new SimpleJsonCodec();
        Object response = responseJson != null ? json.parse(responseJson) : null;
        AntigravityStreamTransform.ThoughtBuffer buffer = AntigravityStreamTransform.createThoughtBuffer();
        return json.stringify(AntigravityStreamTransform.deduplicateThinkingText(response, buffer, null, DATA_URL_SINK));
    }

    /** Exercises {@link AntigravityStreamTransform#transformSseLine} (cache + identity transform) via JsonCodec. */
    @JSExport
    public static String transformSseLine(String line, String sessionKey) {
        JsonCodec json = new SimpleJsonCodec();
        AntigravityStreamTransform.ThoughtBuffer thoughtBuffer = AntigravityStreamTransform.createThoughtBuffer();
        AntigravityStreamTransform.ThoughtBuffer sentBuffer = AntigravityStreamTransform.createThoughtBuffer();
        AntigravityStreamTransform.SignatureStore store = (k, t, s) -> { };
        AntigravityStreamTransform.DebugState debugState = new AntigravityStreamTransform.DebugState(false);
        return AntigravityStreamTransform.transformSseLine(
                json, line, store, thoughtBuffer, sentBuffer,
                null, null, r -> r, DATA_URL_SINK,
                sessionKey, null, true, null, debugState);
    }

    // ---- AntigravityFingerprint / AntigravityRequestKeys / AntigravityRequestSignatures (T7e) -----

    /** Exercises {@link AntigravityFingerprint#platformToDisplayName}. */
    @JSExport
    public static String platformToDisplayName(String platform) {
        return AntigravityFingerprint.platformToDisplayName(platform);
    }

    /** Exercises {@link AntigravityFingerprint#buildFingerprintHeaders} via JsonCodec. */
    @JSExport
    public static String buildFingerprintHeaders(String fingerprintJson) {
        JsonCodec json = new SimpleJsonCodec();
        Map<String, Object> fp = fingerprintJson != null ? asMap(json.parse(fingerprintJson)) : null;
        return json.stringify(AntigravityFingerprint.buildFingerprintHeaders(fp));
    }

    /** Exercises {@link AntigravityRequestKeys#buildSignatureSessionKey}. */
    @JSExport
    public static String buildSignatureSessionKey(String sessionId, String model, String conv, String proj) {
        return AntigravityRequestKeys.buildSignatureSessionKey(sessionId, model, conv, proj);
    }

    /** Exercises {@link AntigravityRequestKeys#resolveConversationKey} (sha256 seed via stub Hasher). */
    @JSExport
    public static String resolveConversationKey(String payloadJson) {
        JsonCodec json = new SimpleJsonCodec();
        String key = AntigravityRequestKeys.resolveConversationKey(STUB_HASHER, asMap(json.parse(payloadJson)));
        return key == null ? null : key;
    }

    /** Exercises {@link AntigravityRequestSignatures#injectDebugThinking} via JsonCodec. */
    @JSExport
    public static String injectDebugThinking(String responseJson, String debugText) {
        JsonCodec json = new SimpleJsonCodec();
        return json.stringify(AntigravityRequestSignatures.injectDebugThinking(json.parse(responseJson), debugText));
    }

    /** Exercises {@link AntigravityRequestSignatures#sanitizeRequestPayloadForAntigravity} (mutates). */
    @JSExport
    public static String sanitizeRequestPayload(String payloadJson) {
        JsonCodec json = new SimpleJsonCodec();
        Map<String, Object> payload = asMap(json.parse(payloadJson));
        AntigravityRequestSignatures.sanitizeRequestPayloadForAntigravity(payload);
        return json.stringify(payload);
    }

    // ---- AntigravityRequestPrep (T7e) -------------------------------------------------------------

    private static final AntigravityRequestKeys.Hasher STUB_HASHER =
            input -> "00000000000000000000000000000000000000000000000000000000000000ff";

    private static AntigravityRequestPrep.IdGenerator counterIds() {
        return new AntigravityRequestPrep.IdGenerator() {
            private long n = 0;

            @Override
            public String randomUuid() {
                n += 1;
                return "00000000-0000-4000-8000-00000000000" + n;
            }
        };
    }

    /** Exercises {@link AntigravityRequestPrep#generateSyntheticProjectId} (fixed Random + counter ids). */
    @JSExport
    public static String generateSyntheticProjectId() {
        return AntigravityRequestPrep.generateSyntheticProjectId(counterIds(), FIXED_RANDOM);
    }

    /**
     * Exercises the full {@link AntigravityRequestPrep#prepare} spine (JSON in -> JSON out), proving the
     * request-preparation pipeline + all its reused ported helpers transpile. Deterministic stub seams.
     */
    @JSExport
    @SuppressWarnings("unchecked")
    public static String prepareAntigravityRequest(String url, String method, String headersJson, String body,
                                                   String accessToken, String projectId, String headerStyle) {
        JsonCodec json = new SimpleJsonCodec();

        AntigravityRequestPrep.Input in = new AntigravityRequestPrep.Input();
        in.url = url;
        in.method = method;
        in.headers = headersJson != null ? asMap(json.parse(headersJson)) : new java.util.LinkedHashMap<>();
        in.body = body;
        in.accessToken = accessToken;
        in.projectId = projectId;
        in.headerStyle = headerStyle;
        in.fingerprint = null;

        AntigravityRequestPrep.Deps deps = new AntigravityRequestPrep.Deps();
        deps.json = json;
        deps.ids = counterIds();
        deps.random = FIXED_RANDOM;
        deps.hasher = STUB_HASHER;
        deps.cachedSignatureLookup = (sessionId, text) -> null;
        deps.signatureStore = new AntigravityRequestSignatures.SignatureStore() {
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
            }
        };
        deps.thinkingRecovery = new AntigravityRequestPrep.ThinkingRecovery() {
            @Override
            public Object analyzeConversationState(List<Object> contents) {
                return new java.util.LinkedHashMap<>();
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
        deps.logger = NOOP_LOGGER;
        deps.keepThinking = false;
        deps.pluginSessionId = "-00000000-0000-4000-8000-000000000000";
        deps.selectedHeaders = new java.util.LinkedHashMap<>();

        AntigravityRequestPrep.PrepareResult r = AntigravityRequestPrep.prepare(in, deps);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("request", r.request);
        out.put("headers", r.headers);
        out.put("body", r.body);
        out.put("streaming", r.streaming);
        out.put("effectiveModel", r.effectiveModel);
        out.put("projectId", r.projectId);
        out.put("sessionId", r.sessionId);
        out.put("headerStyle", r.headerStyle);
        return json.stringify(out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object parsed) {
        return parsed instanceof Map ? (Map<String, Object>) parsed : new java.util.LinkedHashMap<>();
    }

    // ---- AntigravityHandleRouting / AntigravityProjectContext / Orchestrator (T7f) ----------------

    /** Exercises {@link AntigravityHandleRouting#isAutoModel}. */
    @JSExport
    public static boolean isAutoModel(String model) {
        return AntigravityHandleRouting.isAutoModel(model);
    }

    /** Exercises {@link AntigravityHandleRouting#rewriteModelInUrl}. */
    @JSExport
    public static String rewriteModelInUrl(String url, String model) {
        return AntigravityHandleRouting.rewriteModelInUrl(url, model);
    }

    /** Exercises {@link AntigravityHandleRouting#retryAfterMsFromMessage}. */
    @JSExport
    public static String retryAfterMsFromMessage(String message) {
        return String.valueOf(AntigravityHandleRouting.retryAfterMsFromMessage(message));
    }

    /** Exercises {@link AntigravityProjectContext#detectCodeAssistPlatform} via the Platform seam. */
    @JSExport
    public static String detectCodeAssistPlatform(String platform, String arch) {
        return AntigravityProjectContext.detectCodeAssistPlatform(new AntigravityProjectContext.Platform() {
            @Override
            public String platform() {
                return platform;
            }

            @Override
            public String arch() {
                return arch;
            }
        });
    }

    /** Exercises {@link AntigravityProjectContext#ensureProjectContext} short-circuit path (JSON out). */
    @JSExport
    public static String ensureProjectContextManaged(String refresh) {
        JsonCodec json = new SimpleJsonCodec();
        Map<String, Object> auth = new java.util.LinkedHashMap<>();
        auth.put("type", "oauth");
        auth.put("access", "at");
        auth.put("refresh", refresh);
        AntigravityProjectContext.ProjectContextResult r = AntigravityProjectContext.ensureProjectContext(
                auth, null, null,
                (a, p, x) -> null, (a, t, p, x) -> null,
                new AntigravityProjectContext.Platform() {
                    @Override
                    public String platform() {
                        return "linux";
                    }

                    @Override
                    public String arch() {
                        return "x64";
                    }
                });
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("effectiveProjectId", r.effectiveProjectId);
        out.put("refresh", r.auth.get("refresh"));
        return json.stringify(out);
    }

    /**
     * Drives a full {@link AntigravityHandleOrchestrator#handle} through the no-account terminal
     * branch under TeaVM (proves the orchestrator + its JsonCodec body builders + attemptModel
     * transpile), returning the synthetic 503 body. Seams are inline anonymous impls.
     */
    @JSExport
    public static String handleNoAccountSmoke() {
        JsonCodec json = new SimpleJsonCodec();
        AntigravityHandleOrchestrator.AccountOps accounts = new AntigravityHandleOrchestrator.AccountOps() {
            @Override
            public AntigravityHandleOrchestrator.Acquired acquire(String lane) {
                return null;
            }

            @Override
            public Long nextAvailableAt(String lane) {
                return null;
            }

            @Override
            public void reportError(String accountId, int attempt, String message) {
            }

            @Override
            public void reportRateLimit(String accountId, String lane, long resetMs) {
            }

            @Override
            public void reportSuccess(String accountId) {
            }

            @Override
            public void reportProxyRateLimit(String accountId, boolean ipSuspected) {
            }

            @Override
            public List<Map<String, Object>> list() {
                return new ArrayList<>();
            }

            @Override
            public void mutate(String accountId, AntigravityHandleOrchestrator.Mutator mutator) {
            }
        };
        AntigravityHandleOrchestrator o = new AntigravityHandleOrchestrator(
                json, SYSTEM_CLOCK, FIXED_RANDOM, counterIds(), accounts,
                (url, body, method, headers, access, projectId, endpoint, headerStyle, account) -> null,
                (accountId, prepared) -> null,
                id -> null, (a, p, x) -> null, (a, t, p, x) -> null,
                new AntigravityProjectContext.Platform() {
                    @Override
                    public String platform() {
                        return "linux";
                    }

                    @Override
                    public String arch() {
                        return "x64";
                    }
                });
        AntigravityHandleOrchestrator.RequestInputs in = new AntigravityHandleOrchestrator.RequestInputs();
        in.ctxModel = "antigravity-claude-sonnet-4-6";
        in.url = "https://cloudcode-pa.googleapis.com/v1internal/models/antigravity-claude-sonnet-4-6:generateContent";
        in.method = "POST";
        in.headers = new java.util.LinkedHashMap<>();
        AntigravityHandleOrchestrator.HandleDecision d = o.handle(in);
        return d.body;
    }

    // ---- T7g1: the async entry (multi-@Async composition proof) ---------------------------------

    /**
     * THE T7g1 export the live-rewire task (T7g2) will build on: runs the FULL {@link
     * AntigravityHandleOrchestrator#handle} decision loop with host transport, account rotation and
     * project-context discovery supplied as JS async/sync callbacks, and surfaces the whole
     * (repeatedly-suspending) call graph to JS as ONE {@code Promise}. Inside the loop a single
     * {@code attemptModel} iteration can suspend on {@link JsAccountOpsBridge#acquire} (async) then,
     * inside {@code resolveProjectId}, on {@link JsProjectLoaderBridge#load} and {@link
     * JsProjectOnboarderBridge#onboard} (async), then on {@link JsAttemptExecutorBridge#execute}
     * (async) -- up to FOUR DISTINCT {@code @Async} bridges composing in one TeaVM CPS-transformed
     * call graph (a stronger composition than claude's two). Built by hand as a {@code JSPromise} over
     * a thread reaching the {@code @Async} boundaries (identical to {@code
     * ClaudeProviderJs.handleClaudeRequestAsync}) -- not {@code JSPromise.callAsync}, whose generic
     * {@code resolve.accept} would leak a raw {@code jl_String} instead of a real JS string.
     *
     * <p>ZERO live impact: this is not yet wired to antigravity-auth's TS runtime (that is T7g2);
     * the {@code RequestPreparer}/{@code ThinkingRecovery} live-wiring decision is deferred there too.
     * The {@code clock}/{@code random} are deterministic (config {@code nowMs}, else a fixed epoch;
     * fixed {@code random}=0.5) so the smoke can assert byte-parity with the T7f snapshots; T7g2
     * supplies real ones.
     *
     * @param inputsJson        {@code {url, method, headers:{}, bodyText, ctxModel?}}
     * @param configJson        {@code {nowMs?, platform?, arch?}} (deterministic clock + platform seam)
     * @param jsExec            async attempt transport ({@code fetch}+IP-proxy in prod)
     * @param jsAcquire         async {@code manager.acquire(lane)}
     * @param jsAccountOps      the grouped synchronous account ops (report/nextAvailableAt/list/mutate)
     * @param jsProjectLoader   async {@code loadManagedProject}
     * @param jsProjectOnboarder async {@code onboardManagedProject}
     * @param jsPreparer        sync request preparer ({@code prepareAntigravityRequest} stand-in)
     * @param autoCandidatesJson {@code getAutoCandidates(PROVIDER_ID)} as a JSON array, or {@code null}
     * @return a {@code Promise<string>} resolving with the serialized {@link
     *         AntigravityHandleOrchestrator.HandleDecision} (a discriminated union keyed by {@code kind})
     */
    /** JS {@code () => number} (production: {@code Math.random}) -> the {@link Random} SPI. */
    @JSFunctor
    public interface JsRandomFn extends JSObject {
        double next();
    }

    /** JS {@code () => string} (production: {@code crypto.randomUUID}) -> the {@link
     *  AntigravityRequestPrep.IdGenerator} feeding {@code generateSyntheticProjectId}. */
    @JSFunctor
    public interface JsUuidFn extends JSObject {
        JSString uuid();
    }

    @JSExport
    public static JSPromise<JSString> handleAntigravityRequestAsync(
            String inputsJson,
            String configJson,
            JsAttemptExecutorBridge.JsExecFn jsExec,
            JsAccountOpsBridge.JsAcquireFn jsAcquire,
            JsAccountOpsBridge.JsAccountFns jsAccountOps,
            JsProjectLoaderBridge.JsLoadFn jsProjectLoader,
            JsProjectOnboarderBridge.JsOnboardFn jsProjectOnboarder,
            JsRequestPreparerBridge.JsPrepareFn jsPreparer,
            String autoCandidatesJson,
            JsRandomFn jsRandom,
            JsUuidFn jsUuid) {
        return new JSPromise<>((resolve, reject) -> new Thread(() -> {
            try {
                JsonCodec json = new SimpleJsonCodec();
                Clock clock = parseClock(json, configJson);
                AntigravityProjectContext.Platform platform = parsePlatform(json, configJson);

                // Real entropy injected from JS (CRITICAL-1 fix): without these the baked FIXED_RANDOM
                // (0.5) + counter ids made generateSyntheticProjectId a CONSTANT ("swift-spark-00000"),
                // so every account lacking a discovered managed project minted + persisted the SAME
                // x-goog-user-project-equivalent and got correlated (index.ts:108-109). The node smoke /
                // parity harness pass deterministic stand-ins through these SAME seams.
                Random random = () -> jsRandom.next();
                AntigravityRequestPrep.IdGenerator ids = () -> {
                    JSString u = jsUuid.uuid();
                    return u == null ? "" : u.stringValue();
                };

                AntigravityHandleOrchestrator orchestrator = new AntigravityHandleOrchestrator(
                        json, clock, random, ids,
                        new JsAccountOpsBridge(jsAcquire, jsAccountOps, json),
                        new JsRequestPreparerBridge(jsPreparer, json),
                        new JsAttemptExecutorBridge(jsExec, json),
                        modelId -> null,
                        new JsProjectLoaderBridge(jsProjectLoader, json),
                        new JsProjectOnboarderBridge(jsProjectOnboarder, json),
                        platform);

                AntigravityHandleOrchestrator.RequestInputs in = parseInputs(json, inputsJson, autoCandidatesJson);

                // transitively async: handle() -> acquire()/load()/onboard()/execute() each suspend at @Async
                AntigravityHandleOrchestrator.HandleDecision decision = orchestrator.handle(in);

                resolve.accept(JSString.valueOf(decisionToJson(json, decision)));
            } catch (Throwable e) {
                reject.accept(JSString.valueOf("handleAntigravityRequestAsync failed: " + e));
            }
        }).start());
    }

    private static Clock parseClock(JsonCodec json, String configJson) {
        Object parsed = configJson != null ? json.parse(configJson) : null;
        if (parsed instanceof Map) {
            Object nowMs = ((Map<?, ?>) parsed).get("nowMs");
            if (nowMs instanceof Number) {
                final long fixed = ((Number) nowMs).longValue();
                return () -> fixed;
            }
        }
        return () -> System.currentTimeMillis();
    }

    private static AntigravityProjectContext.Platform parsePlatform(JsonCodec json, String configJson) {
        String platform = "linux";
        String arch = "x64";
        Object parsed = configJson != null ? json.parse(configJson) : null;
        if (parsed instanceof Map) {
            Object p = ((Map<?, ?>) parsed).get("platform");
            Object a = ((Map<?, ?>) parsed).get("arch");
            if (p instanceof String) platform = (String) p;
            if (a instanceof String) arch = (String) a;
        }
        final String platformValue = platform;
        final String archValue = arch;
        return new AntigravityProjectContext.Platform() {
            @Override
            public String platform() {
                return platformValue;
            }

            @Override
            public String arch() {
                return archValue;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static AntigravityHandleOrchestrator.RequestInputs parseInputs(JsonCodec json, String inputsJson, String autoCandidatesJson) {
        AntigravityHandleOrchestrator.RequestInputs in = new AntigravityHandleOrchestrator.RequestInputs();
        Object parsed = inputsJson != null ? json.parse(inputsJson) : null;
        if (parsed instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) parsed;
            in.url = asString(m.get("url"));
            in.method = asString(m.get("method"));
            in.bodyText = asString(m.get("bodyText"));
            in.ctxModel = asString(m.get("ctxModel"));
            Map<String, String> headers = new LinkedHashMap<>();
            Object headersVal = m.get("headers");
            if (headersVal instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<Object, Object>) headersVal).entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
            }
            in.headers = headers;
        } else {
            in.headers = new LinkedHashMap<>();
        }
        Object autos = autoCandidatesJson != null ? json.parse(autoCandidatesJson) : null;
        if (autos instanceof List) {
            List<String> candidates = new ArrayList<>();
            for (Object c : (List<Object>) autos) {
                if (c instanceof String) candidates.add((String) c);
            }
            in.autoCandidates = candidates;
        }
        return in;
    }

    private static String decisionToJson(JsonCodec json, AntigravityHandleOrchestrator.HandleDecision decision) {
        Map<String, Object> out = new LinkedHashMap<>();
        AntigravityHandleOrchestrator.HandleDecision.Kind kind = decision.kind;
        out.put("kind", kind.name());
        out.put("status", decision.status);
        if (kind == AntigravityHandleOrchestrator.HandleDecision.Kind.SERVE) {
            out.put("attemptRef", decision.attemptRef);
            out.put("params", paramsToMap(decision.params));
        } else if (kind == AntigravityHandleOrchestrator.HandleDecision.Kind.SERVE_RAW
                || kind == AntigravityHandleOrchestrator.HandleDecision.Kind.BRIDGE_STREAM) {
            out.put("attemptRef", decision.attemptRef);
        } else if (kind == AntigravityHandleOrchestrator.HandleDecision.Kind.SYNTHETIC) {
            out.put("headers", decision.headers);
            out.put("body", decision.body);
        } else if (kind == AntigravityHandleOrchestrator.HandleDecision.Kind.TERMINAL_ERROR) {
            out.put("terminal", terminalToMap(decision.terminal));
        }
        return json.stringify(out);
    }

    private static Map<String, Object> paramsToMap(AntigravityHandleOrchestrator.TransformParams p) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (p == null) return m;
        m.put("requestedModel", p.requestedModel);
        m.put("projectId", p.projectId);
        m.put("endpoint", p.endpoint);
        m.put("effectiveModel", p.effectiveModel);
        m.put("sessionId", p.sessionId);
        m.put("streaming", p.streaming);
        return m;
    }

    private static Map<String, Object> terminalToMap(AntigravityHandleOrchestrator.TerminalError t) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (t == null) return m;
        m.put("kind", t.kind.name());
        m.put("status", t.status);
        m.put("messagePrefix", t.messagePrefix);
        m.put("messageSuffix", t.messageSuffix);
        m.put("resetEpochMs", t.resetEpochMs);
        m.put("retryAfterMs", t.retryAfterMs);
        return m;
    }

    private static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    // ---- Production seam exports (Task 2: TeaVM de-dup) --------------------------------------------

    /** JS {@code (input) => string} (production: sha256 hex) -> the {@link AntigravityRequestKeys.Hasher} SPI. */
    @JSFunctor
    public interface JsHasherFn extends JSObject {
        JSString hash(JSString input);
    }

    /** JS {@code (sessionId, text) => string|null} -> the {@link AntigravityThinkingBlocks.CachedSignatureLookup} SPI. */
    @JSFunctor
    public interface JsCacheLookupFn extends JSObject {
        JSString get(JSString sessionId, JSString text);
    }

    /**
     * {@code defaultSignatureStore}'s get/set/has/delete, grouped in ONE JS object -- multi-method, so
     * NOT a {@code @JSFunctor} (mirrors {@link JsAccountOpsBridge.JsAccountFns}). {@code get} returns a
     * JSON {@code {text,signature}} string (or {@code null}/{@code "null"}/empty for a miss).
     */
    public interface JsSignatureStoreFns extends JSObject {
        JSString get(JSString key);

        boolean has(JSString key);

        void delete(JSString key);

        void set(JSString sessionKey, JSString text, JSString signature);
    }

    /** {@code crypto.randomUUID}-derived id pair for {@link AntigravityStreamMapper.IdGenerator}, grouped like {@link JsSignatureStoreFns}. */
    public interface JsIdsFns extends JSObject {
        JSString newMessageId();

        JSString newToolId();
    }

    /**
     * Production variant of {@link #prepareAntigravityRequest}: same pipeline, but every {@link
     * AntigravityRequestPrep.Deps} seam is bridged to a real JS host implementation instead of a
     * deterministic stub. {@code thinkingRecovery} is the internal {@link AntigravityThinkingRecovery}
     * (Task 1 port) -- not a JS seam. {@code endpointOverride} (empty/{@code null} for "use default"),
     * {@code claudeToolHardening} and {@code claudePromptAutoCaching} close the Task 3 report's gaps
     * #1/#2 -- the host resolves the config-driven booleans (defaults true/false) and the per-attempt
     * endpoint override exactly as {@code prepareAntigravityRequest}'s callers do.
     */
    @JSExport
    public static String prepareAntigravityRequestProd(
            String url, String method, String headersJson, String body,
            String accessToken, String projectId, String headerStyle, String fingerprintJson,
            boolean keepThinking, String pluginSessionId, String endpointOverride,
            boolean claudeToolHardening, boolean claudePromptAutoCaching,
            JsRandomFn jsRandom, JsUuidFn jsUuid, JsHasherFn jsHasher,
            JsCacheLookupFn jsCacheLookup, JsSignatureStoreFns jsSignatureStore) {
        JsonCodec json = new SimpleJsonCodec();
        Map<String, Object> fp = fingerprintJson != null ? asMap(json.parse(fingerprintJson)) : null;

        AntigravityRequestPrep.Input in = new AntigravityRequestPrep.Input();
        in.url = url;
        in.method = method;
        in.headers = headersJson != null ? asMap(json.parse(headersJson)) : new LinkedHashMap<>();
        in.body = body;
        in.accessToken = accessToken;
        in.projectId = projectId;
        in.headerStyle = headerStyle;
        in.fingerprint = fp;
        in.endpointOverride = (endpointOverride != null && !endpointOverride.isEmpty()) ? endpointOverride : null;
        in.claudeToolHardening = claudeToolHardening;
        in.claudePromptAutoCaching = claudePromptAutoCaching;

        AntigravityRequestPrep.Deps deps = new AntigravityRequestPrep.Deps();
        deps.json = json;
        deps.random = () -> jsRandom.next();
        deps.ids = () -> {
            JSString u = jsUuid.uuid();
            return u == null ? "" : u.stringValue();
        };
        deps.hasher = input -> {
            JSString h = jsHasher.hash(JSString.valueOf(input));
            return h == null ? null : h.stringValue();
        };
        deps.cachedSignatureLookup = (sessionId, text) -> {
            JSString r = jsCacheLookup.get(JSString.valueOf(sessionId), JSString.valueOf(text));
            return r == null ? null : r.stringValue();
        };
        deps.signatureStore = new AntigravityRequestSignatures.SignatureStore() {
            @Override
            @SuppressWarnings("unchecked")
            public Map<String, Object> get(String key) {
                JSString r = jsSignatureStore.get(JSString.valueOf(key));
                String s = r == null ? null : r.stringValue();
                if (s == null || s.isEmpty() || "null".equals(s)) return null;
                Object parsed = json.parse(s);
                return parsed instanceof Map ? (Map<String, Object>) parsed : null;
            }

            @Override
            public boolean has(String key) {
                return jsSignatureStore.has(JSString.valueOf(key));
            }

            @Override
            public void delete(String key) {
                jsSignatureStore.delete(JSString.valueOf(key));
            }
        };
        deps.thinkingRecovery = new AntigravityThinkingRecovery(json);
        deps.logger = NOOP_LOGGER;
        deps.keepThinking = keepThinking;
        deps.pluginSessionId = pluginSessionId;
        deps.selectedHeaders = AntigravityFingerprint.buildFingerprintHeaders(fp);

        AntigravityRequestPrep.PrepareResult r = AntigravityRequestPrep.prepare(in, deps);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request", r.request);
        out.put("headers", r.headers);
        out.put("body", r.body);
        out.put("streaming", r.streaming);
        out.put("effectiveModel", r.effectiveModel);
        out.put("projectId", r.projectId);
        out.put("sessionId", r.sessionId);
        out.put("headerStyle", r.headerStyle);
        return json.stringify(out);
    }

    /** Stateful JS handle over one {@link AntigravityStreamMapper} instance -- {@link #newStreamMapper}'s return. */
    public interface JsStreamMapperHandle extends JSObject {
        JSArray<JSString> handle(JSString objJson);

        JSArray<JSString> finish();
    }

    /** Factory for a stateful stream mapper: ONE captured {@link AntigravityStreamMapper}, driven by the TS {@code TransformStream} (Task 3). */
    @JSExport
    public static JsStreamMapperHandle newStreamMapper(String model, JsIdsFns jsIds) {
        JsonCodec json = new SimpleJsonCodec();
        AntigravityStreamMapper.IdGenerator ids = new AntigravityStreamMapper.IdGenerator() {
            @Override
            public String newMessageId() {
                JSString s = jsIds.newMessageId();
                return s == null ? "" : s.stringValue();
            }

            @Override
            public String newToolId() {
                JSString s = jsIds.newToolId();
                return s == null ? "" : s.stringValue();
            }
        };
        AntigravityStreamMapper mapper = new AntigravityStreamMapper(json, ids, model);
        return new JsStreamMapperHandle() {
            @Override
            public JSArray<JSString> handle(JSString objJson) {
                Object parsed = objJson != null ? json.parse(objJson.stringValue()) : null;
                return toJsStringArray(mapper.handle(parsed));
            }

            @Override
            public JSArray<JSString> finish() {
                return toJsStringArray(mapper.finish());
            }
        };
    }

    // No String[] idiom exists elsewhere in this module; JSArray<JSString> is the documented fallback.
    private static JSArray<JSString> toJsStringArray(List<String> values) {
        JSArray<JSString> arr = new JSArray<>();
        for (String v : values) {
            arr.push(JSString.valueOf(v));
        }
        return arr;
    }

    /**
     * Exports {@link AntigravityStreamTransform#cacheThinkingSignaturesFromResponse} (a fresh per-call
     * {@link AntigravityStreamTransform.ThoughtBuffer}; the JVM signature also requires a
     * {@code signatureSessionKey}, so this export takes one beyond the plan's 2-arg sketch).
     */
    @JSExport
    public static void cacheSignaturesFromResponse(String responseJson, String signatureSessionKey, JsSignatureStoreFns jsSignatureStore) {
        JsonCodec json = new SimpleJsonCodec();
        Object response = responseJson != null ? json.parse(responseJson) : null;
        AntigravityStreamTransform.ThoughtBuffer thoughtBuffer = AntigravityStreamTransform.createThoughtBuffer();
        AntigravityStreamTransform.SignatureStore store = (sessionKey, text, signature) ->
                jsSignatureStore.set(JSString.valueOf(sessionKey), JSString.valueOf(text), JSString.valueOf(signature));
        AntigravityStreamTransform.cacheThinkingSignaturesFromResponse(response, signatureSessionKey, store, thoughtBuffer, null);
    }

    // ---- Real-seamed response-transform exports (Task 3c: route SERVE through Java) ----------------

    /**
     * JS {@code (mimeType, base64Data) => string|null} -- production: the real {@code processImageData}
     * (image-saver.ts), which SAVES the decoded image to {@code ~/.opencode|.claude/generated-images/}
     * and returns a markdown link (or a data-URL fallback on a write failure). Replaces the
     * transpilability-only {@code DATA_URL_SINK} stand-in for every production response-transform export
     * below.
     */
    @JSFunctor
    public interface JsImageSinkFn extends JSObject {
        JSString save(JSString mimeType, JSString base64Data);
    }

    /** JS {@code (sessionKey, text, signature) => void} -- production: {@code cacheSignature} (cache.ts), the on-disk signature cache write. */
    @JSFunctor
    public interface JsCacheSignatureFn extends JSObject {
        void onCacheSignature(JSString sessionKey, JSString text, JSString signature);
    }

    /**
     * Host-owned Gemini-3 SSE-reconnect thought-dedup set (request.ts:79's {@code
     * sessionDisplayedThinkingHashes}) -- has/add over hash strings, grouped like {@link
     * JsSignatureStoreFns} (multi-method, so not a {@code @JSFunctor}). The host passes {@code null}
     * for every non-Gemini-3 {@code effectiveModel} (request.ts:1770's exact gate), matching TS.
     */
    public interface JsThoughtDedupFns extends JSObject {
        boolean has(JSString hash);

        void add(JSString hash);
    }

    // Bridges a nullable JsThoughtDedupFns to the java.util.Set<String> that
    // AntigravityStreamTransform#transformSseLine/deduplicateThinkingText expect. Only contains()/add()
    // are ever invoked by that pure logic (never iterated/sized) -- those are the only AbstractSet
    // methods overridden.
    private static Set<String> bridgeThoughtDedup(JsThoughtDedupFns fns) {
        if (fns == null) return null;
        return new AbstractSet<String>() {
            @Override
            public boolean contains(Object o) {
                return o instanceof String && fns.has(JSString.valueOf((String) o));
            }

            @Override
            public boolean add(String hash) {
                fns.add(JSString.valueOf(hash));
                return true;
            }

            @Override
            public Iterator<String> iterator() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int size() {
                throw new UnsupportedOperationException();
            }
        };
    }

    // A missing mimeType/data maps to "" (not null): both are equally falsy in JS, matching
    // processImageData's own `mimeType || 'image/png'` / `if (!data) return null;` fallbacks exactly.
    private static AntigravityThinkingBlocks.ImageSink bridgeImageSink(JsImageSinkFn sink) {
        return (mimeType, data) -> {
            String mt = mimeType instanceof String ? (String) mimeType : "";
            String d = data instanceof String ? (String) data : "";
            JSString r = sink.save(JSString.valueOf(mt), JSString.valueOf(d));
            return r == null ? null : r.stringValue();
        };
    }

    /**
     * Production non-streaming SERVE-transform export: the "OK JSON body" half of
     * {@code transformAntigravityResponse} (request.ts:1782-1926, real-seamed via {@link
     * AntigravityResponseTransform#transformServe}). {@code debugText} is the host-resolved
     * {@code isDebugTuiEnabled()}/{@code getKeepThinking()} placeholder (empty/{@code null} for
     * "none" -- request.ts:1735-1740); {@code jsImageSink} bridges the real {@code processImageData}.
     * Returns {@code {status, headers, body}} JSON for the host to build the final {@code Response}.
     */
    @JSExport
    public static String transformServeBodyProd(
            String bodyText, int status, String headersJson, String requestedModel,
            String debugText, JsImageSinkFn jsImageSink) {
        JsonCodec json = new SimpleJsonCodec();
        HttpResponse upstream = new HttpResponse();
        upstream.status = status;
        upstream.headers = asStringHeaders(json, headersJson);
        upstream.body = bodyText;

        AntigravityHandleOrchestrator.TransformParams params =
                new AntigravityHandleOrchestrator.TransformParams(requestedModel, null, null, null, null, false);
        String resolvedDebugText = debugText != null && !debugText.isEmpty() ? debugText : null;

        HttpResponse result = AntigravityResponseTransform.transformServe(
                json, upstream, params, resolvedDebugText, bridgeImageSink(jsImageSink));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", (long) (result != null ? result.status : status));
        out.put("headers", result != null ? result.headers : upstream.headers);
        out.put("body", result != null ? result.body : bodyText);
        return json.stringify(out);
    }

    private static Map<String, String> asStringHeaders(JsonCodec json, String headersJson) {
        Map<String, String> out = new LinkedHashMap<>();
        Object parsed = headersJson != null ? json.parse(headersJson) : null;
        if (parsed instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        return out;
    }

    /**
     * Stateful JS handle over ONE streaming SERVE-transform "session" (thought/sent buffers +
     * debug-injected flag persist across lines, mirroring {@code createStreamingTransformer}'s
     * per-stream closures) -- {@link #newResponseSseTransformer}'s return.
     */
    public interface JsResponseSseHandle extends JSObject {
        JSString handle(JSString line);
    }

    /**
     * Factory for the streaming half of {@code transformAntigravityResponse}
     * (request.ts:1758-1774's {@code createStreamingTransformer} options, real-seamed via {@link
     * AntigravityStreamTransform#transformSseLine}). The host {@code TransformStream} shell (line
     * buffering + the 45s watchdog + the synthetic-usage flush) stays TS (Task 3c's
     * {@code javaStream.ts}); every line's dedup/signature-cache/debug-inject/thinking-transform
     * decision runs here. {@code jsSignatureStore} is the SAME adapter {@link
     * #prepareAntigravityRequestProd} uses (bridges the real {@code defaultSignatureStore});
     * {@code jsCacheSignature} bridges the real on-disk {@code cacheSignature}; {@code jsImageSink}
     * bridges the real {@code processImageData}; {@code jsThoughtDedup} bridges the real Gemini-3
     * SSE-reconnect {@code sessionDisplayedThinkingHashes} dedup set (request.ts:79) -- the host passes
     * {@code null} here for every non-Gemini-3 {@code effectiveModel}, exactly mirroring TS's own
     * {@code effectiveModel && isGemini3Model(effectiveModel) ? sessionDisplayedThinkingHashes :
     * undefined} gate (request.ts:1770); this method never re-derives that gate itself.
     */
    @JSExport
    public static JsResponseSseHandle newResponseSseTransformer(
            String signatureSessionKey, String debugText, boolean cacheSignatures,
            JsSignatureStoreFns jsSignatureStore, JsCacheSignatureFn jsCacheSignature, JsImageSinkFn jsImageSink,
            JsThoughtDedupFns jsThoughtDedup) {
        JsonCodec json = new SimpleJsonCodec();
        AntigravityStreamTransform.ThoughtBuffer thoughtBuffer = AntigravityStreamTransform.createThoughtBuffer();
        AntigravityStreamTransform.ThoughtBuffer sentBuffer = AntigravityStreamTransform.createThoughtBuffer();
        AntigravityStreamTransform.DebugState debugState = new AntigravityStreamTransform.DebugState(false);
        String resolvedDebugText = debugText != null && !debugText.isEmpty() ? debugText : null;
        Set<String> displayedThinkingHashes = bridgeThoughtDedup(jsThoughtDedup);

        AntigravityStreamTransform.SignatureStore store = (sessionKey, text, signature) ->
                jsSignatureStore.set(JSString.valueOf(sessionKey), JSString.valueOf(text), JSString.valueOf(signature));
        AntigravityStreamTransform.CacheSignatureCallback onCacheSignature = (sessionKey, text, signature) ->
                jsCacheSignature.onCacheSignature(JSString.valueOf(sessionKey), JSString.valueOf(text), JSString.valueOf(signature));
        AntigravityStreamTransform.InjectDebug onInjectDebug = AntigravityRequestSignatures::injectDebugThinking;
        AntigravityThinkingBlocks.ImageSink imageSink = bridgeImageSink(jsImageSink);
        AntigravityStreamTransform.ThinkingPartsTransform transformThinkingParts = response ->
                AntigravityThinkingBlocks.transformThinkingParts(
                        response, value -> AntigravityResponseParse.recursivelyParseJsonStrings(json, value), imageSink);

        return new JsResponseSseHandle() {
            @Override
            public JSString handle(JSString lineJs) {
                String line = lineJs == null ? "" : lineJs.stringValue();
                String out = AntigravityStreamTransform.transformSseLine(
                        json, line, store, thoughtBuffer, sentBuffer,
                        onCacheSignature, onInjectDebug, transformThinkingParts, imageSink,
                        signatureSessionKey, resolvedDebugText, cacheSignatures, displayedThinkingHashes, debugState);
                return JSString.valueOf(out);
            }
        };
    }
}
