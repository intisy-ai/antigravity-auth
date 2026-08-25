package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.JsonCodec;
import io.github.intisy.ai.api.seam.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AntigravityResponseTransform#transformServe} on OK non-streaming JSON responses.
 */
class AntigravityResponseTransformTest {

    private static final JsonCodec JSON = new TestJsonCodec();

    private static HttpResponse okResponse(String body) {
        HttpResponse r = new HttpResponse();
        r.status = 200;
        r.headers = new LinkedHashMap<>();
        r.headers.put("content-type", "application/json");
        r.body = body;
        return r;
    }

    private static AntigravityHandleOrchestrator.TransformParams params(String requestedModel) {
        return new AntigravityHandleOrchestrator.TransformParams(requestedModel, "proj-1", "generateContent",
                requestedModel, "sess-1", false);
    }

    // ---- 1: cloudcode-pa array unwrap + .response extraction ---------------------------------------

    @Test
    void unwrapsCloudcodePaArrayAndExtractsResponse() {
        HttpResponse upstream = okResponse(
                "[{\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}}]");

        HttpResponse result = AntigravityResponseTransform.transformServe(JSON, upstream, params("gemini-test"));

        assertEquals(200, result.status);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) JSON.parse(result.body);
        assertFalse(body.containsKey("response"), "the response wrapper must be unwrapped");
        assertTrue(result.body.contains("\"text\":\"hi\""));
    }

    // ---- 2: thinking parts transformed ---------------------------------------------------------------

    @Test
    void thinkingPartsAreTransformed() {
        HttpResponse upstream = okResponse("[{\"response\":{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"text\":\"pondering\",\"thought\":true}]}}]}}]");

        HttpResponse result = AntigravityResponseTransform.transformServe(JSON, upstream, params("gemini-test"));

        assertTrue(result.body.contains("\"type\":\"reasoning\""),
                "a thought:true part must be converted to type=reasoning by transformThinkingParts");
        assertTrue(result.body.contains("\"reasoning_content\""),
                "an aggregated reasoning_content field must be added to the candidate");
    }

    // ---- 3: usage headers -----------------------------------------------------------------------------

    @Test
    void usageHeadersAreSetFromCachedContentTokenCount() {
        HttpResponse upstream = okResponse("[{\"response\":{"
                + "\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}],"
                + "\"usageMetadata\":{\"totalTokenCount\":100,\"promptTokenCount\":80,"
                + "\"candidatesTokenCount\":20,\"cachedContentTokenCount\":30}}}]");

        HttpResponse result = AntigravityResponseTransform.transformServe(JSON, upstream, params("gemini-test"));

        assertEquals("30", result.headers.get("x-antigravity-cached-content-token-count"));
        assertEquals("100", result.headers.get("x-antigravity-total-token-count"));
        assertEquals("80", result.headers.get("x-antigravity-prompt-token-count"));
        assertEquals("20", result.headers.get("x-antigravity-candidates-token-count"));
    }

    @Test
    void usageHeadersAreAbsentWithoutCachedContentTokenCount() {
        HttpResponse upstream = okResponse("[{\"response\":{"
                + "\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}],"
                + "\"usageMetadata\":{\"totalTokenCount\":100}}}]");

        HttpResponse result = AntigravityResponseTransform.transformServe(JSON, upstream, params("gemini-test"));

        assertFalse(result.headers.containsKey("x-antigravity-cached-content-token-count"));
        assertFalse(result.headers.containsKey("x-antigravity-total-token-count"));
    }

    // ---- 4: preview-access-error rewrite (patched, no .response) --------------------------------------

    @Test
    void rewritesPreviewAccessErrorWhenNoResponseField() {
        HttpResponse upstream = new HttpResponse();
        upstream.status = 404;
        upstream.headers = new LinkedHashMap<>();
        upstream.headers.put("content-type", "application/json");
        upstream.body = "{\"error\":{\"message\":\"model not found\"}}";

        HttpResponse result = AntigravityResponseTransform.transformServe(
                JSON, upstream, params("antigravity-claude-sonnet-4-6"));

        assertEquals(404, result.status);
        assertTrue(result.body.contains("Request preview access at"),
                "the patched (preview-access) body must be returned since there is no .response field");
    }

    // ---- 5: passthrough ---------------------------------------------------------------------------------

    @Test
    void nonJsonContentTypeIsReturnedVerbatim() {
        HttpResponse upstream = new HttpResponse();
        upstream.status = 200;
        upstream.headers = new LinkedHashMap<>();
        upstream.headers.put("content-type", "text/plain");
        upstream.body = "plain text body";

        HttpResponse result = AntigravityResponseTransform.transformServe(JSON, upstream, params("gemini-test"));

        assertSame(upstream, result, "a non-JSON/event-stream content type must be returned verbatim, unmodified");
    }

    @Test
    void unparseableBodyIsReturnedVerbatim() {
        HttpResponse upstream = okResponse("not json at all {{{");

        HttpResponse result = AntigravityResponseTransform.transformServe(JSON, upstream, params("gemini-test"));

        assertSame(upstream, result, "an unparseable body must fall back to the original upstream response");
    }

    // ---- 6: exception safety ------------------------------------------------------------------------------

    @Test
    void malformedResponseFieldFallsBackToOriginalUpstream() {
        // "response" is a bare string, not an object/array, and transformThinkingParts itself tolerates
        // this (returns non-Map input as-is), so force a genuine throw via a JsonCodec whose
        // stringify blows up, proving the outer try/catch returns the ORIGINAL upstream, not a crash.
        JsonCodec throwingJson = new JsonCodec() {
            @Override
            public Object parse(String json) {
                return JSON.parse(json);
            }

            @Override
            public String stringify(Object value) {
                throw new IllegalStateException("boom");
            }
        };
        HttpResponse upstream = okResponse("[{\"response\":{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"text\":\"hi\"}]}}]}}]");

        HttpResponse result = AntigravityResponseTransform.transformServe(throwingJson, upstream, params("gemini-test"));

        assertSame(upstream, result, "any exception during transform must fall back to the original upstream verbatim");
    }

    @Test
    void nullUpstreamIsToleratedAndReturnedAsNull() {
        assertNull(AntigravityResponseTransform.transformServe(JSON, null, params("gemini-test")));
    }
}
