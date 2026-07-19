package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP-2 successor to the deleted {@code AntigravityFormatBridgeTest}: exercises {@link
 * AntigravityIrBridge#anthropicToGemini} (now decode-through-IR-then-encode via core-ir's {@code
 * AnthropicTranslator}/{@code GeminiTranslator}) against the same essential behaviors the old
 * bespoke bridge covered -- text/tool_use/tool_result round trip, system instruction, and the
 * antigravity-specific thinking-budget precedence ({@link AntigravityIrBridge#resolveThinkingBudget}).
 *
 * <p>Two cases are DELIBERATELY different from the old byte-exact fixture (disclosed, not bugs):
 * <ul>
 *   <li>an empty-string message {@code content} now encodes as {@code {"text":""}} instead of
 *       being dropped -- the old ad hoc bridge treated a falsy JS string as "no content"; core-ir's
 *       IR is a faithful structural round trip of whatever the wire actually said.</li>
 *   <li>an explicit {@code thinking.budget_tokens} on a model core-ir recognizes now produces
 *       {@code generationConfig.thinkingConfig.includeThoughts:true} in addition to {@code
 *       thinkingBudget} -- the old bridge never wrote {@code includeThoughts}; {@link
 *       AntigravityRequestPrep} fully rebuilds {@code thinkingConfig} downstream regardless, so this
 *       extra key never reaches the real outbound wire (verified by {@code handle-parity.test.ts}'s
 *       frozen fixture, unaffected by this).</li>
 * </ul>
 */
class AntigravityIrBridgeTest {

    private static final JsonCodec JSON = new TestJsonCodec();

    private static String gemini(String anthropicBody, String model) {
        return AntigravityIrBridge.anthropicToGemini(JSON, anthropicBody, model);
    }

    @Test
    void textMessages_roundTripAsGeminiContents() {
        String gem = gemini(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"},{\"role\":\"assistant\",\"content\":\"yo\"}]}",
                "claude-sonnet");
        assertEquals("{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]},"
                + "{\"role\":\"model\",\"parts\":[{\"text\":\"yo\"}]}]}", gem);
    }

    @Test
    void toolUseAndToolResult_pairByName() {
        // core-ir's GeminiBlockCodec preserves the real Anthropic tool_use id on functionCall/
        // functionResponse (the old bridge never did, dropping straight to name-only pairing) --
        // a strict superset, harmless to a real Gemini/cloudcode-pa endpoint.
        String gem = gemini(
                "{\"messages\":[" +
                        "{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"search\",\"input\":{\"q\":\"cats\"}}]}," +
                        "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"tu_1\",\"content\":\"string result\"}]}" +
                        "]}",
                "claude-sonnet");
        assertTrue(gem.contains("\"functionCall\":{\"id\":\"tu_1\",\"name\":\"search\",\"args\":{\"q\":\"cats\"}}"), gem);
        assertTrue(gem.contains("\"functionResponse\":{\"id\":\"tu_1\",\"name\":\"search\",\"response\":{\"result\":\"string result\"}}"), gem);
    }

    @Test
    void systemString_becomesSystemInstruction() {
        String gem = gemini("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"system\":\"be nice\"}", "claude-sonnet");
        assertTrue(gem.contains("\"systemInstruction\":{\"parts\":[{\"text\":\"be nice\"}]}"), gem);
    }

    @Test
    void emptyStringContent_encodesAsEmptyTextBlock_disclosedDeviation() {
        // Old bridge dropped the message entirely ({"contents":[]}); core-ir's IR is a faithful
        // structural round trip, so the message survives with one empty text block. Unreachable via
        // any spec-valid non-empty Claude Code turn.
        String gem = gemini("{\"messages\":[{\"role\":\"user\",\"content\":\"\"}]}", "claude-sonnet");
        assertEquals("{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"\"}]}]}", gem);
    }

    @Test
    void toolDeclarations_wrapAsFunctionDeclarations_schemaCleaningStaysDownstream() {
        // core-ir's GeminiTranslator only does the STRUCTURAL wrap (tools -> functionDeclarations);
        // AntigravitySchemaCleaner's placeholder-filling for a missing schema is antigravity-specific
        // business logic that now runs downstream in AntigravityRequestPrep (which re-processes
        // "tools" regardless of whether it arrived pre-wrapped like this, or natively from a Gemini
        // client) -- NOT at this bridge layer, unlike the old bespoke bridge which cleaned inline.
        String gem = gemini(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"tools\":[" +
                        "{\"name\":\"search\",\"description\":\"find things\",\"input_schema\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}]}",
                "claude-sonnet");
        assertTrue(gem.contains("\"functionDeclarations\":[{\"name\":\"search\",\"description\":\"find things\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}}]"), gem);
    }

    // ---- thinking budget precedence (anthropic-bridge.ts EFFORT_BUDGET table) ---------------------

    @Test
    void explicitBudgetTokens_wins_whenModelSupportsThinking() {
        String gem = gemini(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"thinking\":{\"type\":\"adaptive\",\"budget_tokens\":4096}}",
                "claude-thinking");
        assertTrue(gem.contains("\"thinkingBudget\":4096"), gem);
    }

    @Test
    void effortTable_resolvesBudget_whenNoExplicitBudget() {
        String high = gemini("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"output_config\":{\"effort\":\"high\"}}", "gemini-3-pro");
        assertTrue(high.contains("\"thinkingBudget\":32768"), high);

        String xhigh = gemini("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"output_config\":{\"effort\":\"xhigh\"}}", "gemini-3-pro");
        assertTrue(xhigh.contains("\"thinkingBudget\":32768"), xhigh);
    }

    @Test
    void thinkingDisabled_suppressesThinkingConfig_evenWithEffort() {
        String gem = gemini(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"thinking\":{\"type\":\"disabled\"},\"output_config\":{\"effort\":\"high\"}}",
                "claude-thinking");
        assertFalse(gem.contains("thinkingConfig"), gem);
    }

    @Test
    void modelWithoutThinkingSupport_getsNoThinkingConfig() {
        String gem = gemini("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"thinking\":{\"budget_tokens\":4096}}", "gpt-oss");
        assertFalse(gem.contains("thinkingConfig"), gem);
    }

    @Test
    void unrecognizedEffort_getsNoThinkingConfig() {
        String gem = gemini("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"output_config\":{\"effort\":\"bogus\"}}", "claude-thinking");
        assertFalse(gem.contains("thinkingConfig"), gem);
    }

    // ---- supportsThinking / isAnthropicMessages -----------------------------------------------

    @Test
    void supportsThinking_parity() {
        assertTrue(AntigravityIrBridge.supportsThinking("claude-thinking"));
        assertTrue(AntigravityIrBridge.supportsThinking("Claude-Thinking"));
        assertTrue(AntigravityIrBridge.supportsThinking("gemini-3-pro"));
        assertTrue(AntigravityIrBridge.supportsThinking("GEMINI-3"));
        assertFalse(AntigravityIrBridge.supportsThinking("claude-sonnet"));
        assertFalse(AntigravityIrBridge.supportsThinking("gpt-oss"));
        assertFalse(AntigravityIrBridge.supportsThinking(""));
        assertFalse(AntigravityIrBridge.supportsThinking(null));
    }

    @Test
    void isAnthropicMessages_parity() {
        assertTrue(AntigravityIrBridge.isAnthropicMessages("https://x/v1/messages"));
        assertTrue(AntigravityIrBridge.isAnthropicMessages("https://x/v1/messages?beta=true"));
        assertTrue(AntigravityIrBridge.isAnthropicMessages("/v1/messages"));
        assertFalse(AntigravityIrBridge.isAnthropicMessages("https://x/v1/chat"));
        assertFalse(AntigravityIrBridge.isAnthropicMessages(""));
        assertFalse(AntigravityIrBridge.isAnthropicMessages(null));
        assertFalse(AntigravityIrBridge.isAnthropicMessages(123));
    }
}
