package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.JsonCodec;
import io.github.intisy.ai.api.seam.HttpResponse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Handles the OK, non-streaming JSON branch of {@code transformAntigravityResponse}. The
 * orchestrator only emits a {@code SERVE} decision on a 2xx attempt, so this class handles ONLY
 * the {@code response.ok} branch; the {@code !response.ok} error branch (non-ok attempts never reach
 * SERVE) and the streaming SSE branch are out of scope.
 *
 * <p>Deviations: (1) {@code logCacheStats}/{@code logAntigravityDebugResponse} are logging-only with
 * no bearing on the returned response and are omitted. (2) the {@code sessionDisplayedThinkingHashes}
 * Gemini-3 SSE-reconnect thought-dedup set is streaming-only, so out of scope for this non-streaming
 * class. (3) {@code debugText} (the {@code isDebugTuiEnabled}/{@code getKeepThinking} placeholder) is
 * an injected parameter and the {@code processImageData} fs write is an injectable
 * {@link AntigravityThinkingBlocks.ImageSink}; a caller that needs neither uses the 3-arg overload
 * below ({@code null}/{@link #NO_OP_IMAGE_SINK}).
 */
public final class AntigravityResponseTransform {

    private static final AntigravityThinkingBlocks.ImageSink NO_OP_IMAGE_SINK = (mimeType, data) -> null;

    private AntigravityResponseTransform() {
    }

    /** 3-arg convenience overload: no debug-text injection, no image persistence. */
    public static HttpResponse transformServe(JsonCodec json, HttpResponse upstream,
                                               AntigravityHandleOrchestrator.TransformParams params) {
        return transformServe(json, upstream, params, null, NO_OP_IMAGE_SINK);
    }

    /**
     * Transforms one {@code SERVE}-decision upstream response. A non-JSON/event-stream content type
     * is returned verbatim; any exception during the transform falls back to returning {@code upstream}
     * verbatim. {@code debugText} (nullable/empty for "none") and {@code imageSink} are injected seams.
     */
    public static HttpResponse transformServe(JsonCodec json, HttpResponse upstream,
                                               AntigravityHandleOrchestrator.TransformParams params,
                                               String debugText, AntigravityThinkingBlocks.ImageSink imageSink) {
        if (upstream == null) {
            return null;
        }
        String contentType = headerValue(upstream.headers, "content-type");
        boolean isJsonResponse = contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("application/json");
        boolean isEventStreamResponse = contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains("text/event-stream");
        if (!isJsonResponse && !isEventStreamResponse) {
            return upstream;
        }

        try {
            return transformOkBody(json, upstream, params, debugText, imageSink);
        } catch (RuntimeException e) {
            return upstream;
        }
    }

    private static HttpResponse transformOkBody(JsonCodec json, HttpResponse upstream,
                                                 AntigravityHandleOrchestrator.TransformParams params,
                                                 String debugText, AntigravityThinkingBlocks.ImageSink imageSink) {
        String text = upstream.body;
        Object parsed = AntigravityResponseParse.parseAntigravityApiBody(json, text);
        if (!(parsed instanceof Map)) {
            // parseAntigravityApiBody returned null (unparseable text, or a cloudcode-pa array with no
            // object element): return `text` verbatim, which is `upstream` as-is. A bare-array unwrap
            // result is unreachable for real cloudcode-pa payloads and is treated the same way (never
            // crashes).
            return upstream;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsedMap = (Map<String, Object>) parsed;
        String requestedModel = params != null ? params.requestedModel : null;
        Map<String, Object> patched = AntigravityResponseParse.rewriteAntigravityPreviewAccessError(
                parsedMap, upstream.status, requestedModel);
        Map<String, Object> effectiveBody = patched != null ? patched : parsedMap;

        Map<String, String> headers = new LinkedHashMap<>(
                upstream.headers != null ? upstream.headers : Collections.<String, String>emptyMap());
        applyUsageHeaders(headers, AntigravityResponseParse.extractUsageMetadata(effectiveBody));

        // containsKey (not get()!=null): a present-but-null "response" key still counts as
        // `!== undefined` and must take this branch.
        if (effectiveBody.containsKey("response")) {
            Object responseBody = effectiveBody.get("response");
            if (debugText != null && !debugText.isEmpty()) {
                responseBody = AntigravityRequestSignatures.injectDebugThinking(responseBody, debugText);
            }
            Object transformed = AntigravityThinkingBlocks.transformThinkingParts(
                    responseBody,
                    value -> AntigravityResponseParse.recursivelyParseJsonStrings(json, value),
                    imageSink);
            return buildResponse(upstream.status, headers, json.stringify(transformed));
        }

        if (patched != null) {
            return buildResponse(upstream.status, headers, json.stringify(patched));
        }

        return buildResponse(upstream.status, headers, text);
    }

    private static void applyUsageHeaders(Map<String, String> headers, Map<String, Object> usage) {
        if (usage == null || usage.get("cachedContentTokenCount") == null) {
            return;
        }
        headers.put("x-antigravity-cached-content-token-count", String.valueOf(usage.get("cachedContentTokenCount")));
        if (usage.get("totalTokenCount") != null) {
            headers.put("x-antigravity-total-token-count", String.valueOf(usage.get("totalTokenCount")));
        }
        if (usage.get("promptTokenCount") != null) {
            headers.put("x-antigravity-prompt-token-count", String.valueOf(usage.get("promptTokenCount")));
        }
        if (usage.get("candidatesTokenCount") != null) {
            headers.put("x-antigravity-candidates-token-count", String.valueOf(usage.get("candidatesTokenCount")));
        }
    }

    private static HttpResponse buildResponse(int status, Map<String, String> headers, String body) {
        HttpResponse response = new HttpResponse();
        response.status = status;
        response.headers = headers;
        response.body = body;
        return response;
    }

    private static String headerValue(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
