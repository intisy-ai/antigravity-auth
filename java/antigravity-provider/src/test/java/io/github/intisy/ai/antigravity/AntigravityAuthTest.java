package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.Clock;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

class AntigravityAuthTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final Clock NOW_CLOCK = TestDoubles.fixedClock(NOW);

    // ---- isOAuthAuth ----------------------------------------------------------------------------

    @Test
    void isOAuthAuth_oauthType_true() {
        assertTrue(AntigravityAuth.isOAuthAuth(map("type", "oauth")));
    }

    @Test
    void isOAuthAuth_apiKeyType_false() {
        assertFalse(AntigravityAuth.isOAuthAuth(map("type", "apiKey")));
    }

    // ---- parseRefreshParts ------------------------------------------------------------------------

    @Test
    void parseRefreshParts_full() {
        AntigravityAuth.RefreshParts parts = AntigravityAuth.parseRefreshParts("rt1|proj1|managed1");
        assertEquals(new AntigravityAuth.RefreshParts("rt1", "proj1", "managed1"), parts);
    }

    @Test
    void parseRefreshParts_noManaged() {
        assertEquals(new AntigravityAuth.RefreshParts("rt1", "proj1", null),
                AntigravityAuth.parseRefreshParts("rt1|proj1"));
    }

    @Test
    void parseRefreshParts_onlyToken() {
        assertEquals(new AntigravityAuth.RefreshParts("rt1", null, null),
                AntigravityAuth.parseRefreshParts("rt1"));
    }

    @Test
    void parseRefreshParts_empty() {
        assertEquals(new AntigravityAuth.RefreshParts("", null, null),
                AntigravityAuth.parseRefreshParts(""));
    }

    @Test
    void parseRefreshParts_nullInput_treatedAsEmptyString() {
        assertEquals(new AntigravityAuth.RefreshParts("", null, null),
                AntigravityAuth.parseRefreshParts(null));
    }

    @Test
    void parseRefreshParts_emptyMiddleSegment_projectIdNullManagedKept() {
        assertEquals(new AntigravityAuth.RefreshParts("a", null, "c"),
                AntigravityAuth.parseRefreshParts("a||c"));
    }

    @Test
    void parseRefreshParts_extraPipes_onlyFirstThreeSegmentsKept() {
        assertEquals(new AntigravityAuth.RefreshParts("a", "b", "c"),
                AntigravityAuth.parseRefreshParts("a|b|c|d"));
    }

    @Test
    void parseRefreshParts_trailingPipe_managedProjectIdNull() {
        assertEquals(new AntigravityAuth.RefreshParts("a", "b", null),
                AntigravityAuth.parseRefreshParts("a|b|"));
    }

    // ---- formatRefreshParts -----------------------------------------------------------------------

    @Test
    void formatRefreshParts_full() {
        assertEquals("rt1|proj1|managed1",
                AntigravityAuth.formatRefreshParts(new AntigravityAuth.RefreshParts("rt1", "proj1", "managed1")));
    }

    @Test
    void formatRefreshParts_noManagedField_twoSegmentsOnly() {
        assertEquals("rt1|proj1",
                AntigravityAuth.formatRefreshParts(new AntigravityAuth.RefreshParts("rt1", "proj1", null)));
    }

    @Test
    void formatRefreshParts_undefinedProjectId_emptySegmentKept() {
        assertEquals("rt1|", AntigravityAuth.formatRefreshParts(new AntigravityAuth.RefreshParts("rt1", null, null)));
    }

    @Test
    void formatRefreshParts_emptyStringManagedProjectId_dropped() {
        assertEquals("rt1|p", AntigravityAuth.formatRefreshParts(new AntigravityAuth.RefreshParts("rt1", "p", "")));
    }

    // ---- accessTokenExpired -----------------------------------------------------------------------

    @Test
    void accessTokenExpired_noAccess_true() {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("expires", (double) (NOW + 100000));
        assertTrue(AntigravityAuth.accessTokenExpired(auth, NOW_CLOCK));
    }

    @Test
    void accessTokenExpired_nonNumberExpires_true() {
        assertTrue(AntigravityAuth.accessTokenExpired(map("access", "tok", "expires", "not-a-number"), NOW_CLOCK));
    }

    @Test
    void accessTokenExpired_wellBeyondBuffer_false() {
        assertFalse(AntigravityAuth.accessTokenExpired(map("access", "tok", "expires", (double) (NOW + 120_000)), NOW_CLOCK));
    }

    @Test
    void accessTokenExpired_withinBuffer_true() {
        assertTrue(AntigravityAuth.accessTokenExpired(map("access", "tok", "expires", (double) (NOW + 30_000)), NOW_CLOCK));
    }

    @Test
    void accessTokenExpired_exactlyAtBufferBoundary_true() {
        assertTrue(AntigravityAuth.accessTokenExpired(map("access", "tok", "expires", (double) (NOW + 60_000)), NOW_CLOCK));
    }

    @Test
    void accessTokenExpired_alreadyPast_true() {
        assertTrue(AntigravityAuth.accessTokenExpired(map("access", "tok", "expires", (double) (NOW - 5000)), NOW_CLOCK));
    }

    // ---- calculateTokenExpiry ---------------------------------------------------------------------

    @Test
    void calculateTokenExpiry_normal() {
        assertEquals(3_601_000L, AntigravityAuth.calculateTokenExpiry(1000, 3600));
    }

    @Test
    void calculateTokenExpiry_nonNumberSeconds_defaultsToOneHour() {
        assertEquals(3_601_000L, AntigravityAuth.calculateTokenExpiry(1000, "bogus"));
    }

    @Test
    void calculateTokenExpiry_zeroSeconds_returnsRequestTime() {
        assertEquals(1000L, AntigravityAuth.calculateTokenExpiry(1000, 0));
    }

    @Test
    void calculateTokenExpiry_negativeSeconds_returnsRequestTime() {
        assertEquals(1000L, AntigravityAuth.calculateTokenExpiry(1000, -5));
    }

    @Test
    void calculateTokenExpiry_nanSeconds_returnsRequestTime() {
        assertEquals(1000L, AntigravityAuth.calculateTokenExpiry(1000, Double.NaN));
    }

    @Test
    void calculateTokenExpiry_fractionalSeconds() {
        assertEquals(2500L, AntigravityAuth.calculateTokenExpiry(1000, 1.5));
    }
}
