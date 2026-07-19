package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP-2 successor to the deleted {@code AntigravityStreamMapperTest}/{@code
 * AntigravityAnthropicBridgeTest}: exercises {@link AntigravityGeminiSseBridge}, now built on
 * core-ir's {@code GeminiTranslator} stream decoder + {@code AnthropicTranslator} stream encoder
 * instead of the bespoke {@code AntigravityStreamMapper} state machine.
 */
class AntigravityGeminiSseBridgeTest {

    private static final JsonCodec JSON = new TestJsonCodec();

    private static final AntigravityGeminiSseBridge.IdGenerator FIXED_IDS = new AntigravityGeminiSseBridge.IdGenerator() {
        @Override
        public String newMessageId() {
            return "msg_fixed";
        }

        @Override
        public String newToolId() {
            return "toolu_fixed";
        }
    };

    private static String sse(String data) {
        return "data: " + data + "\n\n";
    }

    @Test
    void textDelta_thenFinishReason_emitsFullMessage() {
        AntigravityGeminiSseBridge bridge = new AntigravityGeminiSseBridge(JSON, FIXED_IDS, "claude-sonnet-4");
        StringBuilder out = new StringBuilder();
        for (String ev : bridge.handle(sse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]},\"finishReason\":\"STOP\"}]}"))) {
            out.append(ev);
        }
        for (String ev : bridge.finish()) out.append(ev);
        String body = out.toString();

        assertTrue(body.contains("event: message_start"), body);
        assertTrue(body.contains("\"id\":\"msg_fixed\""), body);
        assertTrue(body.contains("\"model\":\"claude-sonnet-4\""), body);
        assertTrue(body.contains("\"type\":\"text_delta\",\"text\":\"hello\""), body);
        assertTrue(body.contains("event: content_block_stop"), body);
        assertTrue(body.contains("\"stop_reason\":\"end_turn\""), body);
        assertTrue(body.contains("event: message_stop"), body);
        // A real Anthropic message_start/message_delta always carries usage -- default-zeroed here
        // since this fixture's Gemini frame never reports usageMetadata (core-ir main@a57bdd5).
        assertTrue(body.contains("\"usage\":{\"input_tokens\":0,\"output_tokens\":0}"), body);
    }

    @Test
    void functionCall_getsFreshMintedToolUseId_regardlessOfWireId() {
        // The old bridge NEVER trusted a Gemini functionCall's own id (rarely present anyway),
        // always minting a fresh one -- this bridge preserves that policy explicitly (see class
        // javadoc), which also protects two parallel calls to the SAME tool name from colliding.
        AntigravityGeminiSseBridge bridge = new AntigravityGeminiSseBridge(JSON, FIXED_IDS, "gemini-3-pro");
        StringBuilder out = new StringBuilder();
        for (String ev : bridge.handle(sse("{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"functionCall\":{\"id\":\"wire-id-should-be-ignored\",\"name\":\"search\",\"args\":{\"q\":\"cats\"}}}"
                + "]},\"finishReason\":\"STOP\"}]}"))) {
            out.append(ev);
        }
        for (String ev : bridge.finish()) out.append(ev);
        String body = out.toString();

        assertTrue(body.contains("\"type\":\"tool_use\",\"id\":\"toolu_fixed\",\"name\":\"search\""), body);
        assertFalse(body.contains("wire-id-should-be-ignored"), body);
        assertTrue(body.contains("\"partial_json\":\"{\\\"q\\\":\\\"cats\\\"}\""), body);
    }

    @Test
    void thinkingPart_opensThinkingBlock_withSignature() {
        AntigravityGeminiSseBridge bridge = new AntigravityGeminiSseBridge(JSON, FIXED_IDS, "gemini-3-pro");
        StringBuilder out = new StringBuilder();
        for (String ev : bridge.handle(sse("{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"thought\":true,\"text\":\"pondering\",\"thoughtSignature\":\"sig-abc\"}"
                + "]},\"finishReason\":\"STOP\"}]}"))) {
            out.append(ev);
        }
        for (String ev : bridge.finish()) out.append(ev);
        String body = out.toString();

        assertTrue(body.contains("\"type\":\"thinking\",\"thinking\":\"\""), body);
        assertTrue(body.contains("\"type\":\"thinking_delta\",\"thinking\":\"pondering\""), body);
        assertTrue(body.contains("\"type\":\"signature_delta\",\"signature\":\"sig-abc\""), body);
    }

    @Test
    void emptyStream_noValidFrame_stillEmitsWellFormedScaffolding() {
        AntigravityGeminiSseBridge bridge = new AntigravityGeminiSseBridge(JSON, FIXED_IDS, "claude-sonnet-4");
        StringBuilder out = new StringBuilder();
        for (String ev : bridge.finish()) out.append(ev);
        String body = out.toString();

        assertTrue(body.contains("event: message_start"), body);
        assertTrue(body.contains("event: message_delta"), body);
        assertTrue(body.contains("event: message_stop"), body);
    }

    @Test
    void danglingOpenBlock_getsForceClosed_whenNoFinishReasonEverArrives() {
        // Connection ends mid-turn (upstream never sent a terminal finishReason chunk): the open
        // text block must still be closed before message_delta/message_stop, or the outbound
        // Anthropic stream would be malformed (a content_block_start with no matching stop).
        AntigravityGeminiSseBridge bridge = new AntigravityGeminiSseBridge(JSON, FIXED_IDS, "claude-sonnet-4");
        StringBuilder out = new StringBuilder();
        for (String ev : bridge.handle(sse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}"))) {
            out.append(ev);
        }
        for (String ev : bridge.finish()) out.append(ev);
        String body = out.toString();

        assertTrue(body.contains("event: content_block_stop"), body);
        assertTrue(body.contains("event: message_stop"), body);
    }

    // ---- buffered (ai-java ServiceLoader Provider path) --------------------------------------------

    @Test
    void bufferedGeminiSseToAnthropic_unwrapsCloudcodeResponseEnvelope() {
        HttpResponse upstream = new HttpResponse();
        upstream.status = 200;
        upstream.headers = new LinkedHashMap<>();
        upstream.body = sse("{\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}}");

        HttpResponse out = AntigravityGeminiSseBridge.bufferedGeminiSseToAnthropic(JSON, "claude-sonnet-4", upstream);

        assertEquals(200, out.status);
        assertEquals("text/event-stream", out.headers.get("content-type"));
        assertTrue(out.body.contains("\"text_delta\",\"text\":\"ok\""), out.body);
        assertFalse(out.body.contains("\"response\""), out.body);
    }

    @Test
    void bufferedGeminiSseToAnthropic_nullUpstream_returnsNull() {
        assertEquals(null, AntigravityGeminiSseBridge.bufferedGeminiSseToAnthropic(JSON, "m", null));
    }

    @Test
    void bufferedGeminiSseToAnthropic_emptyContent_fallsBackToUpstreamVerbatim() {
        // Upstream had bytes but they never opened a content block (envelope shape mismatch) --
        // same safety net as the deleted AntigravityAnthropicBridge.
        HttpResponse upstream = new HttpResponse();
        upstream.status = 200;
        upstream.headers = new LinkedHashMap<>();
        upstream.body = sse("{\"unexpectedShape\":true}");

        HttpResponse out = AntigravityGeminiSseBridge.bufferedGeminiSseToAnthropic(JSON, "m", upstream);
        assertEquals(upstream, out);
    }
}
