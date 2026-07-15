package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.Random;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared recording test doubles for the T7f handle-orchestrator parity tests. The recorded
 * {@link RecordingAccountOps#seq} uses the SAME ordered {@code [op, ...args]} shape the Node harness
 * ({@code .superpowers/sdd/t7f-harness/}) emits, so a scenario's Java sequence is asserted verbatim
 * against the harness fixture. Determinism knobs (a fixed {@link Clock}=={@link #FIXED_NOW}, a fixed
 * {@link Random}==0.5, a counter {@link AntigravityRequestPrep.IdGenerator}) match the harness stub.
 */
final class HandleTestDoubles {

    // stub.ts FIXED_NOW.
    static final long FIXED_NOW = 1_700_000_000_000L;
    static final Clock CLOCK = () -> FIXED_NOW;
    static final Random RANDOM = () -> 0.5;

    private HandleTestDoubles() {
    }

    static AntigravityRequestPrep.IdGenerator counterIds() {
        return new AntigravityRequestPrep.IdGenerator() {
            private long n = 0;

            @Override
            public String randomUuid() {
                n += 1;
                return "00000000-0000-4000-8000-00000000000" + n;
            }
        };
    }

    static List<Object> row(Object... args) {
        return new ArrayList<>(Arrays.asList(args));
    }

    /** A "quiet" account: managed project already known -> resolveProjectId short-circuits (no loader call). */
    static Map<String, Object> quietAccount(String id) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("managedProjectId", "mp-" + id);
        meta.put("syntheticProjectId", "syn-" + id);
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", id);
        account.put("refresh", "rt-" + id);
        account.put("expires", FIXED_NOW + 3_600_000L);
        account.put("meta", meta);
        return account;
    }

    /** Recording {@link AntigravityHandleOrchestrator.AccountOps} -- scripted acquires, recorded calls. */
    static final class RecordingAccountOps implements AntigravityHandleOrchestrator.AccountOps {
        final List<List<Object>> seq = new ArrayList<>();
        private final Deque<AntigravityHandleOrchestrator.Acquired> acquireQueue = new ArrayDeque<>();
        private final List<Map<String, Object>> accounts = new ArrayList<>();
        Long nextAvailable;

        RecordingAccountOps accounts(Map<String, Object>... accs) {
            accounts.addAll(Arrays.asList(accs));
            return this;
        }

        RecordingAccountOps enqueueAcquire(AntigravityHandleOrchestrator.Acquired a) {
            acquireQueue.add(a == null ? NULL_ACQUIRE : a);
            return this;
        }

        RecordingAccountOps nextAvailable(Long v) {
            this.nextAvailable = v;
            return this;
        }

        private static final AntigravityHandleOrchestrator.Acquired NULL_ACQUIRE =
                new AntigravityHandleOrchestrator.Acquired(null, null, null);

        @Override
        public AntigravityHandleOrchestrator.Acquired acquire(String lane) {
            seq.add(row("acquire", lane));
            AntigravityHandleOrchestrator.Acquired a = acquireQueue.poll();
            return a == NULL_ACQUIRE ? null : a;
        }

        @Override
        public Long nextAvailableAt(String lane) {
            seq.add(row("nextAvailableAt", lane));
            return nextAvailable;
        }

        @Override
        public void reportError(String accountId, int attempt, String message) {
            seq.add(row("reportError", accountId, attempt, message));
        }

        @Override
        public void reportRateLimit(String accountId, String lane, long resetMs) {
            seq.add(row("reportRateLimit", accountId, lane, resetMs));
        }

        @Override
        public void reportSuccess(String accountId) {
            seq.add(row("reportSuccess", accountId));
        }

        @Override
        public void reportProxyRateLimit(String accountId, boolean ipSuspected) {
            seq.add(row("reportProxyRateLimit", accountId, ipSuspected));
        }

        @Override
        public List<Map<String, Object>> list() {
            return accounts;
        }

        @Override
        public void mutate(String accountId, AntigravityHandleOrchestrator.Mutator mutator) {
            seq.add(row("mutate", accountId));
            for (Map<String, Object> a : accounts) {
                if (accountId.equals(String.valueOf(a.get("id")))) {
                    mutator.apply(a);
                    return;
                }
            }
        }
    }

    /** Scripted {@link AntigravityHandleOrchestrator.AttemptExecutor}: pops one result per call. */
    static final class ScriptedExecutor implements AntigravityHandleOrchestrator.AttemptExecutor {
        private final Deque<AntigravityHandleOrchestrator.AttemptResult> results = new ArrayDeque<>();

        ScriptedExecutor ok(int status) {
            results.add(new AntigravityHandleOrchestrator.AttemptResult(status, true, false, new Object(), null, null, false));
            return this;
        }

        ScriptedExecutor rateLimit(int status, String message, String reason) {
            return rateLimit(status, message, reason, false);
        }

        ScriptedExecutor rateLimit(int status, String message, String reason, boolean proxyUsed) {
            results.add(new AntigravityHandleOrchestrator.AttemptResult(status, false, false, new Object(), message, reason, proxyUsed));
            return this;
        }

        ScriptedExecutor nonOk(int status) {
            results.add(new AntigravityHandleOrchestrator.AttemptResult(status, false, false, new Object(), null, null, false));
            return this;
        }

        ScriptedExecutor transportFailed() {
            results.add(new AntigravityHandleOrchestrator.AttemptResult(0, false, true, null, null, null, false));
            return this;
        }

        @Override
        public AntigravityHandleOrchestrator.AttemptResult execute(String accountId, Object preparedRequestRef) {
            AntigravityHandleOrchestrator.AttemptResult r = results.poll();
            if (r == null) throw new IllegalStateException("executor queue empty");
            return r;
        }
    }

    /**
     * Faithful port of the harness {@code stubPrepare}: derives the transform params from the url +
     * endpoint deterministically. Throws when {@code failOnce}/{@code failAlways} is set (the
     * prepare-failure DECISION). prepareAntigravityRequest's real byte-parity is T7e's domain.
     */
    static final class StubPreparer implements AntigravityHandleOrchestrator.RequestPreparer {
        boolean failAlways;

        StubPreparer failAlways() {
            this.failAlways = true;
            return this;
        }

        @Override
        public AntigravityHandleOrchestrator.Prepared prepare(String url, String bodyText, String method,
                                                              Map<String, String> headers, String access,
                                                              String projectId, String endpoint, String headerStyle,
                                                              Map<String, Object> account) {
            if (failAlways) throw new RuntimeException("synthetic prepare failure");
            String requestedModel = modelFromUrl(url);
            boolean streaming = url.contains("streamGenerateContent") || url.contains("alt=sse");
            AntigravityHandleOrchestrator.TransformParams params = new AntigravityHandleOrchestrator.TransformParams(
                    requestedModel, projectId, endpoint, requestedModel, "sess-fixed", streaming);
            return new AntigravityHandleOrchestrator.Prepared(new Object(), params);
        }

        private static String modelFromUrl(String url) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("/models/([^:/?]+)").matcher(url);
            return m.find() ? m.group(1) : "antigravity-auto";
        }
    }

    /** A {@link AntigravityProjectContext.ProjectLoader} that fails the test if ever called (quiet path). */
    static final AntigravityProjectContext.ProjectLoader LOADER_UNUSED = (a, p, x) -> {
        throw new AssertionError("ProjectLoader must not be called on the short-circuit path");
    };

    static final AntigravityProjectContext.ProjectOnboarder ONBOARDER_UNUSED = (a, t, p, x) -> {
        throw new AssertionError("ProjectOnboarder must not be called on the short-circuit path");
    };

    static final AntigravityProjectContext.Platform PLATFORM_LINUX = new AntigravityProjectContext.Platform() {
        @Override
        public String platform() {
            return "linux";
        }

        @Override
        public String arch() {
            return "x64";
        }
    };

    static AntigravityHandleOrchestrator orchestrator(RecordingAccountOps accounts,
                                                      AntigravityHandleOrchestrator.RequestPreparer preparer,
                                                      AntigravityHandleOrchestrator.AttemptExecutor executor,
                                                      AntigravityHandleRouting.ModelCacheLookup modelCache,
                                                      AntigravityProjectContext.ProjectLoader loader,
                                                      AntigravityProjectContext.ProjectOnboarder onboarder) {
        return new AntigravityHandleOrchestrator(new TestJsonCodec(), CLOCK, RANDOM, counterIds(),
                accounts, preparer, executor, modelCache, loader, onboarder, PLATFORM_LINUX);
    }
}
