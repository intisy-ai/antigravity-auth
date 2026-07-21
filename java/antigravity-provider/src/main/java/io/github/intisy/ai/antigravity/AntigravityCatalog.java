package io.github.intisy.ai.antigravity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The effort-variant family collapse + ranking + default-model logic: {@code rankedAgentModelIds},
 * {@code effortTagOf}, {@code buildModelEntry}, and {@code buildAntigravityCatalog}. Does NOT fetch
 * models; {@link #buildAntigravityCatalog} takes the ALREADY-FETCHED payload as a plain {@code
 * Map}/{@code List} JSON tree (the shape both gson and this ecosystem's {@code JsonCodec} SPI
 * produce).
 *
 * <p>No java.net/nio/reflection/threads, TeaVM-transpilable, see {@code :antigravity-teavm}.
 */
public final class AntigravityCatalog {

    public static final String MODEL_ID_PREFIX = "antigravity-";

    // The separate Gemini CLI free quota pool (bare ids -> gemini-cli lane/headers); stable public
    // Gemini models, not part of the antigravity agent ranking.
    public static final class GeminiCliModel {
        public final String id;
        public final String name;
        public final long context;
        public final long output;

        public GeminiCliModel(String id, String name, long context, long output) {
            this.id = id;
            this.name = name;
            this.context = context;
            this.output = output;
        }
    }

    public static final List<GeminiCliModel> GEMINI_CLI_MODELS = Collections.unmodifiableList(Arrays.asList(
            new GeminiCliModel("gemini-2.5-flash", "Gemini 2.5 Flash", 1048576, 65536),
            new GeminiCliModel("gemini-2.5-pro", "Gemini 2.5 Pro", 1048576, 65536),
            new GeminiCliModel("gemini-3-flash-preview", "Gemini 3 Flash Preview", 1048576, 65536),
            new GeminiCliModel("gemini-3-pro-preview", "Gemini 3 Pro Preview", 1048576, 65535),
            new GeminiCliModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview", 1048576, 65535)));

    // NEVER infer effort from the raw backend id suffix; only the displayName's trailing
    // parenthesized tag is authoritative.
    private static final Pattern EFFORT_TAG =
            Pattern.compile("\\s*\\((minimal|extra\\s?low|low|medium|high)\\)\\s*$", Pattern.CASE_INSENSITIVE);

    private AntigravityCatalog() {
    }

    // ---- rankedAgentModelIds ---------------------------------------------------------------------

    /** Flattens {@code payload.agentModelSorts} into a single ranked id list (dedup, first-seen order). */
    @SuppressWarnings("unchecked")
    public static List<String> rankedAgentModelIds(Map<String, Object> payload) {
        List<String> ids = new ArrayList<>();
        Object sortsObj = payload.get("agentModelSorts");
        if (!(sortsObj instanceof List)) return ids;
        for (Object sortObj : (List<Object>) sortsObj) {
            if (!(sortObj instanceof Map)) continue;
            Object groupsObj = ((Map<String, Object>) sortObj).get("groups");
            if (!(groupsObj instanceof List)) continue;
            for (Object groupObj : (List<Object>) groupsObj) {
                if (!(groupObj instanceof Map)) continue;
                Object modelIdsObj = ((Map<String, Object>) groupObj).get("modelIds");
                if (!(modelIdsObj instanceof List)) continue;
                for (Object idObj : (List<Object>) modelIdsObj) {
                    if (idObj instanceof String && !ids.contains(idObj)) ids.add((String) idObj);
                }
            }
        }
        return ids;
    }

    // ---- effortTagOf -----------------------------------------------------------------------------

    /** Result of splitting a display name into its effort-tag-stripped base and (optional) effort level. */
    public static final class EffortTag {
        public final String base;
        /** {@code null} when the display name carries no recognized trailing effort tag. */
        public final String level;

        public EffortTag(String base, String level) {
            this.base = base;
            this.level = level;
        }
    }

    /**
     * Splits a trailing {@code " (Low)"}/{@code " (extra low)"}/etc. effort tag off a display
     * name; {@code "extra low"} normalizes to {@code "minimal"} (via {@code replace(/\s+/g, "-")}).
     * A NO-SPACE {@code "extralow"} has no space to collapse, so it normalizes to the literal
     * {@code "extralow"}, NOT {@code "minimal"}; this is an intentional quirk, not a bug.
     */
    public static EffortTag effortTagOf(String displayName) {
        Matcher m = EFFORT_TAG.matcher(displayName);
        if (!m.find()) return new EffortTag(displayName, null);
        String tag = m.group(1).toLowerCase().replaceAll("\\s+", "-");
        String level = tag.equals("extra-low") ? "minimal" : tag;
        String base = EFFORT_TAG.matcher(displayName).replaceFirst("").trim();
        return new EffortTag(base, level);
    }

    // ---- buildModelEntry -------------------------------------------------------------------------

    /** Builds one catalog entry from a single {@code fetchAvailableModels} model info record. */
    public static Map<String, Object> buildModelEntry(String rawId, Map<String, Object> info) {
        Object displayNameObj = info.get("displayName");
        String name = (JsCoercion.isTruthy(displayNameObj) ? String.valueOf(displayNameObj) : rawId) + " (Antigravity)";

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);

        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("context", numberOr(info.get("maxTokens"), 200000));
        limit.put("output", numberOr(info.get("maxOutputTokens"), 65535));
        entry.put("limit", limit);

        Map<String, Object> modalities = new LinkedHashMap<>();
        modalities.put("input", JsCoercion.isTruthy(info.get("supportsImages"))
                ? Arrays.asList("text", "image", "pdf")
                : Arrays.asList("text", "pdf"));
        modalities.put("output", Collections.singletonList("text"));
        entry.put("modalities", modalities);
        return entry;
    }

    // Approximates `x || fallback` for a JSON-parsed numeric field: any non-Number, zero, or NaN
    // value (all falsy in JS) falls back.
    private static long numberOr(Object v, long fallback) {
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d != 0 && !Double.isNaN(d)) return (long) d;
        }
        return fallback;
    }

    // ---- buildAntigravityCatalog -----------------------------------------------------------------

    /** One ranked-and-grouped effort family, keyed by its effort-tag-stripped display-name base. */
    private static final class EffortGroup {
        final String canonical;
        final String base;
        final List<Member> members = new ArrayList<>();

        EffortGroup(String canonical, String base) {
            this.canonical = canonical;
            this.base = base;
        }
    }

    private static final class Member {
        final String rawId;
        final String level;

        Member(String rawId, String level) {
            this.rawId = rawId;
            this.level = level;
        }
    }

    /**
     * Builds the catalog from a {@code fetchAvailableModels} payload: the recommended agent models
     * (in rank order), minus deprecated/image-generation ids, with effort-variant families
     * collapsed into one entry each (a {@code variants} map from effort level to the concrete
     * backend model id) plus the fixed {@code Auto} entry and the Gemini CLI free-pool group.
     *
     * @return a plain map with keys {@code "models"} (the full catalog, keyed by prefixed id),
     *         {@code "ranking"} (prefixed ids in recommended order, the Auto-routing source),
     *         and {@code "defaultModelId"} (the default agent model's prefixed id, or {@code null}).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildAntigravityCatalog(Map<String, Object> payload) {
        Object modelsObj = payload.get("models");
        Map<String, Object> models = modelsObj instanceof Map ? (Map<String, Object>) modelsObj : new LinkedHashMap<>();

        Set<String> deprecated = new LinkedHashSet<>();
        Object deprecatedObj = payload.get("deprecatedModelIds");
        if (deprecatedObj instanceof Map) deprecated.addAll(((Map<String, Object>) deprecatedObj).keySet());

        Set<String> imageOnly = new LinkedHashSet<>();
        Object imageOnlyObj = payload.get("imageGenerationModelIds");
        if (imageOnlyObj instanceof List) {
            for (Object id : (List<Object>) imageOnlyObj) if (id instanceof String) imageOnly.add((String) id);
        }

        List<String> ranked = new ArrayList<>();
        for (String id : rankedAgentModelIds(payload)) {
            if (models.get(id) instanceof Map && !deprecated.contains(id) && !imageOnly.contains(id)) ranked.add(id);
        }

        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put(MODEL_ID_PREFIX + "auto", buildAutoEntry());

        // Pass 1 - group ranked models into effort families by displayName base.
        Map<String, EffortGroup> groups = new LinkedHashMap<>();
        List<String> emitOrder = new ArrayList<>();
        for (String rawId : ranked) {
            Map<String, Object> info = (Map<String, Object>) models.get(rawId);
            Object displayNameObj = info.get("displayName");
            String displayName = JsCoercion.isTruthy(displayNameObj) ? String.valueOf(displayNameObj) : rawId;
            EffortTag tag = effortTagOf(displayName);
            if (tag.level == null) {
                emitOrder.add(rawId);
                continue;
            }
            EffortGroup group = groups.get(tag.base);
            if (group == null) {
                group = new EffortGroup(rawId, tag.base);
                groups.put(tag.base, group);
                emitOrder.add(rawId);
            }
            group.members.add(new Member(rawId, tag.level));
        }

        // Pass 2 - emit. Single-member families keep their tagged name; multi-member families
        // collapse into the canonical (first-seen/API-preferred) id with a `variants` map.
        Map<String, EffortGroup> groupOf = new LinkedHashMap<>();
        for (EffortGroup group : groups.values()) {
            if (group.members.size() > 1) groupOf.put(group.canonical, group);
        }
        for (String rawId : emitOrder) {
            Map<String, Object> info = (Map<String, Object>) models.get(rawId);
            Map<String, Object> entry = buildModelEntry(rawId, info);
            EffortGroup group = groupOf.get(rawId);
            if (group != null) {
                entry.put("name", group.base + " (Antigravity)");
                Map<String, Object> variants = new LinkedHashMap<>();
                for (Member member : group.members) {
                    Map<String, Object> variant = new LinkedHashMap<>();
                    variant.put("thinkingLevel", member.level);
                    variant.put("model", MODEL_ID_PREFIX + member.rawId);
                    variants.put(member.level, variant);
                }
                entry.put("variants", variants);
            }
            catalog.put(MODEL_ID_PREFIX + rawId, entry);
        }

        // Gemini CLI quota pool (bare ids, distinct lane), a second free pool.
        for (GeminiCliModel cli : GEMINI_CLI_MODELS) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", cli.name + " (Gemini CLI)");
            Map<String, Object> limit = new LinkedHashMap<>();
            limit.put("context", cli.context);
            limit.put("output", cli.output);
            entry.put("limit", limit);
            Map<String, Object> modalities = new LinkedHashMap<>();
            modalities.put("input", Arrays.asList("text", "image", "pdf"));
            modalities.put("output", Collections.singletonList("text"));
            entry.put("modalities", modalities);
            entry.put("group", "Gemini CLI · separate free pool (not in Auto)");
            catalog.put(cli.id, entry);
        }

        // The API default may name a hidden family member, so map it to its family's canonical
        // (emitted) id.
        Object defaultAgentModelIdObj = payload.get("defaultAgentModelId");
        String defaultRaw = (defaultAgentModelIdObj instanceof String && ranked.contains(defaultAgentModelIdObj))
                ? (String) defaultAgentModelIdObj
                : (!emitOrder.isEmpty() ? emitOrder.get(0) : null);
        if (defaultRaw != null && !catalog.containsKey(MODEL_ID_PREFIX + defaultRaw)) {
            for (EffortGroup group : groups.values()) {
                boolean found = false;
                for (Member member : group.members) {
                    if (member.rawId.equals(defaultRaw)) {
                        defaultRaw = group.canonical;
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("models", catalog);
        List<String> rankingIds = new ArrayList<>();
        for (String id : emitOrder) rankingIds.add(MODEL_ID_PREFIX + id);
        result.put("ranking", rankingIds);
        result.put("defaultModelId", defaultRaw != null ? MODEL_ID_PREFIX + defaultRaw : null);
        return result;
    }

    private static Map<String, Object> buildAutoEntry() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", "Auto");
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("context", 1048576L);
        limit.put("output", 65535L);
        entry.put("limit", limit);
        Map<String, Object> modalities = new LinkedHashMap<>();
        modalities.put("input", Arrays.asList("text", "image", "pdf"));
        modalities.put("output", Collections.singletonList("text"));
        entry.put("modalities", modalities);
        Map<String, Object> variants = new LinkedHashMap<>();
        for (String level : new String[] {"minimal", "low", "medium", "high"}) {
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("thinkingLevel", level);
            variants.put(level, variant);
        }
        entry.put("variants", variants);
        return entry;
    }
}
