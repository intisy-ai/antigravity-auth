package io.github.intisy.ai.antigravity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.list;
import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline tests for {@link ClaudeTransforms}. The injected {@link ClaudeTransforms.SchemaCleaner}
 * double below is an IDENTITY cleaner (shallow-copy an object, {@code {}} otherwise) so these tests
 * isolate {@code ClaudeTransforms} from schema cleaning.
 */
class ClaudeTransformsTest {

    private static final ClaudeTransforms.SchemaCleaner IDENTITY =
            schema -> schema instanceof Map ? new LinkedHashMap<>((Map<?, ?>) schema) : new LinkedHashMap<>();

    private static final String HINT = ClaudeTransforms.CLAUDE_INTERLEAVED_THINKING_HINT;

    // ---- predicates ------------------------------------------------------------------------------

    @Test
    void predicates() {
        assertTrue(ClaudeTransforms.isClaudeModel("claude-opus"));
        assertFalse(ClaudeTransforms.isClaudeModel("gemini-3"));
        assertTrue(ClaudeTransforms.isClaudeThinkingModel("claude-opus-4-6-thinking"));
        assertFalse(ClaudeTransforms.isClaudeThinkingModel("claude-sonnet-4-6"));
        assertFalse(ClaudeTransforms.isClaudeThinkingModel("gemini-3-thinking"));
    }

    // ---- configureClaudeToolConfig ---------------------------------------------------------------

    @Test
    void toolConfig_forcesValidatedMode() {
        Map<String, Object> empty = map();
        ClaudeTransforms.configureClaudeToolConfig(empty);
        assertEquals(map("toolConfig", map("functionCallingConfig", map("mode", "VALIDATED"))), empty);

        Map<String, Object> existing = map("toolConfig", map("existing", true));
        ClaudeTransforms.configureClaudeToolConfig(existing);
        assertEquals(map("toolConfig", map("existing", true, "functionCallingConfig", map("mode", "VALIDATED"))), existing);

        Map<String, Object> auto = map("toolConfig", map("functionCallingConfig", map("mode", "AUTO")));
        ClaudeTransforms.configureClaudeToolConfig(auto);
        assertEquals(map("toolConfig", map("functionCallingConfig", map("mode", "VALIDATED"))), auto);
    }

    // ---- buildClaudeThinkingConfig (snake_case) --------------------------------------------------

    @Test
    void thinkingConfig_snakeCaseKeys() {
        assertEquals(map("include_thoughts", true, "thinking_budget", 8192), ClaudeTransforms.buildClaudeThinkingConfig(true, 8192));
        assertEquals(map("include_thoughts", true), ClaudeTransforms.buildClaudeThinkingConfig(true, 0));
        assertEquals(map("include_thoughts", false), ClaudeTransforms.buildClaudeThinkingConfig(false, null));
    }

    // ---- ensureClaudeMaxOutputTokens -------------------------------------------------------------

    @Test
    void ensureMax_bumpsWhenAbsentOrTooSmall() {
        Map<String, Object> absent = map();
        ClaudeTransforms.ensureClaudeMaxOutputTokens(absent, 8192);
        assertEquals(map("maxOutputTokens", 64000), absent);

        Map<String, Object> big = map("maxOutputTokens", 100000);
        ClaudeTransforms.ensureClaudeMaxOutputTokens(big, 8192);
        assertEquals(map("maxOutputTokens", 100000), big);

        Map<String, Object> small = map("maxOutputTokens", 5000);
        ClaudeTransforms.ensureClaudeMaxOutputTokens(small, 8192);
        assertEquals(map("maxOutputTokens", 64000), small);
    }

    @Test
    void ensureMax_snakeCaseReplacedOrKept() {
        Map<String, Object> smallSnake = map("max_output_tokens", 3000);
        ClaudeTransforms.ensureClaudeMaxOutputTokens(smallSnake, 8192);
        assertEquals(map("maxOutputTokens", 64000), smallSnake);

        Map<String, Object> bigSnake = map("max_output_tokens", 100000);
        ClaudeTransforms.ensureClaudeMaxOutputTokens(bigSnake, 8192);
        assertEquals(map("max_output_tokens", 100000), bigSnake);
    }

    // ---- appendClaudeThinkingHint ----------------------------------------------------------------

    @Test
    void hint_stringInstruction() {
        Map<String, Object> payload = map("systemInstruction", "existing system");
        ClaudeTransforms.appendClaudeThinkingHint(payload);
        assertEquals("existing system\n\n" + HINT, payload.get("systemInstruction"));

        Map<String, Object> blank = map("systemInstruction", "   ");
        ClaudeTransforms.appendClaudeThinkingHint(blank);
        assertEquals(HINT, blank.get("systemInstruction"));
    }

    @Test
    void hint_objectWithParts() {
        Map<String, Object> payload = map("systemInstruction", map("parts", list(map("text", "part text"))));
        ClaudeTransforms.appendClaudeThinkingHint(payload);
        assertEquals(map("systemInstruction", map("parts", list(map("text", "part text\n\n" + HINT)))), payload);

        Map<String, Object> noText = map("systemInstruction", map("parts", list(map("notText", 1))));
        ClaudeTransforms.appendClaudeThinkingHint(noText);
        assertEquals(map("systemInstruction", map("parts", list(map("notText", 1), map("text", HINT)))), noText);

        Map<String, Object> emptyParts = map("systemInstruction", map("parts", list()));
        ClaudeTransforms.appendClaudeThinkingHint(emptyParts);
        assertEquals(map("systemInstruction", map("parts", list(map("text", HINT)))), emptyParts);
    }

    @Test
    void hint_objectWithoutParts_and_contentsFallback_and_noop() {
        Map<String, Object> noParts = map("systemInstruction", map("other", 1));
        ClaudeTransforms.appendClaudeThinkingHint(noParts);
        assertEquals(map("systemInstruction", map("other", 1, "parts", list(map("text", HINT)))), noParts);

        Map<String, Object> contents = map("contents", list(map("role", "user", "parts", list())));
        ClaudeTransforms.appendClaudeThinkingHint(contents);
        assertEquals(map("contents", list(map("role", "user", "parts", list())), "systemInstruction", map("parts", list(map("text", HINT)))), contents);

        Map<String, Object> nothing = map();
        ClaudeTransforms.appendClaudeThinkingHint(nothing);
        assertEquals(map(), nothing);
    }

    // ---- convertStopSequences --------------------------------------------------------------------

    @Test
    void convertStop() {
        Map<String, Object> snake = map("stop_sequences", list("END", "STOP"));
        ClaudeTransforms.convertStopSequences(snake);
        assertEquals(map("stopSequences", list("END", "STOP")), snake);

        Map<String, Object> already = map("stopSequences", list("A"));
        ClaudeTransforms.convertStopSequences(already);
        assertEquals(map("stopSequences", list("A")), already);

        Map<String, Object> none = map("temperature", 0.5);
        ClaudeTransforms.convertStopSequences(none);
        assertEquals(map("temperature", 0.5), none);
    }

    // ---- normalizeClaudeTools --------------------------------------------------------------------

    @Test
    void normalize_functionWithSchema() {
        Map<String, Object> payload = map("tools", list(map("function", map("name", "f1", "description", "d1",
                "parameters", map("type", "object", "properties", map("a", map("type", "string")), "required", list("a"))))));
        Map<String, Object> result = ClaudeTransforms.normalizeClaudeTools(payload, IDENTITY);
        assertEquals(map("tools", list(map("functionDeclarations", list(map("name", "f1", "description", "d1",
                "parameters", map("type", "object", "properties", map("a", map("type", "string")), "required", list("a"))))))), payload);
        assertEquals(0, result.get("toolDebugMissing"));
        assertEquals(list("decl=f1,src=function/custom,hasSchema=y"), result.get("toolDebugSummaries"));
    }

    @Test
    void normalize_emptyFunctionGetsPlaceholder() {
        Map<String, Object> payload = map("tools", list(map("function", map("name", "empty"))));
        Map<String, Object> result = ClaudeTransforms.normalizeClaudeTools(payload, IDENTITY);
        assertEquals(map("tools", list(map("functionDeclarations", list(map("name", "empty", "description", "",
                "parameters", map("type", "object", "properties", map("_placeholder", map("type", "boolean", "description", "Placeholder. Always pass true.")), "required", list("_placeholder"))))))), payload);
        assertEquals(1, result.get("toolDebugMissing"));
        assertEquals(list("decl=empty,src=function/custom,hasSchema=n"), result.get("toolDebugSummaries"));
    }

    @Test
    void normalize_functionDeclarationsPassthrough() {
        Map<String, Object> payload = map("tools", list(map("functionDeclarations", list(
                map("name", "fd", "description", "x", "parameters", map("type", "object", "properties", map("p", map("type", "string"))))))));
        Map<String, Object> result = ClaudeTransforms.normalizeClaudeTools(payload, IDENTITY);
        assertEquals(map("tools", list(map("functionDeclarations", list(
                map("name", "fd", "description", "x", "parameters", map("type", "object", "properties", map("p", map("type", "string")))))))), payload);
        assertEquals(list("decl=fd,src=functionDeclarations,hasSchema=y"), result.get("toolDebugSummaries"));
    }

    @Test
    void normalize_nameSanitized() {
        Map<String, Object> payload = map("tools", list(map("name", "bad name!@#$", "parameters", map("type", "object", "properties", map("q", map("type", "integer"))))));
        Map<String, Object> result = ClaudeTransforms.normalizeClaudeTools(payload, IDENTITY);
        Map<?, ?> tool = (Map<?, ?>) ((java.util.List<?>) ((Map<?, ?>) ((java.util.List<?>) payload.get("tools")).get(0)).get("functionDeclarations")).get(0);
        assertEquals("bad_name____", tool.get("name"));
        assertEquals(list("decl=bad_name____,src=function/custom,hasSchema=y"), result.get("toolDebugSummaries"));
    }

    @Test
    void normalize_passthroughAndCustom() {
        Map<String, Object> passthrough = map("tools", list(map("passthrough", true)));
        Map<String, Object> pr = ClaudeTransforms.normalizeClaudeTools(passthrough, IDENTITY);
        assertEquals(map("tools", list(map("passthrough", true))), passthrough);
        assertEquals(list(), pr.get("toolDebugSummaries"));

        Map<String, Object> custom = map("tools", list(map("custom", map("name", "c", "input_schema", map("type", "object", "properties", map())))));
        ClaudeTransforms.normalizeClaudeTools(custom, IDENTITY);
        assertEquals(map("tools", list(map("functionDeclarations", list(map("name", "c", "description", "",
                "parameters", map("type", "object", "properties", map("_placeholder", map("type", "boolean", "description", "Placeholder. Always pass true.")), "required", list("_placeholder"))))))), custom);
    }

    // ---- applyClaudeTransforms -------------------------------------------------------------------

    @Test
    void apply_thinkingModelFullPipeline() {
        Map<String, Object> payload = map(
                "tools", list(map("function", map("name", "f", "parameters", map("type", "object", "properties", map("a", map("type", "string")))))),
                "generationConfig", map("stop_sequences", list("X")));
        Map<String, Object> result = ClaudeTransforms.applyClaudeTransforms(payload,
                map("model", "claude-opus-4-6-thinking", "tierThinkingBudget", 8192, "normalizedThinking", map("includeThoughts", true)), IDENTITY);
        assertEquals(map(
                "tools", list(map("functionDeclarations", list(map("name", "f", "description", "", "parameters", map("type", "object", "properties", map("a", map("type", "string"))))))),
                "generationConfig", map("stopSequences", list("X"), "thinkingConfig", map("include_thoughts", true, "thinking_budget", 8192), "maxOutputTokens", 64000),
                "toolConfig", map("functionCallingConfig", map("mode", "VALIDATED"))), payload);
        assertEquals(0, result.get("toolDebugMissing"));
        assertEquals(list("decl=f,src=function/custom,hasSchema=y"), result.get("toolDebugSummaries"));
    }

    @Test
    void apply_nonThinkingModelSkipsThinking() {
        Map<String, Object> payload = map("tools", list(map("function", map("name", "f"))));
        Map<String, Object> result = ClaudeTransforms.applyClaudeTransforms(payload,
                map("model", "claude-sonnet-4-6", "normalizedThinking", map("thinkingBudget", 16384)), IDENTITY);
        assertEquals(map(
                "tools", list(map("functionDeclarations", list(map("name", "f", "description", "",
                        "parameters", map("type", "object", "properties", map("_placeholder", map("type", "boolean", "description", "Placeholder. Always pass true.")), "required", list("_placeholder")))))),
                "toolConfig", map("functionCallingConfig", map("mode", "VALIDATED"))), payload);
        assertEquals(1, result.get("toolDebugMissing"));
    }

    @Test
    void apply_zeroBudgetNoMaxBump() {
        Map<String, Object> payload = map("generationConfig", map("stop_sequences", list("A", "B")));
        ClaudeTransforms.applyClaudeTransforms(payload,
                map("model", "claude-opus-4-6-thinking", "normalizedThinking", map("includeThoughts", false, "thinkingBudget", 0)), IDENTITY);
        assertEquals(map(
                "generationConfig", map("stopSequences", list("A", "B"), "thinkingConfig", map("include_thoughts", false)),
                "toolConfig", map("functionCallingConfig", map("mode", "VALIDATED"))), payload);
    }

    @Test
    void apply_noToolsStillConfiguresThinkingAndToolConfig() {
        Map<String, Object> payload = map();
        ClaudeTransforms.applyClaudeTransforms(payload,
                map("model", "claude-opus-4-6-thinking", "tierThinkingBudget", 32768, "normalizedThinking", map("includeThoughts", true)), IDENTITY);
        assertEquals(map(
                "toolConfig", map("functionCallingConfig", map("mode", "VALIDATED")),
                "generationConfig", map("thinkingConfig", map("include_thoughts", true, "thinking_budget", 32768), "maxOutputTokens", 64000)), payload);
    }

    // ---- closed loop: applyClaudeTransforms with the REAL cleaner --------------------------------
    // These pin the output of the real AntigravitySchemaCleaner::clean routed through
    // applyClaudeTransforms (the tests above use the IDENTITY double instead).

    private static final ClaudeTransforms.SchemaCleaner REAL = AntigravitySchemaCleaner::clean;

    private static Map<String, Object> ph() {
        return map("_placeholder", map("type", "boolean", "description", "Placeholder. Always pass true."));
    }

    @Test
    void apply_realCleaner_enumAndRefFlatten() {
        Map<String, Object> payload = map("tools", list(map("function", map("name", "f", "parameters", map(
                "type", "object", "additionalProperties", false,
                "properties", map("fmt", map("anyOf", list(map("const", "text"), map("const", "html"))),
                        "n", map("type", "integer", "enum", list(1, 2, 3))),
                "required", list("fmt", "ghost"))))));
        Map<String, Object> result = ClaudeTransforms.applyClaudeTransforms(payload,
                map("model", "claude-opus-4-6-thinking", "tierThinkingBudget", 8192, "normalizedThinking", map("includeThoughts", true)), REAL);

        assertEquals(map(
                "tools", list(map("functionDeclarations", list(map("name", "f", "description", "", "parameters", map(
                        "type", "object",
                        "properties", map("fmt", map("type", "string", "enum", list("text", "html")),
                                "n", map("type", "integer", "description", "Allowed: 1, 2, 3")),
                        "required", list("fmt"),
                        "description", "No extra properties allowed"))))),
                "toolConfig", map("functionCallingConfig", map("mode", "VALIDATED")),
                "generationConfig", map("thinkingConfig", map("include_thoughts", true, "thinking_budget", 8192), "maxOutputTokens", 64000)), payload);
        assertEquals(0, result.get("toolDebugMissing"));
        assertEquals(list("decl=f,src=function/custom,hasSchema=y"), result.get("toolDebugSummaries"));
    }

    @Test
    void apply_realCleaner_emptyObjectSchemaGetsPlaceholder() {
        Map<String, Object> payload = map("tools", list(map("function", map("name", "empty", "parameters", map("type", "object")))));
        Map<String, Object> result = ClaudeTransforms.applyClaudeTransforms(payload,
                map("model", "claude-sonnet-4-6", "normalizedThinking", map("thinkingBudget", 16384)), REAL);

        assertEquals(map(
                "tools", list(map("functionDeclarations", list(map("name", "empty", "description", "", "parameters", map(
                        "type", "object", "properties", ph(), "required", list("_placeholder")))))),
                "toolConfig", map("functionCallingConfig", map("mode", "VALIDATED"))), payload);
        // real cleaner filled the empty object schema, so it is NOT counted missing (unlike the identity double)
        assertEquals(0, result.get("toolDebugMissing"));
        assertEquals(list("decl=empty,src=function/custom,hasSchema=y"), result.get("toolDebugSummaries"));
    }

    @Test
    void apply_realCleaner_refAndTypeArrayZeroBudget() {
        Map<String, Object> payload = map("tools", list(map("function", map("name", "ref", "parameters", map(
                "type", "object",
                "properties", map("id", map("$ref", "#/$defs/Id"), "val", map("type", list("string", "null"))),
                "required", list("id", "val"))))));
        Map<String, Object> result = ClaudeTransforms.applyClaudeTransforms(payload,
                map("model", "claude-opus-4-6-thinking", "tierThinkingBudget", 0, "normalizedThinking", map("includeThoughts", false, "thinkingBudget", 0)), REAL);

        assertEquals(map(
                "tools", list(map("functionDeclarations", list(map("name", "ref", "description", "", "parameters", map(
                        "type", "object",
                        "properties", map("id", map("type", "object", "description", "See: Id", "properties", ph(), "required", list("_placeholder")),
                                "val", map("type", "string", "description", "nullable")),
                        "required", list("id")))))),
                "toolConfig", map("functionCallingConfig", map("mode", "VALIDATED")),
                "generationConfig", map("thinkingConfig", map("include_thoughts", false))), payload);
        assertEquals(0, result.get("toolDebugMissing"));
        assertEquals(list("decl=ref,src=function/custom,hasSchema=y"), result.get("toolDebugSummaries"));
    }
}
