package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.Clock;
import io.github.intisy.ai.api.seam.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.list;
import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for {@link AntigravityResponseParse} (response/usage/error-parse half). JSON re-parse
 * rides the injected {@link TestJsonCodec}; {@code createSyntheticErrorResponse}'s {@code Date.now()}
 * rides a fixed {@link Clock}.
 */
class AntigravityResponseParseTest {

    private static final JsonCodec JSON = new TestJsonCodec();
    private static final Clock FIXED = TestDoubles.fixedClock(1234567890000L);

    // ---- parseAntigravityApiBody -----------------------------------------------------------------

    @Test
    void parseAntigravityApiBody_objectAndArrayShapes() {
        assertEquals(map("response", map("usageMetadata", map("totalTokenCount", 10L))),
                AntigravityResponseParse.parseAntigravityApiBody(JSON, "{\"response\":{\"usageMetadata\":{\"totalTokenCount\":10}}}"));
        // cloudcode-pa [{error}] array -> first object element
        assertEquals(map("error", map("code", 404L, "message", "nope")),
                AntigravityResponseParse.parseAntigravityApiBody(JSON, "[{\"error\":{\"code\":404,\"message\":\"nope\"}}]"));
        // skips null/primitive elements, picks first object
        assertEquals(map("a", 1L),
                AntigravityResponseParse.parseAntigravityApiBody(JSON, "[null, 5, {\"a\":1}]"));
        assertEquals(map("first", 1L),
                AntigravityResponseParse.parseAntigravityApiBody(JSON, "[{\"first\":1},{\"second\":2}]"));
        assertNull(AntigravityResponseParse.parseAntigravityApiBody(JSON, "[1, 2, 3]"));
        assertNull(AntigravityResponseParse.parseAntigravityApiBody(JSON, "[]"));
        assertNull(AntigravityResponseParse.parseAntigravityApiBody(JSON, "\"a string\""));
        assertNull(AntigravityResponseParse.parseAntigravityApiBody(JSON, "42"));
        assertNull(AntigravityResponseParse.parseAntigravityApiBody(JSON, "true"));
        assertNull(AntigravityResponseParse.parseAntigravityApiBody(JSON, "null"));
        assertNull(AntigravityResponseParse.parseAntigravityApiBody(JSON, "not json at all"));
    }

    // ---- extractUsageMetadata --------------------------------------------------------------------

    @Test
    void extractUsageMetadata_finiteNumbersOnly() {
        assertEquals(map("totalTokenCount", 100L, "promptTokenCount", 40L, "candidatesTokenCount", 60L,
                        "cachedContentTokenCount", 5L, "thoughtsTokenCount", 12L),
                AntigravityResponseParse.extractUsageMetadata(
                        map("response", map("usageMetadata", map("totalTokenCount", 100L, "promptTokenCount", 40L,
                                "candidatesTokenCount", 60L, "cachedContentTokenCount", 5L, "thoughtsTokenCount", 12L)))));
        assertEquals(map("totalTokenCount", 100L),
                AntigravityResponseParse.extractUsageMetadata(map("response", map("usageMetadata", map("totalTokenCount", 100L)))));
        // non-finite / non-number -> all absent -> empty map (usage object exists)
        assertEquals(new LinkedHashMap<>(),
                AntigravityResponseParse.extractUsageMetadata(map("response", map("usageMetadata",
                        map("totalTokenCount", "x", "promptTokenCount", Double.POSITIVE_INFINITY, "candidatesTokenCount", Double.NaN)))));
        // 0 is finite -> kept
        assertEquals(map("promptTokenCount", 0L),
                AntigravityResponseParse.extractUsageMetadata(map("response", map("usageMetadata", map("promptTokenCount", 0L)))));
        assertNull(AntigravityResponseParse.extractUsageMetadata(map("response", map())));
        assertNull(AntigravityResponseParse.extractUsageMetadata(map("response", "notobj")));
        assertNull(AntigravityResponseParse.extractUsageMetadata(map()));
        assertNull(AntigravityResponseParse.extractUsageMetadata(map("response", map("usageMetadata", null))));
    }

    // ---- extractUsageFromSsePayload --------------------------------------------------------------

    @Test
    void extractUsageFromSsePayload() {
        assertEquals(map("totalTokenCount", 7L),
                AntigravityResponseParse.extractUsageFromSsePayload(JSON, "data: {\"response\":{\"usageMetadata\":{\"totalTokenCount\":7}}}\n\n"));
        assertEquals(map("promptTokenCount", 3L),
                AntigravityResponseParse.extractUsageFromSsePayload(JSON, "event: x\ndata: {\"foo\":1}\ndata: {\"response\":{\"usageMetadata\":{\"promptTokenCount\":3}}}\n"));
        assertEquals(map("totalTokenCount", 9L),
                AntigravityResponseParse.extractUsageFromSsePayload(JSON, "data: [DONE]\ndata: {\"response\":{\"usageMetadata\":{\"totalTokenCount\":9}}}"));
        assertEquals(map("totalTokenCount", 11L),
                AntigravityResponseParse.extractUsageFromSsePayload(JSON, "data: notjson\ndata: {\"response\":{\"usageMetadata\":{\"totalTokenCount\":11}}}"));
        assertNull(AntigravityResponseParse.extractUsageFromSsePayload(JSON, "data:\ndata: \n"));
        assertNull(AntigravityResponseParse.extractUsageFromSsePayload(JSON, "nodata line\n"));
        assertNull(AntigravityResponseParse.extractUsageFromSsePayload(JSON, "data: {\"response\":{}}\n"));
    }

    // ---- rewriteAntigravityPreviewAccessError ----------------------------------------------------

    private static final String HINT = " Request preview access at https://goo.gle/enable-preview-features before using this model.";

    @Test
    void rewriteAntigravityPreviewAccessError() {
        assertEquals(map("error", map("code", 404L, "message", "Model not found." + HINT)),
                AntigravityResponseParse.rewriteAntigravityPreviewAccessError(
                        map("error", map("code", 404L, "message", "Model not found.")), 404, "gemini-3-antigravity"));
        // blank message -> default prefix
        assertEquals(map("error", map("message", "Antigravity preview features are not enabled for this account." + HINT)),
                AntigravityResponseParse.rewriteAntigravityPreviewAccessError(map("error", map("message", "  ")), 404, "claude-opus-4"));
        // no error object -> default
        assertEquals(map("error", map("message", "Antigravity preview features are not enabled for this account." + HINT)),
                AntigravityResponseParse.rewriteAntigravityPreviewAccessError(map(), 404, "opus-thinking"));
        // model not antigravity but error message is
        assertEquals(map("error", map("message", "some antigravity thing" + HINT)),
                AntigravityResponseParse.rewriteAntigravityPreviewAccessError(map("error", map("message", "some antigravity thing")), 404, null));
        // non-antigravity model + message -> null
        assertNull(AntigravityResponseParse.rewriteAntigravityPreviewAccessError(map("error", map("message", "plain model")), 404, "gpt-4o"));
        // non-404 -> null
        assertNull(AntigravityResponseParse.rewriteAntigravityPreviewAccessError(map("error", map("message", "x")), 500, "claude"));
        assertEquals(map("error", map("message", "Antigravity preview features are not enabled for this account." + HINT)),
                AntigravityResponseParse.rewriteAntigravityPreviewAccessError(map("error", map()), 404, "claude"));
    }

    // ---- isEmptyResponseBody ---------------------------------------------------------------------

    @Test
    void isEmptyResponseBody() {
        String[] inputs = {
                "", "   ", "notjson",
                "{\"candidates\":[]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"f\"}}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"t\"}]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]}}]}",
                "{\"candidates\":[null]}",
                "{\"candidates\":[{}]}",
                "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}",
                "{\"choices\":[{\"message\":{}}]}",
                "{\"choices\":[]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[1]}}]}",
                "{\"choices\":[{\"message\":{\"reasoning_content\":\"r\"}}]}",
                "{\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}}",
                "{\"response\":{\"candidates\":[]}}",
                "{\"response\":null}",
                "{\"foo\":\"bar\"}",
        };
        boolean[] expected = {
                true, true, true, true, false, true, false, false, true, true, true, false, true, true,
                false, false, false, true, true, false,
        };
        for (int i = 0; i < inputs.length; i++) {
            assertEquals(expected[i], AntigravityResponseParse.isEmptyResponseBody(JSON, inputs[i]), "case " + i + ": " + inputs[i]);
        }
    }

    // ---- isMeaningfulSseLine ---------------------------------------------------------------------

    @Test
    void isMeaningfulSseLine() {
        assertTrue(AntigravityResponseParse.isMeaningfulSseLine(JSON, "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}"));
        assertTrue(AntigravityResponseParse.isMeaningfulSseLine(JSON, "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"f\"}}]}}]}"));
        assertFalse(AntigravityResponseParse.isMeaningfulSseLine(JSON, "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]}}]}"));
        assertFalse(AntigravityResponseParse.isMeaningfulSseLine(JSON, "data: {\"candidates\":[{\"content\":{\"parts\":[]}}]}"));
        assertFalse(AntigravityResponseParse.isMeaningfulSseLine(JSON, "data: [DONE]"));
        assertFalse(AntigravityResponseParse.isMeaningfulSseLine(JSON, "data: "));
        assertFalse(AntigravityResponseParse.isMeaningfulSseLine(JSON, "event: message"));
        assertFalse(AntigravityResponseParse.isMeaningfulSseLine(JSON, "data: notjson"));
        assertTrue(AntigravityResponseParse.isMeaningfulSseLine(JSON, "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"x\"}]}}]}}"));
        assertFalse(AntigravityResponseParse.isMeaningfulSseLine(JSON, "data: {\"candidates\":[{\"content\":{}}]}"));
    }

    // ---- recursivelyParseJsonStrings -------------------------------------------------------------

    @Test
    void recursivelyParseJsonStrings() {
        assertEquals(map("files", list(map("a", 1L), map("b", 2L))),
                AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("files", "[{\"a\":1},{\"b\":2}]")));
        // skip keys preserved verbatim
        assertEquals(map("data", "[{\"a\":1}]"),
                AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("data", "[{\"a\":1}]")));
        assertEquals(map("text", "{\"x\":1}"),
                AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("text", "{\"x\":1}")));
        assertEquals(map("nested", map("value", "{\"y\":2}")),
                AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("nested", map("value", "{\"y\":2}"))));
        assertEquals(map("obj", map("k", "v")),
                AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("obj", "{\"k\":\"v\"}")));
        // array elements recursed with NO current key -> parseable strings expanded
        assertEquals(map("arr", list(map("z", 9L), "plain", list(1L, 2L))),
                AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("arr", list("{\"z\":9}", "plain", "[1,2]"))));
        assertEquals(map("top", 1L), AntigravityResponseParse.recursivelyParseJsonStrings(JSON, "{\"top\":1}"));
        assertEquals(list(1L, 2L, 3L), AntigravityResponseParse.recursivelyParseJsonStrings(JSON, "[1,2,3]"));
        assertEquals("plain string", AntigravityResponseParse.recursivelyParseJsonStrings(JSON, "plain string"));
        // malformed JSON auto-corrected (trailing junk stripped)
        assertEquals(map("malformedArr", list(map("a", 1L))),
                AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("malformedArr", "[{\"a\":1}]extra")));
        assertEquals(map("malformedObj", map("a", 1L)),
                AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("malformedObj", "{\"a\":1}trailing")));
        // lone control-char escapes unescaped
        assertEquals(map("ctrl", "line1\nline2\ttab"),
                AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("ctrl", "line1\\nline2\\ttab")));
        // intentional escapes present -> left untouched
        assertEquals(map("ctrlIntentional", "has \\\"quote\\\" and \\nnewline"),
                AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("ctrlIntentional", "has \\\"quote\\\" and \\nnewline")));
        assertNull(AntigravityResponseParse.recursivelyParseJsonStrings(JSON, null));
        assertEquals(42L, AntigravityResponseParse.recursivelyParseJsonStrings(JSON, 42L));
        assertEquals(true, AntigravityResponseParse.recursivelyParseJsonStrings(JSON, true));
        assertEquals(map("empty", ""), AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("empty", "")));
        assertEquals(map("notjson", "hello world"), AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("notjson", "hello world")));
        assertEquals(map("onlyOpen", "{no close"), AntigravityResponseParse.recursivelyParseJsonStrings(JSON, map("onlyOpen", "{no close")));
    }

    // ---- createSyntheticErrorResponse ------------------------------------------------------------

    @Test
    void createSyntheticErrorResponse_byteShape() {
        AntigravityResponseParse.SyntheticResponse res =
                AntigravityResponseParse.createSyntheticErrorResponse(JSON, FIXED, "short", null);
        assertEquals(200, res.status);
        String expectedBody =
                "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_synthetic_1234567890000\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"unknown\",\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"short\"}}\n\n"
                + "event: content_block_stop\n"
                + "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":2}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        assertEquals(expectedBody, res.body);
    }

    @Test
    void createSyntheticErrorResponse_modelAndTokenCount() {
        AntigravityResponseParse.SyntheticResponse res =
                AntigravityResponseParse.createSyntheticErrorResponse(JSON, FIXED, "A longer error message that exceeds twenty chars", "gemini-3");
        // model echoed, output_tokens = ceil(len/4); len=48 -> 12
        assertTrue(res.body.contains("\"model\":\"gemini-3\""));
        assertTrue(res.body.contains("\"usage\":{\"output_tokens\":12}"));
        // header keys are the literal-cased TS strings (host reads lowercased; body/status are byte-exact)
        Map<String, String> h = res.headers;
        assertEquals("text/event-stream", h.get("Content-Type"));
        assertEquals("true", h.get("X-Antigravity-Synthetic"));
        assertEquals("prompt_too_long", h.get("X-Antigravity-Error-Type"));
    }
}
