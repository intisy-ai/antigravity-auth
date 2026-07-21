package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.ir.Block;
import io.github.intisy.ai.ir.IrResponse;
import io.github.intisy.ai.ir.IrStopReason;
import io.github.intisy.ai.ir.TextBlock;
import io.github.intisy.ai.ir.ThinkingBlock;
import io.github.intisy.ai.ir.ToolUseBlock;
import io.github.intisy.ai.ir.stream.ContentBlockKind;
import io.github.intisy.ai.ir.stream.ContentBlockStartEvent;
import io.github.intisy.ai.ir.stream.ContentBlockStopEvent;
import io.github.intisy.ai.ir.stream.IrStreamEvent;
import io.github.intisy.ai.ir.stream.MessageDeltaEvent;
import io.github.intisy.ai.ir.stream.MessageStartEvent;
import io.github.intisy.ai.ir.stream.MessageStopEvent;
import io.github.intisy.ai.ir.stream.TextDeltaEvent;
import io.github.intisy.ai.ir.stream.ThinkingDeltaEvent;
import io.github.intisy.ai.ir.stream.ThinkingSignatureEvent;
import io.github.intisy.ai.ir.stream.ToolInputDeltaEvent;
import io.github.intisy.ai.ir.spi.StreamDecoder;
import io.github.intisy.ai.ir.spi.StreamEncoder;
import io.github.intisy.ai.ir.translators.anthropic.AnthropicTranslator;
import io.github.intisy.ai.ir.translators.gemini.GeminiTranslator;
import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A stateful per-connection bridge from upstream Gemini {@code streamGenerateContent} SSE to
 * outbound Anthropic Messages SSE, built on core-ir's translators: {@link
 * GeminiTranslator#newStreamDecoder()} owns the line-buffering + per-part event state machine, and
 * {@link AnthropicTranslator#newStreamEncoder()} owns the outbound SSE framing.
 *
 * <h2>Id minting (provider policy)</h2>
 * A real Gemini {@code functionCall} rarely carries an {@code id} (core-ir falls back to the tool
 * NAME, matching Gemini's own name-based pairing) and cloudcode-pa's {@code responseId} is not
 * guaranteed either, so this bridge never trusts the wire for either id: it always mints a fresh
 * {@code msg_.../toolu_...} via the injected {@link IdGenerator} (real {@code Date.now}/{@code
 * Math.random} host-side), overwriting the decoded {@link MessageStartEvent#id}/{@link
 * ContentBlockStartEvent#toolUseId} before encoding rather than trusting whatever core-ir's decoder
 * derived from the wire. This is necessary so two parallel {@code functionCall}s to the SAME tool
 * name in one turn still get distinct {@code tool_use} ids (a real regression risk if the
 * wire-derived name were used verbatim).
 *
 * <p>{@link MessageStartEvent#model} is likewise overwritten with the CLIENT-facing model name
 * (the model Claude Code requested), never Gemini's own {@code modelVersion} string.
 *
 * <h2>Abnormal termination</h2>
 * By Gemini API contract only the terminal chunk of a candidate carries {@code finishReason},
 * which is what closes any open content block + emits {@code message_delta}/{@code message_stop}
 * (inside {@code GeminiTranslator}'s stream decoder itself, so no separate flush hook is needed for
 * a well-formed stream). {@link #finish()} covers the abnormal case (connection ends before a
 * {@code finishReason} ever arrives): this class tracks the last unmatched {@link
 * ContentBlockStartEvent} itself (core-ir's decoder does not expose that state), so it can still
 * force-close a dangling open block before emitting the closing {@code message_delta}/{@code
 * message_stop}, and mints the empty-stream scaffolding if zero valid frames ever arrived at all
 * (message_start never fired).
 */
public final class AntigravityGeminiSseBridge {

    /** Injected id source: {@code msg_...} minted once per stream, {@code toolu_...} per functionCall. */
    public interface IdGenerator {
        String newMessageId();

        String newToolId();
    }

    private final StreamDecoder decoder;
    private final StreamEncoder encoder;
    private final IdGenerator ids;
    private final String model;

    private boolean sawMessageStart = false;
    private boolean sawMessageStop = false;
    private boolean mintedMessageId = false;
    private Integer openBlockIndex = null;

    public AntigravityGeminiSseBridge(JsonCodec routingJson, IdGenerator ids, String model) {
        io.github.intisy.ai.ir.spi.JsonCodec irJson = new IrJsonCodecAdapter(routingJson);
        this.decoder = new GeminiTranslator(irJson).newStreamDecoder();
        this.encoder = new AnthropicTranslator(irJson).newStreamEncoder();
        this.ids = ids;
        this.model = model;
    }

    /** Feed one raw SSE text chunk (partial or complete lines); returns the Anthropic SSE frames it produced. */
    public List<String> handle(String chunk) {
        return encodeAll(decoder.decode(chunk));
    }

    /**
     * Flush: force-closes a content block left open by a connection that ended before a {@code
     * finishReason} ever arrived, then the empty-stream safety net (mints {@code message_start}
     * first if NOTHING valid ever arrived), then closes out with {@code message_delta}/{@code
     * message_stop} unless that already happened (a well-formed stream closes itself inside {@link
     * #handle}).
     */
    public List<String> finish() {
        List<String> out = new ArrayList<>();
        for (IrStreamEvent ev : finishIrEvents()) {
            out.add(encoder.encode(ev));
        }
        return out;
    }

    /**
     * The enrichment half of {@link #encodeAll} without the Anthropic encode step: feeds one raw
     * Gemini SSE text chunk through the decoder and returns the SAME id-minted/model-overwritten
     * {@link IrStreamEvent}s {@link #handle} would encode, for a caller that wants the neutral IR
     * event stream itself (the IR-native {@code handleIr} boundary) instead of Anthropic wire text.
     */
    public List<IrStreamEvent> handleIrEvents(String chunk) {
        return enrichAll(decoder.decode(chunk));
    }

    /** {@link #finish()} without the Anthropic encode step; see {@link #handleIrEvents}. */
    public List<IrStreamEvent> finishIrEvents() {
        List<IrStreamEvent> out = new ArrayList<>();
        if (!sawMessageStart) {
            MessageStartEvent mse = new MessageStartEvent();
            mse.role = "assistant";
            out.addAll(enrichAll(Collections.<IrStreamEvent>singletonList(mse)));
        }
        if (openBlockIndex != null) {
            ContentBlockStopEvent stop = new ContentBlockStopEvent();
            stop.index = openBlockIndex;
            out.addAll(enrichAll(Collections.<IrStreamEvent>singletonList(stop)));
        }
        if (!sawMessageStop) {
            MessageDeltaEvent mde = new MessageDeltaEvent();
            mde.stopReason = IrStopReason.END_TURN;
            out.addAll(enrichAll(Arrays.<IrStreamEvent>asList(mde, new MessageStopEvent())));
        }
        return out;
    }

    // ---- shared state-tracking/id-minting enrichment (both handle()/finish() and the IR-native --
    // handleIrEvents()/finishIrEvents() build on this SAME pass; only the wire-encode step differs) --

    private List<IrStreamEvent> enrichAll(List<IrStreamEvent> events) {
        for (IrStreamEvent ev : events) {
            if (ev instanceof MessageStartEvent) {
                sawMessageStart = true;
                MessageStartEvent mse = (MessageStartEvent) ev;
                if (!mintedMessageId) {
                    mse.id = ids.newMessageId();
                    mintedMessageId = true;
                }
                mse.model = model;
            } else if (ev instanceof ContentBlockStartEvent) {
                ContentBlockStartEvent cbs = (ContentBlockStartEvent) ev;
                if (ContentBlockKind.TOOL_USE.equals(cbs.blockKind)) {
                    cbs.toolUseId = ids.newToolId();
                }
                openBlockIndex = cbs.index;
            } else if (ev instanceof ContentBlockStopEvent) {
                if (openBlockIndex != null && openBlockIndex == ((ContentBlockStopEvent) ev).index) {
                    openBlockIndex = null;
                }
            } else if (ev instanceof MessageStopEvent) {
                sawMessageStop = true;
            }
        }
        return events;
    }

    private List<String> encodeAll(List<IrStreamEvent> events) {
        List<String> out = new ArrayList<>();
        for (IrStreamEvent ev : enrichAll(events)) {
            out.add(encoder.encode(ev));
        }
        return out;
    }

    // ---- buffered variant (the ai-java ServiceLoader Provider path only) --------------------------

    /**
     * Buffered equivalent for {@link AntigravityProvider}'s raw {@code Provider} SPI path (no live
     * streaming transport there, so the JVM {@code HttpResponse.body} is already a fully buffered
     * {@link String}). Also undoes cloudcode-pa's one-level {@code response} SSE wrapping ({@code
     * data: {"response": {candidates,...}}}) before handing lines to the decoder, since core-ir's
     * Gemini stream decoder models the NATIVE (unwrapped) {@code streamGenerateContent} shape.
     */
    // The host mints msg_.../toolu_... ids from Date.now()/Math.random(), not reproducible in Java
    // (and not required to be: no caller asserts on these bytes, only the msg_.../toolu_... prefix
    // shape matters). A random UUID keeps every response's ids unique.
    private static final IdGenerator RANDOM_IDS = new IdGenerator() {
        @Override
        public String newMessageId() {
            return "msg_" + java.util.UUID.randomUUID().toString().replace("-", "");
        }

        @Override
        public String newToolId() {
            return "toolu_" + java.util.UUID.randomUUID().toString().replace("-", "");
        }
    };

    /** {@link #bufferedGeminiSseToAnthropic(JsonCodec, String, HttpResponse, IdGenerator)} with random ids. */
    public static HttpResponse bufferedGeminiSseToAnthropic(JsonCodec routingJson, String requestedModel,
                                                             HttpResponse upstreamGeminiSse) {
        return bufferedGeminiSseToAnthropic(routingJson, requestedModel, upstreamGeminiSse, RANDOM_IDS);
    }

    public static HttpResponse bufferedGeminiSseToAnthropic(JsonCodec routingJson, String requestedModel,
                                                             HttpResponse upstreamGeminiSse, IdGenerator ids) {
        if (upstreamGeminiSse == null) {
            return null;
        }
        try {
            String unwrapped = unwrapCloudcodeResponseEnvelope(routingJson, upstreamGeminiSse.body);
            AntigravityGeminiSseBridge bridge = new AntigravityGeminiSseBridge(routingJson, ids, requestedModel);
            StringBuilder out = new StringBuilder();
            for (String ev : bridge.handle(unwrapped)) out.append(ev);
            for (String ev : bridge.finish()) out.append(ev);
            String body = out.toString();

            boolean upstreamHadContent = upstreamGeminiSse.body != null && !upstreamGeminiSse.body.trim().isEmpty();
            boolean producedContent = body.contains("content_block_start");
            if (upstreamHadContent && !producedContent) {
                // Empty-content safety net: the upstream had bytes but the decoder never opened a
                // single content block, which almost always means the envelope shape did not match
                // what was expected, not a genuinely empty turn.
                return upstreamGeminiSse;
            }
            return buildSseResponse(body);
        } catch (RuntimeException e) {
            return upstreamGeminiSse;
        }
    }

    /**
     * {@code handleIr}'s response-side counterpart to {@link #bufferedGeminiSseToAnthropic}:
     * decodes the buffered upstream Gemini SSE body into a single, aggregated {@link IrResponse}
     * (no Anthropic re-encoding), for a caller working at the neutral IR boundary. Reuses the SAME
     * unwrap + id-minting policy as the Anthropic path. Unlike the Anthropic variant (which falls
     * back to the raw upstream response on any decode trouble, since a caller there always has a
     * wire-shaped fallback to serve), this throws: {@code handleIr} has no wire response to fall
     * back to, so a decode failure must surface as a thrown error.
     */
    public static IrResponse bufferedGeminiSseToIr(JsonCodec routingJson, String requestedModel,
                                                    HttpResponse upstreamGeminiSse) {
        return bufferedGeminiSseToIr(routingJson, requestedModel, upstreamGeminiSse, RANDOM_IDS);
    }

    public static IrResponse bufferedGeminiSseToIr(JsonCodec routingJson, String requestedModel,
                                                    HttpResponse upstreamGeminiSse, IdGenerator ids) {
        if (upstreamGeminiSse == null) {
            throw new IllegalStateException("antigravity upstream response missing");
        }
        String unwrapped = unwrapCloudcodeResponseEnvelope(routingJson, upstreamGeminiSse.body);
        AntigravityGeminiSseBridge bridge = new AntigravityGeminiSseBridge(routingJson, ids, requestedModel);
        List<IrStreamEvent> events = new ArrayList<>();
        events.addAll(bridge.handleIrEvents(unwrapped));
        events.addAll(bridge.finishIrEvents());

        boolean upstreamHadContent = upstreamGeminiSse.body != null && !upstreamGeminiSse.body.trim().isEmpty();
        boolean producedContent = false;
        for (IrStreamEvent ev : events) {
            if (ev instanceof ContentBlockStartEvent) {
                producedContent = true;
                break;
            }
        }
        if (upstreamHadContent && !producedContent) {
            // Same empty-content safety net as the Anthropic variant: the upstream had bytes but the
            // decoder never opened a single content block, almost always an envelope-shape
            // mismatch, not a genuinely empty turn.
            throw new IllegalStateException("antigravity upstream response did not decode to any content");
        }
        return aggregate(events, requestedModel, routingJson);
    }

    /**
     * Folds a flat, already-enriched {@link IrStreamEvent} sequence (as produced by {@link
     * #handleIrEvents}/{@link #finishIrEvents}) into one buffered {@link IrResponse}: content
     * blocks assembled by index from their start/delta/stop events, final {@code stopReason}/
     * {@code usage} from the last {@link MessageDeltaEvent}.
     */
    private static IrResponse aggregate(List<IrStreamEvent> events, String model, JsonCodec routingJson) {
        IrResponse response = new IrResponse();
        response.model = model;
        Map<Integer, Block> blocksByIndex = new LinkedHashMap<>();
        Map<Integer, StringBuilder> textByIndex = new LinkedHashMap<>();
        Map<Integer, StringBuilder> thinkingByIndex = new LinkedHashMap<>();
        Map<Integer, StringBuilder> toolInputByIndex = new LinkedHashMap<>();
        List<Integer> order = new ArrayList<>();

        for (IrStreamEvent ev : events) {
            if (ev instanceof MessageStartEvent) {
                MessageStartEvent mse = (MessageStartEvent) ev;
                if (mse.id != null) response.id = mse.id;
                if (mse.model != null) response.model = mse.model;
                if (mse.usage != null) response.usage = mse.usage;
            } else if (ev instanceof ContentBlockStartEvent) {
                ContentBlockStartEvent cbs = (ContentBlockStartEvent) ev;
                order.add(cbs.index);
                if (ContentBlockKind.TEXT.equals(cbs.blockKind)) {
                    blocksByIndex.put(cbs.index, new TextBlock());
                    textByIndex.put(cbs.index, new StringBuilder());
                } else if (ContentBlockKind.THINKING.equals(cbs.blockKind)) {
                    blocksByIndex.put(cbs.index, new ThinkingBlock());
                    thinkingByIndex.put(cbs.index, new StringBuilder());
                } else if (ContentBlockKind.TOOL_USE.equals(cbs.blockKind)) {
                    ToolUseBlock block = new ToolUseBlock();
                    block.id = cbs.toolUseId;
                    block.name = cbs.toolName;
                    blocksByIndex.put(cbs.index, block);
                    toolInputByIndex.put(cbs.index, new StringBuilder());
                }
            } else if (ev instanceof TextDeltaEvent) {
                TextDeltaEvent td = (TextDeltaEvent) ev;
                StringBuilder sb = textByIndex.get(td.index);
                if (sb != null && td.text != null) sb.append(td.text);
            } else if (ev instanceof ThinkingDeltaEvent) {
                ThinkingDeltaEvent thd = (ThinkingDeltaEvent) ev;
                StringBuilder sb = thinkingByIndex.get(thd.index);
                if (sb != null && thd.text != null) sb.append(thd.text);
            } else if (ev instanceof ThinkingSignatureEvent) {
                ThinkingSignatureEvent tse = (ThinkingSignatureEvent) ev;
                Block block = blocksByIndex.get(tse.index);
                if (block instanceof ThinkingBlock) {
                    ((ThinkingBlock) block).signature = tse.signature;
                }
            } else if (ev instanceof ToolInputDeltaEvent) {
                ToolInputDeltaEvent tid = (ToolInputDeltaEvent) ev;
                StringBuilder sb = toolInputByIndex.get(tid.index);
                if (sb != null && tid.partialJson != null) sb.append(tid.partialJson);
            } else if (ev instanceof ContentBlockStopEvent) {
                int index = ((ContentBlockStopEvent) ev).index;
                Block block = blocksByIndex.get(index);
                if (block instanceof TextBlock) {
                    ((TextBlock) block).text = textByIndex.get(index).toString();
                } else if (block instanceof ThinkingBlock) {
                    ((ThinkingBlock) block).text = thinkingByIndex.get(index).toString();
                } else if (block instanceof ToolUseBlock) {
                    String rawInput = toolInputByIndex.get(index).toString();
                    ((ToolUseBlock) block).input = rawInput.isEmpty() ? new LinkedHashMap<>() : routingJson.parse(rawInput);
                }
            } else if (ev instanceof MessageDeltaEvent) {
                MessageDeltaEvent mde = (MessageDeltaEvent) ev;
                if (mde.stopReason != null) response.stopReason = mde.stopReason;
                if (mde.usage != null) response.usage = mde.usage;
            }
            // MessageStopEvent carries no data of its own.
        }

        List<Block> content = new ArrayList<>();
        for (Integer index : order) {
            Block block = blocksByIndex.get(index);
            if (block != null) content.add(block);
        }
        response.content = content;
        return response;
    }

    private static String unwrapCloudcodeResponseEnvelope(JsonCodec routingJson, String body) {
        if (body == null) return null;
        String[] lines = body.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                String payload = trimmed.substring(5).trim();
                if (!payload.isEmpty() && !"[DONE]".equals(payload)) {
                    try {
                        Object parsed = routingJson.parse(payload);
                        if (parsed instanceof Map && ((Map<?, ?>) parsed).get("response") instanceof Map) {
                            line = "data: " + routingJson.stringify(((Map<?, ?>) parsed).get("response"));
                        }
                    } catch (RuntimeException ignored) {
                        // leave the line untouched; the stream decoder will skip an unparsable line too
                    }
                }
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private static HttpResponse buildSseResponse(String body) {
        HttpResponse response = new HttpResponse();
        response.status = 200;
        response.headers = new LinkedHashMap<>();
        response.headers.put("content-type", "text/event-stream");
        response.headers.put("cache-control", "no-cache");
        response.headers.put("connection", "keep-alive");
        response.body = body;
        return response;
    }
}
