package io.github.intisy.ai.antigravity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The two pure fingerprint formatters: {@code platformToDisplayName} and {@code buildFingerprintHeaders}.
 * Fingerprint generation itself (os/crypto/Date.now, the session singleton, and the device-id/session-token
 * RNG) stays in TS.
 *
 * <p>{@code buildFingerprintHeaders} takes the fingerprint as a JSON {@link Map} (or {@code null}) and
 * reads only {@code userAgent}. TeaVM-transpilable.
 */
public final class AntigravityFingerprint {

    private AntigravityFingerprint() {
    }

    /** {@code "win32" -> "WINDOWS"}, else {@code "MACOS"}. */
    public static String platformToDisplayName(String platform) {
        return "win32".equals(platform) ? "WINDOWS" : "MACOS";
    }

    /**
     * {@code null -> {}}; else {@code {"User-Agent": fingerprint.userAgent}}. The returned map is fresh;
     * a {@code null}/absent {@code userAgent} passes straight through, though every valid fingerprint
     * carries one.
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
