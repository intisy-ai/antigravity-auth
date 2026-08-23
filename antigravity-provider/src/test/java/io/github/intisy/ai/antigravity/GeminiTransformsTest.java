package io.github.intisy.ai.antigravity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.list;
import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline tests for {@link GeminiTransforms}. The tool-normalization transforms mutate
 * {@code payload.tools} in place, so these tests assert on the mutated payload plus the returned
 * debug/wrap result.
 */
class GeminiTransformsTest {

    // ---- toGeminiSchema --------------------------------------------------------------------------

    @Test
    void schema_uppercasesTypesAndStripsUnsupported() {
        assertEquals(
                map("type", "OBJECT", "properties", map("a", map("type", "STRING")), "required", list("a")),
                GeminiTransforms.toGeminiSchema(map("type", "object", "properties", map("a", map("type", "string")),
                        "required", list("a"), "additionalProperties", false, "$schema", "x")));
    }

    @Test
    void schema_arrayGetsDefaultItems() {
        assertEquals(map("type", "ARRAY", "items", map("type", "NUMBER")),
                GeminiTransforms.toGeminiSchema(map("type", "array", "items", map("type", "number"))));
        assertEquals(map("type", "ARRAY", "items", map("type", "STRING")),
                GeminiTransforms.toGeminiSchema(map("type", "array")));
    }

    @Test
    void schema_requiredFilteredToValidProps_orKeptWhenNoProps() {
        // invalid required entry ("missing") dropped when properties exist
        assertEquals(
                map("type", "OBJECT", "properties", map("a", map("type", "STRING"), "b", map("type", "INTEGER")), "required", list("a")),
                GeminiTransforms.toGeminiSchema(map("type", "object",
                        "properties", map("a", map("type", "string"), "b", map("type", "integer")),
                        "required", list("a", "missing"))));
        // no properties -> required kept as-is
        assertEquals(map("type", "OBJECT", "required", list("x")),
                GeminiTransforms.toGeminiSchema(map("type", "object", "required", list("x"))));
    }

    @Test
    void schema_anyOfEnumDefaultExamples_and_dropsConstIfThen() {
        assertEquals(
                map("anyOf", list(map("type", "STRING"), map("type", "NUMBER")), "enum", list("a", "b"), "default", 1, "examples", list(2)),
                GeminiTransforms.toGeminiSchema(map("anyOf", list(map("type", "string"), map("type", "number")),
                        "enum", list("a", "b"), "default", 1, "examples", list(2))));
        assertEquals(map("type", "STRING"),
                GeminiTransforms.toGeminiSchema(map("type", "string", "const", "x", "contentMediaType", "text",
                        "if", map(), "then", map(), "patternProperties", map())));
    }

    @Test
    void schema_passthroughNonObjects() {
        assertEquals("not-an-object", GeminiTransforms.toGeminiSchema("not-an-object"));
        assertNull(GeminiTransforms.toGeminiSchema(null));
        assertEquals(list(1, 2, 3), GeminiTransforms.toGeminiSchema(list(1, 2, 3)));
    }

    // ---- model predicates ------------------------------------------------------------------------

    @Test
    void predicates() {
        assertTrue(GeminiTransforms.isGeminiModel("gemini-3-pro"));
        assertFalse(GeminiTransforms.isGeminiModel("gemini-claude-sonnet-4-6"));
        assertTrue(GeminiTransforms.isGeminiModel("GEMINI-3"));
        assertTrue(GeminiTransforms.isGemini3Model("gemini-3.1-pro"));
        assertFalse(GeminiTransforms.isGemini3Model("gemini-2.5-pro"));
        assertTrue(GeminiTransforms.isGemini25Model("GEMINI-2.5"));
        assertTrue(GeminiTransforms.isImageGenerationModel("imagen-3"));
        assertFalse(GeminiTransforms.isImageGenerationModel("gemini-3-pro"));
    }

    // ---- thinking config builders ----------------------------------------------------------------

    @Test
    void thinkingConfigBuilders() {
        assertEquals(map("includeThoughts", true, "thinkingLevel", "high"), GeminiTransforms.buildGemini3ThinkingConfig(true, "high"));
        assertEquals(map("includeThoughts", true, "thinkingBudget", 8192), GeminiTransforms.buildGemini25ThinkingConfig(true, 8192));
        assertEquals(map("includeThoughts", true), GeminiTransforms.buildGemini25ThinkingConfig(true, 0));
        assertEquals(map("includeThoughts", true), GeminiTransforms.buildGemini25ThinkingConfig(true, null));
        assertEquals(map("includeThoughts", false), GeminiTransforms.buildGemini25ThinkingConfig(false, -5));
    }

    // ---- buildImageGenerationConfig --------------------------------------------------------------

    @Test
    void imageConfig_defaultValidInvalid() {
        TestDoubles.CapturingLogger logger = new TestDoubles.CapturingLogger();
        assertEquals(map("aspectRatio", "1:1"), GeminiTransforms.buildImageGenerationConfig(null, logger));
        assertEquals(map("aspectRatio", "16:9"), GeminiTransforms.buildImageGenerationConfig("16:9", logger));
        assertTrue(logger.messages.isEmpty());
        assertEquals(map("aspectRatio", "1:1"), GeminiTransforms.buildImageGenerationConfig("bogus", logger));
        assertEquals(1, logger.messages.size());
        assertTrue(logger.messages.get(0).contains("Invalid aspect ratio \"bogus\""));
    }

    // ---- normalizeGeminiTools --------------------------------------------------------------------

    @Test
    void normalizeTools_functionSchemaUppercased() {
        Map<String, Object> payload = map("tools", list(map("function",
                map("name", "f1", "description", "d1", "input_schema", map("type", "object", "properties", map("a", map("type", "string")), "required", list("a"))))));
        Map<String, Object> result = GeminiTransforms.normalizeGeminiTools(payload);
        assertEquals(map("tools", list(map("function",
                map("name", "f1", "description", "d1", "input_schema", map("type", "OBJECT", "properties", map("a", map("type", "STRING")), "required", list("a")))))), payload);
        assertEquals(0, result.get("toolDebugMissing"));
        assertEquals(list("idx=0, hasCustom=true, customSchema=true, hasFunction=true, functionSchema=true"), result.get("toolDebugSummaries"));
    }

    @Test
    void normalizeTools_googleSearchPassthrough() {
        Map<String, Object> payload = map("tools", list(map("googleSearch", map())));
        Map<String, Object> result = GeminiTransforms.normalizeGeminiTools(payload);
        assertEquals(map("tools", list(map("googleSearch", map()))), payload);
        assertEquals(0, result.get("toolDebugMissing"));
        assertEquals(list(), result.get("toolDebugSummaries"));
    }

    @Test
    void normalizeTools_bareToolGetsPlaceholderParameters() {
        Map<String, Object> payload = map("tools", list(map("name", "bare", "description", "b")));
        Map<String, Object> result = GeminiTransforms.normalizeGeminiTools(payload);
        assertEquals(map("tools", list(map("name", "bare", "description", "b",
                "parameters", map("type", "OBJECT", "properties", map("_placeholder", map("type", "BOOLEAN", "description", "Placeholder. Always pass true.")), "required", list("_placeholder"))))), payload);
        assertEquals(1, result.get("toolDebugMissing"));
    }

    @Test
    void normalizeTools_customOnlyBecomesEmptyAfterCustomDeleted() {
        // custom (no function) has its schema normalized, then custom is deleted -> {} remains.
        Map<String, Object> payload = map("tools", list(map("custom", map("name", "c1", "input_schema", map("type", "object", "properties", map())))));
        Map<String, Object> result = GeminiTransforms.normalizeGeminiTools(payload);
        assertEquals(map("tools", list(map())), payload);
        assertEquals(0, result.get("toolDebugMissing"));
    }

    @Test
    void normalizeTools_functionWithParametersAddsUppercasedInputSchema() {
        Map<String, Object> payload = map("tools", list(map("function", map("name", "f", "parameters", map("type", "object", "properties", map("x", map("type", "integer")))))));
        GeminiTransforms.normalizeGeminiTools(payload);
        assertEquals(map("tools", list(map("function", map("name", "f",
                "parameters", map("type", "object", "properties", map("x", map("type", "integer"))),
                "input_schema", map("type", "OBJECT", "properties", map("x", map("type", "INTEGER"))))))), payload);
    }

    @Test
    void normalizeTools_notArray_noop() {
        Map<String, Object> payload = map("notatools", true);
        Map<String, Object> result = GeminiTransforms.normalizeGeminiTools(payload);
        assertEquals(map("notatools", true), payload);
        assertEquals(0, result.get("toolDebugMissing"));
        assertEquals(list(), result.get("toolDebugSummaries"));
    }

    // ---- wrapToolsAsFunctionDeclarations ---------------------------------------------------------

    @Test
    void wrap_functionIntoDeclarations() {
        Map<String, Object> payload = map("tools", list(map("function", map("name", "f1", "description", "d1", "input_schema", map("type", "OBJECT", "properties", map())))));
        Map<String, Object> result = GeminiTransforms.wrapToolsAsFunctionDeclarations(payload, null);
        assertEquals(map("tools", list(map("functionDeclarations", list(map("name", "f1", "description", "d1", "parameters", map("type", "OBJECT", "properties", map())))))), payload);
        assertEquals(map("wrappedFunctionCount", 1, "passthroughToolCount", 0), result);
    }

    @Test
    void wrap_googleSearchPassthroughAfterDeclarations() {
        Map<String, Object> payload = map("tools", list(map("googleSearch", map()), map("function", map("name", "f2"))));
        Map<String, Object> result = GeminiTransforms.wrapToolsAsFunctionDeclarations(payload, null);
        assertEquals(map("tools", list(
                map("functionDeclarations", list(map("name", "f2", "description", "", "parameters", map("type", "OBJECT", "properties", map())))),
                map("googleSearch", map()))), payload);
        assertEquals(map("wrappedFunctionCount", 1, "passthroughToolCount", 1), result);
    }

    @Test
    void wrap_webSearchAloneBecomesGoogleSearch() {
        Map<String, Object> payload = map("tools", list(map("type", "web_search_20250305")));
        Map<String, Object> result = GeminiTransforms.wrapToolsAsFunctionDeclarations(payload, null);
        assertEquals(map("tools", list(map("googleSearch", map()))), payload);
        assertEquals(map("wrappedFunctionCount", 0, "passthroughToolCount", 1), result);
    }

    @Test
    void wrap_webSearchWithDeclarationsDropped_warns() {
        TestDoubles.CapturingLogger logger = new TestDoubles.CapturingLogger();
        Map<String, Object> payload = map("tools", list(map("name", "web_search"), map("function", map("name", "f3"))));
        Map<String, Object> result = GeminiTransforms.wrapToolsAsFunctionDeclarations(payload, logger);
        assertEquals(map("tools", list(map("functionDeclarations", list(map("name", "f3", "description", "", "parameters", map("type", "OBJECT", "properties", map())))))), payload);
        assertEquals(map("wrappedFunctionCount", 1, "passthroughToolCount", 0), result);
        assertEquals(1, logger.messages.size());
        assertTrue(logger.messages.get(0).contains("web_search tool detected"));
    }

    @Test
    void wrap_codeExecutionPassthrough_and_emptyTools() {
        Map<String, Object> payload = map("tools", list(map("codeExecution", map())));
        assertEquals(map("wrappedFunctionCount", 0, "passthroughToolCount", 1), GeminiTransforms.wrapToolsAsFunctionDeclarations(payload, null));
        assertEquals(map("tools", list(map("codeExecution", map()))), payload);

        Map<String, Object> empty = map("tools", list());
        assertEquals(map("wrappedFunctionCount", 0, "passthroughToolCount", 0), GeminiTransforms.wrapToolsAsFunctionDeclarations(empty, null));
    }

    // ---- applyGeminiTransforms -------------------------------------------------------------------

    @Test
    void apply_gemini3ThinkingLevel() {
        Map<String, Object> payload = map("tools", list(map("function", map("name", "f", "input_schema", map("type", "object", "properties", map("a", map("type", "string")))))));
        Map<String, Object> result = GeminiTransforms.applyGeminiTransforms(payload,
                map("model", "gemini-3-pro", "tierThinkingLevel", "high", "normalizedThinking", map("includeThoughts", true)), null);
        assertEquals(map("tools", list(map("functionDeclarations", list(
                map("name", "f", "description", "", "parameters", map("type", "OBJECT", "properties", map("a", map("type", "STRING"))))))),
                "generationConfig", map("thinkingConfig", map("includeThoughts", true, "thinkingLevel", "high"))), payload);
        assertEquals(0, result.get("toolDebugMissing"));
        assertEquals(1, result.get("wrappedFunctionCount"));
        assertEquals(0, result.get("passthroughToolCount"));
    }

    @Test
    void apply_gemini25TierBudgetOverridesNormalized() {
        Map<String, Object> payload = map("tools", list(map("function", map("name", "f"))));
        Map<String, Object> result = GeminiTransforms.applyGeminiTransforms(payload,
                map("model", "gemini-2.5-pro", "tierThinkingBudget", 8192, "normalizedThinking", map("includeThoughts", true, "thinkingBudget", 4096)), null);
        assertEquals(map("includeThoughts", true, "thinkingBudget", 8192),
                ((Map<?, ?>) payload.get("generationConfig")).get("thinkingConfig"));
        assertEquals(1, result.get("toolDebugMissing"));
        assertEquals(1, result.get("wrappedFunctionCount"));
    }

    @Test
    void apply_googleSearchAutoInjectsTool() {
        Map<String, Object> payload = map();
        Map<String, Object> result = GeminiTransforms.applyGeminiTransforms(payload,
                map("model", "gemini-3-pro", "googleSearch", map("mode", "auto")), null);
        assertEquals(map("tools", list(map("googleSearch", map()))), payload);
        assertEquals(0, result.get("wrappedFunctionCount"));
        assertEquals(1, result.get("passthroughToolCount"));
    }

    // ---- expandMultiFunctionCallModelTurns -------------------------------------------------------

    @Test
    void expand_splitsMultiCallTurn() {
        List<Object> contents = list(
                map("role", "model", "parts", list(map("functionCall", map("name", "a")), map("functionCall", map("name", "b")))),
                map("role", "user", "parts", list(map("functionResponse", map("name", "a")), map("functionResponse", map("name", "b")))));
        assertEquals(list(
                map("role", "model", "parts", list(map("functionCall", map("name", "a")))),
                map("role", "user", "parts", list(map("functionResponse", map("name", "a")))),
                map("role", "model", "parts", list(map("functionCall", map("name", "b")))),
                map("role", "user", "parts", list(map("functionResponse", map("name", "b"))))),
                GeminiTransforms.expandMultiFunctionCallModelTurns(contents));
    }

    @Test
    void expand_prependsOtherPartsToFirstPair() {
        List<Object> contents = list(
                map("role", "model", "parts", list(map("text", "hi"), map("functionCall", map("name", "a")), map("functionCall", map("name", "b")))),
                map("role", "user", "parts", list(map("functionResponse", map("name", "a")), map("functionResponse", map("name", "b")))));
        List<Object> out = GeminiTransforms.expandMultiFunctionCallModelTurns(contents);
        assertEquals(map("role", "model", "parts", list(map("text", "hi"), map("functionCall", map("name", "a")))), out.get(0));
    }

    @Test
    void expand_noSplitWhenCountsMismatchOrSingle() {
        List<Object> mismatch = list(
                map("role", "model", "parts", list(map("functionCall", map("name", "a")), map("functionCall", map("name", "b")))),
                map("role", "user", "parts", list(map("functionResponse", map("name", "a")))));
        assertEquals(mismatch, GeminiTransforms.expandMultiFunctionCallModelTurns(mismatch));
        List<Object> single = list(
                map("role", "model", "parts", list(map("functionCall", map("name", "a")))),
                map("role", "user", "parts", list(map("functionResponse", map("name", "a")))));
        assertEquals(single, GeminiTransforms.expandMultiFunctionCallModelTurns(single));
    }

    // ---- sanitizeGeminiContents ------------------------------------------------------------------

    @Test
    void sanitize_assistantToModel() {
        assertEquals(list(
                map("role", "user", "parts", list(map("text", "hello"))),
                map("role", "model", "parts", list(map("text", "hi there")))),
                GeminiTransforms.sanitizeGeminiContents(list(
                        map("role", "user", "parts", list(map("text", "hello"))),
                        map("role", "assistant", "parts", list(map("text", "hi there"))))));
    }

    @Test
    void sanitize_startsWithModelGetsAckUser() {
        assertEquals(list(
                map("role", "user", "parts", list(map("text", "acknowledged"))),
                map("role", "model", "parts", list(map("text", "starts with model"))),
                map("role", "user", "parts", list(map("text", "user turn")))),
                GeminiTransforms.sanitizeGeminiContents(list(
                        map("role", "model", "parts", list(map("text", "starts with model"))),
                        map("role", "user", "parts", list(map("text", "user turn"))))));
    }

    @Test
    void sanitize_mergesTextPartsAndConsecutiveUsers() {
        assertEquals(list(map("role", "user", "parts", list(map("text", "a\n\nb")))),
                GeminiTransforms.sanitizeGeminiContents(list(map("role", "user", "parts", list(map("text", "a"), map("text", "b"))))));
        assertEquals(list(map("role", "user", "parts", list(map("text", "u1\n\nu2")))),
                GeminiTransforms.sanitizeGeminiContents(list(
                        map("role", "user", "parts", list(map("text", "u1"))),
                        map("role", "user", "parts", list(map("text", "u2"))))));
    }

    @Test
    void sanitize_modelFunctionCallReordersAndInsertsFiller() {
        assertEquals(list(
                map("role", "user", "parts", list(map("text", "acknowledged"))),
                map("role", "model", "parts", list(map("text", "thinking"), map("functionCall", map("name", "f")))),
                map("role", "user", "parts", list(map("functionResponse", map("name", "f"))))),
                GeminiTransforms.sanitizeGeminiContents(list(
                        map("role", "model", "parts", list(map("functionCall", map("name", "f")), map("text", "thinking"))),
                        map("role", "user", "parts", list(map("functionResponse", map("name", "f")))))));
    }

    @Test
    void sanitize_userFunctionResponseSplitWithFiller() {
        assertEquals(list(
                map("role", "user", "parts", list(map("functionResponse", map("name", "f")))),
                map("role", "model", "parts", list(map("text", "acknowledged"))),
                map("role", "user", "parts", list(map("text", "normal")))),
                GeminiTransforms.sanitizeGeminiContents(list(
                        map("role", "user", "parts", list(map("functionResponse", map("name", "f")), map("text", "normal"))))));
    }

    @Test
    void sanitize_emptyStringTextParts_truthinessSemantics() {
        // TS mergeTextParts keys the separator AND the flush on the ACCUMULATED string's
        // truthiness (gemini.ts:698/700/708), not on "saw a text part". Fixtures:
        // sanitizeGeminiContents_emptyText in fixtures.json (reviewer fix round).

        // lone {text:""} turn -> merges to no parts -> turn skipped -> normalized empty ->
        // input returned as-is.
        assertEquals(list(map("role", "user", "parts", list(map("text", "")))),
                GeminiTransforms.sanitizeGeminiContents(list(map("role", "user", "parts", list(map("text", ""))))));

        // {text:""} then {text:"b"} -> "b" (empty accumulator adds NO separator)
        assertEquals(list(map("role", "user", "parts", list(map("text", "b")))),
                GeminiTransforms.sanitizeGeminiContents(list(map("role", "user", "parts", list(map("text", ""), map("text", "b"))))));

        // {text:"a"} then {text:""} -> "a\n\n" (truthy accumulator DOES add the separator)
        assertEquals(list(map("role", "user", "parts", list(map("text", "a\n\n")))),
                GeminiTransforms.sanitizeGeminiContents(list(map("role", "user", "parts", list(map("text", "a"), map("text", ""))))));
    }

    @Test
    void sanitize_turnMergingToEmptyIsSkipped() {
        // an empty-merging model turn between user turns disappears; the users merge.
        assertEquals(list(map("role", "user", "parts", list(map("text", "hello\n\nagain")))),
                GeminiTransforms.sanitizeGeminiContents(list(
                        map("role", "user", "parts", list(map("text", "hello"))),
                        map("role", "model", "parts", list(map("text", ""))),
                        map("role", "user", "parts", list(map("text", "again"))))));

        // {text:""} before a functionCall: the empty accumulator is NOT flushed before the
        // non-text part -> only the functionCall survives.
        assertEquals(list(
                map("role", "user", "parts", list(map("text", "q"))),
                map("role", "model", "parts", list(map("functionCall", map("name", "f")))),
                map("role", "user", "parts", list(map("functionResponse", map("name", "f"))))),
                GeminiTransforms.sanitizeGeminiContents(list(
                        map("role", "user", "parts", list(map("text", "q"))),
                        map("role", "model", "parts", list(map("text", ""), map("functionCall", map("name", "f")))),
                        map("role", "user", "parts", list(map("functionResponse", map("name", "f")))))));
    }

    @Test
    void sanitize_emptyReturnedAsIs() {
        assertEquals(list(), GeminiTransforms.sanitizeGeminiContents(list()));
        assertEquals(list(map("role", "user", "parts", list())), GeminiTransforms.sanitizeGeminiContents(list(map("role", "user", "parts", list()))));
    }

    // ---- fixGeminiToolPairing --------------------------------------------------------------------

    @Test
    void pairing_matchedByIdUnchanged() {
        List<Object> contents = list(
                map("role", "model", "parts", list(map("functionCall", map("name", "a", "id", "1")))),
                map("role", "user", "parts", list(map("functionResponse", map("name", "a", "id", "1")))));
        assertEquals(contents, GeminiTransforms.fixGeminiToolPairing(contents));
    }

    @Test
    void pairing_injectsNoResponseReceived() {
        assertEquals(list(
                map("role", "model", "parts", list(map("functionCall", map("name", "a")))),
                map("role", "user", "parts", list(map("text", "no response"), map("functionResponse", map("name", "a", "response", map("result", "[no response received]")))))),
                GeminiTransforms.fixGeminiToolPairing(list(
                        map("role", "model", "parts", list(map("functionCall", map("name", "a")))),
                        map("role", "user", "parts", list(map("text", "no response"))))));
    }

    @Test
    void pairing_appendsPendingUserTurnWhenNoNextTurn() {
        assertEquals(list(
                map("role", "model", "parts", list(map("functionCall", map("name", "a")))),
                map("role", "user", "parts", list(map("functionResponse", map("name", "a", "response", map("result", "[pending]")))))),
                GeminiTransforms.fixGeminiToolPairing(list(map("role", "model", "parts", list(map("functionCall", map("name", "a")))))));
    }

    @Test
    void pairing_partialMatchInjectsMissingOnly() {
        assertEquals(list(
                map("role", "model", "parts", list(map("functionCall", map("name", "a")), map("functionCall", map("name", "b")))),
                map("role", "user", "parts", list(map("functionResponse", map("name", "a")), map("functionResponse", map("name", "b", "response", map("result", "[no response received]")))))),
                GeminiTransforms.fixGeminiToolPairing(list(
                        map("role", "model", "parts", list(map("functionCall", map("name", "a")), map("functionCall", map("name", "b")))),
                        map("role", "user", "parts", list(map("functionResponse", map("name", "a")))))));
    }
}
