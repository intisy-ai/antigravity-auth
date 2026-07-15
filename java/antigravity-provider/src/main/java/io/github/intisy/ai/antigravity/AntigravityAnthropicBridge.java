package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Java port of the buffered half of {@code handleAnthropicMessagesViaJava} ({@code
 * src/driver/javaHandle.ts:352-386}) + {@code geminiToAnthropicStream}'s {@code TransformStream}
 * line-buffering shell ({@code src/plugin/anthropic-bridge.ts:181-200}) -- see {@code
 * .superpowers/sdd/phase-4-brief.md}. Given the FULL upstream Gemini SSE body already buffered as
 * one {@link String} (the JVM {@code HttpResponse.body} is a {@code String}, no live streaming
 * transport -- DECISION FLAG B: buffered SSE), splits it into {@code data:} records exactly as the
 * real {@code transform} loop does, feeds each parsed Gemini object through the already-ported
 * {@link AntigravityStreamMapper} (the mapper IS {@code geminiToAnthropicStream}'s state machine,
 * T7d), then joins every emitted SSE event string into one buffered body.
 *
 * <h2>Line-splitting -- NOT {@code isMeaningfulSseLine}</h2>
 * The real {@code transform} loop ({@code anthropic-bridge.ts:184-192}) is simpler than {@link
 * AntigravityResponseParse#isMeaningfulSseLine}: a line is skipped when it is blank, starts with
 * {@code :} (an SSE comment), does not start with the literal {@code data:} (note: NO required
 * space -- {@code line.indexOf("data:") !== 0}), or whose payload (after {@code data:}, trimmed) is
 * empty or the literal {@code [DONE]}; a JSON-parse failure is caught and the line is silently
 * skipped ({@code catch (e) {}}). This class reproduces THAT exact filter, not {@code
 * isMeaningfulSseLine} (whose stricter "has a candidate with text/functionCall" gate would silently
 * drop a usage-metadata-only chunk -- the real {@code handleObj} still updates {@code outputTokens}
 * from a candidate-less {@code usageMetadata} chunk, exercised by {@code
 * AntigravityStreamMapperTest#usageBeforeCandidate_emptyCandidatesArray} and this class's own
 * {@code usageOnlyChunk_stillFlowsIntoUsage} test).
 *
 * <h2>cloudcode-pa's {@code response}-wrapped SSE shape</h2>
 * {@code handleAnthropicMessagesViaJava} targets {@code generativelanguage.googleapis.com} (the
 * public Gemini API, whose SSE {@code data:} payloads are the RAW {@code
 * {candidates,usageMetadata}} shape the mapper expects verbatim); this JVM provider instead keeps
 * the cloudcode-pa {@code v1internal} host per the brief's fallback decision (no OAuth-account-based
 * auth exists here for the public API). cloudcode-pa's own SSE payloads are wrapped one level
 * ({@code data: {"response": {candidates,usageMetadata}}}) -- confirmed by this repo's own {@code
 * src/plugin/core/streaming/transformer.ts#transformStreamingPayload}, which unwraps {@code
 * parsed.response} before re-emitting for a native Gemini client. This class mirrors that unwrap: a
 * parsed object carrying a {@code response} object is unwrapped before {@link
 * AntigravityStreamMapper#handle}; an already-unwrapped object (e.g. a future
 * {@code generativelanguage.googleapis.com} caller, or any of this class's own raw-shaped test
 * fixtures) is passed through unchanged. Disclosed adaptation -- no harness fixture captures a real
 * cloudcode-pa streaming payload, so this is inferred from the sibling transformer's own unwrap
 * precedent rather than a byte-exact captured parity case.
 *
 * <p>On any parse/transform exception this returns {@code upstreamGeminiSse} verbatim (never
 * throws), matching the class's own not-wired-yet safety net -- there is no TS equivalent of "the
 * whole bridge blew up" because the TS {@code TransformStream} can never throw past its own {@code
 * catch}.
 */
public final class AntigravityAnthropicBridge {

    private AntigravityAnthropicBridge() {
    }

    // javaHandle.ts:112,159 mint msg_.../toolu_... ids from Date.now()/Math.random() -- NOT
    // reproducible in Java (and not required to be: no production caller asserts on these bytes,
    // only the shape msg_.../toolu_... prefix matters). A random UUID keeps every real response's
    // message/tool ids unique without touching Date.now/Math.random.
    private static final AntigravityStreamMapper.IdGenerator RANDOM_IDS = new AntigravityStreamMapper.IdGenerator() {
        @Override
        public String newMessageId() {
            return "msg_" + UUID.randomUUID().toString().replace("-", "");
        }

        @Override
        public String newToolId() {
            return "toolu_" + UUID.randomUUID().toString().replace("-", "");
        }
    };

    /**
     * javaHandle.ts:384-385: pipes the buffered upstream Gemini SSE text through {@code
     * geminiToAnthropicStream} and always answers {@code text/event-stream} -- UNCONDITIONALLY,
     * regardless of the inbound client's own {@code stream} flag (javaHandle.ts:385 has no such
     * branch). On any parse/transform failure, returns {@code upstreamGeminiSse} verbatim.
     */
    public static HttpResponse geminiSseToAnthropic(JsonCodec json, String requestedModel, HttpResponse upstreamGeminiSse) {
        return geminiSseToAnthropic(json, requestedModel, upstreamGeminiSse, RANDOM_IDS);
    }

    /** Test seam: an injectable {@link AntigravityStreamMapper.IdGenerator} for deterministic ids. */
    static HttpResponse geminiSseToAnthropic(JsonCodec json, String requestedModel, HttpResponse upstreamGeminiSse,
                                              AntigravityStreamMapper.IdGenerator ids) {
        if (upstreamGeminiSse == null) {
            return null;
        }
        try {
            AntigravityStreamMapper mapper = new AntigravityStreamMapper(json, ids, requestedModel);
            StringBuilder out = new StringBuilder();
            for (String rawLine : splitLines(upstreamGeminiSse.body)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.charAt(0) == ':' || !line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                Object parsed;
                try {
                    parsed = json.parse(payload);
                } catch (RuntimeException e) {
                    continue; // anthropic-bridge.ts:191 -- skip partial/non-JSON
                }
                for (String event : mapper.handle(unwrapResponse(parsed))) {
                    out.append(event);
                }
            }
            for (String event : mapper.finish()) {
                out.append(event);
            }
            return buildResponse(out.toString());
        } catch (RuntimeException e) {
            return upstreamGeminiSse;
        }
    }

    // transformer.ts:45-46's `parsed.response !== undefined` unwrap, adapted for cloudcode-pa's
    // wrapped SSE shape (see class javadoc) -- only unwraps when `response` is itself an object, so
    // an already-unwrapped {candidates,...} chunk (no `response` key) passes through unchanged.
    private static Object unwrapResponse(Object parsed) {
        if (parsed instanceof Map && ((Map<?, ?>) parsed).get("response") instanceof Map) {
            return ((Map<?, ?>) parsed).get("response");
        }
        return parsed;
    }

    private static String[] splitLines(String body) {
        return (body != null ? body : "").split("\n", -1);
    }

    private static HttpResponse buildResponse(String body) {
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
