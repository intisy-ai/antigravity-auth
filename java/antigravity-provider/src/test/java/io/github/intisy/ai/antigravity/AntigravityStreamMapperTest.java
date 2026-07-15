package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Offline parity tests for {@link AntigravityStreamMapper}, expected values snapshotted from the REAL
 * {@code geminiToAnthropicStream} state machine (src/plugin/anthropic-bridge.ts) via the Node harness
 * ({@code .superpowers/sdd/t7d-harness/}, fixtures.json {@code geminiToAnthropicStream}) -- the real
 * TransformStream was DRIVEN with each case's chunks (Date.now/Math.random stubbed) and its emitted
 * SSE bytes captured verbatim. Each test feeds the harness-recorded parsed {@code objects} to
 * {@link AntigravityStreamMapper#handle(Object)} in order, then {@link AntigravityStreamMapper#finish()},
 * concatenates, and asserts byte-equality with the captured {@code output}. The injected
 * {@link AntigravityStreamMapper.IdGenerator} replays the exact ids the real TS emitted.
 */
class AntigravityStreamMapperTest {

    private static final JsonCodec JSON = new TestJsonCodec();
    private static final String MSG_ID = "msg_loyw3v284fzzzxjy";
    private static final String TOOL_ID = "toolu_loyw3v284fzzzx";

    // Replays harness-captured ids: msgId once, toolIds in order (all identical here under the
    // fixed Math.random stub, but cycled for robustness).
    private static final class ReplayIds implements AntigravityStreamMapper.IdGenerator {
        private final String msgId;
        private final List<String> toolIds;
        private int t = 0;

        ReplayIds(String msgId, List<String> toolIds) {
            this.msgId = msgId;
            this.toolIds = toolIds;
        }

        @Override
        public String newMessageId() {
            return msgId;
        }

        @Override
        public String newToolId() {
            String id = toolIds.get(Math.min(t, toolIds.size() - 1));
            t++;
            return id;
        }
    }

    @SuppressWarnings("unchecked")
    private String run(String model, String objectsJson) {
        AntigravityStreamMapper mapper = new AntigravityStreamMapper(
                JSON, new ReplayIds(MSG_ID, java.util.Collections.singletonList(TOOL_ID)), model);
        StringBuilder sb = new StringBuilder();
        Object parsed = JSON.parse(objectsJson);
        for (Object obj : (List<Object>) parsed) {
            for (String ev : mapper.handle(obj)) sb.append(ev);
        }
        for (String ev : mapper.finish()) sb.append(ev);
        return sb.toString();
    }

    @Test
    void textOnly() {
        assertEquals(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_loyw3v284fzzzxjy\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\" world\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                run("claude-sonnet", "[{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]},{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" world\"}]},\"finishReason\":\"STOP\"}]},{\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":5}}]"));
    }

    @Test
    void thinkingThenText_signatureDeltaOnClose() {
        assertEquals(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_loyw3v284fzzzxjy\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-thinking\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"let me think\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"antigravity-bridge\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"answer\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":1}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":0}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                run("claude-thinking", "[{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"thinking\":\"let me think\"}]}}]},{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"answer\"}]},\"finishReason\":\"STOP\"}]}]"));
    }

    @Test
    void reasoningTypePart() {
        assertEquals(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_loyw3v284fzzzxjy\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-thinking\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"reasoning text\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"antigravity-bridge\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":0}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                run("claude-thinking", "[{\"candidates\":[{\"content\":{\"parts\":[{\"type\":\"reasoning\",\"text\":\"reasoning text\"}]}}]}]"));
    }

    @Test
    void functionCall_toolUseWinsOverMaxTokens() {
        assertEquals(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_loyw3v284fzzzxjy\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_loyw3v284fzzzx\",\"name\":\"search\",\"input\":{}}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"q\\\":\\\"cats\\\"}\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":0}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                run("claude-sonnet", "[{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"search\",\"args\":{\"q\":\"cats\"}}}]},\"finishReason\":\"MAX_TOKENS\"}]}]"));
    }

    @Test
    void functionCall_noArgs() {
        assertEquals(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_loyw3v284fzzzxjy\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_loyw3v284fzzzx\",\"name\":\"ping\",\"input\":{}}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":0}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                run("claude-sonnet", "[{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"ping\"}}]}}]}]"));
    }

    @Test
    void mixedTextToolText_blockTransitions() {
        assertEquals(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_loyw3v284fzzzxjy\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"pre\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_loyw3v284fzzzx\",\"name\":\"f\",\"input\":{}}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":1}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"text_delta\",\"text\":\"post\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":2}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":0}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                run("claude-sonnet", "[{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"pre\"},{\"functionCall\":{\"name\":\"f\",\"args\":{}}},{\"text\":\"post\"}]}}]},{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}]"));
    }

    @Test
    void usageBeforeCandidate_emptyCandidatesArray() {
        assertEquals(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_loyw3v284fzzzxjy\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"x\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":0}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                run("claude-sonnet", "[{\"usageMetadata\":{\"promptTokenCount\":3}},{\"candidates\":[]},{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"x\"}]},\"finishReason\":\"STOP\"}]}]"));
    }

    @Test
    void emptyThinking_noBlockOpened() {
        assertEquals(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_loyw3v284fzzzxjy\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-thinking\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"only text\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":0}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                run("claude-thinking", "[{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"thinking\":\"\"}]}}]},{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"only text\"}]},\"finishReason\":\"STOP\"}]}]"));
    }

    @Test
    void consecutiveText_oneBlock() {
        assertEquals(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_loyw3v284fzzzxjy\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\nevent: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"a\"}}\n\nevent: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"b\"}}\n\nevent: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":0}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                run("claude-sonnet", "[{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"a\"},{\"text\":\"b\"}]}}]}]"));
    }

    @Test
    void emptyStream_flushOnly() {
        assertEquals(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_loyw3v284fzzzxjy\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":0}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
                run("claude-sonnet", "[]"));
    }
}
