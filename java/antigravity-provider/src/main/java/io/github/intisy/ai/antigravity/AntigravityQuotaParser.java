package io.github.intisy.ai.antigravity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of antigravity-auth's {@code src/driver/accounts-controller.ts} (Bucket A, T7a):
 * {@code allPoolsExhausted}, {@code antigravityStatus}, {@code antigravityAvailableAt}, {@code
 * antigravityQuota}, {@code familyLabel}, {@code accountHasQuota}, and the pure per-family quota
 * aggregation loop lifted out of {@code fetchQuotaFamilies} (lines 113-127, here {@link
 * #aggregateQuotaFamilies}) -- the ONLY part of that function that is not network I/O.
 * Deliberately does NOT port the fetch/verify/refresh I/O (Bucket B/C: {@code fetchQuotaFamilies}'s
 * own {@code fetch} call, {@code refreshQuotaOne}/{@code refreshAllQuota}/{@code verify}/{@code
 * verifyAll}/{@code refreshToken}) or {@code createAntigravityAccounts} (TUI wiring).
 *
 * <p>{@code antigravityStatus}/{@code antigravityAvailableAt} take {@code now} as an explicit
 * {@code long} parameter, mirroring the TS functions' OWN signatures exactly -- both already take
 * {@code now} as a caller-supplied argument in the original source (no implicit {@code Date.now()}
 * inside either function), so no {@code Clock} SPI indirection is needed here.
 *
 * <p>Operates over plain {@code Map}/{@code List} JSON trees (the shape both gson and this
 * ecosystem's {@code JsonCodec} SPI produce). No java.net/nio/reflection/threads -- TeaVM-safe.
 */
public final class AntigravityQuotaParser {

    private AntigravityQuotaParser() {
    }

    // ---- allPoolsExhausted (accounts-controller.ts:15-19) -----------------------------------------

    /**
     * True only when EVERY known quota pool reports zero (or no numeric) remaining capacity.
     * Returns {@code false} for an empty/absent pool map (matches the TS {@code if (!pools.length)
     * return false} special case -- "no pools yet" is NOT "all exhausted").
     */
    public static boolean allPoolsExhausted(Map<String, Object> cachedQuota) {
        if (cachedQuota == null || cachedQuota.isEmpty()) return false;
        for (Object poolObj : cachedQuota.values()) {
            if (poolObj instanceof Map) {
                Object rf = ((Map<?, ?>) poolObj).get("remainingFraction");
                if (rf instanceof Number && ((Number) rf).doubleValue() > 0) return false;
            }
        }
        return true;
    }

    // ---- antigravityStatus (accounts-controller.ts:27-38) -----------------------------------------

    /**
     * Status reflects the account's real serving capacity via its quota POOLS, not the per-lane
     * rate-limit backoffs: a single transient lane limit must not flag the whole account
     * rate-limited while other pools still have quota. Falls back to the lane check before the
     * first quota fetch (no {@code cachedQuota} yet).
     */
    @SuppressWarnings("unchecked")
    public static String antigravityStatus(Map<String, Object> account, long now) {
        if (Boolean.FALSE.equals(account.get("enabled"))) return "disabled";
        Object metaObj = account.get("meta");
        Map<String, Object> meta = metaObj instanceof Map ? (Map<String, Object>) metaObj : null;
        if (meta != null && JsCoercion.isTruthy(meta.get("verificationRequired"))) return "verification-required";
        Object coolingDownUntil = account.get("coolingDownUntil");
        if (coolingDownUntil instanceof Number && ((Number) coolingDownUntil).doubleValue() > now) return "cooling-down";
        Object cachedQuotaObj = meta != null ? meta.get("cachedQuota") : null;
        if (cachedQuotaObj instanceof Map && !((Map<?, ?>) cachedQuotaObj).isEmpty()) {
            return allPoolsExhausted((Map<String, Object>) cachedQuotaObj) ? "rate-limited" : "active";
        }
        Object lanesObj = account.get("rateLimitResetTimes");
        if (lanesObj instanceof Map) {
            for (Object reset : ((Map<?, ?>) lanesObj).values()) {
                if (reset instanceof Number && ((Number) reset).doubleValue() > now) return "rate-limited";
            }
        }
        return "active";
    }

    // ---- antigravityAvailableAt (accounts-controller.ts:44-57) ------------------------------------

    /**
     * Usability time for the account, pool-based to match {@link #antigravityStatus}: usable NOW
     * when any pool has capacity (or before the first quota fetch); the soonest pool reset when
     * every pool is exhausted; disabled/cooldown handled as usual.
     *
     * @return an epoch-ms timestamp, {@code now}, or {@link Double#POSITIVE_INFINITY} (mirroring
     *         the TS's {@code Infinity} return for a disabled account) -- a {@code double} return
     *         type since Java has no numeric "Infinity" sentinel compatible with {@code long}.
     */
    @SuppressWarnings("unchecked")
    public static double antigravityAvailableAt(Map<String, Object> account, long now) {
        if (Boolean.FALSE.equals(account.get("enabled"))) return Double.POSITIVE_INFINITY;
        Object coolingDownUntil = account.get("coolingDownUntil");
        if (coolingDownUntil instanceof Number && ((Number) coolingDownUntil).doubleValue() > now) {
            return ((Number) coolingDownUntil).doubleValue();
        }
        Object metaObj = account.get("meta");
        Map<String, Object> meta = metaObj instanceof Map ? (Map<String, Object>) metaObj : null;
        Object cachedQuotaObj = meta != null ? meta.get("cachedQuota") : null;
        if (cachedQuotaObj instanceof Map && !((Map<?, ?>) cachedQuotaObj).isEmpty()) {
            Map<String, Object> cachedQuota = (Map<String, Object>) cachedQuotaObj;
            if (allPoolsExhausted(cachedQuota)) {
                double soonest = Double.POSITIVE_INFINITY;
                for (Object poolObj : cachedQuota.values()) {
                    double t = Double.NaN;
                    if (poolObj instanceof Map) {
                        Object resetTime = ((Map<?, ?>) poolObj).get("resetTime");
                        if (JsCoercion.isTruthy(resetTime)) t = parseDateToEpochMillis(String.valueOf(resetTime));
                    }
                    if (!Double.isNaN(t)) soonest = Math.min(soonest, t);
                }
                return Double.isFinite(soonest) ? soonest : now;
            }
        }
        return now;
    }

    // ---- antigravityQuota (accounts-controller.ts:59-67) ------------------------------------------

    /**
     * Maps a stored account's cached per-family quota to the display shape
     * {@code [{label, remainingFraction, resetTime}]}. Returns {@code null} (TS: {@code
     * undefined}) when there is no cached quota at all.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> antigravityQuota(Map<String, Object> account) {
        Object metaObj = account.get("meta");
        Object cachedObj = metaObj instanceof Map ? ((Map<String, Object>) metaObj).get("cachedQuota") : null;
        if (!JsCoercion.isTruthy(cachedObj) || !(cachedObj instanceof Map)) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) cachedObj).entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", e.getKey());
            Object quota = e.getValue();
            Object rf = (quota instanceof Map) ? ((Map<?, ?>) quota).get("remainingFraction") : null;
            entry.put("remainingFraction", rf instanceof Number ? rf : null);
            entry.put("resetTime", (quota instanceof Map) ? ((Map<?, ?>) quota).get("resetTime") : null);
            result.add(entry);
        }
        return result;
    }

    // ---- familyLabel (accounts-controller.ts:70-76) --------------------------------------------------

    /** Friendly family name for a model. Returns {@code null} for internal/unknown models. */
    public static String familyLabel(Object modelName) {
        String lower = String.valueOf(modelName).toLowerCase();
        if (lower.contains("claude")) return "Claude";
        if (lower.contains("gpt") || lower.contains("oss")) return "GPT-OSS";
        if (lower.contains("gemini")) return "Gemini";
        return null;
    }

    // ---- accountHasQuota (accounts-controller.ts:195-199) --------------------------------------------

    /**
     * Quota still remaining? Used to decide a rate-limit is an IP limit (proxy signal), not real
     * account exhaustion. Unknown quota -&gt; false (never blame the proxy).
     */
    @SuppressWarnings("unchecked")
    public static boolean accountHasQuota(Map<String, Object> account) {
        Object metaObj = account != null ? account.get("meta") : null;
        Object cq = metaObj instanceof Map ? ((Map<String, Object>) metaObj).get("cachedQuota") : null;
        if (!JsCoercion.isTruthy(cq) || !(cq instanceof Map)) return false;
        for (Object poolObj : ((Map<?, ?>) cq).values()) {
            if (poolObj instanceof Map) {
                Object rf = ((Map<?, ?>) poolObj).get("remainingFraction");
                if (rf instanceof Number && ((Number) rf).doubleValue() > 0) return true;
            }
        }
        return false;
    }

    // ---- aggregateQuotaFamilies (accounts-controller.ts:113-127, the PURE slice of fetchQuotaFamilies) --

    /**
     * Aggregates per-model quota info into one entry per FAMILY (Claude/GPT-OSS/Gemini): worst
     * (minimum) remaining fraction + earliest reset time across that family's models. When a pool
     * is exhausted, cloudcode-pa drops {@code remainingFraction} and returns only {@code
     * resetTime} -- that is treated as 0 remaining (not skipped) so the pool still shows "100%
     * used, resets at X" instead of silently vanishing.
     *
     * <p>Does NOT port the surrounding {@code fetch}/response-parsing (Bucket C) -- this is only
     * the aggregation loop, taking the already-parsed {@code payload.models} map as input.
     *
     * @return a map of family label -&gt; {@code {remainingFraction: Double, resetTime: String}},
     *         or {@code null} (TS: {@code null}) when no model contributed to any family.
     */
    public static Map<String, Object> aggregateQuotaFamilies(Map<String, Object> models) {
        Map<String, Map<String, Object>> perFamily = new LinkedHashMap<>();
        if (models != null) {
            for (Map.Entry<String, Object> e : models.entrySet()) {
                String fam = familyLabel(e.getKey());
                if (fam == null) continue;
                Object infoObj = e.getValue();
                if (!JsCoercion.isTruthy(infoObj) || !(infoObj instanceof Map)) continue;
                Object quotaInfoObj = ((Map<?, ?>) infoObj).get("quotaInfo");
                if (!JsCoercion.isTruthy(quotaInfoObj) || !(quotaInfoObj instanceof Map)) continue;
                Map<?, ?> quotaInfo = (Map<?, ?>) quotaInfoObj;

                Object rfObj = quotaInfo.get("remainingFraction");
                Object resetTimeObj = quotaInfo.get("resetTime");
                Double remaining;
                if (rfObj instanceof Number) {
                    remaining = ((Number) rfObj).doubleValue();
                } else if (JsCoercion.isTruthy(resetTimeObj)) {
                    remaining = 0.0;
                } else {
                    remaining = null; // TS: undefined -> skipped below
                }
                if (remaining == null) continue;

                String reset = JsCoercion.isTruthy(resetTimeObj) ? String.valueOf(resetTimeObj) : "";
                Map<String, Object> f = perFamily.get(fam);
                if (f == null) {
                    f = new LinkedHashMap<>();
                    f.put("remainingFraction", remaining);
                    f.put("resetTime", reset);
                    perFamily.put(fam, f);
                } else {
                    double current = ((Number) f.get("remainingFraction")).doubleValue();
                    f.put("remainingFraction", Math.min(current, remaining));
                    String existingReset = (String) f.get("resetTime");
                    if (!reset.isEmpty() && (existingReset == null || existingReset.isEmpty()
                            || parseDateToEpochMillis(reset) < parseDateToEpochMillis(existingReset))) {
                        f.put("resetTime", reset);
                    }
                }
            }
        }
        return perFamily.isEmpty() ? null : new LinkedHashMap<>(perFamily);
    }

    // ---- minimal RFC3339/ISO-8601 -> epoch-ms parser (stands in for `Date.parse`) ----------------

    // Matches the "Z" (UTC) or "+HH:mm"/"-HH:mm" offset forms Google API timestamps and this
    // port's own reset-time strings always use; a purely-local (no zone) date-time string -- a
    // real JS `Date.parse` ambiguity the ECMA-262 date-time grammar itself resolves inconsistently
    // across engines -- is intentionally NOT supported (never produced by any real caller here).
    private static final Pattern ISO_DATE = Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:\\d{2})?$");

    /** Returns {@link Double#NaN} for an unparseable/{@code null} input, mirroring {@code Date.parse}. */
    static double parseDateToEpochMillis(String s) {
        if (s == null) return Double.NaN;
        Matcher m = ISO_DATE.matcher(s);
        if (!m.matches()) return Double.NaN;
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        int hour = Integer.parseInt(m.group(4));
        int minute = Integer.parseInt(m.group(5));
        int second = Integer.parseInt(m.group(6));
        String frac = m.group(7);
        long millisFrac = 0;
        if (frac != null) {
            String threeDigits = (frac + "000").substring(0, 3);
            millisFrac = Long.parseLong(threeDigits);
        }
        String zone = m.group(8);

        long days = daysFromCivil(year, month, day);
        long millis = days * 86_400_000L + hour * 3_600_000L + minute * 60_000L + second * 1_000L + millisFrac;
        if (zone != null && !"Z".equals(zone)) {
            int sign = zone.charAt(0) == '-' ? -1 : 1;
            int offsetHours = Integer.parseInt(zone.substring(1, 3));
            int offsetMinutes = Integer.parseInt(zone.substring(4, 6));
            millis -= sign * (offsetHours * 3_600_000L + offsetMinutes * 60_000L);
        }
        return (double) millis;
    }

    // Howard Hinnant's constexpr "days from civil" algorithm -- pure integer arithmetic (no
    // Calendar/java.time), so it stays TeaVM-safe. Returns days since the Unix epoch for a
    // proleptic-Gregorian (y, m, d) with m in [1, 12].
    private static long daysFromCivil(int y, int m, int d) {
        long yy = y - (m <= 2 ? 1 : 0);
        long era = (yy >= 0 ? yy : yy - 399) / 400;
        long yoe = yy - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }
}
