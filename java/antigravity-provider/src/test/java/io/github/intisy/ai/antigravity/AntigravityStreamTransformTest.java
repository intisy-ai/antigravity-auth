package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for {@link AntigravityStreamTransform}. {@code hashString} produces JS-compatible
 * {@code >>> 0}/{@code toString(16)} bytes, which {@code deduplicateThinkingText} consumes to drop
 * repeats. The {@link AntigravityThinkingBlocks.ImageSink} seam ({@code processImageData}) is
 * exercised with a capturing sink, since its real output is a non-deterministic file path.
 */
class AntigravityStreamTransformTest {

    private static final JsonCodec JSON = new TestJsonCodec();

    // Image sink that must NOT be reached by the (inlineData-free) dedup/sseLine fixtures.
    private static final AntigravityThinkingBlocks.ImageSink UNUSED_SINK = (m, d) -> {
        throw new AssertionError("image sink must not be called");
    };

    // ---- hashString (>>> 0 unsigned, base16) -----------------------------------------------------

    @Test
    void hashString_parity() {
        assertEquals("1505", AntigravityStreamTransform.hashString(""));
        assertEquals("2b606", AntigravityStreamTransform.hashString("a"));
        assertEquals("f923099", AntigravityStreamTransform.hashString("hello"));
        assertEquals("3551c8c1", AntigravityStreamTransform.hashString("hello world"));
        assertEquals("34cc38de", AntigravityStreamTransform.hashString("The quick brown fox jumps over the lazy dog"));
        assertEquals("b886bce", AntigravityStreamTransform.hashString("dup"));
        assertEquals("7c9dca2b", AntigravityStreamTransform.hashString("same"));
        assertEquals("c0d929d8", AntigravityStreamTransform.hashString("a longer string that will overflow 32 bits many times over 1234567890"));
        assertEquals("b39bdc54", AntigravityStreamTransform.hashString("unicode: éè你好"));
    }

    // ---- transformStreamingPayload ---------------------------------------------------------------

    // Harness `upcase` transform: (r) => ({ wrapped: r }).
    private static final AntigravityStreamTransform.ThinkingPartsTransform UPCASE = r -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wrapped", r);
        return m;
    };

    @Test
    void transformStreamingPayload_parity() {
        assertEquals("event: x\n:comment\nplain",
                AntigravityStreamTransform.transformStreamingPayload(JSON, "event: x\n:comment\nplain", null));
        assertEquals("data: {\"wrapped\":{\"a\":1}}",
                AntigravityStreamTransform.transformStreamingPayload(JSON, "data: {\"response\":{\"a\":1}}", UPCASE));
        assertEquals("data: {\"a\":1}",
                AntigravityStreamTransform.transformStreamingPayload(JSON, "data: {\"response\":{\"a\":1}}", null));
        assertEquals("data: {\"other\":1}",
                AntigravityStreamTransform.transformStreamingPayload(JSON, "data: {\"other\":1}", UPCASE));
        assertEquals("data: {\"wrapped\":null}",
                AntigravityStreamTransform.transformStreamingPayload(JSON, "data: {\"response\":null}", UPCASE));
        assertEquals("data: ",
                AntigravityStreamTransform.transformStreamingPayload(JSON, "data: ", UPCASE));
        assertEquals("data: {not json",
                AntigravityStreamTransform.transformStreamingPayload(JSON, "data: {not json", UPCASE));
        assertEquals("data: {\"wrapped\":{\"x\":1}}\nkeep me\ndata: {\"wrapped\":{\"y\":2}}",
                AntigravityStreamTransform.transformStreamingPayload(JSON,
                        "data: {\"response\":{\"x\":1}}\nkeep me\ndata: {\"response\":{\"y\":2}}", UPCASE));
    }

    // ---- deduplicateThinkingText -----------------------------------------------------------------

    private AntigravityStreamTransform.ThoughtBuffer buf(Object[]... presets) {
        AntigravityStreamTransform.ThoughtBuffer b = AntigravityStreamTransform.createThoughtBuffer();
        for (Object[] p : presets) b.set((Integer) p[0], (String) p[1]);
        return b;
    }

    private String bufSnapshot(AntigravityStreamTransform.ThoughtBuffer b) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            String v = b.get(i);
            if (v != null) m.put(String.valueOf(i), v);
        }
        return JSON.stringify(m);
    }

    @Test
    void dedup_nonObjectPassthrough() {
        AntigravityStreamTransform.ThoughtBuffer b = buf();
        Object r = AntigravityStreamTransform.deduplicateThinkingText("just a string", b, null, UNUSED_SINK);
        assertEquals("just a string", r);
        assertEquals("{}", bufSnapshot(b));
    }

    @Test
    void dedup_candFirstSend() {
        AntigravityStreamTransform.ThoughtBuffer b = buf();
        Object r = AntigravityStreamTransform.deduplicateThinkingText(
                JSON.parse("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hello world\"}]}}]}"), b, null, UNUSED_SINK);
        assertEquals("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hello world\",\"thinking\":\"hello world\"}]}}]}", JSON.stringify(r));
        assertEquals("{\"0\":\"hello world\"}", bufSnapshot(b));
    }

    @Test
    void dedup_candDelta() {
        AntigravityStreamTransform.ThoughtBuffer b = buf(new Object[]{0, "hello "});
        Object r = AntigravityStreamTransform.deduplicateThinkingText(
                JSON.parse("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hello world\"}]}}]}"), b, null, UNUSED_SINK);
        assertEquals("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"world\",\"thinking\":\"world\"}]}}]}", JSON.stringify(r));
        assertEquals("{\"0\":\"hello world\"}", bufSnapshot(b));
    }

    @Test
    void dedup_candNoDelta_dropped() {
        AntigravityStreamTransform.ThoughtBuffer b = buf(new Object[]{0, "hello"});
        Object r = AntigravityStreamTransform.deduplicateThinkingText(
                JSON.parse("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hello\"}]}}]}"), b, null, UNUSED_SINK);
        assertEquals("{\"candidates\":[{\"content\":{\"parts\":[]}}]}", JSON.stringify(r));
        assertEquals("{\"0\":\"hello\"}", bufSnapshot(b));
    }

    @Test
    void dedup_candReset_notStartsWith() {
        AntigravityStreamTransform.ThoughtBuffer b = buf(new Object[]{0, "unrelated"});
        Object r = AntigravityStreamTransform.deduplicateThinkingText(
                JSON.parse("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"brand new\"}]}}]}"), b, null, UNUSED_SINK);
        assertEquals("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"brand new\"}]}}]}", JSON.stringify(r));
        assertEquals("{\"0\":\"brand new\"}", bufSnapshot(b));
    }

    @Test
    void dedup_candMixed_nonThinkingAndNoContentCandidate() {
        AntigravityStreamTransform.ThoughtBuffer b = buf();
        Object r = AntigravityStreamTransform.deduplicateThinkingText(
                JSON.parse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"plain\"},{\"thought\":true,\"text\":\"abc\"}]}},{\"finishReason\":\"STOP\"}]}"), b, null, UNUSED_SINK);
        assertEquals("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"plain\"},{\"thought\":true,\"text\":\"abc\",\"thinking\":\"abc\"}]}},{\"finishReason\":\"STOP\"}]}", JSON.stringify(r));
        assertEquals("{\"0\":\"abc\"}", bufSnapshot(b));
    }

    @Test
    void dedup_hashSetCandidates_dropsRepeat() {
        AntigravityStreamTransform.ThoughtBuffer b = buf();
        Set<String> hashes = new TreeSet<>();
        Object r = AntigravityStreamTransform.deduplicateThinkingText(
                JSON.parse("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"dup\"}]}},{\"content\":{\"parts\":[{\"type\":\"thinking\",\"thinking\":\"dup\"}]}}]}"), b, hashes, UNUSED_SINK);
        assertEquals("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"dup\",\"thinking\":\"dup\"}]}},{\"content\":{\"parts\":[]}}]}", JSON.stringify(r));
        assertEquals("{\"0\":\"dup\",\"1\":\"dup\"}", bufSnapshot(b));
        assertEquals(1, hashes.size());
        assertTrue(hashes.contains("b886bce"));
    }

    @Test
    void dedup_contentFirstSend() {
        AntigravityStreamTransform.ThoughtBuffer b = buf();
        Object r = AntigravityStreamTransform.deduplicateThinkingText(
                JSON.parse("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"reason a\"}]}"), b, null, UNUSED_SINK);
        assertEquals("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"reason a\",\"text\":\"reason a\"}]}", JSON.stringify(r));
        assertEquals("{\"0\":\"reason a\"}", bufSnapshot(b));
    }

    @Test
    void dedup_contentDelta() {
        AntigravityStreamTransform.ThoughtBuffer b = buf(new Object[]{0, "reason "});
        Object r = AntigravityStreamTransform.deduplicateThinkingText(
                JSON.parse("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"reason abc\"}]}"), b, null, UNUSED_SINK);
        assertEquals("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"abc\",\"text\":\"abc\"}]}", JSON.stringify(r));
        assertEquals("{\"0\":\"reason abc\"}", bufSnapshot(b));
    }

    @Test
    void dedup_contentTwoBlocks_thinkingIndex() {
        AntigravityStreamTransform.ThoughtBuffer b = buf();
        Object r = AntigravityStreamTransform.deduplicateThinkingText(
                JSON.parse("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"first\"},{\"type\":\"thinking\",\"thinking\":\"second\"},{\"type\":\"text\",\"text\":\"t\"}]}"), b, null, UNUSED_SINK);
        assertEquals("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"first\",\"text\":\"first\"},{\"type\":\"thinking\",\"thinking\":\"second\",\"text\":\"second\"},{\"type\":\"text\",\"text\":\"t\"}]}", JSON.stringify(r));
        assertEquals("{\"0\":\"first\",\"1\":\"second\"}", bufSnapshot(b));
    }

    @Test
    void dedup_hashSetContent_dropsRepeat() {
        AntigravityStreamTransform.ThoughtBuffer b = buf();
        Set<String> hashes = new TreeSet<>();
        Object r = AntigravityStreamTransform.deduplicateThinkingText(
                JSON.parse("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"same\"},{\"type\":\"thinking\",\"thinking\":\"same\"}]}"), b, hashes, UNUSED_SINK);
        assertEquals("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"same\",\"text\":\"same\"}]}", JSON.stringify(r));
        assertEquals("{\"0\":\"same\",\"1\":\"same\"}", bufSnapshot(b));
        assertEquals(1, hashes.size());
        assertTrue(hashes.contains("7c9dca2b"));
    }

    // ImageSink seam (Java-only): inlineData -> {text: sinkResult}; falsy result falls through.
    @Test
    void dedup_imageSinkSeam() {
        AntigravityStreamTransform.ThoughtBuffer b = buf();
        List<Object> captured = new ArrayList<>();
        AntigravityThinkingBlocks.ImageSink capturing = (mime, data) -> {
            captured.add(mime);
            captured.add(data);
            return "IMG(" + mime + ")";
        };
        Object r = AntigravityStreamTransform.deduplicateThinkingText(
                JSON.parse("{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"AAAA\"}}]}}]}"), b, null, capturing);
        assertEquals("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"IMG(image/png)\"}]}}]}", JSON.stringify(r));
        assertEquals("[image/png, AAAA]", captured.toString());

        // Falsy (null) sink result -> the original part passes through unchanged.
        AntigravityThinkingBlocks.ImageSink nullSink = (mime, data) -> null;
        Object r2 = AntigravityStreamTransform.deduplicateThinkingText(
                JSON.parse("{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"AAAA\"}}]}}]}"), buf(), null, nullSink);
        assertEquals("{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"AAAA\"}}]}}]}", JSON.stringify(r2));
    }

    // Copy-vs-mutate: the input tree must not be mutated (dedup spreads {...p}).
    @Test
    void dedup_doesNotMutateInput() {
        Object input = JSON.parse("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hello world\"}]}}]}");
        String before = JSON.stringify(input);
        AntigravityStreamTransform.deduplicateThinkingText(input, buf(), null, UNUSED_SINK);
        assertEquals(before, JSON.stringify(input));
    }

    // ---- cacheThinkingSignaturesFromResponse -----------------------------------------------------

    private static final class CapturingStore implements AntigravityStreamTransform.SignatureStore {
        final List<String> writes = new ArrayList<>();

        @Override
        public void set(String sessionKey, String text, String signature) {
            writes.add(sessionKey + "|" + text + "|" + signature);
        }
    }

    private static final class CapturingCallback implements AntigravityStreamTransform.CacheSignatureCallback {
        final List<String> calls = new ArrayList<>();

        @Override
        public void onCacheSignature(String sessionKey, String text, String signature) {
            calls.add(sessionKey + "|" + text + "|" + signature);
        }
    }

    private CacheResult cache(String inputJson, Object[]... presets) {
        CapturingStore store = new CapturingStore();
        CapturingCallback cb = new CapturingCallback();
        AntigravityStreamTransform.ThoughtBuffer b = buf(presets);
        AntigravityStreamTransform.cacheThinkingSignaturesFromResponse(JSON.parse(inputJson), "sess-key", store, b, cb);
        CacheResult r = new CacheResult();
        r.stored = store.writes;
        r.calls = cb.calls;
        r.buffer = bufSnapshot(b);
        return r;
    }

    private static final class CacheResult {
        List<String> stored;
        List<String> calls;
        String buffer;
    }

    @Test
    void cache_nonObject() {
        CacheResult r = cache("42");
        assertTrue(r.stored.isEmpty());
        assertTrue(r.calls.isEmpty());
        assertEquals("{}", r.buffer);
    }

    @Test
    void cache_candAccumThenSign() {
        CacheResult r = cache("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"part1 \"},{\"thought\":true,\"text\":\"part2\",\"thoughtSignature\":\"SIG-A\"}]}}]}");
        assertEquals("[sess-key|part1 part2|SIG-A]", r.stored.toString());
        assertEquals("[sess-key|part1 part2|SIG-A]", r.calls.toString());
        assertEquals("{\"0\":\"part1 part2\"}", r.buffer);
    }

    @Test
    void cache_candSignNoText_notCached() {
        CacheResult r = cache("{\"candidates\":[{\"content\":{\"parts\":[{\"thoughtSignature\":\"SIG-X\"}]}}]}");
        assertTrue(r.stored.isEmpty());
        assertTrue(r.calls.isEmpty());
        assertEquals("{}", r.buffer);
    }

    @Test
    void cache_candIndexKeying() {
        CacheResult r = cache("{\"candidates\":[{\"content\":{\"parts\":[{\"type\":\"thinking\",\"thinking\":\"c0\",\"thoughtSignature\":\"S0\"}]}},{\"content\":{\"parts\":[{\"type\":\"thinking\",\"thinking\":\"c1\",\"thoughtSignature\":\"S1\"}]}}]}");
        assertEquals("[sess-key|c0|S0, sess-key|c1|S1]", r.stored.toString());
        assertEquals("{\"0\":\"c0\",\"1\":\"c1\"}", r.buffer);
    }

    @Test
    void cache_contentAccumSign_claudeBufferKey0() {
        CacheResult r = cache("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"claude thought \"},{\"type\":\"thinking\",\"thinking\":\"more\",\"signature\":\"CSIG\"}]}");
        assertEquals("[sess-key|claude thought more|CSIG]", r.stored.toString());
        assertEquals("[sess-key|claude thought more|CSIG]", r.calls.toString());
        assertEquals("{\"0\":\"claude thought more\"}", r.buffer);
    }

    @Test
    void cache_contentPresetBuffer() {
        CacheResult r = cache("{\"content\":[{\"signature\":\"PSIG\"}]}", new Object[]{0, "preloaded"});
        assertEquals("[sess-key|preloaded|PSIG]", r.stored.toString());
        assertEquals("{\"0\":\"preloaded\"}", r.buffer);
    }

    // ---- transformSseLine ------------------------------------------------------------------------

    // Harness stubs: transform (r)=>({transformed:true,r}); onInjectDebug (r,dbg)=>({debugInjected:dbg,r}).
    private static final AntigravityStreamTransform.ThinkingPartsTransform TRANSFORM = r -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transformed", true);
        m.put("r", r);
        return m;
    };
    private static final AntigravityStreamTransform.InjectDebug INJECT_DEBUG = (r, dbg) -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("debugInjected", dbg);
        m.put("r", r);
        return m;
    };

    private String sseLine(String line, boolean cacheSignatures, String sessionKey, String debugText,
                           boolean useHashes, boolean useTransform, boolean useInjectDebug, boolean preInjected,
                           CapturingStore store, boolean[] injectedOut) {
        AntigravityStreamTransform.ThoughtBuffer thoughtBuffer = buf();
        AntigravityStreamTransform.ThoughtBuffer sentBuffer = buf();
        AntigravityStreamTransform.DebugState debugState = new AntigravityStreamTransform.DebugState(preInjected);
        Set<String> hashes = useHashes ? new TreeSet<>() : null;
        String out = AntigravityStreamTransform.transformSseLine(
                JSON, line, store, thoughtBuffer, sentBuffer,
                new CapturingCallback(),
                useInjectDebug ? INJECT_DEBUG : null,
                useTransform ? TRANSFORM : null,
                UNUSED_SINK,
                sessionKey, debugText, cacheSignatures, hashes, debugState);
        injectedOut[0] = debugState.injected;
        return out;
    }

    @Test
    void sseLine_nonData() {
        boolean[] inj = new boolean[1];
        assertEquals("event: ping", sseLine("event: ping", false, null, null, false, true, false, false, new CapturingStore(), inj));
    }

    @Test
    void sseLine_noResponse() {
        boolean[] inj = new boolean[1];
        assertEquals("data: {\"x\":1}", sseLine("data: {\"x\":1}", false, null, null, false, true, false, false, new CapturingStore(), inj));
    }

    @Test
    void sseLine_emptyPayload() {
        boolean[] inj = new boolean[1];
        assertEquals("data: ", sseLine("data: ", false, null, null, false, true, false, false, new CapturingStore(), inj));
    }

    @Test
    void sseLine_transformOnly() {
        boolean[] inj = new boolean[1];
        assertEquals("data: {\"transformed\":true,\"r\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}",
                sseLine("data: {\"response\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}", false, null, null, false, true, false, false, new CapturingStore(), inj));
    }

    @Test
    void sseLine_dedupThenTransform() {
        boolean[] inj = new boolean[1];
        assertEquals("data: {\"transformed\":true,\"r\":{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hello\",\"thinking\":\"hello\"}]}}]}}",
                sseLine("data: {\"response\":{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hello\"}]}}]}}", false, null, null, true, true, false, false, new CapturingStore(), inj));
    }

    @Test
    void sseLine_cacheOn_writesStore() {
        boolean[] inj = new boolean[1];
        CapturingStore store = new CapturingStore();
        assertEquals("data: {\"transformed\":true,\"r\":{\"content\":[{\"type\":\"thinking\",\"thinking\":\"deep\",\"signature\":\"SIGSSE\",\"text\":\"deep\"}]}}",
                sseLine("data: {\"response\":{\"content\":[{\"type\":\"thinking\",\"thinking\":\"deep\",\"signature\":\"SIGSSE\"}]}}", true, "sk", null, false, true, false, false, store, inj));
        assertEquals("[sk|deep|SIGSSE]", store.writes.toString());
    }

    @Test
    void sseLine_cacheOnNoKey_noWrite() {
        boolean[] inj = new boolean[1];
        CapturingStore store = new CapturingStore();
        assertEquals("data: {\"transformed\":true,\"r\":{\"content\":[{\"type\":\"thinking\",\"thinking\":\"deep\",\"signature\":\"SIGSSE\",\"text\":\"deep\"}]}}",
                sseLine("data: {\"response\":{\"content\":[{\"type\":\"thinking\",\"thinking\":\"deep\",\"signature\":\"SIGSSE\"}]}}", true, null, null, false, true, false, false, store, inj));
        assertTrue(store.writes.isEmpty());
    }

    @Test
    void sseLine_debugInject_once() {
        boolean[] inj = new boolean[1];
        assertEquals("data: {\"transformed\":true,\"r\":{\"debugInjected\":\"DBG\",\"r\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}}",
                sseLine("data: {\"response\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}", false, null, "DBG", false, true, true, false, new CapturingStore(), inj));
        assertTrue(inj[0]);
    }

    @Test
    void sseLine_debugAlreadyInjected_skips() {
        boolean[] inj = new boolean[1];
        assertEquals("data: {\"transformed\":true,\"r\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}",
                sseLine("data: {\"response\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}", false, null, "DBG", false, true, true, true, new CapturingStore(), inj));
        assertTrue(inj[0]);
    }

    @Test
    void sseLine_noTransformCallback() {
        boolean[] inj = new boolean[1];
        assertEquals("data: {\"k\":\"v\"}",
                sseLine("data: {\"response\":{\"k\":\"v\"}}", false, null, null, false, false, false, false, new CapturingStore(), inj));
    }
}
