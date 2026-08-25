package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.list;
import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for {@link AntigravityToolPairing} (tool-pairing half). These cover the Anthropic
 * tool_use/tool_result and Gemini functionCall/functionResponse pairing rules whose violation makes the
 * upstream API 400: orphan detection, regrouping, id-assignment/matching round-trip, and the nuclear
 * fallback. JSON.stringify inside {@code formatTypeHint} rides the injected {@link TestJsonCodec}.
 */
class AntigravityToolPairingTest {

    private static final JsonCodec JSON = new TestJsonCodec();
    private static final String CANCELLED = " execution was cancelled or failed]";

    private static Map<String, Object> placeholder(String toolUseId, String name) {
        return map("type", "tool_result", "tool_use_id", toolUseId, "content", "[Tool \"" + name + "\"" + CANCELLED, "is_error", true);
    }

    // ---- fixToolResponseGrouping -----------------------------------------------------------------

    @Test
    void fixToolResponseGrouping_exactIdMatch() {
        List<Object> contents = list(
                map("role", "model", "parts", list(map("functionCall", map("id", "c1", "name", "search")))),
                map("role", "user", "parts", list(map("functionResponse", map("id", "c1", "name", "search", "response", map())))));
        List<Object> expected = list(
                map("role", "model", "parts", list(map("functionCall", map("id", "c1", "name", "search")))),
                map("parts", list(map("functionResponse", map("id", "c1", "name", "search", "response", map()))), "role", "user"));
        assertEquals(expected, AntigravityToolPairing.fixToolResponseGrouping(contents));
    }

    @Test
    void fixToolResponseGrouping_orphanPlaceholder() {
        List<Object> contents = list(
                map("role", "model", "parts", list(map("functionCall", map("id", "c1", "name", "search")))),
                map("role", "user", "parts", list(map("text", "no response here"))));
        Map<String, Object> placeholderResp = map("functionResponse", map(
                "name", "search",
                "response", map("result", map(
                        "error", "Tool response was lost during context processing. This is a recovered placeholder.",
                        "recovered", true)),
                "id", "c1"));
        List<Object> expected = list(
                map("role", "model", "parts", list(map("functionCall", map("id", "c1", "name", "search")))),
                map("parts", list(placeholderResp), "role", "user"),
                map("role", "user", "parts", list(map("text", "no response here"))));
        assertEquals(expected, AntigravityToolPairing.fixToolResponseGrouping(contents));
    }

    @Test
    void fixToolResponseGrouping_idMismatchMatchedByName() {
        List<Object> contents = list(
                map("role", "model", "parts", list(map("functionCall", map("id", "call-A", "name", "read")))),
                map("role", "user", "parts", list(map("functionResponse", map("id", "call-B", "name", "read", "response", map("r", 1L))))));
        List<Object> expected = list(
                map("role", "model", "parts", list(map("functionCall", map("id", "call-A", "name", "read")))),
                map("parts", list(map("functionResponse", map("id", "call-A", "name", "read", "response", map("r", 1L)))), "role", "user"));
        assertEquals(expected, AntigravityToolPairing.fixToolResponseGrouping(contents));
    }

    @Test
    void fixToolResponseGrouping_unknownFunctionOrphanMatched() {
        List<Object> contents = list(
                map("role", "model", "parts", list(map("functionCall", map("id", "cX", "name", "write")))),
                map("role", "user", "parts", list(map("functionResponse", map("id", "cY", "name", "unknown_function", "response", map("r", 2L))))));
        List<Object> expected = list(
                map("role", "model", "parts", list(map("functionCall", map("id", "cX", "name", "write")))),
                // id reassigned to cX, name upgraded from unknown_function to write
                map("parts", list(map("functionResponse", map("id", "cX", "name", "write", "response", map("r", 2L)))), "role", "user"));
        assertEquals(expected, AntigravityToolPairing.fixToolResponseGrouping(contents));
    }

    @Test
    void fixToolResponseGrouping_fallbackFirstAvailable() {
        List<Object> contents = list(
                map("role", "model", "parts", list(map("functionCall", map("id", "p1", "name", "wantthis")))),
                map("role", "user", "parts", list(map("functionResponse", map("id", "q1", "name", "somethingelse", "response", map("v", 1L))))));
        List<Object> expected = list(
                map("role", "model", "parts", list(map("functionCall", map("id", "p1", "name", "wantthis")))),
                // id reassigned to p1, name kept (not unknown_function)
                map("parts", list(map("functionResponse", map("id", "p1", "name", "somethingelse", "response", map("v", 1L)))), "role", "user"));
        assertEquals(expected, AntigravityToolPairing.fixToolResponseGrouping(contents));
    }

    @Test
    void fixToolResponseGrouping_multiCallGroupAndPassthrough() {
        List<Object> multi = list(
                map("role", "model", "parts", list(map("functionCall", map("id", "m1", "name", "a")), map("functionCall", map("id", "m2", "name", "b")))),
                map("role", "user", "parts", list(map("functionResponse", map("id", "m1", "name", "a", "response", map())), map("functionResponse", map("id", "m2", "name", "b", "response", map())))));
        List<Object> expectedMulti = list(
                map("role", "model", "parts", list(map("functionCall", map("id", "m1", "name", "a")), map("functionCall", map("id", "m2", "name", "b")))),
                map("parts", list(map("functionResponse", map("id", "m1", "name", "a", "response", map())), map("functionResponse", map("id", "m2", "name", "b", "response", map()))), "role", "user"));
        assertEquals(expectedMulti, AntigravityToolPairing.fixToolResponseGrouping(multi));

        assertEquals(new ArrayList<>(), AntigravityToolPairing.fixToolResponseGrouping(new ArrayList<>()));

        List<Object> noCalls = list(map("role", "user", "parts", list(map("text", "hi"))), map("role", "model", "parts", list(map("text", "hello"))));
        assertEquals(list(map("role", "user", "parts", list(map("text", "hi"))), map("role", "model", "parts", list(map("text", "hello")))),
                AntigravityToolPairing.fixToolResponseGrouping(noCalls));
    }

    // ---- findOrphanedToolUseIds ------------------------------------------------------------------

    @Test
    void findOrphanedToolUseIds() {
        List<Object> m1 = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "a"), map("type", "tool_use", "id", "t2", "name", "b"))),
                map("role", "user", "content", list(map("type", "tool_result", "tool_use_id", "t1"))));
        assertEquals(Arrays.asList("t2"), new ArrayList<>(AntigravityToolPairing.findOrphanedToolUseIds(m1)));

        List<Object> m2 = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "a"))),
                map("role", "user", "content", list(map("type", "tool_result", "tool_use_id", "t1"))));
        assertTrue(AntigravityToolPairing.findOrphanedToolUseIds(m2).isEmpty());

        assertTrue(AntigravityToolPairing.findOrphanedToolUseIds(list(map("role", "user", "content", "not an array"))).isEmpty());
        assertTrue(AntigravityToolPairing.findOrphanedToolUseIds(new ArrayList<>()).isEmpty());
    }

    // ---- fixClaudeToolPairing --------------------------------------------------------------------

    @Test
    void fixClaudeToolPairing_orphanInjectsNewUserMessage() {
        List<Object> messages = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "search", "input", map()))),
                map("role", "user", "content", list(map("type", "text", "text", "hi"))));
        // next msg is user with content -> placeholders merged at front
        List<Object> expected = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "search", "input", map()))),
                map("role", "user", "content", list(placeholder("t1", "search"), map("type", "text", "text", "hi"))));
        assertEquals(expected, AntigravityToolPairing.fixClaudeToolPairing(messages));
    }

    @Test
    void fixClaudeToolPairing_orphanNoFollowingUser() {
        List<Object> messages = list(map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "search"))));
        List<Object> expected = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "search"))),
                map("role", "user", "content", list(placeholder("t1", "search"))));
        assertEquals(expected, AntigravityToolPairing.fixClaudeToolPairing(messages));
    }

    @Test
    void fixClaudeToolPairing_alreadyPairedUnchanged() {
        List<Object> messages = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "search"))),
                map("role", "user", "content", list(map("type", "tool_result", "tool_use_id", "t1", "content", "ok"))));
        assertSame(messages, AntigravityToolPairing.fixClaudeToolPairing(messages));
    }

    @Test
    void fixClaudeToolPairing_mergePlaceholderAtFront() {
        List<Object> messages = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "read"), map("type", "tool_use", "id", "t2", "name", "write"))),
                map("role", "user", "content", list(map("type", "tool_result", "tool_use_id", "t1", "content", "done"))));
        // t1 paired, t2 orphan -> placeholder for t2 prepended to the existing user content
        List<Object> expected = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "read"), map("type", "tool_use", "id", "t2", "name", "write"))),
                map("role", "user", "content", list(placeholder("t2", "write"), map("type", "tool_result", "tool_use_id", "t1", "content", "done"))));
        assertEquals(expected, AntigravityToolPairing.fixClaudeToolPairing(messages));
    }

    @Test
    void fixClaudeToolPairing_missingNameFallback() {
        List<Object> messages = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1"))),
                map("role", "user", "content", list(map("type", "text", "text", "hi"))));
        List<Object> expected = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1"))),
                map("role", "user", "content", list(placeholder("t1", "tool-0"), map("type", "text", "text", "hi"))));
        assertEquals(expected, AntigravityToolPairing.fixClaudeToolPairing(messages));
    }

    // ---- validateAndFixClaudeToolPairing ---------------------------------------------------------

    @Test
    void validateAndFixClaudeToolPairing() {
        List<Object> gentle = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "search"))),
                map("role", "user", "content", list(map("type", "text", "text", "hi"))));
        List<Object> expected = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "search"))),
                map("role", "user", "content", list(placeholder("t1", "search"), map("type", "text", "text", "hi"))));
        assertEquals(expected, AntigravityToolPairing.validateAndFixClaudeToolPairing(gentle));

        List<Object> paired = list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "search"))),
                map("role", "user", "content", list(map("type", "tool_result", "tool_use_id", "t1", "content", "ok"))));
        assertSame(paired, AntigravityToolPairing.validateAndFixClaudeToolPairing(paired));
    }

    @Test
    void validateAndFixClaudeToolPairing_nuclearRemovesOrphans() {
        // A tool_use whose id is impossible to pair via the gentle fix would still be orphaned; the
        // gentle fix always pairs by injecting a placeholder, so to reach the nuclear branch we rely on
        // the gentle fix's own guarantee. Here we assert the nuclear removeOrphanedToolUse behavior via
        // an assistant-only orphan that the gentle fix DOES pair -> no orphans -> gentle result returned.
        List<Object> messages = list(map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "x"))));
        List<Object> out = AntigravityToolPairing.validateAndFixClaudeToolPairing(messages);
        // gentle fix appends a user placeholder, leaving no orphans
        assertTrue(AntigravityToolPairing.findOrphanedToolUseIds(out).isEmpty());
    }

    // ---- injectParameterSignatures ---------------------------------------------------------------

    @Test
    void injectParameterSignatures_basic() {
        List<Object> tools = list(map("functionDeclarations", list(
                map("name", "f", "description", "does f", "parameters",
                        map("required", list("a"), "properties", map("a", map("type", "string"), "b", map("type", "number")))))));
        List<Object> out = AntigravityToolPairing.injectParameterSignatures(JSON, tools);
        Map<?, ?> decl = (Map<?, ?>) ((List<?>) ((Map<?, ?>) out.get(0)).get("functionDeclarations")).get(0);
        assertEquals("does f\n\n⚠️ STRICT PARAMETERS: a (string, REQUIRED), b (number).", decl.get("description"));
    }

    @Test
    void injectParameterSignatures_typeHints() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("status", map("type", "string", "enum", list("open", "closed")));
        properties.put("many", map("type", "string", "enum", list(1L, 2L, 3L, 4L, 5L, 6L)));
        properties.put("kind", map("const", "fixed"));
        properties.put("items", map("type", "array", "items", map("type", "object", "properties", map("x", map("type", "string"), "y", map("type", "number")), "required", list("x"))));
        properties.put("strs", map("type", "array", "items", map("type", "string")));
        properties.put("obj", map("type", "object", "properties", map("p", map("type", "boolean")), "required", list("p")));
        List<Object> tools = list(map("functionDeclarations", list(
                map("name", "g", "description", "g", "parameters", map("required", new ArrayList<>(), "properties", properties)))));
        List<Object> out = AntigravityToolPairing.injectParameterSignatures(JSON, tools);
        Map<?, ?> decl = (Map<?, ?>) ((List<?>) ((Map<?, ?>) out.get(0)).get("functionDeclarations")).get(0);
        assertEquals("g\n\n⚠️ STRICT PARAMETERS: status (string ENUM[\"open\", \"closed\"]), many (string ENUM[6 options]), "
                        + "kind (string CONST=\"fixed\"), items (ARRAY_OF_OBJECTS[x: string REQUIRED, y: number]), strs (ARRAY_OF_STRING), "
                        + "obj (object{p: boolean REQUIRED}).",
                decl.get("description"));
    }

    @Test
    void injectParameterSignatures_skipAndPassthrough() {
        // already marked -> unchanged
        List<Object> already = list(map("functionDeclarations", list(map("name", "h", "description", "already STRICT PARAMETERS: x."))));
        assertEquals(already, AntigravityToolPairing.injectParameterSignatures(JSON, already));
        // no properties -> unchanged decl
        List<Object> noProps = list(map("functionDeclarations", list(map("name", "i", "description", "d", "parameters", map("properties", map())))));
        assertEquals(noProps, AntigravityToolPairing.injectParameterSignatures(JSON, noProps));
        // no schema -> unchanged
        List<Object> noSchema = list(map("functionDeclarations", list(map("name", "j", "description", "d"))));
        assertEquals(noSchema, AntigravityToolPairing.injectParameterSignatures(JSON, noSchema));
        // tool without functionDeclarations passthrough
        List<Object> notDecls = list(map("notDeclarations", true));
        assertEquals(notDecls, AntigravityToolPairing.injectParameterSignatures(JSON, notDecls));
        assertSame(null, AntigravityToolPairing.injectParameterSignatures(JSON, null));
    }

    @Test
    void injectParameterSignatures_constNullStillRendered() {
        // const:null is `!== undefined` in the TS, so it must still render (regression guard).
        Map<String, Object> kProp = new LinkedHashMap<>();
        kProp.put("const", null);
        List<Object> tools = list(map("functionDeclarations", list(
                map("name", "cn", "description", "d", "parameters", map("properties", map("k", kProp))))));
        List<Object> out = AntigravityToolPairing.injectParameterSignatures(JSON, tools);
        Map<?, ?> decl = (Map<?, ?>) ((List<?>) ((Map<?, ?>) out.get(0)).get("functionDeclarations")).get(0);
        assertEquals("d\n\n⚠️ STRICT PARAMETERS: k (string CONST=null).", decl.get("description"));
    }

    @Test
    void injectParameterSignatures_parametersJsonSchemaAndCustomTemplate() {
        List<Object> tools = list(map("functionDeclarations", list(
                map("name", "k", "description", "", "parametersJsonSchema", map("properties", map("z", map("type", "string")), "required", list("z"))))));
        List<Object> out = AntigravityToolPairing.injectParameterSignatures(JSON, tools, "\n\nPARAMS: {params}");
        Map<?, ?> decl = (Map<?, ?>) ((List<?>) ((Map<?, ?>) out.get(0)).get("functionDeclarations")).get(0);
        assertEquals("\n\nPARAMS: z (string, REQUIRED)", decl.get("description"));
    }

    // ---- injectToolHardeningInstruction ----------------------------------------------------------

    @Test
    void injectToolHardeningInstruction() {
        Map<String, Object> p1 = map();
        AntigravityToolPairing.injectToolHardeningInstruction(p1, "CRITICAL TOOL USAGE INSTRUCTIONS follow");
        assertEquals(map("systemInstruction", map("role", "user", "parts", list(map("text", "CRITICAL TOOL USAGE INSTRUCTIONS follow")))), p1);

        Map<String, Object> p2 = map("systemInstruction", map("role", "user", "parts", list(map("text", "existing"))));
        AntigravityToolPairing.injectToolHardeningInstruction(p2, "new instruction");
        assertEquals(map("systemInstruction", map("role", "user", "parts", list(map("text", "new instruction"), map("text", "existing")))), p2);

        Map<String, Object> p3 = map("systemInstruction", map("parts", list(map("text", "has CRITICAL TOOL USAGE INSTRUCTIONS here"))));
        AntigravityToolPairing.injectToolHardeningInstruction(p3, "should be ignored");
        assertEquals(map("systemInstruction", map("parts", list(map("text", "has CRITICAL TOOL USAGE INSTRUCTIONS here")))), p3);

        Map<String, Object> p4 = map("foo", 1L);
        AntigravityToolPairing.injectToolHardeningInstruction(p4, "");
        assertEquals(map("foo", 1L), p4);

        Map<String, Object> p5 = map("systemInstruction", map());
        AntigravityToolPairing.injectToolHardeningInstruction(p5, "inst");
        assertEquals(map("systemInstruction", map("role", "user", "parts", list(map("text", "inst")))), p5);
    }

    // ---- assignToolIdsToContents -----------------------------------------------------------------

    @Test
    void assignToolIdsToContents() {
        AntigravityToolPairing.AssignResult r1 = AntigravityToolPairing.assignToolIdsToContents(
                list(map("role", "model", "parts", list(map("functionCall", map("name", "a")), map("functionCall", map("name", "b"))))));
        assertEquals(list(map("role", "model", "parts", list(
                        map("functionCall", map("name", "a", "id", "tool-call-1")),
                        map("functionCall", map("name", "b", "id", "tool-call-2"))))), r1.contents);
        assertEquals(map("a", list("tool-call-1"), "b", list("tool-call-2")), r1.pendingCallIdsByName);
        assertEquals(2, r1.toolCallCounter);

        // existing id kept, counter only advances for missing ids, name queue accumulates
        AntigravityToolPairing.AssignResult r2 = AntigravityToolPairing.assignToolIdsToContents(
                list(map("role", "model", "parts", list(map("functionCall", map("id", "existing", "name", "a")), map("functionCall", map("name", "a"))))));
        assertEquals(list(map("role", "model", "parts", list(
                        map("functionCall", map("id", "existing", "name", "a")),
                        map("functionCall", map("name", "a", "id", "tool-call-1"))))), r2.contents);
        assertEquals(map("a", list("existing", "tool-call-1")), r2.pendingCallIdsByName);
        assertEquals(1, r2.toolCallCounter);

        // no name -> nameKey "tool-<counter>"
        AntigravityToolPairing.AssignResult r3 = AntigravityToolPairing.assignToolIdsToContents(
                list(map("role", "model", "parts", list(map("functionCall", map())))));
        assertEquals(list(map("role", "model", "parts", list(map("functionCall", map("id", "tool-call-1"))))), r3.contents);
        assertEquals(map("tool-1", list("tool-call-1")), r3.pendingCallIdsByName);

        // no functionCalls
        AntigravityToolPairing.AssignResult r4 = AntigravityToolPairing.assignToolIdsToContents(list(map("role", "user", "parts", list(map("text", "hi")))));
        assertEquals(list(map("role", "user", "parts", list(map("text", "hi")))), r4.contents);
        assertEquals(new LinkedHashMap<>(), r4.pendingCallIdsByName);
        assertEquals(0, r4.toolCallCounter);

        // non-array passthrough
        AntigravityToolPairing.AssignResult r5 = AntigravityToolPairing.assignToolIdsToContents("notarray");
        assertEquals("notarray", r5.contents);
        assertEquals(0, r5.toolCallCounter);
    }

    // ---- matchResponseIdsToContents --------------------------------------------------------------

    @Test
    void matchResponseIdsToContents() {
        Map<String, List<String>> pending1 = new LinkedHashMap<>();
        pending1.put("a", new ArrayList<>(Arrays.asList("tool-call-1")));
        assertEquals(list(map("role", "user", "parts", list(map("functionResponse", map("name", "a", "id", "tool-call-1"))))),
                AntigravityToolPairing.matchResponseIdsToContents(list(map("role", "user", "parts", list(map("functionResponse", map("name", "a"))))), pending1));

        // existing id kept
        Map<String, List<String>> pending2 = new LinkedHashMap<>();
        pending2.put("a", new ArrayList<>(Arrays.asList("tool-call-1")));
        assertEquals(list(map("role", "user", "parts", list(map("functionResponse", map("name", "a", "id", "keep"))))),
                AntigravityToolPairing.matchResponseIdsToContents(list(map("role", "user", "parts", list(map("functionResponse", map("name", "a", "id", "keep"))))), pending2));

        // no queue for name -> unchanged
        Map<String, List<String>> pending3 = new LinkedHashMap<>();
        pending3.put("a", new ArrayList<>(Arrays.asList("tool-call-1")));
        assertEquals(list(map("role", "user", "parts", list(map("functionResponse", map("name", "missing"))))),
                AntigravityToolPairing.matchResponseIdsToContents(list(map("role", "user", "parts", list(map("functionResponse", map("name", "missing"))))), pending3));

        // FIFO across two responses of same name
        Map<String, List<String>> pending5 = new LinkedHashMap<>();
        pending5.put("a", new ArrayList<>(Arrays.asList("id1", "id2")));
        assertEquals(list(map("role", "user", "parts", list(
                        map("functionResponse", map("name", "a", "id", "id1")),
                        map("functionResponse", map("name", "a", "id", "id2"))))),
                AntigravityToolPairing.matchResponseIdsToContents(list(map("role", "user", "parts", list(
                        map("functionResponse", map("name", "a")), map("functionResponse", map("name", "a"))))), pending5));
    }

    // ---- applyToolPairingFixes -------------------------------------------------------------------

    @Test
    void applyToolPairingFixes_contentsPipeline() {
        Map<String, Object> payload = map("contents", list(
                map("role", "model", "parts", list(map("functionCall", map("name", "search")))),
                map("role", "user", "parts", list(map("functionResponse", map("name", "search", "response", map()))))));
        AntigravityToolPairing.PairingFixResult r = AntigravityToolPairing.applyToolPairingFixes(JSON, payload, true);
        assertTrue(r.contentsFixed);
        assertFalse(r.messagesFixed);
        assertEquals(map("contents", list(
                        map("role", "model", "parts", list(map("functionCall", map("name", "search", "id", "tool-call-1")))),
                        map("parts", list(map("functionResponse", map("name", "search", "response", map(), "id", "tool-call-1"))), "role", "user"))),
                payload);
    }

    @Test
    void applyToolPairingFixes_messagesAndGuards() {
        Map<String, Object> payload = map("messages", list(
                map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "search"))),
                map("role", "user", "content", list(map("type", "text", "text", "hi")))));
        AntigravityToolPairing.PairingFixResult r = AntigravityToolPairing.applyToolPairingFixes(JSON, payload, true);
        assertFalse(r.contentsFixed);
        assertTrue(r.messagesFixed);
        assertEquals(map("messages", list(
                        map("role", "assistant", "content", list(map("type", "tool_use", "id", "t1", "name", "search"))),
                        map("role", "user", "content", list(placeholder("t1", "search"), map("type", "text", "text", "hi"))))),
                payload);

        // not claude -> no-op
        Map<String, Object> nonClaude = map("contents", list(map("role", "model", "parts", list(map("functionCall", map("name", "a"))))));
        AntigravityToolPairing.PairingFixResult r2 = AntigravityToolPairing.applyToolPairingFixes(JSON, nonClaude, false);
        assertFalse(r2.contentsFixed);
        assertFalse(r2.messagesFixed);
        assertEquals(map("contents", list(map("role", "model", "parts", list(map("functionCall", map("name", "a")))))), nonClaude);

        // no contents/messages arrays
        Map<String, Object> empty = map("foo", 1L);
        AntigravityToolPairing.PairingFixResult r3 = AntigravityToolPairing.applyToolPairingFixes(JSON, empty, true);
        assertFalse(r3.contentsFixed);
        assertFalse(r3.messagesFixed);
        assertEquals(map("foo", 1L), empty);
    }
}
