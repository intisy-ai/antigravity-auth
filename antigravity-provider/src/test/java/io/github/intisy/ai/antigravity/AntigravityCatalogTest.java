package io.github.intisy.ai.antigravity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.list;
import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline deterministic tests for {@link AntigravityCatalog}, including the antigravity gotchas:
 * effort-variant family collapse and the Gemini CLI free-pool group.
 */
class AntigravityCatalogTest {

    // ---- rankedAgentModelIds ----------------------------------------------------------------------

    @Test
    void rankedAgentModelIds_flattensAndDedupesInFirstSeenOrder() {
        Map<String, Object> payload = map("agentModelSorts", list(
                map("groups", list(map("modelIds", list("m1", "m2")), map("modelIds", list("m2", "m3")))),
                map("groups", list(map("modelIds", list("m4"))))));
        assertEquals(Arrays.asList("m1", "m2", "m3", "m4"), AntigravityCatalog.rankedAgentModelIds(payload));
    }

    @Test
    void rankedAgentModelIds_emptyPayload_emptyList() {
        assertTrue(AntigravityCatalog.rankedAgentModelIds(map()).isEmpty());
    }

    // ---- effortTagOf ------------------------------------------------------------------------------

    @Test
    void effortTagOf_minimal() {
        AntigravityCatalog.EffortTag t = AntigravityCatalog.effortTagOf("Gemini 3.5 Flash (Minimal)");
        assertEquals("Gemini 3.5 Flash", t.base);
        assertEquals("minimal", t.level);
    }

    @Test
    void effortTagOf_extraLowWithSpace_normalizesToMinimal() {
        AntigravityCatalog.EffortTag t = AntigravityCatalog.effortTagOf("Gemini 3.5 Flash (extra low)");
        assertEquals("Gemini 3.5 Flash", t.base);
        assertEquals("minimal", t.level);
    }

    @Test
    void effortTagOf_extraLowNoSpace_staysLiteralExtralow() {
        // No embedded space to collapse -> "extralow" != "extra-low" -> NOT remapped to minimal.
        // Intentional TS-faithful quirk (see AntigravityCatalog.effortTagOf javadoc).
        AntigravityCatalog.EffortTag t = AntigravityCatalog.effortTagOf("Gemini 3.5 Flash (extralow)");
        assertEquals("Gemini 3.5 Flash", t.base);
        assertEquals("extralow", t.level);
    }

    @Test
    void effortTagOf_low() {
        assertEquals("low", AntigravityCatalog.effortTagOf("Gemini 3.5 Flash (Low)").level);
    }

    @Test
    void effortTagOf_medium() {
        assertEquals("medium", AntigravityCatalog.effortTagOf("Gemini 3.5 Flash (Medium)").level);
    }

    @Test
    void effortTagOf_high() {
        assertEquals("high", AntigravityCatalog.effortTagOf("Gemini 3.5 Flash (High)").level);
    }

    @Test
    void effortTagOf_noTag_baseUnchangedLevelNull() {
        AntigravityCatalog.EffortTag t = AntigravityCatalog.effortTagOf("Gemini 3 Pro");
        assertEquals("Gemini 3 Pro", t.base);
        assertNull(t.level);
    }

    // ---- buildModelEntry --------------------------------------------------------------------------

    @Test
    void buildModelEntry_fullInfo() {
        Map<String, Object> entry = AntigravityCatalog.buildModelEntry("gemini-3-pro-agent", map(
                "displayName", "Gemini 3 Pro", "maxTokens", 1000000L, "maxOutputTokens", 32000L, "supportsImages", true));
        assertEquals("Gemini 3 Pro (Antigravity)", entry.get("name"));
        assertEquals(map("context", 1000000L, "output", 32000L), entry.get("limit"));
        assertEquals(map("input", list("text", "image", "pdf"), "output", list("text")), entry.get("modalities"));
    }

    @Test
    void buildModelEntry_defaultsWhenFieldsMissing() {
        Map<String, Object> entry = AntigravityCatalog.buildModelEntry("raw-id-x", map());
        assertEquals("raw-id-x (Antigravity)", entry.get("name"));
        assertEquals(map("context", 200000L, "output", 65535L), entry.get("limit"));
        assertEquals(map("input", list("text", "pdf"), "output", list("text")), entry.get("modalities"));
    }

    // ---- buildAntigravityCatalog: full effort-collapse case ---------------------------------------

    private static Map<String, Object> mainPayload() {
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("gemini-3-pro-agent", map("displayName", "Gemini 3 Pro", "maxTokens", 1000000L, "maxOutputTokens", 32000L));
        models.put("gemini-3.5-flash-low", map("displayName", "Gemini 3.5 Flash (Low)", "maxTokens", 1000000L, "maxOutputTokens", 32000L));
        models.put("gemini-3.5-flash-medium", map("displayName", "Gemini 3.5 Flash (Medium)", "maxTokens", 1000000L, "maxOutputTokens", 32000L));
        models.put("gemini-3.5-flash-high", map("displayName", "Gemini 3.5 Flash (High)", "maxTokens", 1000000L, "maxOutputTokens", 32000L));
        models.put("gemini-3-flash-agent", map("displayName", "Gemini 3.5 Flash (Extra Low)", "maxTokens", 1000000L, "maxOutputTokens", 32000L));
        models.put("deprecated-model", map("displayName", "Old Model"));
        models.put("image-model", map("displayName", "Image Gen Model"));
        return map(
                "defaultAgentModelId", "gemini-3-flash-agent",
                "agentModelSorts", list(map("groups", list(map("modelIds", list(
                        "gemini-3-pro-agent", "gemini-3.5-flash-low", "gemini-3.5-flash-medium",
                        "gemini-3.5-flash-high", "gemini-3-flash-agent", "deprecated-model", "image-model"))))),
                "models", models,
                "deprecatedModelIds", map("deprecated-model", true),
                "imageGenerationModelIds", list("image-model"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildAntigravityCatalog_rankingAndDefault_mapToCanonicalFamilyId() {
        Map<String, Object> result = AntigravityCatalog.buildAntigravityCatalog(mainPayload());
        assertEquals(Arrays.asList("antigravity-gemini-3-pro-agent", "antigravity-gemini-3.5-flash-low"),
                result.get("ranking"));
        // default names the hidden family member gemini-3-flash-agent -> maps to canonical flash id.
        assertEquals("antigravity-gemini-3.5-flash-low", result.get("defaultModelId"));

        Map<String, Object> catalog = (Map<String, Object>) result.get("models");
        // deprecated + image-only ids excluded; hidden member id NOT emitted as its own entry.
        assertFalse(catalog.containsKey("antigravity-deprecated-model"));
        assertFalse(catalog.containsKey("antigravity-image-model"));
        assertFalse(catalog.containsKey("antigravity-gemini-3.5-flash-medium"));
        assertFalse(catalog.containsKey("antigravity-gemini-3-flash-agent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildAntigravityCatalog_autoEntry_fixedShape() {
        Map<String, Object> catalog = (Map<String, Object>) AntigravityCatalog.buildAntigravityCatalog(mainPayload()).get("models");
        Map<String, Object> auto = (Map<String, Object>) catalog.get("antigravity-auto");
        assertEquals("Auto", auto.get("name"));
        assertEquals(map("context", 1048576L, "output", 65535L), auto.get("limit"));
        assertEquals(map(
                "minimal", map("thinkingLevel", "minimal"),
                "low", map("thinkingLevel", "low"),
                "medium", map("thinkingLevel", "medium"),
                "high", map("thinkingLevel", "high")), auto.get("variants"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildAntigravityCatalog_singleMemberFamily_noVariants() {
        Map<String, Object> catalog = (Map<String, Object>) AntigravityCatalog.buildAntigravityCatalog(mainPayload()).get("models");
        Map<String, Object> pro = (Map<String, Object>) catalog.get("antigravity-gemini-3-pro-agent");
        assertEquals("Gemini 3 Pro (Antigravity)", pro.get("name"));
        assertFalse(pro.containsKey("variants"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildAntigravityCatalog_multiMemberFamily_collapsesWithVariantsInMemberOrder() {
        Map<String, Object> catalog = (Map<String, Object>) AntigravityCatalog.buildAntigravityCatalog(mainPayload()).get("models");
        Map<String, Object> flash = (Map<String, Object>) catalog.get("antigravity-gemini-3.5-flash-low");
        assertEquals("Gemini 3.5 Flash (Antigravity)", flash.get("name"));
        Map<String, Object> expectedVariants = map(
                "low", map("thinkingLevel", "low", "model", "antigravity-gemini-3.5-flash-low"),
                "medium", map("thinkingLevel", "medium", "model", "antigravity-gemini-3.5-flash-medium"),
                "high", map("thinkingLevel", "high", "model", "antigravity-gemini-3.5-flash-high"),
                "minimal", map("thinkingLevel", "minimal", "model", "antigravity-gemini-3-flash-agent"));
        assertEquals(expectedVariants, flash.get("variants"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildAntigravityCatalog_geminiCliPool_labeledGroup() {
        Map<String, Object> catalog = (Map<String, Object>) AntigravityCatalog.buildAntigravityCatalog(mainPayload()).get("models");
        Map<String, Object> cli = (Map<String, Object>) catalog.get("gemini-2.5-flash");
        assertEquals("Gemini 2.5 Flash (Gemini CLI)", cli.get("name"));
        assertEquals("Gemini CLI · separate free pool (not in Auto)", cli.get("group"));
        assertEquals(map("context", 1048576L, "output", 65536L), cli.get("limit"));
        // all five CLI models are present as bare (unprefixed) ids
        for (String id : Arrays.asList("gemini-2.5-flash", "gemini-2.5-pro", "gemini-3-flash-preview",
                "gemini-3-pro-preview", "gemini-3.1-pro-preview")) {
            assertTrue(catalog.containsKey(id), "missing gemini-cli model " + id);
        }
    }

    @Test
    void buildAntigravityCatalog_noDefaultAgentModelId_fallsBackToFirstEmitted() {
        Map<String, Object> payload = map(
                "agentModelSorts", list(map("groups", list(map("modelIds", list("solo-model"))))),
                "models", map("solo-model", map("displayName", "Solo Model")));
        Map<String, Object> result = AntigravityCatalog.buildAntigravityCatalog(payload);
        assertEquals(Arrays.asList("antigravity-solo-model"), result.get("ranking"));
        assertEquals("antigravity-solo-model", result.get("defaultModelId"));
    }
}
