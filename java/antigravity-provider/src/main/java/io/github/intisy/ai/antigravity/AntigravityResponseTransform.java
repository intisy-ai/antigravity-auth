package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Java port of the OK, non-streaming JSON branch of {@code transformAntigravityResponse}
 * (src/plugin/request.ts:1711-1938) -- see {@code .superpowers/sdd/phase-3b-brief.md}. The
 * orchestrator only ever emits a {@code SERVE} decision on a 2xx attempt, so this class ports ONLY
 * the {@code response.ok} branch (request.ts:1863-1926); the {@code !response.ok} error branch
 * (L1788-1861, non-ok attempts never reach SERVE) and the streaming SSE branch (L1751-1780, Phase 4)
 * are out of scope.
 *
 * <p>Disclosed deviations: (1) {@code debugText} (the {@code isDebugTuiEnabled}/{@code
 * getKeepThinking} placeholder injected via {@code AntigravityRequestSignatures.injectDebugThinking})
 * is NOT wired -- deferred past Phase 3b, matching this provider's existing Bucket-C deferrals (e.g.
 * {@code AntigravityProvider}'s {@code NO_CACHED_SIGNATURE}/{@code NOOP_SIGNATURE_STORE}). (2) {@code
 * logCacheStats}/{@code logAntigravityDebugResponse} are logging-only TS calls with no bearing on the
 * returned response and are omitted. (3) The {@link AntigravityThinkingBlocks.ImageSink} passed to
 * {@code transformThinkingParts} is a no-op (returns {@code null}, so an {@code inlineData} part
 * falls through unchanged) -- the real {@code processImageData} fs write is Bucket C and is not yet
 * wired anywhere in this provider (no production caller constructs a non-no-op sink today).
 */
public final class AntigravityResponseTransform {

    private static final AntigravityThinkingBlocks.ImageSink NO_OP_IMAGE_SINK = (mimeType, data) -> null;

    private AntigravityResponseTransform() {
    }

    /**
     * Transforms one {@code SERVE}-decision upstream response. A non-JSON/event-stream content type
     * is returned verbatim (request.ts:1743-1748); any exception during the transform falls back to
     * returning {@code upstream} verbatim, mirroring request.ts:1927-1937's {@code responseFallback}.
     */
    public static HttpResponse transformServe(JsonCodec json, HttpResponse upstream,
                                               AntigravityHandleOrchestrator.TransformParams params) {
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
            return transformOkBody(json, upstream, params);
        } catch (RuntimeException e) {
            return upstream;
        }
    }

    private static HttpResponse transformOkBody(JsonCodec json, HttpResponse upstream,
                                                 AntigravityHandleOrchestrator.TransformParams params) {
        String text = upstream.body;
        Object parsed = AntigravityResponseParse.parseAntigravityApiBody(json, text);
        if (!(parsed instanceof Map)) {
            // parseAntigravityApiBody returned null (unparseable text, or a cloudcode-pa array with no
            // object element) -- request.ts:1907-1909 returns `text` verbatim, which is `upstream` as-is.
            // A bare-array unwrap result is unreachable for real cloudcode-pa payloads and is treated
            // the same way (never crashes, matches AntigravityResponseParse's own disclosed fidelity).
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
        // `!== undefined` in the TS and must take this branch, per request.ts:1911.
        if (effectiveBody.containsKey("response")) {
            Object responseBody = effectiveBody.get("response");
            // TODO Phase 4: wire the debugText path (isDebugTuiEnabled()/getKeepThinking() ->
            // AntigravityRequestSignatures.injectDebugThinking) -- deferred, not reachable in 3b.
            Object transformed = AntigravityThinkingBlocks.transformThinkingParts(
                    responseBody,
                    value -> AntigravityResponseParse.recursivelyParseJsonStrings(json, value),
                    NO_OP_IMAGE_SINK);
            return buildResponse(upstream.status, headers, json.stringify(transformed));
        }

        if (patched != null) {
            return buildResponse(upstream.status, headers, json.stringify(patched));
        }

        return buildResponse(upstream.status, headers, text);
    }

    // request.ts:1886-1897.
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
