package io.github.intisy.ai.js;

import io.github.intisy.ai.antigravity.AntigravityHandleOrchestrator;
import io.github.intisy.ai.api.seam.JsonCodec;

import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSString;

import java.util.Map;

/**
 * The {@link AntigravityHandleOrchestrator.RequestPreparer} bridge. Unlike the other four seams this
 * one is SYNCHRONOUS (prepare does no I/O), so it is a plain {@code @JSFunctor} call, no {@code
 * @Async}.
 *
 * <p>{@code (url, bodyText, method, headersJson, access, projectId, endpoint, headerStyle,
 * accountJson) => preparedJson}. {@code preparedJson} is {@code {"requestRef": <opaque>, "params":
 * {requestedModel, projectId, endpoint, effectiveModel, sessionId, streaming}}}. A JS {@code
 * null}/{@code undefined}/empty/{@code "null"} return signals "prepare threw, skip this endpoint",
 * which the bridge re-raises as a {@link RuntimeException} the orchestrator catches.
 */
public final class JsRequestPreparerBridge implements AntigravityHandleOrchestrator.RequestPreparer {

    /** The host's request preparation, as one JS function. */
    @JSFunctor
    public interface JsPrepareFn extends JSObject {
        /**
         * One request, prepared for one account and endpoint.
         *
         * @param url the request url
         * @param bodyText the request body
         * @param method the request method
         * @param headersJson the caller's headers, as a JSON object
         * @param access the account's access token
         * @param projectId the project the request is billed to
         * @param endpoint the endpoint this attempt uses
         * @param headerStyle which header set that endpoint expects
         * @param accountJson the whole account, as JSON
         * @return an opaque handle plus the transform parameters as JSON, or {@code null} when
         *         preparing threw
         */
        JSString prepare(JSString url, JSString bodyText, JSString method, JSString headersJson,
                         JSString access, JSString projectId, JSString endpoint, JSString headerStyle,
                         JSString accountJson);
    }

    private final JsPrepareFn jsPrepare;
    private final JsonCodec json;

    /**
     * One bridge over the host's request preparation.
     *
     * @param jsPrepare the host's preparation
     * @param json the codec each request crosses the boundary through
     */
    public JsRequestPreparerBridge(JsPrepareFn jsPrepare, JsonCodec json) {
        this.jsPrepare = jsPrepare;
        this.json = json;
    }

    @Override
    public AntigravityHandleOrchestrator.Prepared prepare(String url, String bodyText, String method,
                                                          Map<String, String> headers, String access,
                                                          String projectId, String endpoint, String headerStyle,
                                                          Map<String, Object> account) {
        JSString res = jsPrepare.prepare(
                JSString.valueOf(nonNull(url)), JSString.valueOf(nonNull(bodyText)), JSString.valueOf(nonNull(method)),
                JSString.valueOf(json.stringify(headers)), JSString.valueOf(nonNull(access)),
                JSString.valueOf(nonNull(projectId)), JSString.valueOf(nonNull(endpoint)),
                JSString.valueOf(nonNull(headerStyle)), JSString.valueOf(json.stringify(account)));

        if (res == null) throw new RuntimeException("prepare skipped endpoint");
        String preparedJson = res.stringValue();
        if (preparedJson == null || preparedJson.isEmpty() || "null".equals(preparedJson)) {
            throw new RuntimeException("prepare skipped endpoint");
        }
        Object parsed = json.parse(preparedJson);
        if (!(parsed instanceof Map)) throw new RuntimeException("prepare returned a non-object");
        Map<?, ?> m = (Map<?, ?>) parsed;
        Object requestRef = m.get("requestRef");
        AntigravityHandleOrchestrator.TransformParams params = parseParams(m.get("params"));
        return new AntigravityHandleOrchestrator.Prepared(requestRef, params);
    }

    private static AntigravityHandleOrchestrator.TransformParams parseParams(Object paramsObj) {
        String requestedModel = null;
        String projectId = null;
        String endpoint = null;
        String effectiveModel = null;
        String sessionId = null;
        boolean streaming = false;
        if (paramsObj instanceof Map) {
            Map<?, ?> p = (Map<?, ?>) paramsObj;
            requestedModel = asString(p.get("requestedModel"));
            projectId = asString(p.get("projectId"));
            endpoint = asString(p.get("endpoint"));
            effectiveModel = asString(p.get("effectiveModel"));
            sessionId = asString(p.get("sessionId"));
            Object s = p.get("streaming");
            if (s instanceof Boolean) streaming = (Boolean) s;
        }
        return new AntigravityHandleOrchestrator.TransformParams(
                requestedModel, projectId, endpoint, effectiveModel, sessionId, streaming);
    }

    private static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static String nonNull(String s) {
        return s != null ? s : "";
    }
}
