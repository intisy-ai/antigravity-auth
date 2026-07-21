package io.github.intisy.ai.antigravity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.list;
import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for {@link AntigravityThinkingBlocks}. The injected {@link
 * AntigravityThinkingBlocks.ImageSink} ({@code processImageData}) is verified by
 * {@link #transformThinkingParts_imageSinkSeam()} with a capturing sink, since its real output is a
 * non-deterministic file path.
 */
class AntigravityThinkingBlocksTest {

    private static final String SIG60 = repeat("S", 60);
    private static final String FOR70 = repeat("F", 70);
    private static final String SKIP = AntigravityThinkingBlocks.SKIP_THOUGHT_SIGNATURE;

    // Deterministic signature-cache getter (only "sess1" has entries).
    private static final AntigravityThinkingBlocks.CachedSignatureLookup CACHE =
            (sessionId, text) -> "sess1".equals(sessionId) && "ourtext".equals(text) ? SIG60 : null;
    private static final AntigravityThinkingBlocks.CachedSignatureLookup NONE = null;

    private static final AntigravityThinkingBlocks.JsonStringParser IDENTITY = value -> value;
    private static final AntigravityThinkingBlocks.ImageSink UNUSED_SINK = (m, d) -> {
        throw new AssertionError("image sink must not be called");
    };

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // ---- shared part builders (fresh maps each call) ---------------------------------------------
    private static Map<String, Object> pText() { return map("text", "final answer"); }
    private static Map<String, Object> pUnsigned() { return map("type", "thinking", "thinking", "unsigned"); }
    private static Map<String, Object> pSignedOurs() { return map("type", "thinking", "thinking", "ourtext", "signature", SIG60); }
    private static Map<String, Object> pForeign() { return map("type", "thinking", "thinking", "foreign", "signature", FOR70); }
    private static Map<String, Object> pThoughtOurs() { return map("thought", true, "text", "ourtext", "thoughtSignature", SIG60); }
    private static Map<String, Object> pToolUse() { return map("type", "tool_use", "id", "t1", "name", "search", "input", map()); }
    private static Map<String, Object> pReasoning() { return map("type", "reasoning", "text", "ourtext", "signature", SIG60); }

    private static Map<String, Object> sentinel(String type, String thinking) {
        return map("type", type, "thinking", thinking, "signature", SKIP);
    }

    // ---- filterUnsignedThinkingBlocks (Gemini parts) ---------------------------------------------

    @Test
    void filterUnsigned_nonClaude_fullFilter() {
        List<Object> contents = list(
                map("role", "user", "parts", list(map("text", "question"))),
                map("role", "model", "parts", list(pText(), pUnsigned(), pSignedOurs(), pForeign(), pThoughtOurs())));
        List<Object> expected = list(
                map("role", "user", "parts", list(map("text", "question"))),
                map("role", "model", "parts", list(
                        map("text", "final answer"),
                        sentinel("thinking", "unsigned"),
                        map("type", "thinking", "thinking", "ourtext", "signature", SIG60),
                        sentinel("thinking", "foreign"),
                        map("thought", true, "text", "ourtext", "thoughtSignature", SIG60))));
        assertEquals(expected, AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contents, "sess1", CACHE, false, false));
    }

    @Test
    void filterUnsigned_claude_stripAll_whenKeepFalse() {
        List<Object> contents = list(
                map("role", "user", "parts", list(map("text", "question"))),
                map("role", "model", "parts", list(pText(), pUnsigned(), pSignedOurs(), pForeign(), pThoughtOurs())));
        List<Object> expected = list(
                map("role", "user", "parts", list(map("text", "question"))),
                map("role", "model", "parts", list(map("text", "final answer"))));
        assertEquals(expected, AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contents, "sess1", CACHE, true, false));
    }

    @Test
    void filterUnsigned_claude_sentinels_whenKeepTrue() {
        List<Object> contents = list(
                map("role", "user", "parts", list(map("text", "question"))),
                map("role", "model", "parts", list(pText(), pUnsigned(), pSignedOurs(), pForeign(), pThoughtOurs())));
        List<Object> expected = list(
                map("role", "user", "parts", list(map("text", "question"))),
                map("role", "model", "parts", list(
                        map("text", "final answer"),
                        sentinel("thinking", "unsigned"),
                        sentinel("thinking", "ourtext"),
                        sentinel("thinking", "foreign"),
                        sentinel("thinking", "ourtext"))));
        assertEquals(expected, AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contents, "sess1", CACHE, true, true));
    }

    @Test
    void filterUnsigned_noCache_dropsUnsignedAndTrailing() {
        List<Object> contents = list(
                map("role", "user", "parts", list(map("text", "question"))),
                map("role", "model", "parts", list(pText(), pUnsigned(), pSignedOurs(), pForeign(), pThoughtOurs())));
        List<Object> expected = list(
                map("role", "user", "parts", list(map("text", "question"))),
                map("role", "model", "parts", list(map("text", "final answer"))));
        assertEquals(expected, AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contents, null, NONE, false, false));
    }

    @Test
    void filterUnsigned_tools_preservedNonClaude_strippedClaude() {
        List<Object> contentsWithTools = list(
                map("role", "model", "parts", list(pToolUse(), pUnsigned(), pSignedOurs())));

        // non-claude: tool passthrough, unsigned -> sentinel, signed-ours -> sanitized (kept as trailing valid)
        assertEquals(list(map("role", "model", "parts", list(
                        map("type", "tool_use", "id", "t1", "name", "search", "input", map()),
                        sentinel("thinking", "unsigned"),
                        map("type", "thinking", "thinking", "ourtext", "signature", SIG60)))),
                AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contentsWithTools, "sess1", CACHE, false, false));

        // claude + keepFalse: stripAll removes both thinking blocks, tool kept
        assertEquals(list(map("role", "model", "parts", list(
                        map("type", "tool_use", "id", "t1", "name", "search", "input", map())))),
                AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contentsWithTools, "sess1", CACHE, true, false));

        // claude + keepTrue: tool sanitized (no sig fields to remove), thinking -> claude sentinels
        assertEquals(list(map("role", "model", "parts", list(
                        map("type", "tool_use", "id", "t1", "name", "search", "input", map()),
                        sentinel("thinking", "unsigned"),
                        sentinel("thinking", "ourtext")))),
                AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contentsWithTools, "sess1", CACHE, true, true));
    }

    @Test
    void filterUnsigned_trailingUnsignedRemovedKeepingValid() {
        List<Object> contents = list(map("role", "model", "parts", list(pText(), pSignedOurs(), pUnsigned())));
        List<Object> expected = list(map("role", "model", "parts", list(
                map("text", "final answer"),
                map("type", "thinking", "thinking", "ourtext", "signature", SIG60))));
        assertEquals(expected, AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contents, "sess1", CACHE, false, false));
    }

    @Test
    void filterUnsigned_sentinelPoppedToEmpty() {
        // unsigned thinking with cache-known text but no signature -> last-message sentinel, then
        // removeTrailingThinkingBlocks pops it (SKIP != cached signature) -> empty parts.
        List<Object> contents = list(map("role", "model", "parts", list(map("type", "thinking", "thinking", "ourtext"))));
        List<Object> expected = list(map("role", "model", "parts", list()));
        assertEquals(expected, AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contents, "sess1", CACHE, false, false));
    }

    @Test
    void filterUnsigned_nonObjectPassthrough() {
        List<Object> contents = new ArrayList<>();
        contents.add(null);
        contents.add("string");
        contents.add(5);
        contents.add(map("role", "model", "parts", list(pUnsigned())));

        List<Object> expectedFalse = new ArrayList<>();
        expectedFalse.add(null);
        expectedFalse.add("string");
        expectedFalse.add(5);
        expectedFalse.add(map("role", "model", "parts", list()));
        assertEquals(expectedFalse, AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contents, "sess1", CACHE, false, false));

        // claude + keepTrue: unsigned -> claude sentinel (no trailing removal for claude)
        List<Object> contents2 = new ArrayList<>();
        contents2.add(null);
        contents2.add("string");
        contents2.add(5);
        contents2.add(map("role", "model", "parts", list(pUnsigned())));
        List<Object> expectedTrue = new ArrayList<>();
        expectedTrue.add(null);
        expectedTrue.add("string");
        expectedTrue.add(5);
        expectedTrue.add(map("role", "model", "parts", list(sentinel("thinking", "unsigned"))));
        assertEquals(expectedTrue, AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contents2, "sess1", CACHE, true, true));
    }

    // ---- regression: sanitizeThinkingPart `part.thinking ?? part.text` fallback ------------------

    @Test
    void filterUnsigned_thinkingContentInTextKey_preserved() {
        // {type:"thinking", text:"ourtext", signature:<validCached>}, the `thinking` key is ABSENT,
        // content lives in `text`. sanitizeThinkingPart must fall through to `text` and emit
        // thinking:"ourtext".
        List<Object> contents = list(map("role", "model", "parts", list(
                map("type", "thinking", "text", "ourtext", "signature", SIG60))));
        assertEquals(list(map("role", "model", "parts", list(
                        map("type", "thinking", "thinking", "ourtext", "signature", SIG60)))),
                AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contents, "sess1", CACHE, false, false));

        List<Object> messages = list(map("role", "assistant", "content", list(
                map("type", "thinking", "text", "ourtext", "signature", SIG60))));
        assertEquals(list(map("role", "assistant", "content", list(
                        map("type", "thinking", "thinking", "ourtext", "signature", SIG60)))),
                AntigravityThinkingBlocks.filterMessagesThinkingBlocks(messages, "sess1", CACHE, false, false));
    }

    // ---- filterUnsignedThinkingBlocks (Anthropic content[] shape) --------------------------------

    @Test
    void filterUnsigned_anthropicContentShape() {
        List<Object> contents = list(
                map("role", "user", "content", list(map("text", "q"))),
                map("role", "assistant", "content", list(pText(), pUnsigned(), pSignedOurs(), pForeign())));
        // non-claude: foreign is trailing -> its sentinel popped; unsigned sentinel + signed-ours kept
        assertEquals(list(
                        map("role", "user", "content", list(map("text", "q"))),
                        map("role", "assistant", "content", list(
                                map("text", "final answer"),
                                sentinel("thinking", "unsigned"),
                                map("type", "thinking", "thinking", "ourtext", "signature", SIG60)))),
                AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contents, "sess1", CACHE, false, false));

        // claude + keepFalse -> stripAll
        List<Object> contents2 = list(
                map("role", "user", "content", list(map("text", "q"))),
                map("role", "assistant", "content", list(pText(), pUnsigned(), pSignedOurs(), pForeign())));
        assertEquals(list(
                        map("role", "user", "content", list(map("text", "q"))),
                        map("role", "assistant", "content", list(map("text", "final answer")))),
                AntigravityThinkingBlocks.filterUnsignedThinkingBlocks(contents2, "sess1", CACHE, true, false));
    }

    // ---- filterMessagesThinkingBlocks ------------------------------------------------------------

    @Test
    void filterMessages_nonClaude() {
        List<Object> messages = list(
                map("role", "user", "content", list(map("text", "hi"))),
                map("role", "assistant", "content", list(pText(), pUnsigned(), pSignedOurs(), pForeign(), pReasoning())));
        List<Object> expected = list(
                map("role", "user", "content", list(map("text", "hi"))),
                map("role", "assistant", "content", list(
                        map("text", "final answer"),
                        sentinel("thinking", "unsigned"),
                        map("type", "thinking", "thinking", "ourtext", "signature", SIG60),
                        sentinel("thinking", "foreign"),
                        map("type", "reasoning", "text", "ourtext", "signature", SIG60))));
        assertEquals(expected, AntigravityThinkingBlocks.filterMessagesThinkingBlocks(messages, "sess1", CACHE, false, false));
    }

    @Test
    void filterMessages_claudeStripAndSentinel() {
        List<Object> messages = list(
                map("role", "user", "content", list(map("text", "hi"))),
                map("role", "assistant", "content", list(pText(), pUnsigned(), pSignedOurs(), pForeign(), pReasoning())));
        assertEquals(list(
                        map("role", "user", "content", list(map("text", "hi"))),
                        map("role", "assistant", "content", list(map("text", "final answer")))),
                AntigravityThinkingBlocks.filterMessagesThinkingBlocks(messages, "sess1", CACHE, true, false));

        List<Object> messages2 = list(
                map("role", "user", "content", list(map("text", "hi"))),
                map("role", "assistant", "content", list(pText(), pUnsigned(), pSignedOurs(), pForeign(), pReasoning())));
        assertEquals(list(
                        map("role", "user", "content", list(map("text", "hi"))),
                        map("role", "assistant", "content", list(
                                map("text", "final answer"),
                                sentinel("thinking", "unsigned"),
                                sentinel("thinking", "ourtext"),
                                sentinel("thinking", "foreign"),
                                sentinel("thinking", "ourtext")))),
                AntigravityThinkingBlocks.filterMessagesThinkingBlocks(messages2, "sess1", CACHE, true, true));
    }

    @Test
    void filterMessages_restoreAndTrailing() {
        // messagesTrailing: trailing unsigned popped, signed-ours kept
        List<Object> trailing = list(map("role", "assistant", "content", list(pText(), pSignedOurs(), pUnsigned())));
        assertEquals(list(map("role", "assistant", "content", list(
                        map("text", "final answer"),
                        map("type", "thinking", "thinking", "ourtext", "signature", SIG60)))),
                AntigravityThinkingBlocks.filterMessagesThinkingBlocks(trailing, "sess1", CACHE, false, false));

        // messagesRestore: two unsigned-but-cache-known blocks -> sentinels, both popped -> empty
        List<Object> restore = list(map("role", "assistant", "content", list(
                map("type", "thinking", "thinking", "ourtext"),
                map("thought", true, "text", "ourtext"))));
        assertEquals(list(map("role", "assistant", "content", list())),
                AntigravityThinkingBlocks.filterMessagesThinkingBlocks(restore, "sess1", CACHE, false, false));
    }

    // ---- deepFilterThinkingBlocks ----------------------------------------------------------------

    private static Map<String, Object> deepPayload() {
        return map(
                "model", "claude",
                "contents", list(map("role", "model", "parts", list(pUnsigned(), pSignedOurs()))),
                "messages", list(map("role", "assistant", "content", list(pText(), pForeign()))),
                "nested", map("requests", list(map("contents", list(map("role", "model", "parts", list(map("thought", true, "text", "notcached"))))))));
    }

    @Test
    void deepFilter_nonClaude_mutatesInPlace() {
        Map<String, Object> payload = deepPayload();
        Object out = AntigravityThinkingBlocks.deepFilterThinkingBlocks(payload, "sess1", CACHE, false, false);
        assertSame(payload, out, "deepFilter returns the same (mutated) reference");

        Map<String, Object> expected = map(
                "model", "claude",
                "contents", list(map("role", "model", "parts", list(
                        sentinel("thinking", "unsigned"),
                        map("type", "thinking", "thinking", "ourtext", "signature", SIG60)))),
                "messages", list(map("role", "assistant", "content", list(map("text", "final answer")))),
                "nested", map("requests", list(map("contents", list(map("role", "model", "parts", list()))))));
        assertEquals(expected, out);
    }

    @Test
    void deepFilter_claudeKeepTrue() {
        Map<String, Object> payload = deepPayload();
        Object out = AntigravityThinkingBlocks.deepFilterThinkingBlocks(payload, "sess1", CACHE, true, true);
        Map<String, Object> expected = map(
                "model", "claude",
                "contents", list(map("role", "model", "parts", list(
                        sentinel("thinking", "unsigned"),
                        sentinel("thinking", "ourtext")))),
                "messages", list(map("role", "assistant", "content", list(
                        map("text", "final answer"),
                        sentinel("thinking", "foreign")))),
                "nested", map("requests", list(map("contents", list(map("role", "model", "parts", list(
                        sentinel("thinking", "notcached"))))))));
        assertEquals(expected, out);
    }

    @Test
    void deepFilter_scalarsUntouched() {
        Map<String, Object> payload = map("scalar", 5, "arr", list(1, 2, 3), "nil", null);
        Object out = AntigravityThinkingBlocks.deepFilterThinkingBlocks(payload, "sess1", CACHE, false, false);
        assertEquals(map("scalar", 5, "arr", list(1, 2, 3), "nil", null), out);
    }

    // ---- transformThinkingParts ------------------------------------------------------------------

    private static Object tf(Object response) {
        return AntigravityThinkingBlocks.transformThinkingParts(response, IDENTITY, UNUSED_SINK);
    }

    @Test
    void transformThinkingParts_anthropicContent() {
        assertEquals(map(
                        "content", list(
                                map("type", "reasoning", "thinking", "reasoned", "text", "reasoned", "thought", true,
                                        "providerMetadata", map("anthropic", map("signature", SIG60))),
                                map("type", "text", "text", "answer")),
                        "reasoning_content", "reasoned"),
                tf(map("content", list(map("type", "thinking", "thinking", "reasoned", "signature", SIG60), map("type", "text", "text", "answer")))));

        assertEquals(map("content", list(map("type", "reasoning", "text", "viaText", "thought", true)), "reasoning_content", "viaText"),
                tf(map("content", list(map("type", "thinking", "text", "viaText")))));

        // existing reasoning_content not overwritten; no thinking blocks
        assertEquals(map("content", list(map("type", "text", "text", "plain")), "reasoning_content", "existing"),
                tf(map("content", list(map("type", "text", "text", "plain")), "reasoning_content", "existing")));
    }

    @Test
    void transformThinkingParts_geminiCandidates() {
        Object in = map("candidates", list(map("content", map("parts", list(
                map("thought", true, "text", "gthink", "signature", SIG60),
                map("type", "thinking", "thinking", "athink", "thoughtSignature", SIG60),
                map("functionCall", map("name", "search", "args", map("location", "NYC", "days", 3))),
                map("text", "regular"))))));

        Object expected = map("candidates", list(map(
                "content", map("parts", list(
                        map("thought", true, "text", "gthink", "type", "reasoning",
                                "providerMetadata", map("anthropic", map("signature", SIG60))),
                        map("type", "reasoning", "thinking", "athink", "text", "athink", "thought", true,
                                "providerMetadata", map("anthropic", map("signature", SIG60))),
                        map("functionCall", map("name", "search", "args", map("location", "NYC", "days", 3))),
                        map("text", "regular"))),
                "reasoning_content", "gthink\n\nathink")));
        assertEquals(expected, tf(in));
    }

    @Test
    void transformThinkingParts_functionCallNoArgs_cacheControlPreserved() {
        Object in = map("candidates", list(map("content", map("parts", list(
                map("thought", true, "text", "c", "cache_control", map("type", "ephemeral")),
                map("functionCall", map("name", "noargs")))))));
        Object expected = map("candidates", list(map(
                "content", map("parts", list(
                        map("thought", true, "text", "c", "cache_control", map("type", "ephemeral"), "type", "reasoning"),
                        map("functionCall", map("name", "noargs", "args", map())))),
                "reasoning_content", "c")));
        assertEquals(expected, tf(in));
    }

    @Test
    void transformThinkingParts_candidateWithoutParts_passthrough() {
        Object in = map("candidates", list(map("content", map("role", "model")), map("notContent", 1)));
        Object expected = map("candidates", list(map("content", map("role", "model")), map("notContent", 1)));
        assertEquals(expected, tf(in));
    }

    @Test
    void transformThinkingParts_contentAndCandidates() {
        Object in = map(
                "content", list(map("type", "thinking", "thinking", "one")),
                "candidates", list(map("content", map("parts", list(map("thought", true, "text", "two"))))));
        Object expected = map(
                "content", list(map("type", "reasoning", "thinking", "one", "text", "one", "thought", true)),
                "candidates", list(map("content", map("parts", list(map("thought", true, "text", "two", "type", "reasoning"))), "reasoning_content", "two")),
                "reasoning_content", "one");
        assertEquals(expected, tf(in));
    }

    @Test
    void transformThinkingParts_nonObject() {
        assertNull(tf(null));
        assertEquals("astring", tf("astring"));
    }

    // ---- injected seams (image sink) -------------------------------------------------------------

    @Test
    void transformThinkingParts_imageSinkSeam() {
        List<Object> captured = new ArrayList<>();
        AntigravityThinkingBlocks.ImageSink capturing = (mimeType, data) -> {
            captured.add(list(mimeType, data));
            return "REPLACED[" + mimeType + "]";
        };
        Object in = map("candidates", list(map("content", map("parts", list(
                map("inlineData", map("mimeType", "image/png", "data", "BASE64")))))));
        Object out = AntigravityThinkingBlocks.transformThinkingParts(in, IDENTITY, capturing);

        // Java owns the decision: reads mimeType/data, replaces the part with {text: sinkResult}.
        assertEquals(list(list("image/png", "BASE64")), captured);
        assertEquals(map("candidates", list(map("content", map("parts", list(map("text", "REPLACED[image/png]")))))), out);
    }

    @Test
    void transformThinkingParts_imageSinkFalsyResult_keepsOriginalPart() {
        AntigravityThinkingBlocks.ImageSink nullSink = (mimeType, data) -> null;
        Object in = map("candidates", list(map("content", map("parts", list(
                map("inlineData", map("mimeType", "image/png", "data", "BASE64")))))));
        Object out = AntigravityThinkingBlocks.transformThinkingParts(in, IDENTITY, nullSink);
        // sink returned null (falsy) -> the original inlineData part is kept unchanged.
        assertEquals(map("candidates", list(map("content", map("parts", list(
                map("inlineData", map("mimeType", "image/png", "data", "BASE64"))))))), out);
    }

    @Test
    void transformThinkingParts_functionCallParserSeam() {
        // Verify the injected parser is invoked with functionCall.args and its result is wrapped.
        List<Object> seen = new ArrayList<>();
        AntigravityThinkingBlocks.JsonStringParser parser = value -> {
            seen.add(value);
            return map("parsed", true);
        };
        Object in = map("candidates", list(map("content", map("parts", list(
                map("functionCall", map("name", "f", "args", "{\"raw\":1}")))))));
        Object out = AntigravityThinkingBlocks.transformThinkingParts(in, parser, UNUSED_SINK);
        assertEquals(list("{\"raw\":1}"), seen);
        assertEquals(map("candidates", list(map("content", map("parts", list(
                map("functionCall", map("name", "f", "args", map("parsed", true)))))))), out);
        assertTrue(seen.size() == 1);
    }
}
