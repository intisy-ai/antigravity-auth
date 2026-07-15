package io.github.intisy.ai.antigravity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java port of the two PURE formatters from {@code src/plugin/fingerprint.ts} (T7e; deferred from
 * T7a's review correction): {@code platformToDisplayName} (:84) and {@code buildFingerprintHeaders}
 * (:126). Everything else in fingerprint.ts STAYS TS (Bucket C): {@code generateFingerprint} (:100,
 * uses {@code os}/{@code crypto}/{@code Date.now}), {@code getSessionFingerprint} (:146 singleton),
 * {@code generateDeviceId}/{@code generateSessionToken}/{@code randomFrom} (crypto/RNG).
 *
 * <p>{@code buildFingerprintHeaders} takes the fingerprint as a JSON {@link Map} (or {@code null})
 * mirroring the TS {@code Fingerprint | null}; it reads only {@code userAgent}. TeaVM-transpilable.
 */
public final class AntigravityFingerprint {

    private AntigravityFingerprint() {
    }

    /** fingerprint.ts:84 -- {@code "win32" -> "WINDOWS"}, else {@code "MACOS"}. */
    public static String platformToDisplayName(String platform) {
        return "win32".equals(platform) ? "WINDOWS" : "MACOS";
    }

    /**
     * fingerprint.ts:126 -- {@code null -> {}}; else {@code {"User-Agent": fingerprint.userAgent}}.
     * The returned map is fresh; a {@code null}/absent {@code userAgent} passes straight through
     * (matching the TS, which would set {@code undefined}) but every valid fingerprint carries one.
     */
    public static Map<String, Object> buildFingerprintHeaders(Map<String, Object> fingerprint) {
        Map<String, Object> headers = new LinkedHashMap<>();
        if (fingerprint == null) {
            return headers;
        }
        headers.put("User-Agent", fingerprint.get("userAgent"));
        return headers;
    }
}
