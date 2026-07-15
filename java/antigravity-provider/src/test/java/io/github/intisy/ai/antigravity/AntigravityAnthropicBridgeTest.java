package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AntigravityAnthropicBridge#geminiSseToAnthropic}, per {@code
 * .superpowers/sdd/phase-4-brief.md}'s test plan. Every "happy" case feeds a buffered {@code
 * data:}-line SSE body (the shape the JVM's {@code AttemptExecutor} hands back verbatim) and
 * asserts the joined Anthropic-event body + headers; the {@link AntigravityStreamMapper}'s own
 * event-shape parity is already covered by {@code AntigravityStreamMapperTest} -- these tests only
 * exercise the SSE-splitting/unwrap/response-shell layer this class adds on top.
 */
class AntigravityAnthropicBridgeTest {

    private static final JsonCodec JSON = new TestJsonCodec();
    private static final String MSG_ID = "msg_replay0000000";
    private static final String TOOL_ID = "toolu_replay00000";

    private static final AntigravityStreamMapper.IdGenerator REPLAY_IDS = new AntigravityStreamMapper.IdGenerator() {
        @Override
        public String newMessageId() {
            return MSG_ID;
        }

        @Override
        public String newToolId() {
            return TOOL_ID;
        }
    };

    private static HttpResponse upstream(String body) {
        HttpResponse r = new HttpResponse();
        r.status = 200;
        r.headers = new LinkedHashMap<>();
        r.headers.put("content-type", "text/event-stream");
        r.body = body;
        return r;
    }

    private static HttpResponse bridge(String body) {
        return AntigravityAnthropicBridge.geminiSseToAnthropic(JSON, "claude-sonnet", upstream(body), REPLAY_IDS);
    }

    // ---- test plan 1: text response ---------------------------------------------------------------

    @Test
    void textResponse_emitsAnthropicTextBlock() {
        HttpResponse resp = bridge(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]},\"finishReason\":\"STOP\"}]}\n\n");

        assertEquals(200, resp.status);
        assertEquals("text/event-stream", resp.headers.get("content-type"));
        assertEquals("no-cache", resp.headers.get("cache-control"));
        assertEquals("keep-alive", resp.headers.get("connection"));
        assertTrue(resp.body.contains("event: message_start"));
        assertTrue(resp.body.contains("\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}"));
        assertTrue(resp.body.contains("\"type\":\"text_delta\",\"text\":\"hi\""));
        assertTrue(resp.body.contains("event: content_block_stop"));
        assertTrue(resp.body.contains("\"stop_reason\":\"end_turn\""));
        assertTrue(resp.body.contains("event: message_stop"));
    }

    // ---- test plan 2: tool call ---------------------------------------------------------------------

    @Test
    void toolCall_emitsToolUseBlock_stopReasonToolUse() {
        HttpResponse resp = bridge(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"search\",\"args\":{\"q\":\"cats\"}}}]}}]}\n\n");

        assertTrue(resp.body.contains("\"type\":\"tool_use\",\"id\":\"" + TOOL_ID + "\",\"name\":\"search\""));
        assertTrue(resp.body.contains("\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"q\\\":\\\"cats\\\"}\""));
        assertTrue(resp.body.contains("\"stop_reason\":\"tool_use\""));
    }

    // ---- test plan 3: thinking ------------------------------------------------------------------

    @Test
    void thinkingPart_emitsThinkingBlock_withSignatureDeltaOnClose() {
        HttpResponse resp = bridge(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"thinking\":\"pondering\"}]}}]}\n\n"
                        + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]},\"finishReason\":\"STOP\"}]}\n\n");

        assertTrue(resp.body.contains("\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}"));
        assertTrue(resp.body.contains("\"type\":\"thinking_delta\",\"thinking\":\"pondering\""));
        assertTrue(resp.body.contains("\"type\":\"signature_delta\",\"signature\":\"antigravity-bridge\""));
    }

    // ---- test plan 4: usage --------------------------------------------------------------------

    @Test
    void usageMetadata_flowsIntoMessageDeltaUsage() {
        HttpResponse resp = bridge(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"x\"}]},\"finishReason\":\"STOP\"}]}\n\n"
                        + "data: {\"usageMetadata\":{\"promptTokenCount\":7,\"candidatesTokenCount\":3}}\n\n");

        assertTrue(resp.body.contains("\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":3}"));
    }

    @Test
    void usageOnlyChunk_withNoCandidates_stillFlowsIntoUsage() {
        // Real handleObj (anthropic-bridge.ts:145-151) reads usageMetadata BEFORE checking
        // `cand`, so a candidate-less usage chunk still updates outputTokens -- isMeaningfulSseLine
        // would have wrongly dropped this line (see class javadoc); this must NOT use that filter.
        HttpResponse resp = bridge(
                "data: {\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":4}}\n\n"
                        + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"y\"}]},\"finishReason\":\"STOP\"}]}\n\n");

        assertTrue(resp.body.contains("\"usage\":{\"output_tokens\":4}"),
                "usage-only chunk (no candidates) must still update output_tokens: " + resp.body);
    }

    // ---- test plan 5: multi-chunk buffered SSE ---------------------------------------------------

    @Test
    void multiChunk_blocksOpenAndCloseAcrossChunks() {
        HttpResponse resp = bridge(
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"pre\"}]}}]}\n\n"
                        + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"f\",\"args\":{}}}]}}]}\n\n"
                        + "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"post\"}]},\"finishReason\":\"MAX_TOKENS\"}]}\n\n");

        assertTrue(resp.body.contains("\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}"));
        assertTrue(resp.body.contains("\"index\":1,\"content_block\":{\"type\":\"tool_use\""));
        assertTrue(resp.body.contains("\"index\":2,\"content_block\":{\"type\":\"text\",\"text\":\"\"}"));
        assertTrue(resp.body.contains("\"text_delta\",\"text\":\"post\""));
        // tool_use wins over MAX_TOKENS (mapper precedence, honored end-to-end).
        assertTrue(resp.body.contains("\"stop_reason\":\"tool_use\""));
    }

    // ---- cloudcode-pa response-wrapped shape ------------------------------------------------------

    @Test
    void responseWrappedChunk_isUnwrappedBeforeMapping() {
        HttpResponse resp = bridge(
                "data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"wrapped\"}]},\"finishReason\":\"STOP\"}]}}\n\n");

        assertTrue(resp.body.contains("\"text_delta\",\"text\":\"wrapped\""));
        assertFalse(resp.body.contains("\"response\""), "the .response envelope must never leak into the Anthropic events");
    }

    // ---- SSE line filter parity with anthropic-bridge.ts:184-192 --------------------------------

    @Test
    void nonDataLines_commentsAndDoneSentinel_areIgnored() {
        HttpResponse resp = bridge(
                ": keep-alive comment\n\n"
                        + "data: [DONE]\n\n"
                        + "data:{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"z\"}]},\"finishReason\":\"STOP\"}]}\n\n");

        // "data:" with no space must still be recognized (line.indexOf("data:") !== 0 in the TS).
        assertTrue(resp.body.contains("\"text_delta\",\"text\":\"z\""));
    }

    // ---- test plan 6: exception safety ------------------------------------------------------------

    @Test
    void malformedJsonLine_isSkipped_neverThrows() {
        HttpResponse resp = bridge("data: {not json\n\ndata: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}\n\n");

        assertTrue(resp.body.contains("\"text_delta\",\"text\":\"ok\""));
    }

    @Test
    void nullUpstream_returnsNull() {
        assertNull(AntigravityAnthropicBridge.geminiSseToAnthropic(JSON, "m", null));
    }

    @Test
    void mapperThrows_fallsBackToUpstreamVerbatim() {
        HttpResponse upstream = upstream("data: {\"candidates\":\"not-a-list-but-a-string\"}\n\n");
        // A JsonCodec whose stringify always blows up forces mapper.handle's internal
        // json.stringify(args) (functionCall branch) -- but simpler: force parse() to throw only
        // for the SECOND call (finish() never parses); instead assert via a poison JsonCodec whose
        // stringify throws, tripping the outer catch around the whole method.
        JsonCodec poison = new JsonCodec() {
            @Override
            public Object parse(String jsonText) {
                return JSON.parse(jsonText);
            }

            @Override
            public String stringify(Object value) {
                throw new RuntimeException("boom");
            }
        };
        HttpResponse resp = AntigravityAnthropicBridge.geminiSseToAnthropic(poison, "m", upstream, REPLAY_IDS);
        assertSame(upstream, resp, "on any parse/transform exception the upstream must be returned verbatim");
    }

    // ---- empty stream (flush only) ----------------------------------------------------------------

    @Test
    void emptyBody_stillEmitsMessageStartAndStop() {
        HttpResponse resp = bridge("");
        assertTrue(resp.body.contains("event: message_start"));
        assertTrue(resp.body.contains("event: message_stop"));
    }

    // ---- empty-content safety net (shape-mismatch guard) -----------------------------------------

    @Test
    void shapeMismatch_parsedLinesWithNoCandidates_fallsBackToUpstreamVerbatim() {
        // Simulates an upstream envelope shape unwrapResponse doesn't anticipate: every data: line
        // parses as valid JSON, but none carries a `candidates` array, so handleObj's `if (!cand)
        // return` fires every time and the mapper would otherwise emit only the message_start/
        // message_delta/message_stop scaffolding -- a look-alike 200 SSE with zero content blocks
        // and no error signal. The guard must detect that and return the raw upstream instead.
        HttpResponse upstream = upstream(
                "data: {\"foo\":\"bar\"}\n\n" + "data: {\"anotherShape\":{\"nested\":true}}\n\n");
        HttpResponse resp = AntigravityAnthropicBridge.geminiSseToAnthropic(JSON, "m", upstream, REPLAY_IDS);

        assertSame(upstream, resp,
                "a non-empty upstream that parsed but produced zero content blocks must fall back "
                        + "to the raw upstream response, not a contentless look-alike SSE body");
    }

    // ---- production id generator shape ------------------------------------------------------------

    @Test
    void productionIdGenerator_usesMsgAndTooluPrefixes_notReplayIds() {
        HttpResponse resp = AntigravityAnthropicBridge.geminiSseToAnthropic(JSON, "claude-sonnet",
                upstream("data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"f\"}}]}}]}\n\n"));
        assertTrue(resp.body.contains("\"id\":\"msg_"));
        assertTrue(resp.body.contains("\"id\":\"toolu_"));
        assertFalse(resp.body.contains(MSG_ID), "production path must not use the test's replay id");
    }
}
