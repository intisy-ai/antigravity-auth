package io.github.intisy.ai.js;

import io.github.intisy.ai.antigravity.AntigravityHandleOrchestrator;
import io.github.intisy.ai.shared.spi.JsonCodec;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSString;

import java.util.Map;

/**
 * One of the async JS bridges that compose inside one TeaVM CPS-transformed {@link
 * AntigravityHandleOrchestrator#handle} loop: an {@link AntigravityHandleOrchestrator.AttemptExecutor}
 * (blocking-shaped, {@code execute(accountId, preparedRef): AttemptResult}) whose implementation is
 * actually a JS-provided async function ({@code fetch} + the IP-proxy pool in production; a delayed
 * fake in the node smoke), bridged via TeaVM's {@code @Async} native method + {@link AsyncCallback}
 * mechanism, the EXACT shape of claude-code-auth's {@code JsAttemptExecutorBridge} and core-proxy's
 * {@code JsHttpClientBridge}.
 *
 * <p>Mechanism: {@link #execute} looks synchronous to the orchestrator but internally suspends on
 * {@link #awaitExecute}, a native method marked {@code @Async}. TeaVM's whole-program CPS transform
 * propagates "this call graph suspends" up to the entrypoint ({@code
 * AntigravityProviderJs.handleAntigravityRequestAsync}'s {@code JSPromise}-backing thread), so the
 * suspend/resume surfaces to JS as the returned Promise settling once the JS-side {@code execute}
 * resolves. Because {@code attemptModel} calls this inside a loop that ALSO suspends on {@link
 * JsAccountOpsBridge#acquire}, {@link JsProjectLoaderBridge#load} and {@link
 * JsProjectOnboarderBridge#onboard}, this is one strand of the multi-{@code @Async}-in-one-call-graph
 * composition.
 */
public final class JsAttemptExecutorBridge implements AntigravityHandleOrchestrator.AttemptExecutor {

    /**
     * JS-provided async attempt transport: {@code (accountId, preparedRefJson) => Promise<attemptResultJson>}.
     * Request handle and result cross as plain JSON strings (mirrors the claude bridge's JSON
     * boundary), no per-field JSO overlay types. {@code preparedRefJson} is the opaque request
     * handle the JS preparer minted (JSON-encoded); {@code attemptResultJson} carries antigravity's
     * {@link AntigravityHandleOrchestrator.AttemptResult} fields {@code {status, ok, transportFailed,
     * attemptRef, errorMessage?, errorReason?, proxyUsed?}}, NO response body (the host retains the
     * live {@code Response} keyed by its own opaque {@code attemptRef}).
     *
     * <p>Uses {@link JSString} (not {@code String}) at this generic {@link JSPromise} functor
     * boundary, per the claude precedent: TeaVM's automatic String&lt;-&gt;native-JS-string conversion
     * only fires at a DECLARED (non-generic) boundary, so a value flowing through {@code
     * JSPromise<T>}'s mapping/consumer is type-erased. {@link JSString} overlays the native string
     * directly; {@code String} conversion happens at the edges via {@code JSString.valueOf}/{@code
     * .stringValue()}.
     */
    @JSFunctor
    public interface JsExecFn extends JSObject {
        JSPromise<JSString> execute(JSString accountId, JSString preparedRefJson);
    }

    private final JsExecFn jsExec;
    private final JsonCodec json;

    public JsAttemptExecutorBridge(JsExecFn jsExec, JsonCodec json) {
        this.jsExec = jsExec;
        this.json = json;
    }

    @Override
    public AntigravityHandleOrchestrator.AttemptResult execute(String accountId, Object preparedRequestRef) {
        String preparedRefJson = json.stringify(preparedRequestRef);

        String resultJson = awaitExecute(jsExec, accountId, preparedRefJson); // <-- suspends; resumes on the JS Promise

        return parseAttemptResult(resultJson);
    }

    private AntigravityHandleOrchestrator.AttemptResult parseAttemptResult(String resultJson) {
        int status = 0;
        boolean ok = false;
        boolean transportFailed = false;
        Object attemptRef = null; // opaque: keep the JSON-decoded value verbatim
        String errorMessage = null;
        String errorReason = null;
        boolean proxyUsed = false;

        Object parsed = resultJson != null ? json.parse(resultJson) : null;
        if (parsed instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) parsed;
            Object statusVal = m.get("status");
            if (statusVal instanceof Number) status = ((Number) statusVal).intValue();
            Object okVal = m.get("ok");
            if (okVal instanceof Boolean) ok = (Boolean) okVal;
            Object tfVal = m.get("transportFailed");
            if (tfVal instanceof Boolean) transportFailed = (Boolean) tfVal;
            attemptRef = m.get("attemptRef");
            Object emVal = m.get("errorMessage");
            if (emVal instanceof String) errorMessage = (String) emVal;
            Object erVal = m.get("errorReason");
            if (erVal instanceof String) errorReason = (String) erVal;
            Object puVal = m.get("proxyUsed");
            if (puVal instanceof Boolean) proxyUsed = (Boolean) puVal;
        }
        return new AntigravityHandleOrchestrator.AttemptResult(
                status, ok, transportFailed, attemptRef, errorMessage, errorReason, proxyUsed);
    }

    // @Async bridge ---------------------------------------------------------------

    /** Blocking-looking native entrypoint; TeaVM's async transform makes every (transitive) caller
     *  suspend/resume instead of blocking. Same shape as the claude {@code awaitExecute}. */
    @Async
    private static native String awaitExecute(JsExecFn fn, String accountId, String preparedRefJson);

    // Companion: same name, void return, trailing AsyncCallback<T>, the exact pairing TeaVM's
    // async codegen looks for.
    private static void awaitExecute(JsExecFn fn, String accountId, String preparedRefJson, AsyncCallback<String> callback) {
        fn.execute(JSString.valueOf(accountId), JSString.valueOf(preparedRefJson)).then(
                value -> {
                    callback.complete(value == null ? null : value.stringValue());
                    return null;
                },
                error -> {
                    callback.error(new RuntimeException("attempt execute rejected: " + error));
                    return null;
                });
    }
}
