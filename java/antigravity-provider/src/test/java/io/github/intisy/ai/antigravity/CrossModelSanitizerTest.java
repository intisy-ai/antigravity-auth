package io.github.intisy.ai.antigravity;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.list;
import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline parity tests for {@link CrossModelSanitizer}, expected values snapshotted from the real
 * {@code src/plugin/transform/cross-model-sanitizer.ts} via the Node harness
 * ({@code .superpowers/sdd/t7b-harness/}) -- see t7b-report.md. Covers both sides of the
 * MIN_SIGNATURE_LENGTH=50 gate, the thinking-block always-strip path, and the copy-vs-mutate
 * contract of the deep walk vs the in-place walk.
 */
class CrossModelSanitizerTest {

    private static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    // ---- getModelFamily --------------------------------------------------------------------------

    @Test
    void getModelFamily_claudeGeminiUnknown() {
        assertEquals("claude", CrossModelSanitizer.getModelFamily("claude-opus-4-6-thinking-medium"));
        assertEquals("gemini", CrossModelSanitizer.getModelFamily("gemini-3-pro-low"));
        assertEquals("gemini", CrossModelSanitizer.getModelFamily("gemini-2.5-pro"));
        assertEquals("unknown", CrossModelSanitizer.getModelFamily("gpt-4"));
        assertEquals("unknown", CrossModelSanitizer.getModelFamily("unknown-model"));
    }

    // ---- stripGeminiThinkingMetadata (mutates part) ----------------------------------------------

    @Test
    void stripGemini_topLevelThoughtSignature() {
        Map<String, Object> part = map("thought", true, "text", "thinking...", "thoughtSignature", "EsgQ...");
        int stripped = CrossModelSanitizer.stripGeminiThinkingMetadata(part, true);
        assertEquals(1, stripped);
        assertEquals(map("thought", true, "text", "thinking..."), part);
    }

    @Test
    void stripGemini_nestedGoogle_preserveKeepsGrounding() {
        Map<String, Object> part = map(
                "functionCall", map("name", "bash"),
                "metadata", map("google", map("thoughtSignature", "sig123", "groundingMetadata", "preserved"),
                        "cache_control", map("type", "ephemeral")));
        int stripped = CrossModelSanitizer.stripGeminiThinkingMetadata(part, true);
        assertEquals(1, stripped);
        assertEquals(map(
                "functionCall", map("name", "bash"),
                "metadata", map("google", map("groundingMetadata", "preserved"),
                        "cache_control", map("type", "ephemeral"))), part);
    }

    @Test
    void stripGemini_emptyGoogleAndMetadataCleanedUp() {
        Map<String, Object> part = map("text", "hello", "metadata", map("google", map("thoughtSignature", "sig123")));
        int stripped = CrossModelSanitizer.stripGeminiThinkingMetadata(part, true);
        assertEquals(1, stripped);
        assertEquals(map("text", "hello"), part);
    }

    @Test
    void stripGemini_noPreserve_dropsGoogleEvenWithOtherKeys() {
        Map<String, Object> part = map("functionCall", map("name", "bash"),
                "metadata", map("google", map("thoughtSignature", "s", "groundingMetadata", "g")));
        int stripped = CrossModelSanitizer.stripGeminiThinkingMetadata(part, false);
        assertEquals(1, stripped);
        assertEquals(map("functionCall", map("name", "bash")), part);
    }

    @Test
    void stripGemini_noMetadata_noop() {
        Map<String, Object> part = map("text", "Hello");
        assertEquals(0, CrossModelSanitizer.stripGeminiThinkingMetadata(part, true));
        assertEquals(map("text", "Hello"), part);
    }

    // ---- stripClaudeThinkingFields ---------------------------------------------------------------

    @Test
    void stripClaude_thinkingBlockAlwaysStripsRegardlessOfLength() {
        // thinking block: signature stripped even below the 50-char gate.
        Map<String, Object> shortSig = map("type", "thinking", "thinking", "x", "signature", rep("a", 49));
        assertEquals(1, CrossModelSanitizer.stripClaudeThinkingFields(shortSig));
        assertEquals(map("type", "thinking", "thinking", "x"), shortSig);

        Map<String, Object> longSig = map("type", "thinking", "thinking", "x", "signature", rep("a", 50));
        assertEquals(1, CrossModelSanitizer.stripClaudeThinkingFields(longSig));
    }

    @Test
    void stripClaude_nonThinkingPart_lengthGate() {
        // non-thinking part: only >=50 stripped.
        Map<String, Object> shortSig = map("type", "text", "text", "hello", "signature", "short");
        assertEquals(0, CrossModelSanitizer.stripClaudeThinkingFields(shortSig));
        assertEquals(map("type", "text", "text", "hello", "signature", "short"), shortSig);

        Map<String, Object> longSig = map("type", "text", "text", "hello", "signature", rep("a", 60));
        assertEquals(1, CrossModelSanitizer.stripClaudeThinkingFields(longSig));
        assertEquals(map("type", "text", "text", "hello"), longSig);
    }

    @Test
    void stripClaude_redactedThinking() {
        Map<String, Object> part = map("type", "redacted_thinking", "data", "encrypted", "signature", rep("a]", 30));
        assertEquals(1, CrossModelSanitizer.stripClaudeThinkingFields(part));
        assertEquals(map("type", "redacted_thinking", "data", "encrypted"), part);
    }

    // ---- deepSanitizeCrossModelMetadata (returns NEW tree; does NOT mutate input) ----------------

    @Test
    void deep_contents_claudeTarget_stripsGeminiSignatures() {
        Map<String, Object> payload = map("contents", list(
                map("role", "model", "parts", list(
                        map("thought", true, "text", "thinking...", "thoughtSignature", "sig1"),
                        map("functionCall", map("name", "bash"), "metadata", map("google", map("thoughtSignature", "sig2")))))));
        CrossModelSanitizer.DeepResult r = CrossModelSanitizer.deepSanitizeCrossModelMetadata(payload, "claude", true);
        assertEquals(2, r.stripped);
        assertEquals(map("contents", list(
                map("role", "model", "parts", list(
                        map("thought", true, "text", "thinking..."),
                        map("functionCall", map("name", "bash")))))), r.obj);
        // input untouched (deep walk copies at every level)
        assertEquals("sig1", ((Map<?, ?>) ((java.util.List<?>) ((Map<?, ?>) ((java.util.List<?>) payload.get("contents")).get(0)).get("parts")).get(0)).get("thoughtSignature"));
    }

    @Test
    void deep_messages_geminiTarget_stripsClaudeSignature() {
        Map<String, Object> payload = map("messages", list(
                map("role", "assistant", "content", list(
                        map("type", "thinking", "thinking", "analyzing...", "signature", rep("a", 60)),
                        map("type", "tool_use", "id", "tool_1", "name", "bash")))));
        CrossModelSanitizer.DeepResult r = CrossModelSanitizer.deepSanitizeCrossModelMetadata(payload, "gemini", true);
        assertEquals(1, r.stripped);
        assertEquals(map("messages", list(
                map("role", "assistant", "content", list(
                        map("type", "thinking", "thinking", "analyzing..."),
                        map("type", "tool_use", "id", "tool_1", "name", "bash"))))), r.obj);
    }

    @Test
    void deep_extraBodyAndRequests() {
        Map<String, Object> extraBodyPayload = map("extra_body", map("messages", list(
                map("role", "assistant", "content", list(
                        map("type", "tool_use", "metadata", map("google", map("thoughtSignature", "sig"))))))));
        CrossModelSanitizer.DeepResult r1 = CrossModelSanitizer.deepSanitizeCrossModelMetadata(extraBodyPayload, "claude", true);
        assertEquals(1, r1.stripped);
        assertEquals(map("extra_body", map("messages", list(
                map("role", "assistant", "content", list(map("type", "tool_use")))))), r1.obj);

        Map<String, Object> requestsPayload = map("requests", list(
                map("contents", list(map("role", "model", "parts", list(map("thoughtSignature", "sig1"))))),
                map("contents", list(map("role", "model", "parts", list(map("thoughtSignature", "sig2")))))));
        CrossModelSanitizer.DeepResult r2 = CrossModelSanitizer.deepSanitizeCrossModelMetadata(requestsPayload, "claude", true);
        assertEquals(2, r2.stripped);
    }

    // ---- sanitizeCrossModelPayload ---------------------------------------------------------------

    @Test
    void payload_modifiedAndCount() {
        Map<String, Object> payload = map("contents", list(
                map("role", "model", "parts", list(
                        map("thought", true, "text", "thinking...", "thoughtSignature", "sig1"),
                        map("functionCall", map("name", "bash"), "metadata", map("google", map("thoughtSignature", "sig2")))))));
        CrossModelSanitizer.SanitizationResult r = CrossModelSanitizer.sanitizeCrossModelPayload(payload, map("targetModel", "claude-opus-4-6-thinking-medium"));
        assertTrue(r.modified);
        assertEquals(2, r.signaturesStripped);
    }

    @Test
    void payload_unknownTarget_skips() {
        Map<String, Object> payload = map("contents", list(map("parts", list(map("thoughtSignature", "sig")))));
        CrossModelSanitizer.SanitizationResult r = CrossModelSanitizer.sanitizeCrossModelPayload(payload, map("targetModel", "gpt-4"));
        assertFalse(r.modified);
        assertEquals(0, r.signaturesStripped);
        assertSame(payload, r.payload);
    }

    @Test
    void payload_preservesFunctionCallAndGrounding() {
        Map<String, Object> payload = map("contents", list(
                map("parts", list(map(
                        "functionCall", map("name", "read"),
                        "metadata", map("google", map("thoughtSignature", "strip-me", "groundingMetadata", "keep-me"),
                                "cache_control", map("type", "ephemeral")))))));
        CrossModelSanitizer.SanitizationResult r = CrossModelSanitizer.sanitizeCrossModelPayload(payload,
                map("targetModel", "claude-sonnet-4", "preserveNonSignatureMetadata", true));
        assertEquals(1, r.signaturesStripped);
        assertEquals(map("contents", list(
                map("parts", list(map(
                        "functionCall", map("name", "read"),
                        "metadata", map("google", map("groundingMetadata", "keep-me"),
                                "cache_control", map("type", "ephemeral"))))))), r.payload);
    }

    // ---- sanitizeCrossModelPayloadInPlace (mutates payload) --------------------------------------

    @Test
    void inPlace_mutatesContents() {
        Map<String, Object> payload = map("contents", list(map("parts", list(map("thought", true, "thoughtSignature", "sig")))));
        int stripped = CrossModelSanitizer.sanitizeCrossModelPayloadInPlace(payload, map("targetModel", "claude-opus-4-6-thinking-high"));
        assertEquals(1, stripped);
        assertEquals(map("contents", list(map("parts", list(map("thought", true))))), payload);
    }

    @Test
    void inPlace_extraBodyMessages() {
        Map<String, Object> payload = map("extra_body", map("messages", list(
                map("content", list(map("metadata", map("google", map("thoughtSignature", "sig"))))))));
        int stripped = CrossModelSanitizer.sanitizeCrossModelPayloadInPlace(payload, map("targetModel", "claude-sonnet-4"));
        assertEquals(1, stripped);
        assertEquals(map("extra_body", map("messages", list(map("content", list(map()))))), payload);
    }

    @Test
    void inPlace_unknownTargetNoMutation() {
        Map<String, Object> payload = map("contents", list(map("parts", list(map("thoughtSignature", "x")))));
        assertEquals(0, CrossModelSanitizer.sanitizeCrossModelPayloadInPlace(payload, map("targetModel", "gpt-4")));
        assertEquals(map("contents", list(map("parts", list(map("thoughtSignature", "x"))))), payload);
    }
}
