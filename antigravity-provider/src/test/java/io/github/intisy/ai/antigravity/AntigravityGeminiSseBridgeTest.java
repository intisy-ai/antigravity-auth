package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.IrStopReason;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.ir.ThinkingBlock;
import io.github.intisy.ai.ir.ToolUseBlock;
import io.github.intisy.ai.ir.stream.ContentBlockStartEvent;
import io.github.intisy.ai.ir.stream.ContentBlockStopEvent;
import io.github.intisy.ai.ir.stream.IrStreamEvent;
import io.github.intisy.ai.ir.stream.MessageDeltaEvent;
import io.github.intisy.ai.ir.stream.MessageStartEvent;
import io.github.intisy.ai.ir.stream.MessageStopEvent;
import io.github.intisy.ai.api.seam.JsonCodec;
import io.github.intisy.ai.api.seam.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link AntigravityGeminiSseBridge}, built on core-ir's {@code GeminiTranslator} stream
 * decoder. The bridge's only output is the canonical IR event stream (and its buffered
 * {@link IrResponse} aggregate); there is no vendor-wire encode step here.
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

    private static List<IrStreamEvent> allEvents(AntigravityGeminiSseBridge bridge, String chunk) {
        List<IrStreamEvent> events = new ArrayList<>(bridge.handleIrEvents(chunk));
        events.addAll(bridge.finishIrEvents());
        return events;
    }

    @Test
    void textDelta_thenFinishReason_emitsFullMessage() {
        AntigravityGeminiSseBridge bridge = new AntigravityGeminiSseBridge(JSON, FIXED_IDS, "claude-sonnet-4");
        List<IrStreamEvent> events = allEvents(bridge,
                sse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]},\"finishReason\":\"STOP\"}]}"));

        MessageStartEvent start = (MessageStartEvent) events.get(0);
        assertEquals("msg_fixed", start.id);
        assertEquals("claude-sonnet-4", start.model);
        assertTrue(events.stream().anyMatch(ev -> ev instanceof ContentBlockStopEvent));
        assertTrue(events.stream().anyMatch(ev -> ev instanceof MessageStopEvent));
        MessageDeltaEvent delta = (MessageDeltaEvent) events.stream()
                .filter(ev -> ev instanceof MessageDeltaEvent).findFirst().orElseThrow(NoSuchElementException::new);
        assertEquals(IrStopReason.END_TURN, delta.stopReason);

        IrResponse response = AntigravityGeminiSseBridge.aggregate(events, "claude-sonnet-4", JSON);
        assertEquals(1, response.content.size());
        assertEquals("hello", ((TextBlock) response.content.get(0)).text);
    }

    @Test
    void functionCall_getsFreshMintedToolUseId_regardlessOfWireId() {
        // The bridge never trusts a Gemini functionCall's own id (rarely present anyway), always
        // minting a fresh one, which also protects two parallel calls to the SAME tool name from
        // colliding.
        AntigravityGeminiSseBridge bridge = new AntigravityGeminiSseBridge(JSON, FIXED_IDS, "gemini-3-pro");
        List<IrStreamEvent> events = allEvents(bridge, sse("{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"functionCall\":{\"id\":\"wire-id-should-be-ignored\",\"name\":\"search\",\"args\":{\"q\":\"cats\"}}}"
                + "]},\"finishReason\":\"STOP\"}]}"));

        ContentBlockStartEvent toolStart = (ContentBlockStartEvent) events.stream()
                .filter(ev -> ev instanceof ContentBlockStartEvent).findFirst().orElseThrow(NoSuchElementException::new);
        assertEquals("toolu_fixed", toolStart.toolUseId);
        assertEquals("search", toolStart.toolName);

        IrResponse response = AntigravityGeminiSseBridge.aggregate(events, "gemini-3-pro", JSON);
        ToolUseBlock tool = (ToolUseBlock) response.content.get(0);
        assertEquals("toolu_fixed", tool.id);
        assertEquals("search", tool.name);
    }

    @Test
    void thinkingPart_opensThinkingBlock_withSignature() {
        AntigravityGeminiSseBridge bridge = new AntigravityGeminiSseBridge(JSON, FIXED_IDS, "gemini-3-pro");
        List<IrStreamEvent> events = allEvents(bridge, sse("{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"thought\":true,\"text\":\"pondering\",\"thoughtSignature\":\"sig-abc\"}"
                + "]},\"finishReason\":\"STOP\"}]}"));

        IrResponse response = AntigravityGeminiSseBridge.aggregate(events, "gemini-3-pro", JSON);
        ThinkingBlock thinking = (ThinkingBlock) response.content.get(0);
        assertEquals("pondering", thinking.text);
        assertEquals("sig-abc", thinking.signature);
    }

    @Test
    void emptyStream_noValidFrame_stillEmitsWellFormedScaffolding() {
        AntigravityGeminiSseBridge bridge = new AntigravityGeminiSseBridge(JSON, FIXED_IDS, "claude-sonnet-4");
        List<IrStreamEvent> events = bridge.finishIrEvents();

        assertTrue(events.get(0) instanceof MessageStartEvent);
        assertTrue(events.stream().anyMatch(ev -> ev instanceof MessageDeltaEvent));
        assertTrue(events.stream().anyMatch(ev -> ev instanceof MessageStopEvent));
    }

    @Test
    void danglingOpenBlock_getsForceClosed_whenNoFinishReasonEverArrives() {
        // Connection ends mid-turn (upstream never sent a terminal finishReason chunk): the open
        // text block must still be closed before message_delta/message_stop, or the outbound IR
        // event stream would be malformed (a content_block_start with no matching stop).
        AntigravityGeminiSseBridge bridge = new AntigravityGeminiSseBridge(JSON, FIXED_IDS, "claude-sonnet-4");
        List<IrStreamEvent> events = allEvents(bridge, sse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}"));

        assertTrue(events.stream().anyMatch(ev -> ev instanceof ContentBlockStopEvent));
        assertTrue(events.stream().anyMatch(ev -> ev instanceof MessageStopEvent));
    }

    // ---- buffered (ai-java ServiceLoader Provider path) --------------------------------------------

    @Test
    void bufferedGeminiSseToIr_unwrapsCloudcodeResponseEnvelope() {
        HttpResponse upstream = new HttpResponse();
        upstream.status = 200;
        upstream.headers = new LinkedHashMap<>();
        upstream.body = sse("{\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}}");

        IrResponse response = AntigravityGeminiSseBridge.bufferedGeminiSseToIr(JSON, "claude-sonnet-4", upstream);

        assertEquals("claude-sonnet-4", response.model);
        assertEquals(1, response.content.size());
        assertEquals("ok", ((TextBlock) response.content.get(0)).text);
    }

    @Test
    void bufferedGeminiSseToIr_nullUpstream_throws() {
        try {
            AntigravityGeminiSseBridge.bufferedGeminiSseToIr(JSON, "m", null);
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected: handleIr has no wire response to fall back to
        }
    }

    @Test
    void bufferedGeminiSseToIr_emptyContent_throws() {
        // Upstream had bytes but they never opened a content block (envelope shape mismatch), so
        // this must surface as a thrown decode error rather than silently returning empty content.
        HttpResponse upstream = new HttpResponse();
        upstream.status = 200;
        upstream.headers = new LinkedHashMap<>();
        upstream.body = sse("{\"unexpectedShape\":true}");

        try {
            AntigravityGeminiSseBridge.bufferedGeminiSseToIr(JSON, "m", upstream);
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }
    }
}
