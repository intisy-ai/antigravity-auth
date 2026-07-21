package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.list;
import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AntigravityRequestSignaturesTest {

    private final JsonCodec json = new TestJsonCodec();
    private static final String SKIP = "skip_thought_signature_validator";
    private static final String LONG = repeat("x", 50);
    private static final String MID = repeat("y", 50);
    private static final String SHORT = repeat("x", 49);

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // ---- predicates -----------------------------------------------------------------------------

    @Test
    void isGeminiToolUsePart() {
        assertTrue(AntigravityRequestSignatures.isGeminiToolUsePart(map("functionCall", map())));
        assertTrue(AntigravityRequestSignatures.isGeminiToolUsePart(map("tool_use", 1L)));
        assertTrue(AntigravityRequestSignatures.isGeminiToolUsePart(map("toolUse", 1L)));
        assertFalse(AntigravityRequestSignatures.isGeminiToolUsePart(map("text", "x")));
        assertFalse(AntigravityRequestSignatures.isGeminiToolUsePart(null));
    }

    @Test
    void isGeminiThinkingPart() {
        assertTrue(AntigravityRequestSignatures.isGeminiThinkingPart(map("thought", true)));
        assertTrue(AntigravityRequestSignatures.isGeminiThinkingPart(map("type", "thinking")));
        assertTrue(AntigravityRequestSignatures.isGeminiThinkingPart(map("type", "reasoning")));
        assertFalse(AntigravityRequestSignatures.isGeminiThinkingPart(map("thought", false)));
        assertFalse(AntigravityRequestSignatures.isGeminiThinkingPart(map("text", "x")));
    }

    @Test
    void isValidRequestPart() {
        assertTrue(AntigravityRequestSignatures.isValidRequestPart(map("text", "")));
        assertTrue(AntigravityRequestSignatures.isValidRequestPart(map("functionCall", map())));
        assertTrue(AntigravityRequestSignatures.isValidRequestPart(map("thought", true)));
        assertFalse(AntigravityRequestSignatures.isValidRequestPart(map("nope", 1L)));
        assertFalse(AntigravityRequestSignatures.isValidRequestPart(null));
    }

    // ---- ensureThoughtSignature -----------------------------------------------------------------

    @Test
    void ensureThoughtSignature() {
        assertEquals("{\"thought\":true,\"text\":\"t\",\"thoughtSignature\":\"" + SKIP + "\"}",
                json.stringify(AntigravityRequestSignatures.ensureThoughtSignature(map("thought", true, "text", "t"), "s")));
        assertEquals("{\"type\":\"thinking\",\"thinking\":\"t\",\"signature\":\"" + SKIP + "\"}",
                json.stringify(AntigravityRequestSignatures.ensureThoughtSignature(map("type", "thinking", "thinking", "t"), "s")));
        assertEquals("{\"type\":\"reasoning\",\"text\":\"t\",\"signature\":\"" + SKIP + "\"}",
                json.stringify(AntigravityRequestSignatures.ensureThoughtSignature(map("type", "reasoning", "text", "t"), "s")));
        assertEquals("{\"type\":\"redacted_thinking\",\"text\":\"t\",\"signature\":\"" + SKIP + "\"}",
                json.stringify(AntigravityRequestSignatures.ensureThoughtSignature(map("type", "redacted_thinking", "text", "t"), "s")));
        assertEquals("{\"thought\":true,\"text\":\"t\"}",
                json.stringify(AntigravityRequestSignatures.ensureThoughtSignature(map("thought", true, "text", "t"), "")));
        assertEquals("{\"thought\":true}",
                json.stringify(AntigravityRequestSignatures.ensureThoughtSignature(map("thought", true), "s")));
        assertEquals("{\"text\":\"plain\"}",
                json.stringify(AntigravityRequestSignatures.ensureThoughtSignature(map("text", "plain"), "s")));
    }

    // ---- hasSignedThinkingPart (non-cache + cache) ----------------------------------------------

    @Test
    void hasSignedThinkingPart_nonCache() {
        assertTrue(AntigravityRequestSignatures.hasSignedThinkingPart(map("thought", true, "thoughtSignature", SKIP), null, null));
        assertTrue(AntigravityRequestSignatures.hasSignedThinkingPart(map("thought", true, "thoughtSignature", LONG), null, null));
        assertFalse(AntigravityRequestSignatures.hasSignedThinkingPart(map("thought", true, "thoughtSignature", SHORT), null, null));
        assertTrue(AntigravityRequestSignatures.hasSignedThinkingPart(map("type", "thinking", "signature", SKIP), null, null));
        assertTrue(AntigravityRequestSignatures.hasSignedThinkingPart(map("type", "reasoning", "signature", LONG), null, null));
        assertFalse(AntigravityRequestSignatures.hasSignedThinkingPart(map("text", "x"), null, null));
    }

    @Test
    void hasCachedMatchingSignature() {
        RequestTestDoubles.MapLookup lookup = new RequestTestDoubles.MapLookup();
        lookup.put("cache-sid", "cached text", LONG);
        assertTrue(AntigravityRequestSignatures.hasSignedThinkingPart(map("thought", true, "text", "cached text", "thoughtSignature", LONG), "cache-sid", lookup));
        assertFalse(AntigravityRequestSignatures.hasSignedThinkingPart(map("thought", true, "text", "cached text", "thoughtSignature", MID), "cache-sid", lookup));
        assertTrue(AntigravityRequestSignatures.hasSignedThinkingPart(map("type", "thinking", "thinking", "cached text", "signature", LONG), "cache-sid", lookup));
        assertFalse(AntigravityRequestSignatures.hasSignedThinkingPart(map("thought", true, "text", "not cached", "thoughtSignature", LONG), "cache-sid", lookup));
    }

    // ---- hasToolUse* / hasSignedThinking* -------------------------------------------------------

    @Test
    void hasToolUseAndSigned() {
        assertTrue(AntigravityRequestSignatures.hasToolUseInContents(list(map("parts", list(map("functionCall", map()))))));
        assertFalse(AntigravityRequestSignatures.hasToolUseInContents(list(map("parts", list(map("text", "x"))))));
        assertTrue(AntigravityRequestSignatures.hasToolUseInMessages(list(map("content", list(map("type", "tool_use"))))));
        assertTrue(AntigravityRequestSignatures.hasToolUseInMessages(list(map("content", list(map("type", "tool_result"))))));
        assertFalse(AntigravityRequestSignatures.hasToolUseInMessages(list(map("content", list(map("type", "text"))))));
        assertTrue(AntigravityRequestSignatures.hasSignedThinkingInContents(list(map("parts", list(map("thought", true, "thoughtSignature", SKIP)))), null, null));
        assertFalse(AntigravityRequestSignatures.hasSignedThinkingInContents(list(map("parts", list(map("text", "x")))), null, null));
        assertTrue(AntigravityRequestSignatures.hasSignedThinkingInMessages(list(map("content", list(map("type", "thinking", "signature", SKIP)))), null, null));
        assertFalse(AntigravityRequestSignatures.hasSignedThinkingInMessages(list(map("content", list(map("type", "text")))), null, null));
    }

    // ---- ensureThinkingBeforeToolUseInContents --------------------------------------------------

    @Test
    void ensureThinkingBeforeToolUseInContents() {
        RequestTestDoubles.MapLookup lookup = new RequestTestDoubles.MapLookup();
        RequestTestDoubles.MapStore store = new RequestTestDoubles.MapStore();
        store.set("key-inject", "stored-thought", "sig");
        store.set("key-store2", "stored2", "sig");

        assertEquals("[{\"role\":\"model\",\"parts\":[{\"thought\":true,\"text\":\"th\",\"thoughtSignature\":\"" + SKIP + "\"},{\"functionCall\":{\"name\":\"f\"}}]}]",
                json.stringify(AntigravityRequestSignatures.ensureThinkingBeforeToolUseInContents(
                        list(map("role", "model", "parts", list(map("functionCall", map("name", "f")), map("thought", true, "text", "th", "thoughtSignature", SKIP)))),
                        "key-signed", lookup, store)));

        assertEquals("[{\"role\":\"model\",\"parts\":[{\"thought\":true,\"text\":\"unsigned\",\"thoughtSignature\":\"" + SKIP + "\"},{\"functionCall\":{\"name\":\"f\"}}]}]",
                json.stringify(AntigravityRequestSignatures.ensureThinkingBeforeToolUseInContents(
                        list(map("role", "model", "parts", list(map("functionCall", map("name", "f")), map("thought", true, "text", "unsigned")))),
                        "key-miss", lookup, store)));

        assertEquals("[{\"role\":\"user\",\"parts\":[{\"functionCall\":{\"name\":\"f\"}}]}]",
                json.stringify(AntigravityRequestSignatures.ensureThinkingBeforeToolUseInContents(
                        list(map("role", "user", "parts", list(map("functionCall", map("name", "f"))))),
                        "key-x", lookup, store)));

        // no-text thinking -> unsigned -> empty store -> strip thinking
        assertEquals("[{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"f\"}}]}]",
                json.stringify(AntigravityRequestSignatures.ensureThinkingBeforeToolUseInContents(
                        list(map("role", "model", "parts", list(map("functionCall", map("name", "f")), map("thought", true)))),
                        "key-empty2", lookup, store)));

        // no-text thinking -> unsigned -> store has -> inject stored
        assertEquals("[{\"role\":\"model\",\"parts\":[{\"thought\":true,\"text\":\"stored2\",\"thoughtSignature\":\"" + SKIP + "\"},{\"functionCall\":{\"name\":\"f\"}}]}]",
                json.stringify(AntigravityRequestSignatures.ensureThinkingBeforeToolUseInContents(
                        list(map("role", "model", "parts", list(map("functionCall", map("name", "f")), map("thought", true)))),
                        "key-store2", lookup, store)));
    }

    @Test
    void ensureThinkingBeforeToolUseInMessages() {
        RequestTestDoubles.MapLookup lookup = new RequestTestDoubles.MapLookup();
        RequestTestDoubles.MapStore store = new RequestTestDoubles.MapStore();
        store.set("mkey-store2", "stored-m2", "sig");

        assertEquals("[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"th\",\"signature\":\"" + SKIP + "\"},{\"type\":\"tool_use\"}]}]",
                json.stringify(AntigravityRequestSignatures.ensureThinkingBeforeToolUseInMessages(
                        list(map("role", "assistant", "content", list(map("type", "tool_use"), map("type", "thinking", "thinking", "th", "signature", SKIP)))),
                        "mkey-signed", lookup, store)));

        assertEquals("[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"unsigned\",\"signature\":\"" + SKIP + "\"},{\"type\":\"tool_use\"}]}]",
                json.stringify(AntigravityRequestSignatures.ensureThinkingBeforeToolUseInMessages(
                        list(map("role", "assistant", "content", list(map("type", "tool_use"), map("type", "thinking", "thinking", "unsigned")))),
                        "mkey-miss", lookup, store)));

        assertEquals("[{\"role\":\"user\",\"content\":[{\"type\":\"tool_use\"}]}]",
                json.stringify(AntigravityRequestSignatures.ensureThinkingBeforeToolUseInMessages(
                        list(map("role", "user", "content", list(map("type", "tool_use")))),
                        "mkey-x", lookup, store)));

        // no-text thinking -> unsigned -> empty store -> sentinel with empty text
        assertEquals("[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"" + SKIP + "\"},{\"type\":\"tool_use\"}]}]",
                json.stringify(AntigravityRequestSignatures.ensureThinkingBeforeToolUseInMessages(
                        list(map("role", "assistant", "content", list(map("type", "tool_use"), map("type", "thinking")))),
                        "mkey-empty2", lookup, store)));

        assertEquals("[{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"stored-m2\",\"signature\":\"" + SKIP + "\"},{\"type\":\"tool_use\"}]}]",
                json.stringify(AntigravityRequestSignatures.ensureThinkingBeforeToolUseInMessages(
                        list(map("role", "assistant", "content", list(map("type", "tool_use"), map("type", "thinking")))),
                        "mkey-store2", lookup, store)));
    }

    // ---- mutators -------------------------------------------------------------------------------

    @Test
    void sanitizeRequestPayloadForAntigravity() {
        Map<String, Object> p1 = map("contents", list(map("role", "user", "parts",
                list(map("text", "a"), map("nope", 1L), map("functionCall", map("name", "f"), "thoughtSignature", repeat("x", 60))))));
        AntigravityRequestSignatures.sanitizeRequestPayloadForAntigravity(p1);
        assertEquals("{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"a\"},{\"functionCall\":{\"name\":\"f\"},\"thoughtSignature\":\"" + repeat("x", 60) + "\",\"thought_signature\":\"" + repeat("x", 60) + "\"}]}]}",
                json.stringify(p1));

        Map<String, Object> p2 = map("contents", list(map("role", "user", "parts",
                list(map("functionCall", map("name", "f")), map("functionCall", map("name", "g"), "thoughtSignature", repeat("z", 60))))));
        AntigravityRequestSignatures.sanitizeRequestPayloadForAntigravity(p2);
        assertEquals("{\"contents\":[{\"role\":\"user\",\"parts\":[{\"functionCall\":{\"name\":\"f\"},\"thought_signature\":\"" + SKIP + "\",\"thoughtSignature\":\"" + SKIP + "\"},{\"functionCall\":{\"name\":\"g\"}}]}]}",
                json.stringify(p2));

        Map<String, Object> p3 = map("contents", list("notobj", map("role", "user", "parts", list(map("nope", 1L)))));
        AntigravityRequestSignatures.sanitizeRequestPayloadForAntigravity(p3);
        assertEquals("{\"contents\":[]}", json.stringify(p3));

        Map<String, Object> p4 = map("systemInstruction", map("parts", list(map("text", "s"), map("nope", 1L))));
        AntigravityRequestSignatures.sanitizeRequestPayloadForAntigravity(p4);
        assertEquals("{\"systemInstruction\":{\"parts\":[{\"text\":\"s\"}]}}", json.stringify(p4));

        Map<String, Object> p5 = map("systemInstruction", map("parts", list(map("nope", 1L))));
        AntigravityRequestSignatures.sanitizeRequestPayloadForAntigravity(p5);
        assertEquals("{}", json.stringify(p5));
    }

    @Test
    void stripInjectedDebugFromParts() {
        List<Object> parts = list(map("text", "keep"), map("text", "[opencode-antigravity-auth debug] strip"),
                map("thinking", "[Thinking preserved]"), map("functionCall", map()), "primitive");
        assertEquals("[{\"text\":\"keep\"},{\"functionCall\":{}},\"primitive\"]",
                json.stringify(AntigravityRequestSignatures.stripInjectedDebugFromParts(parts)));
    }

    @Test
    void stripInjectedDebugFromRequestPayload() {
        Map<String, Object> pl = map(
                "contents", list(
                        map("role", "model", "parts", list(map("text", "keep"), map("text", "[opencode-antigravity-auth debug] x"))),
                        map("role", "user", "content", list(map("text", "[Thinking preserved] y"), map("text", "keep2")))),
                "messages", list(map("role", "assistant", "content", list(map("text", "[opencode-antigravity-auth debug] z"), map("text", "ok")))));
        AntigravityRequestSignatures.stripInjectedDebugFromRequestPayload(pl);
        assertEquals("{\"contents\":[{\"role\":\"model\",\"parts\":[{\"text\":\"keep\"}]},{\"role\":\"user\",\"content\":[{\"text\":\"keep2\"}]}],\"messages\":[{\"role\":\"assistant\",\"content\":[{\"text\":\"ok\"}]}]}",
                json.stringify(pl));
    }

    @Test
    void injectDebugThinking() {
        assertEquals("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"DBG\"},{\"text\":\"orig\"}]}}]}",
                json.stringify(AntigravityRequestSignatures.injectDebugThinking(
                        map("candidates", list(map("content", map("parts", list(map("text", "orig")))))), "DBG")));
        assertEquals("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"DBG\"},{\"type\":\"text\",\"text\":\"orig\"}]}",
                json.stringify(AntigravityRequestSignatures.injectDebugThinking(
                        map("content", list(map("type", "text", "text", "orig"))), "DBG")));
        assertEquals("{\"foo\":1,\"reasoning_content\":\"DBG\"}",
                json.stringify(AntigravityRequestSignatures.injectDebugThinking(map("foo", 1L), "DBG")));
        assertEquals("{\"reasoning_content\":\"已有\",\"foo\":1}",
                json.stringify(AntigravityRequestSignatures.injectDebugThinking(map("reasoning_content", "已有", "foo", 1L), "DBG")));
        assertEquals("str", AntigravityRequestSignatures.injectDebugThinking("str", "DBG"));
    }
}
