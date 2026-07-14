package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.Random;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline deterministic parity tests for {@link AntigravityVersions}, checked against antigravity-
 * auth's actual {@code src/plugin/versions.ts} drift math: the TS's functions were extracted
 * verbatim into a throwaway Node harness (with the module-level {@code versionList} closure
 * variable turned into an explicit parameter, matching this port's parameterized signature) and
 * executed with {@code node} (v26.3.1), stubbing {@code Math.random} to fixed values, to snapshot
 * the exact expected values used below -- not hand-derived. See task-7a-report.md for the harness.
 */
class AntigravityVersionsTest {

    private static final List<String> POOL = AntigravityVersions.FALLBACK_VERSIONS; // already newest-first

    // ---- cmpSemver -------------------------------------------------------------------------------

    @Test
    void cmpSemver_equal() {
        assertEquals(0, AntigravityVersions.cmpSemver("2.1.1", "2.1.1"));
    }

    @Test
    void cmpSemver_aNewer_positive() {
        assertTrue(AntigravityVersions.cmpSemver("2.1.1", "2.0.4") > 0);
    }

    @Test
    void cmpSemver_minorComparedNumerically_9Below10() {
        assertTrue(AntigravityVersions.cmpSemver("1.9.0", "1.10.0") < 0);
    }

    @Test
    void cmpSemver_patchDifference_exactDelta() {
        assertEquals(-6, AntigravityVersions.cmpSemver("1.2.3", "1.2.9"));
    }

    @Test
    void cmpSemver_nonNumericSegment_coercesToZero() {
        assertEquals(0, AntigravityVersions.cmpSemver("1.x.0", "1.0.0"));
    }

    // ---- normalize -------------------------------------------------------------------------------

    @Test
    void normalize_mixedValidInvalid_dedupedSortedNewestFirstWithFallback() {
        List<String> expected = Arrays.asList(
                "9.9.9", "2.1.1", "2.0.4", "2.0.3", "2.0.2", "2.0.1",
                "1.23.2", "1.22.2", "1.21.9", "1.21.6", "1.20.6", "1.19.6", "1.18.4", "1.18.3");
        assertEquals(expected, AntigravityVersions.normalize(Arrays.asList("9.9.9", "not-a-version", "2.1.1", "9.9.9")));
    }

    @Test
    void normalize_empty_yieldsFallbackNewestFirst() {
        assertEquals(new java.util.ArrayList<>(AntigravityVersions.FALLBACK_VERSIONS),
                AntigravityVersions.normalize(Collections.<String>emptyList()));
    }

    // ---- getNewestVersion ------------------------------------------------------------------------

    @Test
    void getNewestVersion_normalPool() {
        assertEquals("2.1.1", AntigravityVersions.getNewestVersion(POOL));
    }

    @Test
    void getNewestVersion_emptyPool_fallsBackToFallbackHead() {
        assertEquals("2.1.1", AntigravityVersions.getNewestVersion(Collections.<String>emptyList()));
    }

    // ---- pickVersion (Random fixed) --------------------------------------------------------------

    private static final Random RANDOM_ZERO = TestDoubles.fixedRandom(0.0);
    private static final Random RANDOM_NEAR_ONE = TestDoubles.fixedRandom(0.999999);

    @Test
    void pickVersion_randZero_noMin_picksNewest() {
        assertEquals("2.1.1", AntigravityVersions.pickVersion(POOL, null, RANDOM_ZERO));
    }

    @Test
    void pickVersion_randZero_midMin_picksNewestGteMin() {
        assertEquals("2.1.1", AntigravityVersions.pickVersion(POOL, "1.21.6", RANDOM_ZERO));
    }

    @Test
    void pickVersion_randZero_minAboveAll_falsyFilterKeepsFullPool_picksNewest() {
        assertEquals("2.1.1", AntigravityVersions.pickVersion(POOL, "9.9.9", RANDOM_ZERO));
    }

    @Test
    void pickVersion_randNearOne_noMin_picksLastOfConsideredWindow() {
        // r near total consumes all weights across the top CONSIDER_NEWEST(10) entries; index 9 is "1.20.6".
        assertEquals("1.20.6", AntigravityVersions.pickVersion(POOL, null, RANDOM_NEAR_ONE));
    }

    // ---- nextVersionDriftDelay -------------------------------------------------------------------

    @Test
    void nextVersionDriftDelay_hasVersion_randZero_minWindow() {
        assertEquals(1_209_600_000L, AntigravityVersions.nextVersionDriftDelay(true, RANDOM_ZERO)); // 14 days
    }

    @Test
    void nextVersionDriftDelay_noVersion_randZero_zero() {
        assertEquals(0L, AntigravityVersions.nextVersionDriftDelay(false, RANDOM_ZERO));
    }

    @Test
    void nextVersionDriftDelay_hasVersion_randNearOne() {
        assertEquals(3_023_998_185L, AntigravityVersions.nextVersionDriftDelay(true, RANDOM_NEAR_ONE));
    }

    @Test
    void nextVersionDriftDelay_noVersion_randNearOne() {
        assertEquals(604_799_395L, AntigravityVersions.nextVersionDriftDelay(false, RANDOM_NEAR_ONE));
    }

    // ---- driftVersion ----------------------------------------------------------------------------

    @Test
    void driftVersion_noCurrent_randZero_picksNewest() {
        assertEquals("2.1.1", AntigravityVersions.driftVersion(null, POOL, RANDOM_ZERO));
    }

    @Test
    void driftVersion_midCurrent_randZero_picksNewestGteCurrent() {
        assertEquals("2.1.1", AntigravityVersions.driftVersion("1.21.6", POOL, RANDOM_ZERO));
    }

    @Test
    void driftVersion_currentIsNewest_staysNewest() {
        assertEquals("2.1.1", AntigravityVersions.driftVersion("2.1.1", POOL, RANDOM_ZERO));
    }

    @Test
    void driftVersion_currentAboveAll_neverDowngrades_returnsNewest() {
        assertEquals("2.1.1", AntigravityVersions.driftVersion("9.9.9", POOL, RANDOM_ZERO));
    }
}
