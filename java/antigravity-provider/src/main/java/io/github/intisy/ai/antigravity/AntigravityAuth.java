package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.Clock;

import java.util.Map;

/**
 * Java port of antigravity-auth's {@code src/plugin/auth.ts} (Bucket A, "Small self-contained
 * units (T7a)" of {@code .superpowers/port-grounding-map.md}): {@code isOAuthAuth},
 * {@code parseRefreshParts}, {@code formatRefreshParts}, {@code accessTokenExpired} (Clock
 * injected in place of {@code Date.now()}), and {@code calculateTokenExpiry}.
 *
 * <p>Operates over a plain {@code Map<String, Object>} JSON tree (the shape both gson and this
 * ecosystem's {@code JsonCodec} SPI produce) rather than a typed {@code AuthDetails} class, so it
 * stays TeaVM-transpilable without needing a dedicated DTO. No java.net/nio/reflection/threads/
 * System.getenv -- see {@code :antigravity-teavm}.
 */
public final class AntigravityAuth {

    // auth.ts:3
    private static final long ACCESS_TOKEN_EXPIRY_BUFFER_MS = 60 * 1000;

    private AntigravityAuth() {
    }

    // ---- isOAuthAuth (auth.ts:5-7) --------------------------------------------------------------

    /** True when {@code auth.type === "oauth"} (a TS type guard here reduced to a plain check). */
    public static boolean isOAuthAuth(Map<String, Object> auth) {
        return auth != null && "oauth".equals(auth.get("type"));
    }

    // ---- parseRefreshParts (auth.ts:9-19) ---------------------------------------------------------

    /** Mirrors the TS {@code RefreshParts} shape: {@code {refreshToken, projectId?, managedProjectId?}}. */
    public static final class RefreshParts {
        public String refreshToken;
        /** {@code null} represents TS {@code undefined} (an empty segment coerces to undefined). */
        public String projectId;
        public String managedProjectId;

        public RefreshParts() {
        }

        public RefreshParts(String refreshToken, String projectId, String managedProjectId) {
            this.refreshToken = refreshToken;
            this.projectId = projectId;
            this.managedProjectId = managedProjectId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof RefreshParts)) return false;
            RefreshParts other = (RefreshParts) o;
            return eq(refreshToken, other.refreshToken) && eq(projectId, other.projectId)
                    && eq(managedProjectId, other.managedProjectId);
        }

        private static boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(refreshToken, projectId, managedProjectId);
        }
    }

    /**
     * Splits a packed refresh string into its constituent refresh token and project IDs, matching
     * the TS destructuring EXACTLY: {@code const [refreshToken = "", projectId = "", managedProjectId
     * = ""] = (refresh ?? "").split("|")} followed by {@code projectId || undefined} /
     * {@code managedProjectId || undefined}. A default ({@code ""}) only applies when the split
     * array is SHORTER than the destructured index (not when that segment is itself an empty
     * string) -- so {@code split("\\|", -1)} (which, unlike Java's default split, preserves
     * trailing empty segments exactly like JS's {@code String.split}) is required for parity.
     */
    public static RefreshParts parseRefreshParts(String refresh) {
        String[] parts = (refresh == null ? "" : refresh).split("\\|", -1);
        String refreshToken = parts.length > 0 ? parts[0] : "";
        String projectIdRaw = parts.length > 1 ? parts[1] : "";
        String managedRaw = parts.length > 2 ? parts[2] : "";
        return new RefreshParts(refreshToken, projectIdRaw.isEmpty() ? null : projectIdRaw,
                managedRaw.isEmpty() ? null : managedRaw);
    }

    // ---- formatRefreshParts (auth.ts:21-28) -------------------------------------------------------

    /**
     * Serializes refresh token parts into the stored string format: {@code refreshToken|projectId}
     * (or {@code refreshToken|projectId|managedProjectId} when a managed project id is present).
     * Mirrors the TS {@code ??} (nullish, so an explicit empty-string {@code projectId} is kept
     * as-is) vs {@code ? :} (truthy, so an empty-string {@code managedProjectId} is DROPPED)
     * asymmetry exactly.
     */
    public static String formatRefreshParts(RefreshParts parts) {
        String projectSegment = parts.projectId != null ? parts.projectId : "";
        String base = parts.refreshToken + "|" + projectSegment;
        return (parts.managedProjectId != null && !parts.managedProjectId.isEmpty())
                ? base + "|" + parts.managedProjectId
                : base;
    }

    // ---- accessTokenExpired (auth.ts:30-38) -------------------------------------------------------

    /**
     * Determines whether an access token is expired or missing, with a 60s buffer for clock skew.
     * {@code auth.expires} must be a {@code Number} (matches the TS {@code typeof ... !== "number"}
     * guard, which also rejects a numeric string).
     *
     * @param clock injected in place of the TS's implicit {@code Date.now()} call, for
     *              deterministic parity tests.
     */
    public static boolean accessTokenExpired(Map<String, Object> auth, Clock clock) {
        Object access = auth == null ? null : auth.get("access");
        Object expires = auth == null ? null : auth.get("expires");
        if (!JsCoercion.isTruthy(access) || !(expires instanceof Number)) {
            return true;
        }
        double expiresMs = ((Number) expires).doubleValue();
        return expiresMs <= clock.now() + ACCESS_TOKEN_EXPIRY_BUFFER_MS;
    }

    // ---- calculateTokenExpiry (auth.ts:40-52) -----------------------------------------------------

    /**
     * Calculates the absolute expiry timestamp from a requested duration, defaulting to one hour
     * when {@code expiresInSeconds} is not a {@code Number} (matches the TS {@code typeof ... ===
     * "number" ? expiresInSeconds : 3600}), and returning {@code requestTimeMs} unchanged for a
     * NaN/non-positive duration.
     *
     * @param requestTimeMs   the local time when the request was initiated
     * @param expiresInSeconds the duration returned by the server (may be any JSON-parsed value)
     */
    public static long calculateTokenExpiry(long requestTimeMs, Object expiresInSeconds) {
        double seconds = expiresInSeconds instanceof Number ? ((Number) expiresInSeconds).doubleValue() : 3600;
        if (Double.isNaN(seconds) || seconds <= 0) {
            return requestTimeMs;
        }
        return requestTimeMs + (long) (seconds * 1000);
    }
}
