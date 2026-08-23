package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.Clock;
import io.github.intisy.ai.api.seam.Random;

/**
 * Lane derivation ({@code isGeminiCliModel}/{@code laneFor}/{@code headerStyleFor}),
 * rate-limit-reason classification ({@code parseRateLimitReason}), and the
 * exponential-backoff/jitter math ({@code calculateBackoffMs}/{@code resetTimeFor}/{@code jitter})
 * that partitions rate-limit state per quota family (claude | gemini-antigravity(pro/flash) |
 * gpt-oss | gemini-cli).
 *
 * <p>{@code jitter}/{@code calculateBackoffMs} take an injected {@link Random} in place of {@code
 * Math.random()}; {@code resetTimeFor} additionally takes an injected {@link Clock} in place of
 * {@code Date.now()}, both for deterministic parity tests. {@link Random#next()} is assumed to
 * return a value in {@code [0, 1)}, matching {@code Math.random()}.
 */
public final class AntigravityLanes {

    private static final long[] QUOTA_EXHAUSTED_BACKOFFS = {60_000, 300_000, 1_800_000, 7_200_000};
    private static final double MODEL_CAPACITY_BASE = 45_000;
    private static final double MODEL_CAPACITY_JITTER_MAX = 30_000;
    private static final long MIN_BACKOFF_MS = 2_000;
    private static final long MAX_EXPONENTIAL_BACKOFF = 60L * 60 * 1000;

    private AntigravityLanes() {
    }

    // ---- jitter ----------------------------------------------------------------------------------

    /** {@code Math.random() * maxMs - maxMs / 2}, a signed jitter centered on zero. */
    public static double jitter(Random random, double maxMs) {
        return random.next() * maxMs - maxMs / 2;
    }

    // ---- isGeminiCliModel ------------------------------------------------------------------------

    /**
     * Bare {@code gemini-*} ids are the separate (free) Gemini CLI quota pool; anything else
     * (including {@code antigravity-}-prefixed ids) is the antigravity pool. Accepts {@code Object}
     * (any JSON value) and requires a {@code String}.
     */
    public static boolean isGeminiCliModel(Object model) {
        return model instanceof String && ((String) model).startsWith("gemini-");
    }

    // ---- laneFor ---------------------------------------------------------------------------------

    /**
     * Partitions rate-limit state by REAL quota pool so exhausting one family never blocks
     * another: claude | gemini-pro | gemini-flash | gpt-oss | gemini-cli.
     */
    public static String laneFor(Object model) {
        String raw = JsCoercion.isTruthy(model) ? String.valueOf(model) : "";
        if (isGeminiCliModel(raw)) return "gemini-cli";
        String lower = raw.replaceFirst("(?i)^antigravity-", "").toLowerCase();
        if (lower.contains("claude")) return "claude";
        if (lower.contains("gpt")) return "gpt-oss";
        if (lower.contains("flash")) return "gemini-flash";
        return "gemini-pro";
    }

    // ---- headerStyleFor --------------------------------------------------------------------------

    public static String headerStyleFor(Object model) {
        return isGeminiCliModel(model) ? "gemini-cli" : "antigravity";
    }

    // ---- parseRateLimitReason --------------------------------------------------------------------

    /**
     * Classifies a rate-limit signal from an explicit {@code reason} code, an HTTP {@code status},
     * or (as a last resort) a free-text {@code message}. {@code status} uses {@code Integer} boxing
     * so a {@code null} status behaves like JS {@code undefined} (never equal to 529/503/500).
     */
    public static String parseRateLimitReason(String reason, String message, Integer status) {
        if (status != null && (status == 529 || status == 503)) return "MODEL_CAPACITY_EXHAUSTED";
        if (status != null && status == 500) return "SERVER_ERROR";
        if (reason != null && !reason.isEmpty()) {
            switch (reason.toUpperCase()) {
                case "QUOTA_EXHAUSTED":
                    return "QUOTA_EXHAUSTED";
                case "RATE_LIMIT_EXCEEDED":
                    return "RATE_LIMIT_EXCEEDED";
                case "MODEL_CAPACITY_EXHAUSTED":
                    return "MODEL_CAPACITY_EXHAUSTED";
                default:
                    break;
            }
        }
        if (message != null && !message.isEmpty()) {
            String lower = message.toLowerCase();
            if (lower.contains("capacity") || lower.contains("overloaded") || lower.contains("resource exhausted")) {
                return "MODEL_CAPACITY_EXHAUSTED";
            }
            if (lower.contains("per minute") || lower.contains("rate limit") || lower.contains("too many requests")
                    || lower.contains("presque")) {
                return "RATE_LIMIT_EXCEEDED";
            }
            if (lower.contains("exhausted") || lower.contains("quota")) return "QUOTA_EXHAUSTED";
        }
        return "UNKNOWN";
    }

    // ---- calculateBackoffMs ----------------------------------------------------------------------

    /**
     * Computes the backoff duration for a classified rate-limit reason: an explicit
     * {@code retryAfterMs} wins outright (floored at {@link #MIN_BACKOFF_MS}); {@code
     * QUOTA_EXHAUSTED} indexes a fixed escalating table (NO exponential multiplier applied); every
     * other reason applies a {@code 1.5^consecutiveFailures} multiplier to a reason-specific base,
     * capped at {@link #MAX_EXPONENTIAL_BACKOFF}.
     *
     * <p>{@code consecutiveFailures} is clamped to {@code >= 0} before indexing the
     * {@code QUOTA_EXHAUSTED} table: a negative count (never produced by any real caller) would
     * otherwise index the array negatively, so it is clamped to avoid an {@code
     * ArrayIndexOutOfBoundsException}.
     */
    public static long calculateBackoffMs(String reason, Integer consecutiveFailures, Long retryAfterMs, Random random) {
        if (retryAfterMs != null && retryAfterMs > 0) return Math.max(retryAfterMs, MIN_BACKOFF_MS);
        int failures = consecutiveFailures != null ? consecutiveFailures : 0;
        if ("QUOTA_EXHAUSTED".equals(reason)) {
            int index = Math.max(0, Math.min(failures, QUOTA_EXHAUSTED_BACKOFFS.length - 1));
            return QUOTA_EXHAUSTED_BACKOFFS[index];
        }
        double base;
        if ("RATE_LIMIT_EXCEEDED".equals(reason)) {
            base = 45_000;
        } else if ("MODEL_CAPACITY_EXHAUSTED".equals(reason)) {
            base = MODEL_CAPACITY_BASE + jitter(random, MODEL_CAPACITY_JITTER_MAX);
        } else if ("SERVER_ERROR".equals(reason)) {
            base = 30_000;
        } else {
            base = 90_000;
        }
        double multiplier = Math.pow(1.5, failures);
        return Math.min(Math.round(base * multiplier), MAX_EXPONENTIAL_BACKOFF);
    }

    // ---- resetTimeFor ----------------------------------------------------------------------------

    public static long resetTimeFor(String reason, Integer consecutiveFailures, Long retryAfterMs, Random random, Clock clock) {
        return clock.now() + calculateBackoffMs(reason, consecutiveFailures, retryAfterMs, random);
    }
}
