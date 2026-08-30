package io.github.intisy.ai.antigravity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The project-id discovery decision core: the {@code resolveContext} state machine plus its pure
 * helpers {@code detectCodeAssistPlatform} (via an injected {@link Platform} seam),
 * {@code buildMetadata}, {@code getDefaultTierId}, {@code extractManagedProjectId} and
 * {@code getCacheKey}.
 *
 * <p>The two fetch loops {@code loadManagedProject} and {@code onboardManagedProject} stay host-side
 * behind the {@link ProjectLoader} / {@link ProjectOnboarder} seams: this class decides the SEQUENCE
 * (short-circuit on a packed managed id, load, extract, onboard, persist, fall back) and never
 * performs I/O. The result/pending caches stay host too (a dedup/memo concern, not a decision), so
 * this is the pure {@code resolveContext} the host wraps in that cache. Refresh pack/unpack reuses
 * {@link AntigravityAuth}. No gson/java.net/java.nio/reflection/threads/{@code process.*},
 * TeaVM-transpilable.
 */
public final class AntigravityProjectContext {

    /** What a request is billed to when the upstream names no project of its own. */
    public static final String ANTIGRAVITY_DEFAULT_PROJECT_ID = "rising-fact-p41fc";

    private AntigravityProjectContext() {
    }

    // ---- injected seams (host-owned I/O; Java only decides) -------------------------------------

    /** {@code process.platform}/{@code process.arch} (detectCodeAssistPlatform). */
    public interface Platform {
        /** {@return the platform name, as the runtime reports it} */
        String platform();

        /** {@return the architecture, as the runtime reports it} */
        String arch();
    }

    /** {@code loadManagedProject(accessToken, projectId, proxy)}: the fetch loop stays host. */
    public interface ProjectLoader {
        /**
         * What the upstream says about one account's managed project.
         *
         * @param accessToken the account's access token
         * @param projectId the project to ask about, or blank to let the upstream choose
         * @param proxy the outbound proxy, or blank for a direct connection
         * @return the parsed payload, or {@code null} when every endpoint failed
         */
        Map<String, Object> load(String accessToken, String projectId, String proxy);
    }

    /** {@code onboardManagedProject(accessToken, tierId, projectId, proxy)}: the fetch loop stays host. */
    public interface ProjectOnboarder {
        /**
         * Provisions a managed project for one account.
         *
         * @param accessToken the account's access token
         * @param tierId the tier to provision under
         * @param projectId the project to provision, or blank to let the upstream choose
         * @param proxy the outbound proxy, or blank for a direct connection
         * @return the provisioned project id, or {@code null} when provisioning did not complete
         */
        String onboard(String accessToken, String tierId, String projectId, String proxy);
    }

    /** {@code ProjectContextResult}: {@code {auth, effectiveProjectId}}. */
    public static final class ProjectContextResult {
        /** The auth tree, whose refresh string carries any newly discovered project. */
        public final Map<String, Object> auth;
        /** The project the account's requests are billed to. */
        public final String effectiveProjectId;

        /**
         * One resolution's outcome.
         *
         * @param auth the auth tree, possibly rewritten
         * @param effectiveProjectId the project to bill to
         */
        public ProjectContextResult(Map<String, Object> auth, String effectiveProjectId) {
            this.auth = auth;
            this.effectiveProjectId = effectiveProjectId;
        }
    }

    // ---- detectCodeAssistPlatform ---------------------------------------------------------------

    /**
     * The {@code ClientMetadata.Platform} enum value ({@code WINDOWS_AMD64}/{@code DARWIN_ARM64}/
     * {@code LINUX_AMD64}/...) from {@code process.platform}+{@code process.arch}. "WINDOWS"/"MACOS"
     * alone are NOT valid enum values (they 400 the request), so the arch suffix is mandatory.
     *
     * @param platform the host platform and architecture
     * @return the enum value the upstream validates against
     */
    public static String detectCodeAssistPlatform(Platform platform) {
        String arch = "arm64".equals(platform.arch()) ? "ARM64" : "AMD64";
        String p = platform.platform();
        if ("win32".equals(p)) return "WINDOWS_" + arch;
        if ("darwin".equals(p)) return "DARWIN_" + arch;
        if ("linux".equals(p)) return "LINUX_" + arch;
        return "PLATFORM_UNSPECIFIED";
    }

    // ---- buildMetadata --------------------------------------------------------------------------

    /**
     * The client metadata every project request carries.
     *
     * @param projectId the project to name, or blank to name none
     * @param platform the host platform and architecture
     * @return the metadata block
     */
    public static Map<String, Object> buildMetadata(String projectId, Platform platform) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("ideType", "ANTIGRAVITY");
        metadata.put("platform", detectCodeAssistPlatform(platform));
        metadata.put("pluginType", "GEMINI");
        if (JsCoercion.isTruthy(projectId)) metadata.put("duetProject", projectId);
        return metadata;
    }

    // ---- getDefaultTierId -----------------------------------------------------------------------

    /**
     * Which tier a project is provisioned under.
     *
     * @param allowedTiers the tiers the upstream offered
     * @return the default tier's id, the first one's when none is marked, or {@code null} for none
     */
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

    // ---- extractManagedProjectId ----------------------------------------------------------------

    /**
     * The managed project the upstream reported, in whichever shape it reported it.
     *
     * @param payload the parsed upstream payload
     * @return the project id, or {@code null} when it named none
     */
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

    // ---- getCacheKey ----------------------------------------------------------------------------

    /**
     * What a resolved project context is cached under.
     *
     * @param auth the stored auth tree
     * @return its refresh string, trimmed, or {@code null} when it carries none
     */
    public static String getCacheKey(Map<String, Object> auth) {
        Object refresh = auth != null ? auth.get("refresh") : null;
        if (!(refresh instanceof String)) return null;
        String trimmed = ((String) refresh).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    // ---- ensureProjectContext resolveContext core -----------------------------------------------

    /**
     * The project-id discovery DECISION (the {@code resolveContext}, minus the host-owned
     * {@code Map}/{@code Promise} cache the outer {@code ensureProjectContext} wraps it in):
     * <ol>
     *   <li>no access token -&gt; {@code {auth, ""}};</li>
     *   <li>a packed {@code managedProjectId} in the refresh -&gt; use it directly;</li>
     *   <li>else {@link ProjectLoader#load} + {@link #extractManagedProjectId} -&gt; persist;</li>
     *   <li>else {@link #getDefaultTierId}{@code ?? "FREE"} + {@link ProjectOnboarder#onboard} -&gt;
     *       persist;</li>
     *   <li>fall back to {@code parts.projectId}, else {@code fallbackProjectId}.</li>
     * </ol>
     * "Persist" rewrites {@code auth.refresh} via {@link AntigravityAuth#formatRefreshParts},
     * keeping the original refresh token + project id and appending the discovered managed id.
     *
     * @param auth the stored auth tree, whose refresh string is rewritten when a project is found
     * @param proxy the outbound proxy, or {@code null} for a direct connection
     * @param fallbackProjectIdOption what to bill to when nothing is discovered
     * @param loader the host's managed-project fetch
     * @param onboarder the host's managed-project provisioning
     * @param platform the host platform and architecture
     * @return the auth tree and the project to bill to
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
