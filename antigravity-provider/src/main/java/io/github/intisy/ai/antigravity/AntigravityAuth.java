package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.Clock;

import java.util.Map;

/**
 * OAuth auth-detail helpers over antigravity's stored auth: {@code isOAuthAuth},
 * {@code parseRefreshParts}, {@code formatRefreshParts}, {@code accessTokenExpired} (Clock
 * injected in place of {@code Date.now()}), and {@code calculateTokenExpiry}.
 *
 * <p>Operates over a plain {@code Map<String, Object>} JSON tree (the shape both gson and this
 * ecosystem's {@code JsonCodec} SPI produce) rather than a typed {@code AuthDetails} class, so it
 * stays TeaVM-transpilable without needing a dedicated DTO. No java.net/nio/reflection/threads/
 * System.getenv, see {@code :antigravity-teavm}.
 */
public final class AntigravityAuth {

    private static final long ACCESS_TOKEN_EXPIRY_BUFFER_MS = 60 * 1000;

    private AntigravityAuth() {
    }

    // ---- isOAuthAuth ----------------------------------------------------------------------------

    /**
     * Whether stored auth is an OAuth credential rather than another kind.
     *
     * @param auth the stored auth tree
     * @return true when it names the oauth type
     */
    public static boolean isOAuthAuth(Map<String, Object> auth) {
        return auth != null && "oauth".equals(auth.get("type"));
    }

    // ---- parseRefreshParts ------------------------------------------------------------------------

    /** The three segments a stored refresh string packs together. */
    public static final class RefreshParts {
        /** The OAuth refresh token, which is the durable credential. */
        public String refreshToken;
        /** The project the account discovered, or {@code null} for an empty segment. */
        public String projectId;
        /** The managed project the account was onboarded to, or {@code null} for an empty segment. */
        public String managedProjectId;

        /** An empty set of parts, for a caller that fills them in itself. */
        public RefreshParts() {
        }

        /**
         * One set of parts.
         *
         * @param refreshToken the OAuth refresh token
         * @param projectId the discovered project, or {@code null}
         * @param managedProjectId the onboarded managed project, or {@code null}
         */
        public RefreshParts(String refreshToken, String projectId, String managedProjectId) {
            this.refreshToken = refreshToken;
            this.projectId = projectId;
            this.managedProjectId = managedProjectId;
        }

        /**
         * Whether another object is a set of parts with the same three segments.
         *
         * @param o the object to compare against
         * @return true when every segment matches
         */
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

        /** {@return a hash over the three segments, consistent with equality} */
        @Override
        public int hashCode() {
            return java.util.Objects.hash(refreshToken, projectId, managedProjectId);
        }
    }

    /**
     * Splits a packed refresh string into its refresh token and project IDs. A default ({@code ""})
     * applies only when the split array is SHORTER than the destructured index, not when a segment is
     * itself empty, so {@code split("\\|", -1)} (which preserves trailing empty segments, unlike
     * Java's default split) is required to match JS's {@code String.split}.
     *
     * @param refresh the packed refresh string
     * @return its three segments, with an empty one read as absent
     */
    public static RefreshParts parseRefreshParts(String refresh) {
        String[] parts = (refresh == null ? "" : refresh).split("\\|", -1);
        String refreshToken = parts.length > 0 ? parts[0] : "";
        String projectIdRaw = parts.length > 1 ? parts[1] : "";
        String managedRaw = parts.length > 2 ? parts[2] : "";
        return new RefreshParts(refreshToken, projectIdRaw.isEmpty() ? null : projectIdRaw,
                managedRaw.isEmpty() ? null : managedRaw);
    }

    // ---- formatRefreshParts -----------------------------------------------------------------------

    /**
     * Serializes refresh token parts into the stored string format: {@code refreshToken|projectId}
     * (or {@code refreshToken|projectId|managedProjectId} when a managed project id is present). An
     * explicit empty-string {@code projectId} is kept as-is, but an empty-string {@code
     * managedProjectId} is DROPPED.
     *
     * @param parts the segments to pack
     * @return the packed refresh string
     */
    public static String formatRefreshParts(RefreshParts parts) {
        String projectSegment = parts.projectId != null ? parts.projectId : "";
        String base = parts.refreshToken + "|" + projectSegment;
        return (parts.managedProjectId != null && !parts.managedProjectId.isEmpty())
                ? base + "|" + parts.managedProjectId
                : base;
    }

    // ---- accessTokenExpired -----------------------------------------------------------------------

    /**
     * Determines whether an access token is expired or missing, with a 60s buffer for clock skew.
     * {@code auth.expires} must be a {@code Number} (a numeric string is rejected too).
     *
     * @param auth the stored auth tree
     * @param clock injected in place of {@code Date.now()}, for deterministic parity tests.
     * @return true when the token is missing or within the buffer of expiring
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

    // ---- calculateTokenExpiry ---------------------------------------------------------------------

    /**
     * Calculates the absolute expiry timestamp from a requested duration, defaulting to one hour
     * when {@code expiresInSeconds} is not a {@code Number}, and returning {@code requestTimeMs}
     * unchanged for a NaN/non-positive duration.
     *
     * @param requestTimeMs   the local time when the request was initiated
     * @param expiresInSeconds the duration returned by the server (may be any JSON-parsed value)
     * @return the epoch-millisecond time the token expires
     */
    public static long calculateTokenExpiry(long requestTimeMs, Object expiresInSeconds) {
        double seconds = expiresInSeconds instanceof Number ? ((Number) expiresInSeconds).doubleValue() : 3600;
        if (Double.isNaN(seconds) || seconds <= 0) {
            return requestTimeMs;
        }
        return requestTimeMs + (long) (seconds * 1000);
    }
}
