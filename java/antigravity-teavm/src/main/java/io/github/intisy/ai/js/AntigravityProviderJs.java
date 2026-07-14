package io.github.intisy.ai.js;

import io.github.intisy.ai.antigravity.AntigravityAuth;
import io.github.intisy.ai.antigravity.AntigravityCatalog;
import io.github.intisy.ai.antigravity.AntigravityLanes;
import io.github.intisy.ai.antigravity.AntigravityModelResolver;
import io.github.intisy.ai.antigravity.AntigravityQuotaParser;
import io.github.intisy.ai.antigravity.AntigravitySchemaCleaner;
import io.github.intisy.ai.antigravity.AntigravityThinkingBlocks;
import io.github.intisy.ai.antigravity.AntigravityThinkingConfig;
import io.github.intisy.ai.antigravity.AntigravityVersions;
import io.github.intisy.ai.antigravity.ClaudeTransforms;
import io.github.intisy.ai.antigravity.CrossModelSanitizer;
import io.github.intisy.ai.antigravity.GeminiTransforms;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Random;

import org.teavm.jso.JSExport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object parsed) {
        return parsed instanceof Map ? (Map<String, Object>) parsed : new java.util.LinkedHashMap<>();
    }
}
