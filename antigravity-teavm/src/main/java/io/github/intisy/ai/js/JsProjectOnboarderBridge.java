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

/**
 * The {@link AntigravityProjectContext.ProjectOnboarder} bridge: a FOURTH distinct {@code @Async}
 * native method + {@link AsyncCallback}, driving the host-side {@code onboardManagedProject} fetch
 * loop. It suspends INSIDE {@code ensureProjectContext} only when {@link JsProjectLoaderBridge#load}
 * yields no managed project id, so the fully-cold-account path chains acquire -&gt; load -&gt; onboard
 * -&gt; execute across FOUR separate {@code @Async} bridges in one CPS-transformed call graph.
 *
 * <p>{@code (accessToken, tierId, projectId, proxy) => Promise<managedIdJson | null>}; resolves the
 * provisioned managed project id string, or {@code null} when provisioning did not complete. {@link
 * JSString} at the generic {@link JSPromise} boundary, per the rule.
 */
public final class JsProjectOnboarderBridge implements AntigravityProjectContext.ProjectOnboarder {

    @JSFunctor
    public interface JsOnboardFn extends JSObject {
        JSPromise<JSString> onboard(JSString accessToken, JSString tierId, JSString projectId, JSString proxy);
    }

    private final JsOnboardFn jsOnboard;
    private final JsonCodec json;

    public JsProjectOnboarderBridge(JsOnboardFn jsOnboard, JsonCodec json) {
        this.jsOnboard = jsOnboard;
        this.json = json;
    }

    @Override
    public String onboard(String accessToken, String tierId, String projectId, String proxy) {
        String managedIdJson = awaitOnboard(jsOnboard, nonNull(accessToken), nonNull(tierId),
                nonNull(projectId), nonNull(proxy)); // suspends
        if (managedIdJson == null || managedIdJson.isEmpty() || "null".equals(managedIdJson)) return null;
        Object parsed = json.parse(managedIdJson);
        if (parsed instanceof String) {
            String s = (String) parsed;
            return s.isEmpty() ? null : s;
        }
        return null;
    }

    private static String nonNull(String s) {
        return s != null ? s : "";
    }

    @Async
    private static native String awaitOnboard(JsOnboardFn fn, String accessToken, String tierId, String projectId, String proxy);

    private static void awaitOnboard(JsOnboardFn fn, String accessToken, String tierId, String projectId, String proxy,
                                     AsyncCallback<String> callback) {
        fn.onboard(JSString.valueOf(accessToken), JSString.valueOf(tierId), JSString.valueOf(projectId), JSString.valueOf(proxy)).then(
                value -> {
                    callback.complete(value == null || JSObjects.isUndefined(value) ? null : value.stringValue());
                    return null;
                },
                error -> {
                    callback.error(new RuntimeException("project onboard rejected: " + error));
                    return null;
                });
    }
}
