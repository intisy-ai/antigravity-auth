package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.antigravity.AntigravityRequestKeys.Hasher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.list;
import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** T7e parity: {@link AntigravityRequestKeys} vs the real request.ts key/seed helpers (fixtures.json). */
final class AntigravityRequestKeysTest {

    private final Hasher sha = RequestTestDoubles.sha256();

    @Test
    void buildSignatureSessionKey() {
        assertEquals("sid:claude-sonnet:proj1:conv1", AntigravityRequestKeys.buildSignatureSessionKey("sid", "Claude-Sonnet", "conv1", "proj1"));
        assertEquals("sid:unknown:default:default", AntigravityRequestKeys.buildSignatureSessionKey("sid", "  ", "  ", "  "));
        assertEquals("sid:unknown:default:default", AntigravityRequestKeys.buildSignatureSessionKey("sid", null, null, null));
        assertEquals("sid:gemini-3:p:c", AntigravityRequestKeys.buildSignatureSessionKey("sid", "GEMINI-3", "  c  ", "  p  "));
    }

    @Test
    void hashConversationSeed_realSha256() {
        assertEquals("2cf24dba5fb0a30e", AntigravityRequestKeys.hashConversationSeed(sha, "hello"));
        assertEquals("f290a413f5423326", AntigravityRequestKeys.hashConversationSeed(sha, "system|user text"));
        assertEquals("e3b0c44298fc1c14", AntigravityRequestKeys.hashConversationSeed(sha, ""));
        assertEquals("7d3e775298422761", AntigravityRequestKeys.hashConversationSeed(sha, "unicode ✈️ end"));
    }

    @Test
    void extractTextFromContent() {
        assertEquals("plain", AntigravityRequestKeys.extractTextFromContent("plain"));
        assertEquals("", AntigravityRequestKeys.extractTextFromContent(123L));
        assertEquals("a", AntigravityRequestKeys.extractTextFromContent(list(map("text", "a"), map("text", "b"))));
        assertEquals("found", AntigravityRequestKeys.extractTextFromContent(list(null, "x", map("nope", 1L), map("text", "found"))));
        assertEquals("nested", AntigravityRequestKeys.extractTextFromContent(list(map("text", map("text", "nested")))));
        assertEquals("", AntigravityRequestKeys.extractTextFromContent(list(map("foo", 1L))));
    }

    @Test
    void resolveConversationKey() {
        assertEquals("conv-42", AntigravityRequestKeys.resolveConversationKey(sha, map("conversationId", "  conv-42  ")));
        assertEquals("th-9", AntigravityRequestKeys.resolveConversationKey(sha, map("metadata", map("thread_id", "th-9"))));
        assertNull(AntigravityRequestKeys.resolveConversationKey(sha, map()));
        assertEquals("seed-24fa648b17b474ea", AntigravityRequestKeys.resolveConversationKey(sha, map(
                "system", "you are helpful",
                "messages", list(map("role", "user", "content", "first q"), map("role", "user", "content", "last q")))));
        assertEquals("seed-9b96a1fe1d548cbb", AntigravityRequestKeys.resolveConversationKey(sha, map(
                "contents", list(map("role", "user", "parts", list(map("text", "hi there")))))));
        assertEquals("seed-e1fe8a1a11f84ab4", AntigravityRequestKeys.resolveConversationKey(sha, map(
                "systemInstruction", map("parts", list(map("text", "sys"))),
                "contents", list(map("role", "user", "parts", list(map("text", "u")))))));
    }

    @Test
    void resolveProjectKey() {
        assertEquals("p", AntigravityRequestKeys.resolveProjectKey("  p  ", null));
        assertEquals("fb", AntigravityRequestKeys.resolveProjectKey(null, "  fb  "));
        assertNull(AntigravityRequestKeys.resolveProjectKey("", ""));
        assertEquals("fb", AntigravityRequestKeys.resolveProjectKey(42L, "fb"));
    }
}
