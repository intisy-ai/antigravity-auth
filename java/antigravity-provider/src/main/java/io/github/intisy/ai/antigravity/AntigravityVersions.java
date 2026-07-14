package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.Random;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Java port of antigravity-auth's {@code src/plugin/versions.ts} DRIFT MATH ONLY (Bucket A, T7a):
 * {@code cmpSemver}, {@code normalize}, {@code pickVersion}, {@code nextVersionDriftDelay}, and
 * {@code driftVersion}. Deliberately does NOT port {@code refreshVersions} (network fetch of the
 * public release feed, Bucket C) or the module-level mutable {@code versionList}/{@code
 * lastFetchAt}/{@code fetching} state it maintains -- callers pass the CURRENT version pool
 * (curated {@link #FALLBACK_VERSIONS} or whatever the TS side's live-refreshed list is) as an
 * explicit {@code versionList} parameter instead, matching how {@link #pickVersion} and
 * {@link #driftVersion} read the TS's {@code versionList} closure variable.
 *
 * <p>{@link Random} is injected in place of {@code Math.random()}, for deterministic parity
 * tests -- see {@link AntigravityLanes} javadoc for the assumed {@code [0, 1)} range.
 */
public final class AntigravityVersions {

    // versions.ts:10-14 -- curated fallback (newest-first), used both as the harness's/caller's
    // default pool and as normalize()'s always-present floor set.
    public static final List<String> FALLBACK_VERSIONS = Collections.unmodifiableList(java.util.Arrays.asList(
            "2.1.1", "2.0.4", "2.0.3", "2.0.2", "2.0.1",
            "1.23.2", "1.22.2", "1.21.9", "1.21.6", "1.20.6",
            "1.19.6", "1.18.4", "1.18.3"));

    // versions.ts:19-20
    private static final int CONSIDER_NEWEST = 10;
    private static final double NEWER_BIAS = 0.6;

    // versions.ts:26
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    // versions.ts:71
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private AntigravityVersions() {
    }

    // ---- cmpSemver (versions.ts:28-36) --------------------------------------------------------------

    /**
     * Compares two dotted-triple version strings component-wise (major, then minor, then patch).
     * A non-numeric component coerces to {@code 0} (matches JS's {@code Number("x") || 0}, since
     * {@code NaN} is falsy); a missing (short) component also coerces to {@code 0}.
     */
    public static int cmpSemver(String a, String b) {
        int[] pa = semverParts(a);
        int[] pb = semverParts(b);
        for (int i = 0; i < 3; i++) {
            int d = pa[i] - pb[i];
            if (d != 0) return d;
        }
        return 0;
    }

    private static int[] semverParts(Object v) {
        String[] segments = String.valueOf(v).split("\\.");
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            out[i] = i < segments.length ? jsNumberOrZero(segments[i]) : 0;
        }
        return out;
    }

    // Approximates `Number(segment) || 0`: a non-numeric segment (Number(...) -> NaN, which is
    // falsy) or a missing segment both coerce to 0. Real semver segments are plain non-negative
    // integers, so a plain parseInt covers every realistic input (this port does not need
    // AntigravityAuth/Lanes's broader JsCoercion.jsNumber -- no signs/hex/exponents/whitespace in
    // a version-string component).
    private static int jsNumberOrZero(String segment) {
        try {
            int n = Integer.parseInt(segment);
            return n;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- normalize (versions.ts:39-44) --------------------------------------------------------------

    /**
     * Merges a fetched version list with {@link #FALLBACK_VERSIONS}, keeping only valid semver
     * strings from the input, deduping, and sorting newest-first.
     */
    public static List<String> normalize(List<String> versions) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (versions != null) {
            for (String v : versions) {
                if (v != null && SEMVER.matcher(v).matches()) set.add(v);
            }
        }
        set.addAll(FALLBACK_VERSIONS);
        List<String> list = new ArrayList<>(set);
        list.sort((x, y) -> cmpSemver(y, x));
        return list;
    }

    // ---- getNewestVersion (versions.ts:50-52) ---------------------------------------------------------

    /** {@code versionList[0] || FALLBACK_VERSIONS[0]} -- falls back on an empty OR falsy-first-entry list. */
    public static String getNewestVersion(List<String> versionList) {
        if (versionList != null && !versionList.isEmpty() && JsCoercion.isTruthy(versionList.get(0))) {
            return versionList.get(0);
        }
        return FALLBACK_VERSIONS.get(0);
    }

    // ---- pickVersion (versions.ts:55-69) ----------------------------------------------------------------

    /**
     * Weighted-random pick toward newer, restricted to versions {@code >= min} (min {@code null}
     * or empty = any), drawn from a geometric distribution over the top {@link #CONSIDER_NEWEST}
     * entries of {@code versionList} (assumed already sorted newest-first, matching the TS state
     * invariant).
     */
    public static String pickVersion(List<String> versionList, String min, Random random) {
        List<String> pool = versionList.size() > CONSIDER_NEWEST
                ? versionList.subList(0, CONSIDER_NEWEST)
                : versionList;
        pool = new ArrayList<>(pool);
        if (min != null && !min.isEmpty()) {
            List<String> newer = new ArrayList<>();
            for (String v : pool) {
                if (cmpSemver(v, min) >= 0) newer.add(v);
            }
            if (!newer.isEmpty()) pool = newer;
        }
        double[] weights = new double[pool.size()];
        double total = 0;
        for (int i = 0; i < pool.size(); i++) {
            weights[i] = Math.pow(NEWER_BIAS, i);
            total += weights[i];
        }
        double r = random.next() * total;
        for (int i = 0; i < pool.size(); i++) {
            r -= weights[i];
            if (r <= 0) return pool.get(i);
        }
        return pool.isEmpty() ? getNewestVersion(versionList) : pool.get(0);
    }

    // ---- nextVersionDriftDelay (versions.ts:78-82) -----------------------------------------------------

    /**
     * Per-account delay (ms) until the next User-Agent version-drift check, randomized so accounts
     * never update in lockstep. An account with no stored version yet migrates SOON (0-7 days); an
     * already-versioned account re-drifts on a wide 14-35 day window.
     */
    public static long nextVersionDriftDelay(boolean hasVersion, Random random) {
        long min = hasVersion ? 14 * DAY_MS : 0;
        long max = hasVersion ? 35 * DAY_MS : 7 * DAY_MS;
        return min + (long) Math.floor(random.next() * (max - min));
    }

    // ---- driftVersion (versions.ts:86-90) ----------------------------------------------------------------

    /**
     * Forward-only pick for an account that already has a version (simulates an IDE auto-update):
     * weighted-newer among versions {@code >= current}; never downgrades (falls back to the
     * absolute newest if the weighted pick would otherwise regress -- can only happen when {@code
     * current} is itself newer than every entry in the top-{@link #CONSIDER_NEWEST} pool, so {@code
     * min} filtering yields no match and the unfiltered pick lands below {@code current}).
     */
    public static String driftVersion(String current, List<String> versionList, Random random) {
        if (current == null || current.isEmpty()) return pickVersion(versionList, null, random);
        String pick = pickVersion(versionList, current, random);
        return cmpSemver(pick, current) >= 0 ? pick : getNewestVersion(versionList);
    }
}
