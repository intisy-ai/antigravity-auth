package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline parity tests for {@link AntigravityFormatBridge}, expected values snapshotted from the REAL
 * {@code anthropicToGemini}/{@code supportsThinking}/{@code isAnthropicMessages}
 * (src/plugin/anthropic-bridge.ts) via the Node harness ({@code .superpowers/sdd/t7d-harness/},
 * fixtures.json {@code anthropicToGemini} / {@code supportsThinking} / {@code isAnthropicMessages}) --
 * never hand-derived. Inputs are parsed from the harness-recorded {@code body} JSON and each output is
 * compared as a serialized JSON string (so key order + value types are pinned to the real TS). The
 * injected schema cleaner is the REAL {@link AntigravitySchemaCleaner} (closed loop, as in T7c-1).
 */
class AntigravityFormatBridgeTest {

    private static final JsonCodec JSON = new TestJsonCodec();
    private static final ClaudeTransforms.SchemaCleaner CLEANER = AntigravitySchemaCleaner::clean;

    // {input body JSON, model, expected Gemini body JSON} -- all transcribed from fixtures.json.
    private static final String[][] BRIDGE_CASES = {
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":\"yo\"}]}",
                    "claude-sonnet",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]},{\"role\":\"model\",\"parts\":[{\"text\":\"yo\"}]}]}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"\"}]}",
                    "claude-sonnet",
                    "{\"contents\":[]}"},
            {"{\"messages\":[{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"thinking about it\"},{\"type\":\"text\"},{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"search\",\"input\":{\"q\":\"cats\"}},{\"type\":\"tool_use\",\"id\":\"tu_2\",\"name\":\"noargs\"}]},{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"tu_1\",\"content\":\"string result\"},{\"type\":\"tool_result\",\"tool_use_id\":\"tu_2\",\"content\":[{\"text\":\"a\"},{\"text\":\"b\"},{}]},{\"type\":\"tool_result\",\"tool_use_id\":\"tu_1\",\"content\":{\"obj\":1}},{\"type\":\"tool_result\",\"tool_use_id\":\"tu_1\"},{\"type\":\"tool_result\",\"tool_use_id\":\"missing_id\",\"content\":\"x\"},{\"type\":\"tool_result\",\"content\":\"y\"},{\"type\":\"image\",\"source\":{}}]}]}",
                    "claude-sonnet",
                    "{\"contents\":[{\"role\":\"model\",\"parts\":[{\"text\":\"thinking about it\"},{\"text\":\"\"},{\"functionCall\":{\"name\":\"search\",\"args\":{\"q\":\"cats\"}}},{\"functionCall\":{\"name\":\"noargs\",\"args\":{}}}]},{\"role\":\"user\",\"parts\":[{\"functionResponse\":{\"name\":\"search\",\"response\":{\"result\":\"string result\"}}},{\"functionResponse\":{\"name\":\"noargs\",\"response\":{\"result\":\"ab\"}}},{\"functionResponse\":{\"name\":\"search\",\"response\":{\"result\":\"{\\\"obj\\\":1}\"}}},{\"functionResponse\":{\"name\":\"search\",\"response\":{\"result\":\"\"}}},{\"functionResponse\":{\"name\":\"missing_id\",\"response\":{\"result\":\"x\"}}},{\"functionResponse\":{\"name\":\"tool\",\"response\":{\"result\":\"y\"}}}]}]}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"system\":\"be nice\"}",
                    "claude-sonnet",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"systemInstruction\":{\"parts\":[{\"text\":\"be nice\"}]}}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"system\":[{\"text\":\"line1\"},{},{\"text\":\"line3\"}]}",
                    "claude-sonnet",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"systemInstruction\":{\"parts\":[{\"text\":\"line1\\n\\nline3\"}]}}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"system\":\"\"}",
                    "claude-sonnet",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}]}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1024,\"temperature\":0,\"top_p\":0.9,\"stop_sequences\":[\"STOP\"]}",
                    "claude-sonnet",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"generationConfig\":{\"maxOutputTokens\":1024,\"temperature\":0,\"topP\":0.9,\"stopSequences\":[\"STOP\"]}}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stop_sequences\":[]}",
                    "claude-sonnet",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}]}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"thinking\":{\"type\":\"adaptive\",\"budget_tokens\":4096}}",
                    "claude-thinking",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":4096}}}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"output_config\":{\"effort\":\"high\"}}",
                    "gemini-3-pro",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":32768}}}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"output_config\":{\"effort\":\"xhigh\"}}",
                    "gemini-3-pro",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":32768}}}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"thinking\":{\"budget_tokens\":4096}}",
                    "gpt-oss",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}]}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"thinking\":{\"type\":\"disabled\"},\"output_config\":{\"effort\":\"high\"}}",
                    "claude-thinking",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}]}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"thinking\":{\"budget_tokens\":100},\"output_config\":{\"effort\":\"high\"}}",
                    "claude-thinking",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"generationConfig\":{\"thinkingConfig\":{\"thinkingBudget\":100}}}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"output_config\":{\"effort\":\"bogus\"}}",
                    "claude-thinking",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}]}"},
            {"{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"tools\":[{\"name\":\"search\",\"description\":\"find things\",\"input_schema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}},{\"name\":\"noschema\"},{\"description\":\"no name -> filtered out\"},null]}",
                    "claude-sonnet",
                    "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],\"tools\":[{\"functionDeclarations\":[{\"name\":\"search\",\"description\":\"find things\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}},{\"name\":\"noschema\",\"description\":\"\",\"parameters\":{\"type\":\"object\",\"properties\":{\"_placeholder\":{\"type\":\"boolean\",\"description\":\"Placeholder. Always pass true.\"}},\"required\":[\"_placeholder\"]}}]}]}"},
            {"{}",
                    "claude-sonnet",
                    "{\"contents\":[]}"},
    };

    @Test
    @SuppressWarnings("unchecked")
    void anthropicToGemini_parity() {
        for (String[] c : BRIDGE_CASES) {
            Map<String, Object> body = (Map<String, Object>) JSON.parse(c[0]);
            Map<String, Object> gem = AntigravityFormatBridge.anthropicToGemini(JSON, body, c[1], CLEANER);
            assertEquals(c[2], JSON.stringify(gem), "case body=" + c[0]);
        }
    }

    @Test
    void supportsThinking_parity() {
        assertTrue(AntigravityFormatBridge.supportsThinking("claude-thinking"));
        assertTrue(AntigravityFormatBridge.supportsThinking("Claude-Thinking"));
        assertTrue(AntigravityFormatBridge.supportsThinking("gemini-3-pro"));
        assertTrue(AntigravityFormatBridge.supportsThinking("GEMINI-3"));
        assertFalse(AntigravityFormatBridge.supportsThinking("claude-sonnet"));
        assertFalse(AntigravityFormatBridge.supportsThinking("gpt-oss"));
        assertFalse(AntigravityFormatBridge.supportsThinking(""));
        assertFalse(AntigravityFormatBridge.supportsThinking(null));
    }

    @Test
    void isAnthropicMessages_parity() {
        assertTrue(AntigravityFormatBridge.isAnthropicMessages("https://x/v1/messages"));
        assertTrue(AntigravityFormatBridge.isAnthropicMessages("https://x/v1/messages?beta=true"));
        assertTrue(AntigravityFormatBridge.isAnthropicMessages("/v1/messages"));
        assertFalse(AntigravityFormatBridge.isAnthropicMessages("https://x/v1/chat"));
        assertFalse(AntigravityFormatBridge.isAnthropicMessages(""));
        assertFalse(AntigravityFormatBridge.isAnthropicMessages(null));
        assertFalse(AntigravityFormatBridge.isAnthropicMessages(123));
    }
}
