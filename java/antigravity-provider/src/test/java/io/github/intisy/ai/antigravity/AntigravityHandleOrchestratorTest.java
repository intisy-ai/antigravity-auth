package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.Logger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.antigravity.HandleTestDoubles.LOADER_UNUSED;
import static io.github.intisy.ai.antigravity.HandleTestDoubles.ONBOARDER_UNUSED;
import static io.github.intisy.ai.antigravity.HandleTestDoubles.RecordingAccountOps;
import static io.github.intisy.ai.antigravity.HandleTestDoubles.ScriptedExecutor;
import static io.github.intisy.ai.antigravity.HandleTestDoubles.StubPreparer;
import static io.github.intisy.ai.antigravity.HandleTestDoubles.orchestrator;
import static io.github.intisy.ai.antigravity.HandleTestDoubles.quietAccount;
import static io.github.intisy.ai.antigravity.HandleTestDoubles.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Decision-sequence and synthetic-body tests for {@link AntigravityHandleOrchestrator}, covering
 * {@code attemptModel}, {@code handle}, {@code resolveProjectId}, and {@code syntheticProjectFor}.
 */
class AntigravityHandleOrchestratorTest {

    private static final Logger NOOP = m -> { };
    private static final String PROD = "https://cloudcode-pa.googleapis.com";
    private static final String DAILY = "https://daily-cloudcode-pa.sandbox.googleapis.com";
    private static final String ANTI_URL = PROD + "/v1internal/models/antigravity-claude-sonnet-4-6:generateContent";

    private static AntigravityHandleOrchestrator.RequestInputs inputs(String ctxModel, String url, List<String> auto) {
        AntigravityHandleOrchestrator.RequestInputs in = new AntigravityHandleOrchestrator.RequestInputs();
        in.ctxModel = ctxModel;
        in.url = url;
        in.method = "POST";
        in.headers = new LinkedHashMap<>();
        in.autoCandidates = auto;
        in.log = NOOP;
        return in;
    }

    private static AntigravityHandleOrchestrator orch(RecordingAccountOps a, AntigravityHandleOrchestrator.RequestPreparer p,
                                                      AntigravityHandleOrchestrator.AttemptExecutor e) {
        return orchestrator(a, p, e, id -> null, LOADER_UNUSED, ONBOARDER_UNUSED);
    }

    // ---- handle / attemptModel scenarios --------------------------------------------------------

    @Test
    void happyPath() {
        RecordingAccountOps a = new RecordingAccountOps().accounts(quietAccount("acc1"));
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("acc1", "at1", quietAccount("acc1")));
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), new ScriptedExecutor().ok(200));

        AntigravityHandleOrchestrator.HandleDecision d = o.handle(inputs("antigravity-claude-sonnet-4-6", ANTI_URL, null));

        assertEquals(list(row("acquire", "claude"), row("reportSuccess", "acc1")), a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.SERVE, d.kind);
        assertEquals(200, d.status);
        assertParams(d.params, "antigravity-claude-sonnet-4-6", "mp-acc1", PROD, "antigravity-claude-sonnet-4-6", "sess-fixed", false);
    }

    @Test
    void missingAccess() {
        RecordingAccountOps a = new RecordingAccountOps().accounts(quietAccount("acc1"));
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("acc1", "", quietAccount("acc1")));
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("acc1", "at1", quietAccount("acc1")));
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), new ScriptedExecutor().ok(200));

        AntigravityHandleOrchestrator.HandleDecision d = o.handle(inputs("antigravity-claude-sonnet-4-6", ANTI_URL, null));

        assertEquals(list(row("acquire", "claude"), row("reportError", "acc1", "claude", 0, "missing access token"),
                row("acquire", "claude"), row("reportSuccess", "acc1")), a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.SERVE, d.kind);
    }

    @Test
    void rateThenRotate() {
        RecordingAccountOps a = new RecordingAccountOps().accounts(quietAccount("acc1"), quietAccount("acc2"));
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("acc1", "at1", quietAccount("acc1")));
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("acc2", "at2", quietAccount("acc2")));
        ScriptedExecutor e = new ScriptedExecutor()
                .rateLimit(429, "quota reached, resets after 30s", "RESOURCE_EXHAUSTED", true)
                .rateLimit(429, "quota reached, resets after 30s", "RESOURCE_EXHAUSTED", true)
                .rateLimit(429, "quota reached, resets after 30s", "RESOURCE_EXHAUSTED", true)
                .ok(200);
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), e);

        AntigravityHandleOrchestrator.HandleDecision d = o.handle(inputs("antigravity-claude-sonnet-4-6", ANTI_URL, null));

        assertEquals(list(
                row("acquire", "claude"),
                row("reportRateLimit", "acc1", "claude", 1700000030000L), row("reportProxyRateLimit", "acc1", false),
                row("reportRateLimit", "acc1", "claude", 1700000030000L), row("reportProxyRateLimit", "acc1", false),
                row("reportRateLimit", "acc1", "claude", 1700000030000L), row("reportProxyRateLimit", "acc1", false),
                row("acquire", "claude"), row("reportSuccess", "acc2")), a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.SERVE, d.kind);
        assertParams(d.params, "antigravity-claude-sonnet-4-6", "mp-acc2", PROD, "antigravity-claude-sonnet-4-6", "sess-fixed", false);
    }

    @Test
    void noAccountCooling() {
        RecordingAccountOps a = new RecordingAccountOps().nextAvailable(HandleTestDoubles.FIXED_NOW + 42_000L);
        a.enqueueAcquire(null);
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), new ScriptedExecutor());

        AntigravityHandleOrchestrator.HandleDecision d = o.handle(inputs("antigravity-claude-sonnet-4-6", ANTI_URL, null));

        assertEquals(list(row("acquire", "claude"), row("nextAvailableAt", "claude")), a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.SYNTHETIC, d.kind);
        assertEquals(503, d.status);
        assertEquals("application/json", d.headers.get("content-type"));
        assertEquals("{\"error\":{\"message\":\"claude quota exhausted, resets in ~42s. Pick another model or use Auto (it falls through to a free pool).\"}}", d.body);
    }

    @Test
    void noAccountNoReset() {
        RecordingAccountOps a = new RecordingAccountOps().nextAvailable(null);
        a.enqueueAcquire(null);
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), new ScriptedExecutor());

        AntigravityHandleOrchestrator.HandleDecision d = o.handle(inputs("antigravity-claude-sonnet-4-6", ANTI_URL, null));

        assertEquals(list(row("acquire", "claude"), row("nextAvailableAt", "claude")), a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.SYNTHETIC, d.kind);
        assertEquals(503, d.status);
        assertEquals("{\"error\":{\"message\":\"No available antigravity account for lane claude.\"}}", d.body);
    }

    @Test
    void endpointFallback() {
        RecordingAccountOps a = new RecordingAccountOps().accounts(quietAccount("acc1"));
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("acc1", "at1", quietAccount("acc1")));
        ScriptedExecutor e = new ScriptedExecutor().nonOk(403).ok(200);
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), e);

        AntigravityHandleOrchestrator.HandleDecision d = o.handle(inputs("antigravity-claude-sonnet-4-6", ANTI_URL, null));

        assertEquals(list(row("acquire", "claude"), row("reportSuccess", "acc1")), a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.SERVE, d.kind);
        assertParams(d.params, "antigravity-claude-sonnet-4-6", "mp-acc1", DAILY, "antigravity-claude-sonnet-4-6", "sess-fixed", false);
    }

    @Test
    void sandbox403DoesNotMask429() {
        RecordingAccountOps a = new RecordingAccountOps().accounts(quietAccount("acc1")).nextAvailable(null);
        ScriptedExecutor e = new ScriptedExecutor();
        List<List<Object>> expected = new ArrayList<>();
        for (int i = 0; i < AntigravityHandleOrchestrator.MAX_ATTEMPTS; i++) {
            a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("acc1", "at1", quietAccount("acc1")));
            e.rateLimit(429, "capacity exhausted, resets after 60s", "RESOURCE_EXHAUSTED").nonOk(403).nonOk(403);
            expected.add(row("acquire", "claude"));
            expected.add(row("reportRateLimit", "acc1", "claude", 1700000060000L));
        }
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), e);

        AntigravityHandleOrchestrator.HandleDecision d = o.handle(inputs("antigravity-claude-sonnet-4-6", ANTI_URL, null));

        assertEquals(expected, a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.SERVE_RAW, d.kind);
        assertEquals(429, d.status);
    }

    @Test
    void autoFallThrough() {
        Map<String, Object> accA = quietAccount("accA");
        RecordingAccountOps a = new RecordingAccountOps().accounts(accA).nextAvailable(null);
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("accA", "atA", accA));
        a.enqueueAcquire(null);
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("accA", "atA", accA));
        ScriptedExecutor e = new ScriptedExecutor()
                .rateLimit(429, "resets after 30s", null)
                .rateLimit(429, "resets after 30s", null)
                .rateLimit(429, "resets after 30s", null)
                .ok(200);
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), e);

        List<String> auto = java.util.Arrays.asList("antigravity-gemini-3-pro", "antigravity-claude-sonnet-4-6");
        AntigravityHandleOrchestrator.HandleDecision d = o.handle(
                inputs("antigravity-auto", PROD + "/v1internal/models/antigravity-auto:generateContent", auto));

        assertEquals(list(
                row("acquire", "gemini-pro"),
                row("reportRateLimit", "accA", "gemini-pro", 1700000030000L),
                row("reportRateLimit", "accA", "gemini-pro", 1700000030000L),
                row("reportRateLimit", "accA", "gemini-pro", 1700000030000L),
                row("acquire", "gemini-pro"), row("nextAvailableAt", "gemini-pro"),
                row("acquire", "claude"), row("reportSuccess", "accA")), a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.SERVE, d.kind);
        assertParams(d.params, "antigravity-claude-sonnet-4-6", "mp-accA", PROD, "antigravity-claude-sonnet-4-6", "sess-fixed", false);
    }

    @Test
    void terminalGeminiCli() {
        Map<String, Object> cli = quietAccount("cli1");
        RecordingAccountOps a = new RecordingAccountOps().accounts(cli).nextAvailable(null);
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("cli1", "atc", cli));
        a.enqueueAcquire(null);
        ScriptedExecutor e = new ScriptedExecutor().rateLimit(429, "exhausted your capacity on this model", null);
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), e);

        AntigravityHandleOrchestrator.HandleDecision d = o.handle(
                inputs("gemini-2.5-pro", PROD + "/v1internal/models/gemini-2.5-pro:generateContent", null));

        assertEquals(list(
                row("acquire", "gemini-cli"),
                row("reportRateLimit", "cli1", "gemini-cli", 1700000045000L),
                row("acquire", "gemini-cli"), row("nextAvailableAt", "gemini-cli")), a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.TERMINAL_ERROR, d.kind);
        assertEquals(AntigravityHandleOrchestrator.TerminalError.Kind.GEMINI_CLI_EXHAUSTED, d.terminal.kind);
        assertEquals(AntigravityHandleOrchestrator.GEMINI_CLI_EXHAUSTED_MESSAGE, d.terminal.messagePrefix);
    }

    @Test
    void terminalQuotaReset() {
        Map<String, Object> quota = quietAccount("q1");
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("remainingFraction", 0);
        pool.put("resetTime", "2023-11-14T23:00:00Z");
        Map<String, Object> cq = new LinkedHashMap<>();
        cq.put("Claude", pool);
        ((Map<String, Object>) quota.get("meta")).put("cachedQuota", cq);

        RecordingAccountOps a = new RecordingAccountOps().accounts(quota).nextAvailable(null);
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("q1", "atq", quota));
        a.enqueueAcquire(null);
        ScriptedExecutor e = new ScriptedExecutor()
                .rateLimit(429, "quota reached", null).rateLimit(429, "quota reached", null).rateLimit(429, "quota reached", null);
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), e);

        AntigravityHandleOrchestrator.HandleDecision d = o.handle(inputs("antigravity-claude-sonnet-4-6", ANTI_URL, null));

        assertEquals(list(
                row("acquire", "claude"),
                row("reportRateLimit", "q1", "claude", 1700000060000L),
                row("reportRateLimit", "q1", "claude", 1700000060000L),
                row("reportRateLimit", "q1", "claude", 1700000060000L),
                row("acquire", "claude"), row("nextAvailableAt", "claude")), a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.TERMINAL_ERROR, d.kind);
        assertEquals(AntigravityHandleOrchestrator.TerminalError.Kind.ANTIGRAVITY_QUOTA_RESET, d.terminal.kind);
        assertEquals(1700002800000L, d.terminal.resetEpochMs);
        assertEquals(2800000L, d.terminal.retryAfterMs);
        assertEquals(AntigravityHandleOrchestrator.QUOTA_RESET_PREFIX, d.terminal.messagePrefix);
        assertEquals(AntigravityHandleOrchestrator.QUOTA_RESET_SUFFIX, d.terminal.messageSuffix);
    }

    @Test
    void terminalTransientBecomesNoAccount503() {
        RecordingAccountOps a = new RecordingAccountOps().accounts(quietAccount("acc1")).nextAvailable(null);
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("acc1", "at1", quietAccount("acc1")));
        a.enqueueAcquire(null);
        ScriptedExecutor e = new ScriptedExecutor()
                .rateLimit(429, "transient burst", null).rateLimit(429, "transient burst", null).rateLimit(429, "transient burst", null);
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), e);

        AntigravityHandleOrchestrator.HandleDecision d = o.handle(inputs("antigravity-claude-sonnet-4-6", ANTI_URL, null));

        assertEquals(list(
                row("acquire", "claude"),
                row("reportRateLimit", "acc1", "claude", 1700000090000L),
                row("reportRateLimit", "acc1", "claude", 1700000090000L),
                row("reportRateLimit", "acc1", "claude", 1700000090000L),
                row("acquire", "claude"), row("nextAvailableAt", "claude")), a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.SYNTHETIC, d.kind);
        assertEquals(503, d.status);
        assertEquals("{\"error\":{\"message\":\"No available antigravity account for lane claude.\"}}", d.body);
    }

    @Test
    void prepareFails() {
        RecordingAccountOps a = new RecordingAccountOps().accounts(quietAccount("acc1"));
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("acc1", "at1", quietAccount("acc1")));
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer().failAlways(), new ScriptedExecutor());

        AntigravityHandleOrchestrator.HandleDecision d = o.handle(inputs("antigravity-claude-sonnet-4-6", ANTI_URL, null));

        assertEquals(list(row("acquire", "claude")), a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.SYNTHETIC, d.kind);
        assertEquals(502, d.status);
        assertEquals("{\"error\":{\"message\":\"antigravity request failed after 6 attempts\"}}", d.body);
    }

    @Test
    void transportFailThenOk() {
        RecordingAccountOps a = new RecordingAccountOps().accounts(quietAccount("acc1"));
        a.enqueueAcquire(new AntigravityHandleOrchestrator.Acquired("acc1", "at1", quietAccount("acc1")));
        ScriptedExecutor e = new ScriptedExecutor().transportFailed().ok(200);
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), e);

        AntigravityHandleOrchestrator.HandleDecision d = o.handle(inputs("antigravity-claude-sonnet-4-6", ANTI_URL, null));

        assertEquals(list(row("acquire", "claude"), row("reportSuccess", "acc1")), a.seq);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.SERVE, d.kind);
        assertParams(d.params, "antigravity-claude-sonnet-4-6", "mp-acc1", DAILY, "antigravity-claude-sonnet-4-6", "sess-fixed", false);
    }

    // ---- anthropic-bridge classification --------------------------------------------------------

    @Test
    void anthropicRateLimited() {
        AntigravityHandleOrchestrator o = orch(new RecordingAccountOps(), new StubPreparer(), new ScriptedExecutor());
        AntigravityHandleOrchestrator.AnthropicInnerResult r = new AntigravityHandleOrchestrator.AnthropicInnerResult();
        r.present = true; r.ok = false; r.hasBody = true; r.status = 429;
        r.chatError = true; r.rateLimited = true; r.retryAfterMs = "5000"; r.extractedErrorMessage = "limit hit";
        AntigravityHandleOrchestrator.HandleDecision d = o.classifyAnthropicResult(r);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.SYNTHETIC, d.kind);
        assertEquals(429, d.status);
        assertEquals("application/json", d.headers.get("content-type"));
        assertEquals("1", d.headers.get("x-hub-rate-limited"));
        assertEquals("5000", d.headers.get("x-hub-retry-after-ms"));
        assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"limit hit\"}}", d.body);
    }

    @Test
    void anthropicTerminalInvalidRequest() {
        AntigravityHandleOrchestrator o = orch(new RecordingAccountOps(), new StubPreparer(), new ScriptedExecutor());
        AntigravityHandleOrchestrator.AnthropicInnerResult r = new AntigravityHandleOrchestrator.AnthropicInnerResult();
        r.present = true; r.ok = false; r.hasBody = true; r.status = 400;
        r.chatError = true; r.rateLimited = false; r.extractedErrorMessage = "bad req";
        AntigravityHandleOrchestrator.HandleDecision d = o.classifyAnthropicResult(r);
        assertEquals(400, d.status);
        assertNull(d.headers.get("x-hub-rate-limited"));
        assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad req\"}}", d.body);
    }

    @Test
    void anthropicApiError() {
        AntigravityHandleOrchestrator o = orch(new RecordingAccountOps(), new StubPreparer(), new ScriptedExecutor());
        AntigravityHandleOrchestrator.AnthropicInnerResult r = new AntigravityHandleOrchestrator.AnthropicInnerResult();
        r.present = true; r.ok = false; r.hasBody = true; r.status = 500; r.detail = "upstream boom";
        AntigravityHandleOrchestrator.HandleDecision d = o.classifyAnthropicResult(r);
        assertEquals(500, d.status);
        assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"upstream boom\"}}", d.body);
    }

    @Test
    void anthropicOkBridgeStream() {
        AntigravityHandleOrchestrator o = orch(new RecordingAccountOps(), new StubPreparer(), new ScriptedExecutor());
        AntigravityHandleOrchestrator.AnthropicInnerResult r = new AntigravityHandleOrchestrator.AnthropicInnerResult();
        r.present = true; r.ok = true; r.hasBody = true; r.status = 200; r.attemptRef = new Object();
        AntigravityHandleOrchestrator.HandleDecision d = o.classifyAnthropicResult(r);
        assertEquals(AntigravityHandleOrchestrator.HandleDecision.Kind.BRIDGE_STREAM, d.kind);
        assertEquals(200, d.status);
    }

    // ---- resolveProjectId / syntheticProjectFor -------------------------------------------------

    @Test
    void resolveProjectIdDiscoversManaged() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("syntheticProjectId", "syn-r1");
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", "r1");
        account.put("refresh", "rtr1");
        account.put("expires", HandleTestDoubles.FIXED_NOW + 3_600_000L);
        account.put("meta", meta);

        RecordingAccountOps a = new RecordingAccountOps().accounts(account);
        AntigravityProjectContext.ProjectLoader loader = (access, projectId, proxy) -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cloudaicompanionProject", "disc-proj");
            return payload;
        };
        AntigravityHandleOrchestrator o = orchestrator(a, new StubPreparer(), new ScriptedExecutor(),
                id -> null, loader, ONBOARDER_UNUSED);

        String projectId = o.resolveProjectId(account, "atR", NOOP);

        assertEquals("disc-proj", projectId);
        assertEquals(list(row("mutate", "r1")), a.seq);
        assertEquals("disc-proj", ((Map<String, Object>) account.get("meta")).get("managedProjectId"));
    }

    @Test
    void syntheticProjectForGeneratesAndPersists() {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", "s1");
        account.put("refresh", "rts1");
        account.put("meta", new LinkedHashMap<>());
        RecordingAccountOps a = new RecordingAccountOps().accounts(account);
        AntigravityHandleOrchestrator o = orch(a, new StubPreparer(), new ScriptedExecutor());

        String synthetic = o.syntheticProjectFor(account);

        assertEquals("swift-spark-00000", synthetic);
        assertEquals(list(row("mutate", "s1")), a.seq);
        assertEquals("swift-spark-00000", ((Map<String, Object>) account.get("meta")).get("syntheticProjectId"));
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static void assertParams(AntigravityHandleOrchestrator.TransformParams p, String req, String proj,
                                     String endpoint, String eff, String sess, boolean streaming) {
        assertEquals(req, p.requestedModel);
        assertEquals(proj, p.projectId);
        assertEquals(endpoint, p.endpoint);
        assertEquals(eff, p.effectiveModel);
        assertEquals(sess, p.sessionId);
        assertEquals(streaming, p.streaming);
    }

    @SafeVarargs
    private static List<List<Object>> list(List<Object>... rows) {
        return new ArrayList<>(java.util.Arrays.asList(rows));
    }
}
