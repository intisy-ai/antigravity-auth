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

    /**
     * What a platform is called in the headers the upstream reads.
     *
     * @param platform the platform name, as the runtime reports it
     * @return the display name, which is the mac one for anything that is not windows
     */
    public static String platformToDisplayName(String platform) {
        return "win32".equals(platform) ? "WINDOWS" : "MACOS";
    }

    /**
     * {@code null -> {}}; else {@code {"User-Agent": fingerprint.userAgent}}. The returned map is fresh;
     * a {@code null}/absent {@code userAgent} passes straight through, though every valid fingerprint
     * carries one.
     *
     * @param fingerprint the account's stored fingerprint, or {@code null}
     * @return the headers it contributes, which is a fresh map
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
