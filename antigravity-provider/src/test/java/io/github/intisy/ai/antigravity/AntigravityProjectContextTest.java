package io.github.intisy.ai.antigravity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AntigravityProjectContext}. Exercises the {@code ensureProjectContext} decision
 * sequences (short-circuit / load-hit / onboard / fallback) with a scripted global fetch, plus the
 * pure helpers asserted directly.
 */
class AntigravityProjectContextTest {

    private static AntigravityProjectContext.Platform platform(String p, String arch) {
        return new AntigravityProjectContext.Platform() {
            @Override
            public String platform() {
                return p;
            }

            @Override
            public String arch() {
                return arch;
            }
        };
    }

    @Test
    void detectCodeAssistPlatform() {
        assertEquals("LINUX_AMD64", AntigravityProjectContext.detectCodeAssistPlatform(platform("linux", "x64")));
        assertEquals("WINDOWS_ARM64", AntigravityProjectContext.detectCodeAssistPlatform(platform("win32", "arm64")));
        assertEquals("DARWIN_AMD64", AntigravityProjectContext.detectCodeAssistPlatform(platform("darwin", "x64")));
        assertEquals("PLATFORM_UNSPECIFIED", AntigravityProjectContext.detectCodeAssistPlatform(platform("aix", "x64")));
    }

    @Test
    void buildMetadata() {
        Map<String, Object> withProject = AntigravityProjectContext.buildMetadata("proj-1", platform("linux", "x64"));
        assertEquals("ANTIGRAVITY", withProject.get("ideType"));
        assertEquals("LINUX_AMD64", withProject.get("platform"));
        assertEquals("GEMINI", withProject.get("pluginType"));
        assertEquals("proj-1", withProject.get("duetProject"));

        Map<String, Object> noProject = AntigravityProjectContext.buildMetadata(null, platform("linux", "x64"));
        assertFalse(noProject.containsKey("duetProject"));
    }

    @Test
    void getDefaultTierId() {
        assertEquals("B", AntigravityProjectContext.getDefaultTierId(Arrays.asList(tier("A", false), tier("B", true))));
        assertEquals("A", AntigravityProjectContext.getDefaultTierId(Arrays.asList(tier("A", false), tier("C", false))));
        assertNull(AntigravityProjectContext.getDefaultTierId(new ArrayList<>()));
        assertNull(AntigravityProjectContext.getDefaultTierId(null));
    }

    @Test
    void extractManagedProjectId() {
        Map<String, Object> asString = new LinkedHashMap<>();
        asString.put("cloudaicompanionProject", "proj-str");
        assertEquals("proj-str", AntigravityProjectContext.extractManagedProjectId(asString));

        Map<String, Object> asObject = new LinkedHashMap<>();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("id", "proj-id");
        asObject.put("cloudaicompanionProject", inner);
        assertEquals("proj-id", AntigravityProjectContext.extractManagedProjectId(asObject));

        assertNull(AntigravityProjectContext.extractManagedProjectId(new LinkedHashMap<>()));
        assertNull(AntigravityProjectContext.extractManagedProjectId(null));
    }

    @Test
    void getCacheKey() {
        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("refresh", "  rt-trim  ");
        assertEquals("rt-trim", AntigravityProjectContext.getCacheKey(auth));
        auth.put("refresh", "   ");
        assertNull(AntigravityProjectContext.getCacheKey(auth));
        assertNull(AntigravityProjectContext.getCacheKey(new LinkedHashMap<>()));
    }

    // ---- ensureProjectContext decision (fixtures) -----------------------------------------------

    @Test
    void ensureManagedShortCircuit() {
        RecordingLoader loader = new RecordingLoader(null);
        RecordingOnboarder onboarder = new RecordingOnboarder(null);
        AntigravityProjectContext.ProjectContextResult r = AntigravityProjectContext.ensureProjectContext(
                auth("rtok||managed-123"), null, null, loader, onboarder, platform("linux", "x64"));
        assertEquals("managed-123", r.effectiveProjectId);
        assertEquals("rtok||managed-123", r.auth.get("refresh"));
        assertFalse(loader.called);
        assertFalse(onboarder.called);
    }

    @Test
    void ensureLoadHit() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cloudaicompanionProject", "loaded-proj");
        RecordingLoader loader = new RecordingLoader(payload);
        RecordingOnboarder onboarder = new RecordingOnboarder(null);
        AntigravityProjectContext.ProjectContextResult r = AntigravityProjectContext.ensureProjectContext(
                auth("rtload"), null, null, loader, onboarder, platform("linux", "x64"));
        assertEquals("loaded-proj", r.effectiveProjectId);
        assertEquals("rtload||loaded-proj", r.auth.get("refresh"));
        assertTrue(loader.called);
        assertFalse(onboarder.called);
    }

    @Test
    void ensureOnboard() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("allowedTiers", Arrays.asList(tier("FREE", true)));
        RecordingLoader loader = new RecordingLoader(payload);
        RecordingOnboarder onboarder = new RecordingOnboarder("onboarded-proj");
        AntigravityProjectContext.ProjectContextResult r = AntigravityProjectContext.ensureProjectContext(
                auth("rtonboard"), null, null, loader, onboarder, platform("linux", "x64"));
        assertEquals("onboarded-proj", r.effectiveProjectId);
        assertEquals("rtonboard||onboarded-proj", r.auth.get("refresh"));
        assertTrue(loader.called);
        assertTrue(onboarder.called);
        assertEquals("FREE", onboarder.tierId);
    }

    @Test
    void ensureFallback() {
        RecordingLoader loader = new RecordingLoader(new LinkedHashMap<>());
        RecordingOnboarder onboarder = new RecordingOnboarder(null);
        AntigravityProjectContext.ProjectContextResult r = AntigravityProjectContext.ensureProjectContext(
                auth("rtfb"), null, "fb-proj", loader, onboarder, platform("linux", "x64"));
        assertEquals("fb-proj", r.effectiveProjectId);
        assertEquals("rtfb", r.auth.get("refresh")); // unchanged
        assertTrue(loader.called);
        assertTrue(onboarder.called);
        assertEquals("FREE", onboarder.tierId); // getDefaultTierId(null) ?? "FREE"
    }

    @Test
    void ensureNoAccessToken() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "oauth");
        a.put("refresh", "rt");
        AntigravityProjectContext.ProjectContextResult r = AntigravityProjectContext.ensureProjectContext(
                a, null, null, new RecordingLoader(null), new RecordingOnboarder(null), platform("linux", "x64"));
        assertEquals("", r.effectiveProjectId);
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static Map<String, Object> tier(String id, boolean isDefault) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        if (isDefault) t.put("isDefault", true);
        return t;
    }

    private static Map<String, Object> auth(String refresh) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "oauth");
        a.put("access", "atX");
        a.put("refresh", refresh);
        return a;
    }

    private static final class RecordingLoader implements AntigravityProjectContext.ProjectLoader {
        final Map<String, Object> payload;
        boolean called;

        RecordingLoader(Map<String, Object> payload) {
            this.payload = payload;
        }

        @Override
        public Map<String, Object> load(String accessToken, String projectId, String proxy) {
            called = true;
            return payload;
        }
    }

    private static final class RecordingOnboarder implements AntigravityProjectContext.ProjectOnboarder {
        final String result;
        boolean called;
        String tierId;

        RecordingOnboarder(String result) {
            this.result = result;
        }

        @Override
        public String onboard(String accessToken, String tierId, String projectId, String proxy) {
            called = true;
            this.tierId = tierId;
            return result;
        }
    }
}
