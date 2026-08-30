package io.github.intisy.ai.js;

import io.github.intisy.ai.antigravity.AntigravityProjectContext;
import io.github.intisy.ai.api.seam.JsonCodec;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSObjects;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSString;

import java.util.Map;

/**
 * The {@link AntigravityProjectContext.ProjectLoader} bridge: a THIRD distinct {@code @Async} native
 * method + {@link AsyncCallback}, driving the host-side {@code loadManagedProject} fetch loop. It
 * suspends INSIDE {@code resolveProjectId} -&gt; {@code ensureProjectContext}, which runs BEFORE the
 * {@link JsAttemptExecutorBridge#execute} suspend in the same attempt, so a single {@code
 * attemptModel} iteration can chain acquire -&gt; load -&gt; (onboard) -&gt; execute, all suspending on
 * separate {@code @Async} bridges in one CPS-transformed call graph.
 *
 * <p>{@code (accessToken, projectId, proxy) => Promise<payloadJson | null>}; the parsed {@code
 * loadCodeAssist} payload is a JSON object, or {@code null} on every-endpoint failure. {@link
 * JSString} at the generic {@link JSPromise} boundary, per the rule.
 */
public final class JsProjectLoaderBridge implements AntigravityProjectContext.ProjectLoader {

    /** The host's managed-project fetch, as one JS function. */
    @JSFunctor
    public interface JsLoadFn extends JSObject {
        /**
         * What the upstream says about one account's managed project.
         *
         * @param accessToken the account's access token
         * @param projectId the project to ask about, or empty to let the upstream choose
         * @param proxy the outbound proxy, or empty for a direct connection
         * @return the payload as JSON, or {@code null} when every endpoint failed
         */
        JSPromise<JSString> load(JSString accessToken, JSString projectId, JSString proxy);
    }

    private final JsLoadFn jsLoad;
    private final JsonCodec json;

    /**
     * One bridge over the host's managed-project fetch.
     *
     * @param jsLoad the host's fetch
     * @param json the codec each payload crosses the boundary through
     */
    public JsProjectLoaderBridge(JsLoadFn jsLoad, JsonCodec json) {
        this.jsLoad = jsLoad;
        this.json = json;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> load(String accessToken, String projectId, String proxy) {
        String payloadJson = awaitLoad(jsLoad, nonNull(accessToken), nonNull(projectId), nonNull(proxy)); // suspends
        if (payloadJson == null || payloadJson.isEmpty() || "null".equals(payloadJson)) return null;
        Object parsed = json.parse(payloadJson);
        return parsed instanceof Map ? (Map<String, Object>) parsed : null;
    }

    private static String nonNull(String s) {
        return s != null ? s : "";
    }

    @Async
    private static native String awaitLoad(JsLoadFn fn, String accessToken, String projectId, String proxy);

    private static void awaitLoad(JsLoadFn fn, String accessToken, String projectId, String proxy, AsyncCallback<String> callback) {
        fn.load(JSString.valueOf(accessToken), JSString.valueOf(projectId), JSString.valueOf(proxy)).then(
                value -> {
                    callback.complete(value == null || JSObjects.isUndefined(value) ? null : value.stringValue());
                    return null;
                },
                error -> {
                    callback.error(new RuntimeException("project load rejected: " + error));
                    return null;
                });
    }
}
