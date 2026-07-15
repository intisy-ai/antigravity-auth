package io.github.intisy.ai.antigravity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of the DECISION core of antigravity-auth's {@code src/plugin/project.ts} (Bucket B,
 * "§5 ensureProjectContext" of {@code .superpowers/port-grounding-map.md}, T7f): the project-id
 * discovery state machine ({@code ensureProjectContext}'s inner {@code resolveContext}, :260-316)
 * plus its pure helpers -- {@code detectCodeAssistPlatform} (:19-27, via an injected {@link
 * Platform} seam), {@code buildMetadata} (:58-68), {@code getDefaultTierId} (:73-83), {@code
 * extractManagedProjectId} (:97-108) and {@code getCacheKey} (:113-116).
 *
 * <p>Boundary (Option B): the two fetch loops {@code loadManagedProject} (:121-170) and {@code
 * onboardManagedProject} (:176-233) stay host-side (Bucket C) behind the {@link ProjectLoader} /
 * {@link ProjectOnboarder} seams -- Java decides the SEQUENCE (short-circuit on a packed managed id,
 * load, extract, onboard, persist, fall back) and never performs I/O. The result/pending {@code
 * Map}+{@code Promise} caches (:248-258, :318-338) also stay host (a dedup/memo concern, not a
 * decision) -- this port is the pure {@code resolveContext} the host wraps in that cache. Refresh
 * pack/unpack reuses {@link AntigravityAuth} (T7a); no re-porting. No gson/java.net/java.nio/
 * reflection/threads/{@code process.*} -- TeaVM-transpilable.
 */
public final class AntigravityProjectContext {

    // constants.ts:76 -- fallback project id when Antigravity returns none.
    public static final String ANTIGRAVITY_DEFAULT_PROJECT_ID = "rising-fact-p41fc";

    private AntigravityProjectContext() {
    }

    // ---- injected seams (host-owned I/O; Java only decides) -------------------------------------

    /** {@code process.platform}/{@code process.arch} (detectCodeAssistPlatform, :19-27). */
    public interface Platform {
        /** {@code process.platform}: {@code "win32"}/{@code "darwin"}/{@code "linux"}/other. */
        String platform();

        /** {@code process.arch}: {@code "arm64"} or anything else. */
        String arch();
    }

    /** {@code loadManagedProject(accessToken, projectId, proxy)} (:121-170) -- the fetch loop stays host. */
    public interface ProjectLoader {
        /** The parsed {@code loadCodeAssist} payload (a {@code Map}), or {@code null} on every-endpoint failure. */
        Map<String, Object> load(String accessToken, String projectId, String proxy);
    }

    /** {@code onboardManagedProject(accessToken, tierId, projectId, proxy)} (:176-233) -- the fetch loop stays host. */
    public interface ProjectOnboarder {
        /** The provisioned managed project id, or {@code null} when provisioning did not complete. */
        String onboard(String accessToken, String tierId, String projectId, String proxy);
    }

    /** {@code ProjectContextResult}: {@code {auth, effectiveProjectId}} (types.ts). */
    public static final class ProjectContextResult {
        public final Map<String, Object> auth;
        public final String effectiveProjectId;

        public ProjectContextResult(Map<String, Object> auth, String effectiveProjectId) {
            this.auth = auth;
            this.effectiveProjectId = effectiveProjectId;
        }
    }

    // ---- detectCodeAssistPlatform (project.ts:19-27) --------------------------------------------

    /**
     * The {@code ClientMetadata.Platform} enum value -- {@code WINDOWS_AMD64}/{@code DARWIN_ARM64}/
     * {@code LINUX_AMD64}/... -- from {@code process.platform}+{@code process.arch}. "WINDOWS"/"MACOS"
     * alone are NOT valid enum values (they 400 the request), so the arch suffix is mandatory.
     */
    public static String detectCodeAssistPlatform(Platform platform) {
        String arch = "arm64".equals(platform.arch()) ? "ARM64" : "AMD64";
        String p = platform.platform();
        if ("win32".equals(p)) return "WINDOWS_" + arch;
        if ("darwin".equals(p)) return "DARWIN_" + arch;
        if ("linux".equals(p)) return "LINUX_" + arch;
        return "PLATFORM_UNSPECIFIED";
    }

    // ---- buildMetadata (project.ts:58-68) ------------------------------------------------------

    /** {@code {ideType:"ANTIGRAVITY", platform, pluginType:"GEMINI", [duetProject]}}. */
    public static Map<String, Object> buildMetadata(String projectId, Platform platform) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("ideType", "ANTIGRAVITY");
        metadata.put("platform", detectCodeAssistPlatform(platform));
        metadata.put("pluginType", "GEMINI");
        if (JsCoercion.isTruthy(projectId)) metadata.put("duetProject", projectId);
        return metadata;
    }

    // ---- getDefaultTierId (project.ts:73-83) ----------------------------------------------------

    /** The default tier id: the first {@code isDefault} tier's id, else the first tier's id, else {@code null}. */
    @SuppressWarnings("unchecked")
    public static String getDefaultTierId(List<Object> allowedTiers) {
        if (allowedTiers == null || allowedTiers.isEmpty()) return null;
        for (Object tierObj : allowedTiers) {
            if (tierObj instanceof Map && JsCoercion.isTruthy(((Map<String, Object>) tierObj).get("isDefault"))) {
                Object id = ((Map<String, Object>) tierObj).get("id");
                return id != null ? String.valueOf(id) : null;
            }
        }
        Object first = allowedTiers.get(0);
        Object id = first instanceof Map ? ((Map<String, Object>) first).get("id") : null;
        return id != null ? String.valueOf(id) : null;
    }

    // ---- extractManagedProjectId (project.ts:97-108) --------------------------------------------

    /** The {@code cloudaicompanionProject} id from a {@code loadCodeAssist} payload (string or {@code .id}). */
    @SuppressWarnings("unchecked")
    public static String extractManagedProjectId(Map<String, Object> payload) {
        if (payload == null) return null;
        Object project = payload.get("cloudaicompanionProject");
        if (project instanceof String) return (String) project;
        if (project instanceof Map) {
            Object id = ((Map<String, Object>) project).get("id");
            if (id instanceof String) return (String) id;
        }
        return null;
    }

    // ---- getCacheKey (project.ts:113-116) -------------------------------------------------------

    /** {@code auth.refresh?.trim() || undefined} -- the refresh string, trimmed, or {@code null}. */
    public static String getCacheKey(Map<String, Object> auth) {
        Object refresh = auth != null ? auth.get("refresh") : null;
        if (!(refresh instanceof String)) return null;
        String trimmed = ((String) refresh).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---- ensureProjectContext resolveContext core (project.ts:260-316) --------------------------

    /**
     * The project-id discovery DECISION (the TS {@code resolveContext}, minus the host-owned
     * {@code Map}/{@code Promise} cache the outer {@code ensureProjectContext} wraps it in):
     * <ol>
     *   <li>no access token -&gt; {@code {auth, ""}} (the outer TS short-circuit, :244-246, folded in);</li>
     *   <li>a packed {@code managedProjectId} in the refresh -&gt; use it directly (:262-264);</li>
     *   <li>else {@link ProjectLoader#load} + {@link #extractManagedProjectId} -&gt; persist (:281-286);</li>
     *   <li>else {@link #getDefaultTierId}{@code ?? "FREE"} + {@link ProjectOnboarder#onboard} -&gt;
     *       persist (:289-304);</li>
     *   <li>fall back to {@code parts.projectId}, else {@code fallbackProjectId} (:310-315).</li>
     * </ol>
     * "Persist" rewrites {@code auth.refresh} via {@link AntigravityAuth#formatRefreshParts},
     * keeping the original refresh token + project id and appending the discovered managed id.
     */
    @SuppressWarnings("unchecked")
    public static ProjectContextResult ensureProjectContext(Map<String, Object> auth, String proxy,
                                                            String fallbackProjectIdOption,
                                                            ProjectLoader loader, ProjectOnboarder onboarder,
                                                            Platform platform) {
        Object accessObj = auth != null ? auth.get("access") : null;
        String accessToken = accessObj != null ? String.valueOf(accessObj) : null;
        if (!JsCoercion.isTruthy(accessToken)) {
            return new ProjectContextResult(auth, "");
        }

        Object refreshObj = auth.get("refresh");
        AntigravityAuth.RefreshParts parts = AntigravityAuth.parseRefreshParts(
                refreshObj != null ? String.valueOf(refreshObj) : null);
        if (JsCoercion.isTruthy(parts.managedProjectId)) {
            return new ProjectContextResult(auth, parts.managedProjectId);
        }

        String fallbackProjectId = JsCoercion.isTruthy(fallbackProjectIdOption)
                ? fallbackProjectIdOption : ANTIGRAVITY_DEFAULT_PROJECT_ID;

        String loadProjectId = JsCoercion.isTruthy(parts.projectId) ? parts.projectId : fallbackProjectId;
        Map<String, Object> loadPayload = loader.load(accessToken, loadProjectId, proxy);
        String resolvedManagedProjectId = extractManagedProjectId(loadPayload);
        if (JsCoercion.isTruthy(resolvedManagedProjectId)) {
            return persistManagedProject(auth, parts, resolvedManagedProjectId);
        }

        List<Object> allowedTiers = null;
        if (loadPayload != null) {
            Object at = loadPayload.get("allowedTiers");
            if (at instanceof List) allowedTiers = (List<Object>) at;
        }
        String tierId = getDefaultTierId(allowedTiers);
        if (!JsCoercion.isTruthy(tierId)) tierId = "FREE";

        String provisionedProjectId = onboarder.onboard(accessToken, tierId, parts.projectId, proxy);
        if (JsCoercion.isTruthy(provisionedProjectId)) {
            return persistManagedProject(auth, parts, provisionedProjectId);
        }

        if (JsCoercion.isTruthy(parts.projectId)) {
            return new ProjectContextResult(auth, parts.projectId);
        }
        return new ProjectContextResult(auth, fallbackProjectId);
    }

    private static ProjectContextResult persistManagedProject(Map<String, Object> auth,
                                                              AntigravityAuth.RefreshParts parts,
                                                              String managedProjectId) {
        AntigravityAuth.RefreshParts next = new AntigravityAuth.RefreshParts(
                parts.refreshToken, parts.projectId, managedProjectId);
        Map<String, Object> updatedAuth = new LinkedHashMap<>(auth);
        updatedAuth.put("refresh", AntigravityAuth.formatRefreshParts(next));
        return new ProjectContextResult(updatedAuth, managedProjectId);
    }
}
