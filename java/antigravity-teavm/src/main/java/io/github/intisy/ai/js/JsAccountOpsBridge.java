package io.github.intisy.ai.js;

import io.github.intisy.ai.antigravity.AntigravityHandleOrchestrator;
import io.github.intisy.ai.shared.spi.JsonCodec;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.core.JSObjects;
import org.teavm.jso.core.JSPromise;
import org.teavm.jso.core.JSString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@link AntigravityHandleOrchestrator.AccountOps} bridge. Its ONE async operation ({@code
 * manager.acquire(lane)}) is bridged with an {@code @Async} native method + {@link AsyncCallback},
 * one strand of the multi-{@code @Async} composition (the orchestrator's retry loop does {@code
 * acquire} then {@code execute} then {@code acquire}...). The SYNCHRONOUS ops ({@code
 * nextAvailableAt}/{@code reportError}/{@code reportRateLimit}/{@code reportSuccess}/{@code
 * reportProxyRateLimit}/{@code list}/{@code mutate}) return synchronously in TS, so they call plain
 * {@code @JSFunctor}/JSObject-method JS callbacks DIRECTLY, no {@code @Async} (mirrors the claude
 * {@code JsAccountOpsBridge.JsReportFns} pattern).
 *
 * <p>Antigravity's {@link AntigravityHandleOrchestrator.Acquired} carries a THIRD field beyond the
 * claude shape, the full account {@code Map}, because {@code resolveProjectId}/{@code
 * syntheticProjectFor} read {@code account.meta} and mutate it. {@link #mutate} therefore applies the
 * Java {@link AntigravityHandleOrchestrator.Mutator} to the locally-tracked account map (populated on
 * {@code acquire}/{@code list}, keyed by id) and then ships the mutated account JSON to the host to
 * persist.
 */
public final class JsAccountOpsBridge implements AntigravityHandleOrchestrator.AccountOps {

    /**
     * JS-provided async account acquisition: {@code (lane) => Promise<acquiredJson | null>}.
     * Resolves with a JSON string {@code {"accountId","access","account":{...}}}, or a JSON {@code
     * "null"} / an actual JS {@code null}/{@code undefined} / empty string when no account is free,
     * ALL of which the bridge collapses to Java {@code null}, matching the TS {@code !acquired ||
     * !acquired.account}. {@link JSString} at the generic {@link JSPromise} boundary, per the rule.
     */
    @JSFunctor
    public interface JsAcquireFn extends JSObject {
        JSPromise<JSString> acquire(JSString lane);
    }

    /**
     * The synchronous {@link AntigravityHandleOrchestrator.AccountOps} callbacks, grouped in ONE JS
     * object (a non-functor JSObject overlay; each method is invoked on the underlying JS object by
     * name, exactly like the claude {@code JsReportFns}). {@code resetMs} crosses as a {@code double}
     * (epoch ms &lt; 2^53, so exact); the nullable {@code nextAvailableAt} and the {@code list()}
     * accounts + {@code mutate} account are JSON-encoded to a {@link JSString} ({@code "null"} for a
     * missing next-available).
     */
    public interface JsAccountFns extends JSObject {
        JSString nextAvailableAt(JSString lane);

        void reportError(JSString accountId, JSString lane, int attempt, JSString message);

        void reportRateLimit(JSString accountId, JSString lane, double resetMs);

        void reportSuccess(JSString accountId);

        void reportProxyRateLimit(JSString accountId, boolean ipSuspected);

        JSString list();

        void mutate(JSString accountId, JSString updatedAccountJson);
    }

    private final JsAcquireFn jsAcquire;
    private final JsAccountFns jsOps;
    private final JsonCodec json;
    // Account maps seen via acquire()/list(), keyed by id: the Map instance that flows into the
    // orchestrator as Acquired.account, so mutate() applies the mutator to the SAME instance.
    private final Map<String, Map<String, Object>> knownAccounts = new LinkedHashMap<>();

    public JsAccountOpsBridge(JsAcquireFn jsAcquire, JsAccountFns jsOps, JsonCodec json) {
        this.jsAcquire = jsAcquire;
        this.jsOps = jsOps;
        this.json = json;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AntigravityHandleOrchestrator.Acquired acquire(String lane) {
        String acquiredJson = awaitAcquire(jsAcquire, lane); // <-- suspends; resumes on the JS Promise
        if (acquiredJson == null || acquiredJson.isEmpty() || "null".equals(acquiredJson)) return null;
        Object parsed = json.parse(acquiredJson);
        if (!(parsed instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) parsed;
        Object id = m.get("accountId");
        if (!(id instanceof String) || ((String) id).isEmpty()) return null;
        Object access = m.get("access");
        Object accObj = m.get("account");
        Map<String, Object> account = accObj instanceof Map ? (Map<String, Object>) accObj : new LinkedHashMap<>();
        knownAccounts.put((String) id, account);
        return new AntigravityHandleOrchestrator.Acquired(
                (String) id, access instanceof String ? (String) access : null, account);
    }

    @Override
    public Long nextAvailableAt(String lane) {
        JSString res = jsOps.nextAvailableAt(JSString.valueOf(lane));
        if (res == null) return null;
        String s = res.stringValue();
        if (s == null || s.isEmpty()) return null;
        Object parsed = json.parse(s);
        return parsed instanceof Number ? ((Number) parsed).longValue() : null;
    }

    @Override
    public void reportError(String accountId, String lane, int attempt, String message) {
        jsOps.reportError(JSString.valueOf(accountId), JSString.valueOf(lane), attempt, JSString.valueOf(message != null ? message : ""));
    }

    @Override
    public void reportRateLimit(String accountId, String lane, long resetMs) {
        jsOps.reportRateLimit(JSString.valueOf(accountId), JSString.valueOf(lane), (double) resetMs);
    }

    @Override
    public void reportSuccess(String accountId) {
        jsOps.reportSuccess(JSString.valueOf(accountId));
    }

    @Override
    public void reportProxyRateLimit(String accountId, boolean ipSuspected) {
        jsOps.reportProxyRateLimit(JSString.valueOf(accountId), ipSuspected);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        JSString res = jsOps.list();
        if (res == null) return out;
        String s = res.stringValue();
        if (s == null || s.isEmpty()) return out;
        Object parsed = json.parse(s);
        if (parsed instanceof List) {
            for (Object o : (List<Object>) parsed) {
                if (o instanceof Map) {
                    Map<String, Object> acc = (Map<String, Object>) o;
                    out.add(acc);
                    Object accId = acc.get("id");
                    if (accId != null) knownAccounts.put(String.valueOf(accId), acc);
                }
            }
        }
        return out;
    }

    @Override
    public void mutate(String accountId, AntigravityHandleOrchestrator.Mutator mutator) {
        Map<String, Object> account = knownAccounts.get(accountId);
        if (account == null) {
            account = new LinkedHashMap<>();
            account.put("id", accountId);
            knownAccounts.put(accountId, account);
        }
        mutator.apply(account); // mutate the live account map (the instance the orchestrator holds)
        jsOps.mutate(JSString.valueOf(accountId), JSString.valueOf(json.stringify(account)));
    }

    // @Async bridge (one distinct @Async in the composed call graph) ------------------------------

    @Async
    private static native String awaitAcquire(JsAcquireFn fn, String lane);

    private static void awaitAcquire(JsAcquireFn fn, String lane, AsyncCallback<String> callback) {
        fn.acquire(JSString.valueOf(lane)).then(
                value -> {
                    // A JS null/undefined resolve (no account free) -> Java null; else the JSON string.
                    callback.complete(value == null || JSObjects.isUndefined(value) ? null : value.stringValue());
                    return null;
                },
                error -> {
                    callback.error(new RuntimeException("account acquire rejected: " + error));
                    return null;
                });
    }
}
