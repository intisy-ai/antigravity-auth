package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.JsonCodec;
import io.github.intisy.ai.api.seam.Logger;
import io.github.intisy.ai.api.seam.Random;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure decision helpers the antigravity {@code handle}/{@code attemptModel} state machine calls:
 * {@code isRateLimitStatus}, {@code isAutoModel}, {@code rewriteModelInUrl}, {@code modelFromRequest},
 * {@code requestedThinkingLevel}, {@code resolveEffortVariant}, {@code soonestQuotaReset}, {@code
 * endpointsFor}, {@code buildAuth} and {@code driftAccountVersions}. Every method is static and
 * side-effect-free (drift returns the per-account mutations as data rather than applying them, keeping
 * the "decision returns, host applies" boundary); the model-cache read is an injected
 * {@link ModelCacheLookup} seam (the disk read stays host-side).
 *
 * <p>Reuses {@link AntigravityLanes} (lanes), {@link AntigravityAuth#formatRefreshParts} (buildAuth),
 * {@link AntigravityVersions} (drift math) and {@link AntigravityQuotaParser#parseDateToEpochMillis}
 * ({@code Date.parse} stand-in). No gson/java.net/java.nio/reflection/threads/
 * {@code System.currentTimeMillis}, TeaVM-transpilable.
 */
public final class AntigravityHandleRouting {

    // endpoint fallback order (prod -> daily -> autopush).
    public static final String ANTIGRAVITY_ENDPOINT_PROD = "https://cloudcode-pa.googleapis.com";
    public static final String ANTIGRAVITY_ENDPOINT_DAILY = "https://daily-cloudcode-pa.sandbox.googleapis.com";
    public static final String ANTIGRAVITY_ENDPOINT_AUTOPUSH = "https://autopush-cloudcode-pa.sandbox.googleapis.com";

    // effort-variant ordering.
    private static final List<String> LEVEL_ORDER = Arrays.asList("minimal", "low", "medium", "high");

    private static final Pattern MODELS_IN_URL = Pattern.compile("/models/([^:/?]+)");
    private static final Pattern MODELS_REWRITE = Pattern.compile("/models/[^:/?]+");
    private static final Pattern AUTO_PREFIX = Pattern.compile("(?i)^antigravity-");
    private static final Pattern UA_VERSION = Pattern.compile("antigravity/([^ ]+)");
    private static final Pattern RESET_AFTER = Pattern.compile("(?i)reset(?:s)?\\s+(?:after|in)\\s+(\\d+)\\s*s");

    private AntigravityHandleRouting() {
    }

    // ---- isRateLimitStatus ----------------------------------------------------------------------

    public static boolean isRateLimitStatus(int status) {
        return status == 429 || status == 503 || status == 529;
    }

    // ---- isAutoModel ----------------------------------------------------------------------------

    /** {@code String(model||"").replace(/^antigravity-/i,"")} then {@code ==="auto" || startsWith("auto-")}. */
    public static boolean isAutoModel(Object model) {
        String raw = JsCoercion.isTruthy(model) ? String.valueOf(model) : "";
        String stripped = AUTO_PREFIX.matcher(raw).replaceFirst("");
        return "auto".equals(stripped) || stripped.startsWith("auto-");
    }

    // ---- rewriteModelInUrl ----------------------------------------------------------------------

    /** Replaces the FIRST {@code /models/<id>} segment with {@code /models/<model>} (JS replace = first only). */
    public static String rewriteModelInUrl(Object url, String model) {
        return MODELS_REWRITE.matcher(String.valueOf(url)).replaceFirst(Matcher.quoteReplacement("/models/" + model));
    }

    // ---- modelFromRequest -----------------------------------------------------------------------

    /**
     * The requested model id: {@code ctxModel} wins outright; else the {@code /models/<id>} URL
     * segment (percent-decoded); else {@code body.model}; else {@code "antigravity-auto"}.
     */
    @SuppressWarnings("unchecked")
    public static String modelFromRequest(Object url, String bodyText, String ctxModel, JsonCodec json) {
        if (JsCoercion.isTruthy(ctxModel)) return ctxModel;
        if (url instanceof String) {
            Matcher m = MODELS_IN_URL.matcher((String) url);
            if (m.find()) return decodeUriComponent(m.group(1));
        }
        try {
            Object parsed = json.parse(bodyText == null || bodyText.isEmpty() ? "{}" : bodyText);
            if (parsed instanceof Map) {
                Object model = ((Map<String, Object>) parsed).get("model");
                if (JsCoercion.isTruthy(model)) return String.valueOf(model);
            }
        } catch (RuntimeException ignored) {
            // empty catch: falls through to the default.
        }
        return "antigravity-auto";
    }

    // ---- requestedThinkingLevel -----------------------------------------------------------------

    /**
     * Requested thinking level from the body: opencode's {@code providerOptions.google.thinkingLevel}
     * / {@code .thinkingConfig}, or the Claude bridge's {@code generationConfig.thinkingConfig}. A
     * numeric {@code thinkingBudget} maps to low/medium/high by budget thresholds. Returns {@code
     * null} when none is present. Wrapped bodies ({@code parsed.request}) are unwrapped first.
     */
    @SuppressWarnings("unchecked")
    public static String requestedThinkingLevel(String bodyText, JsonCodec json) {
        try {
            Object parsedObj = json.parse(bodyText == null || bodyText.isEmpty() ? "{}" : bodyText);
            if (!(parsedObj instanceof Map)) return null;
            Map<String, Object> parsed = (Map<String, Object>) parsedObj;
            Object reqObj = parsed.get("request");
            Map<String, Object> req = reqObj instanceof Map ? (Map<String, Object>) reqObj : parsed;
            Object providerOptions = req.get("providerOptions");
            Object google = providerOptions instanceof Map ? ((Map<String, Object>) providerOptions).get("google") : null;
            Map<String, Object> googleMap = google instanceof Map ? (Map<String, Object>) google : null;
            if (googleMap != null && googleMap.get("thinkingLevel") instanceof String) {
                return (String) googleMap.get("thinkingLevel");
            }
            Object tcObj = googleMap != null ? googleMap.get("thinkingConfig") : null;
            if (!(tcObj instanceof Map)) {
                Object gc = req.get("generationConfig");
                tcObj = gc instanceof Map ? ((Map<String, Object>) gc).get("thinkingConfig") : null;
            }
            if (tcObj instanceof Map) {
                Map<String, Object> tc = (Map<String, Object>) tcObj;
                if (tc.get("thinkingLevel") instanceof String) return (String) tc.get("thinkingLevel");
                Object budget = tc.get("thinkingBudget");
                if (budget instanceof Number) {
                    double b = ((Number) budget).doubleValue();
                    return b <= 8192 ? "low" : b <= 16384 ? "medium" : "high";
                }
            }
        } catch (RuntimeException ignored) {
            // empty catch: null.
        }
        return null;
    }

    /** {@code readModelCache(PROVIDER_ID).models[modelId].variants} (the disk read stays host-side). */
    public interface ModelCacheLookup {
        /** The catalog entry's {@code variants} map for {@code modelId}, or {@code null}. */
        Map<String, Object> variantsFor(String modelId);
    }

    // ---- resolveEffortVariant -------------------------------------------------------------------

    /**
     * Picks the concrete backend model id for the requested thinking level from the catalog entry's
     * {@code variants} map: exact level, else the highest available level not above the request,
     * else the lowest. Returns {@code modelId} unchanged when there are no variants / no requested
     * level / an unknown level / no usable variant. {@code json} is threaded so the inner
     * {@code JSON.parse} (inside {@code requestedThinkingLevel}) stays on the SPI.
     */
    @SuppressWarnings("unchecked")
    public static String resolveEffortVariant(String modelId, String bodyText, ModelCacheLookup cache, JsonCodec json, Logger log) {
        Map<String, Object> variants = null;
        try {
            variants = cache != null ? cache.variantsFor(modelId) : null;
        } catch (RuntimeException ignored) {
            // empty catch around readModelCache: treated as "no variants".
        }
        if (variants == null || variants.isEmpty()) return modelId;
        String level = requestedThinkingLevel(bodyText, json);
        if (level == null) return modelId;
        int want = LEVEL_ORDER.indexOf(level);
        if (want < 0) return modelId;
        // Keep only variants with a truthy .model whose key is a known level, sorted by level.
        List<Map.Entry<String, Object>> available = new ArrayList<>();
        for (Map.Entry<String, Object> e : variants.entrySet()) {
            Object v = e.getValue();
            Object vModel = v instanceof Map ? ((Map<String, Object>) v).get("model") : null;
            if (JsCoercion.isTruthy(vModel) && LEVEL_ORDER.contains(e.getKey())) available.add(e);
        }
        available.sort((a, b) -> LEVEL_ORDER.indexOf(a.getKey()) - LEVEL_ORDER.indexOf(b.getKey()));
        if (available.isEmpty()) return modelId;
        Map.Entry<String, Object> chosen = available.get(0);
        for (Map.Entry<String, Object> candidate : available) {
            if (LEVEL_ORDER.indexOf(candidate.getKey()) <= want) chosen = candidate;
        }
        Object target = ((Map<String, Object>) chosen.getValue()).get("model");
        String targetStr = target != null ? String.valueOf(target) : null;
        if (JsCoercion.isTruthy(targetStr) && !targetStr.equals(modelId) && log != null) {
            log.info("effort variant: " + modelId + " @ " + level + " -> " + targetStr);
        }
        return JsCoercion.isTruthy(targetStr) ? targetStr : modelId;
    }

    // ---- endpointsFor ---------------------------------------------------------------------------

    public static List<String> endpointsFor(String headerStyle) {
        if ("gemini-cli".equals(headerStyle)) {
            return new ArrayList<>(Arrays.asList(ANTIGRAVITY_ENDPOINT_PROD));
        }
        return new ArrayList<>(Arrays.asList(
                ANTIGRAVITY_ENDPOINT_PROD, ANTIGRAVITY_ENDPOINT_DAILY, ANTIGRAVITY_ENDPOINT_AUTOPUSH));
    }

    // ---- soonestQuotaReset ----------------------------------------------------------------------

    /**
     * Soonest quota-pool reset (epoch ms) among EXHAUSTED pools across all accounts'
     * {@code meta.cachedQuota}. A pool counts as exhausted when its {@code remainingFraction} is 0
     * or absent. Returns 0 when unknown. Uses {@link
     * AntigravityQuotaParser#parseDateToEpochMillis} as the {@code Date.parse} stand-in.
     */
    @SuppressWarnings("unchecked")
    public static long soonestQuotaReset(List<Map<String, Object>> accounts) {
        long soonest = 0;
        if (accounts == null) return 0;
        for (Map<String, Object> a : accounts) {
            Object metaObj = a != null ? a.get("meta") : null;
            Object cqObj = metaObj instanceof Map ? ((Map<String, Object>) metaObj).get("cachedQuota") : null;
            if (!(cqObj instanceof Map)) continue;
            Map<String, Object> cq = (Map<String, Object>) cqObj;
            for (Object qObj : cq.values()) {
                if (!(qObj instanceof Map)) continue;
                Map<String, Object> q = (Map<String, Object>) qObj;
                Object resetTime = q.get("resetTime");
                if (!JsCoercion.isTruthy(resetTime)) continue;
                Object rf = q.get("remainingFraction");
                boolean exhausted = !(rf instanceof Number) || ((Number) rf).doubleValue() == 0;
                if (!exhausted) continue;
                double t = AntigravityQuotaParser.parseDateToEpochMillis(String.valueOf(resetTime));
                if (Double.isFinite(t) && (soonest == 0 || t < soonest)) soonest = (long) t;
            }
        }
        return soonest;
    }

    // ---- buildAuth ------------------------------------------------------------------------------

    /**
     * Reconstructs the {@code OAuthAuthDetails} the project/transform code expects from a stored
     * account + a fresh access token. Returns a plain {@code {type,access,expires,refresh}} map;
     * {@code refresh} packs the project ids via {@link AntigravityAuth#formatRefreshParts}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildAuth(Map<String, Object> account, String access) {
        Object metaObj = account != null ? account.get("meta") : null;
        Map<String, Object> meta = metaObj instanceof Map ? (Map<String, Object>) metaObj : new java.util.LinkedHashMap<>();
        AntigravityAuth.RefreshParts parts = new AntigravityAuth.RefreshParts();
        Object refresh = account != null ? account.get("refresh") : null;
        parts.refreshToken = refresh != null ? String.valueOf(refresh) : null;
        Object projectId = meta.get("projectId");
        parts.projectId = projectId != null ? String.valueOf(projectId) : null;
        Object managedProjectId = meta.get("managedProjectId");
        parts.managedProjectId = managedProjectId != null ? String.valueOf(managedProjectId) : null;
        Map<String, Object> auth = new java.util.LinkedHashMap<>();
        auth.put("type", "oauth");
        auth.put("access", access);
        auth.put("expires", account != null ? account.get("expires") : null);
        auth.put("refresh", AntigravityAuth.formatRefreshParts(parts));
        return auth;
    }

    // ---- driftAccountVersions -------------------------------------------------------------------

    /** One per-account version-drift mutation the host applies via {@code manager.mutate}. */
    public static final class VersionDrift {
        public final String accountId;
        /** First-sight: only schedule {@code nextVersionDriftAt}, change nothing else. */
        public final boolean scheduleOnly;
        public final long nextVersionDriftAt;
        /** Drift only: the new {@code userAgent}/{@code version}/{@code versionPickedAt} (null when scheduleOnly). */
        public final String userAgent;
        public final String version;
        public final Long versionPickedAt;

        VersionDrift(String accountId, boolean scheduleOnly, long nextVersionDriftAt,
                     String userAgent, String version, Long versionPickedAt) {
            this.accountId = accountId;
            this.scheduleOnly = scheduleOnly;
            this.nextVersionDriftAt = nextVersionDriftAt;
            this.userAgent = userAgent;
            this.version = version;
            this.versionPickedAt = versionPickedAt;
        }
    }

    /**
     * Bumps accounts' stored User-Agent version forward over time (simulated IDE auto-update),
     * each on its OWN randomized due date so they never move in lockstep. First sight schedules a
     * staggered due date and changes nothing; a due account drifts forward via {@link
     * AntigravityVersions#driftVersion} and reschedules. Accounts without a {@code
     * meta.fingerprint.userAgent} are skipped. Returns the ordered per-account mutations for the
     * host to apply.
     */
    @SuppressWarnings("unchecked")
    public static List<VersionDrift> driftAccountVersions(List<Map<String, Object>> accounts, long now,
                                                          Random random, List<String> versionList) {
        List<VersionDrift> out = new ArrayList<>();
        if (accounts == null) return out;
        for (Map<String, Object> account : accounts) {
            Object metaObj = account != null ? account.get("meta") : null;
            Object fpObj = metaObj instanceof Map ? ((Map<String, Object>) metaObj).get("fingerprint") : null;
            if (!(fpObj instanceof Map)) continue;
            Map<String, Object> fp = (Map<String, Object>) fpObj;
            if (!JsCoercion.isTruthy(fp.get("userAgent"))) continue;

            String accountId = String.valueOf(account.get("id"));
            Object nextAt = fp.get("nextVersionDriftAt");
            if (!(nextAt instanceof Number)) {
                long delay = AntigravityVersions.nextVersionDriftDelay(JsCoercion.isTruthy(fp.get("version")), random);
                out.add(new VersionDrift(accountId, true, now + delay, null, null, null));
                continue;
            }
            if (now < ((Number) nextAt).doubleValue()) continue; // not due yet

            String current = firstNonEmpty(
                    JsCoercion.isTruthy(fp.get("version")) ? String.valueOf(fp.get("version")) : "",
                    uaVersion(String.valueOf(fp.get("userAgent"))));
            String next = AntigravityVersions.driftVersion(current, versionList, random);
            String userAgent = UA_VERSION.matcher(String.valueOf(fp.get("userAgent")))
                    .replaceFirst(Matcher.quoteReplacement("antigravity/" + next));
            long reschedule = now + AntigravityVersions.nextVersionDriftDelay(true, random);
            out.add(new VersionDrift(accountId, false, reschedule, userAgent, next, now));
        }
        return out;
    }

    // ---- reset-after regex + percent-decode helpers ---------------------------------------------

    /** {@code /reset(?:s)?\s+(?:after|in)\s+(\d+)\s*s/i} on the error message -> ms, or 0. */
    public static long retryAfterMsFromMessage(String message) {
        if (message == null || message.isEmpty()) return 0;
        Matcher m = RESET_AFTER.matcher(message);
        return m.find() ? Long.parseLong(m.group(1)) * 1000L : 0;
    }

    private static String uaVersion(String userAgent) {
        Matcher m = UA_VERSION.matcher(userAgent);
        return m.find() ? m.group(1) : "";
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b != null ? b : "");
    }

    /**
     * Minimal {@code decodeURIComponent} for the {@code /models/<id>} path segment: decodes
     * {@code %XX} escapes as single bytes (ASCII). Model ids are always plain ASCII in practice, so
     * the full multi-byte UTF-8 decode {@code decodeURIComponent} performs is not reproduced, an
     * unreachable edge never exercised by any real model id.
     */
    private static String decodeUriComponent(String s) {
        if (s.indexOf('%') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                    // fall through: keep the literal '%'
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
