package io.github.intisy.ai.js;

import io.github.intisy.ai.antigravity.AntigravityAuth;
import io.github.intisy.ai.antigravity.AntigravityCatalog;
import io.github.intisy.ai.antigravity.AntigravityLanes;
import io.github.intisy.ai.antigravity.AntigravityQuotaParser;
import io.github.intisy.ai.antigravity.AntigravityVersions;
import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.JsonCodec;
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
}
