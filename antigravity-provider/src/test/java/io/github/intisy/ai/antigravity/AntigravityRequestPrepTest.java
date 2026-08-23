package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.seam.NoopLogger;
import io.github.intisy.ai.antigravity.AntigravityRequestPrep.Deps;
import io.github.intisy.ai.antigravity.AntigravityRequestPrep.Input;
import io.github.intisy.ai.antigravity.AntigravityRequestPrep.PrepareResult;
import io.github.intisy.ai.api.seam.JsonCodec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.list;
import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for {@link AntigravityRequestPrep#prepare}. Body strings are compared byte-for-byte
 * (key order verified); the ANTIGRAVITY_SYSTEM_INSTRUCTION / CLAUDE_TOOL_SYSTEM_INSTRUCTION /
 * interleaved hint are spliced from the reused constants via {@link #jesc}.
 */
final class AntigravityRequestPrepTest {

    private final JsonCodec json = new TestJsonCodec();
    private static final String PLUGIN_SID = "-00000000-0000-4000-8000-000000000001";
    private static final String SYS = AntigravityRequestPrep.ANTIGRAVITY_SYSTEM_INSTRUCTION;
    private static final String HINT = ClaudeTransforms.CLAUDE_INTERLEAVED_THINKING_HINT;
    private static final String TOOL = AntigravityRequestPrep.CLAUDE_TOOL_SYSTEM_INSTRUCTION;
    private static final Map<String, Object> FP = map("userAgent", "antigravity/9.9.9 windows/amd64");

    private Deps deps() {
        Deps d = new Deps();
        d.json = json;
        d.ids = RequestTestDoubles.counterIds();
        d.random = RequestTestDoubles.fixedRandom(0.5);
        d.hasher = RequestTestDoubles.sha256();
        d.cachedSignatureLookup = new RequestTestDoubles.MapLookup();
        d.signatureStore = new RequestTestDoubles.MapStore();
        d.thinkingRecovery = new RequestTestDoubles.Recovery();
        d.logger = NoopLogger.INSTANCE;
        d.keepThinking = false;
        d.pluginSessionId = PLUGIN_SID;
        d.selectedHeaders = map("User-Agent", "antigravity/UNUSED windows/amd64");
        return d;
    }

    private Input base(String url, String method, Map<String, Object> headers, String body,
                       String projectId, String headerStyle) {
        Input in = new Input();
        in.url = url;
        in.method = method;
        in.headers = headers;
        in.body = body;
        in.accessToken = "tok";
        in.projectId = projectId;
        in.headerStyle = headerStyle;
        return in;
    }

    private static Map<String, Object> hdrs() {
        return map("content-type", "application/json", "x-api-key", "dummy", "x-goog-api-key", "dummy2");
    }

    // ---- passthroughs ---------------------------------------------------------------------------

    @Test
    void passthroughNotGenerative() {
        Input in = base("https://example.com/foo", "POST", map("x-api-key", "k"), "{\"a\":1}", "proj", "antigravity");
        PrepareResult r = AntigravityRequestPrep.prepare(in, deps());
        assertEquals("https://example.com/foo", r.request);
        assertEquals(map("x-api-key", "k"), r.headers);
        assertEquals("{\"a\":1}", r.body);
        assertEquals(false, r.streaming);
        assertNull(r.requestedModel);
        assertNull(r.projectId);
        assertNull(r.sessionId);
        assertNull(r.toolDebugMissing);
        assertEquals("antigravity", r.headerStyle);
    }

    @Test
    void passthroughNoModelMatch() {
        Input in = base("https://cloudcode-pa.googleapis.com/v1/other", "POST", hdrs(), "{\"a\":1}", "proj", "antigravity");
        in.fingerprint = FP;
        PrepareResult r = AntigravityRequestPrep.prepare(in, deps());
        assertEquals("https://cloudcode-pa.googleapis.com/v1/other", r.request);
        assertEquals(map("authorization", "Bearer tok", "content-type", "application/json"), r.headers);
        assertEquals("{\"a\":1}", r.body);
        assertEquals(false, r.streaming);
        assertEquals("antigravity", r.headerStyle);
    }

    // ---- unwrapped gemini -----------------------------------------------------------------------

    @Test
    void unwrappedGeminiAntigravitySyntheticProject() {
        Input in = base("https://cloudcode-pa.googleapis.com/v1/models/gemini-2.5-flash:streamGenerateContent",
                "POST", hdrs(), json.stringify(map("contents", list(map("role", "user", "parts", list(map("text", "hello")))))),
                "", "antigravity");
        in.fingerprint = FP;
        PrepareResult r = AntigravityRequestPrep.prepare(in, deps());

        assertEquals("https://cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse", r.request);
        assertTrue(r.streaming);
        assertEquals("gemini-2.5-flash", r.effectiveModel);
        assertEquals("swift-spark-00000", r.projectId);
        String sid = PLUGIN_SID + ":gemini-2.5-flash:default:seed-2cf24dba5fb0a30e";
        assertEquals(sid, r.sessionId);
        assertEquals(map("accept", "text/event-stream", "authorization", "Bearer tok",
                "content-type", "application/json", "user-agent", "antigravity/9.9.9 windows/amd64"), r.headers);
        String expected = "{\"project\":\"swift-spark-00000\",\"model\":\"gemini-2.5-flash\",\"request\":{"
                + "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hello\"}]}],"
                + "\"generationConfig\":{\"thinkingConfig\":{\"includeThoughts\":true,\"thinkingBudget\":16000}},"
                + "\"systemInstruction\":{\"role\":\"user\",\"parts\":[{\"text\":\"" + jesc(SYS) + "\"}]},"
                + "\"sessionId\":\"" + sid + "\"},"
                + "\"requestType\":\"agent\",\"userAgent\":\"antigravity\",\"requestId\":\"agent-00000000-0000-4000-8000-000000000002\"}";
        assertEquals(expected, r.body);
        assertEquals(Integer.valueOf(0), r.toolDebugMissing);
        assertEquals("", r.toolDebugSummary);
    }

    @Test
    void unwrappedGeminiAntigravityWithProject() {
        Input in = base("https://cloudcode-pa.googleapis.com/v1/models/gemini-2.5-flash:streamGenerateContent",
                "POST", hdrs(), json.stringify(map("contents", list(map("role", "user", "parts", list(map("text", "hi")))),
                        "systemInstruction", map("parts", list(map("text", "be nice"))))),
                "my-project", "antigravity");
        in.fingerprint = FP;
        PrepareResult r = AntigravityRequestPrep.prepare(in, deps());

        assertEquals("my-project", r.projectId);
        String sid = PLUGIN_SID + ":gemini-2.5-flash:my-project:seed-dc190784b68a379c";
        assertEquals(sid, r.sessionId);
        String expected = "{\"project\":\"my-project\",\"model\":\"gemini-2.5-flash\",\"request\":{"
                + "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],"
                + "\"systemInstruction\":{\"parts\":[{\"text\":\"" + jesc(SYS + "\n\nbe nice") + "\"}],\"role\":\"user\"},"
                + "\"generationConfig\":{\"thinkingConfig\":{\"includeThoughts\":true,\"thinkingBudget\":16000}},"
                + "\"sessionId\":\"" + sid + "\"},"
                + "\"requestType\":\"agent\",\"userAgent\":\"antigravity\",\"requestId\":\"agent-00000000-0000-4000-8000-000000000001\"}";
        assertEquals(expected, r.body);
    }

    @Test
    void unwrappedGeminiCli() {
        Input in = base("https://cloudcode-pa.googleapis.com/v1/models/gemini-2.5-flash:streamGenerateContent",
                "POST", hdrs(), json.stringify(map("contents", list(map("role", "user", "parts", list(map("text", "hi")))))),
                "", "gemini-cli");
        PrepareResult r = AntigravityRequestPrep.prepare(in, deps());

        assertEquals("", r.projectId);
        assertEquals("gemini-cli", r.headerStyle);
        assertEquals(map("accept", "text/event-stream", "authorization", "Bearer tok",
                "client-metadata", "ideType=IDE_UNSPECIFIED,platform=PLATFORM_UNSPECIFIED,pluginType=GEMINI",
                "content-type", "application/json", "user-agent", "google-api-nodejs-client/9.15.1",
                "x-goog-api-client", "gl-node/22.17.0"), r.headers);
        String sid = PLUGIN_SID + ":gemini-2.5-flash:default:seed-8f434346648f6b96";
        String expected = "{\"project\":\"\",\"model\":\"gemini-2.5-flash\",\"request\":{"
                + "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hi\"}]}],"
                + "\"generationConfig\":{\"thinkingConfig\":{\"includeThoughts\":true,\"thinkingBudget\":16000}},"
                + "\"sessionId\":\"" + sid + "\"}}";
        assertEquals(expected, r.body);
    }

    @Test
    void wrappedGeminiNonClaude() {
        Input in = base("https://cloudcode-pa.googleapis.com/v1/models/gemini-2.5-flash:generateContent",
                "POST", hdrs(), json.stringify(map("project", "wrapped-proj",
                        "request", map("contents", list(map("role", "user", "parts", list(map("text", "q"), map("nope", 1L))))))),
                "ignored-here", "antigravity");
        in.fingerprint = FP;
        PrepareResult r = AntigravityRequestPrep.prepare(in, deps());

        assertEquals("ignored-here", r.projectId);
        String sid = PLUGIN_SID + ":gemini-2.5-flash:wrapped-proj:seed-8e35c2cd3bf6641b";
        assertEquals(sid, r.sessionId);
        String expected = "{\"project\":\"wrapped-proj\",\"request\":{"
                + "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"q\"}]}],\"sessionId\":\"" + sid + "\"},"
                + "\"model\":\"gemini-2.5-flash\"}";
        assertEquals(expected, r.body);
    }

    @Test
    void unwrappedWithToolsNonClaude() {
        Input in = base("https://cloudcode-pa.googleapis.com/v1/models/gemini-2.5-flash:generateContent",
                "POST", hdrs(), json.stringify(map(
                        "contents", list(map("role", "user", "parts", list(map("text", "use tool")))),
                        "tools", list(map("functionDeclarations", list(map("name", "get_weather", "description", "d",
                                "parameters", map("type", "object", "properties", map("city", map("type", "string")), "required", list("city")))))))),
                "p1", "antigravity");
        in.fingerprint = FP;
        PrepareResult r = AntigravityRequestPrep.prepare(in, deps());

        String sid = PLUGIN_SID + ":gemini-2.5-flash:p1:seed-967c46073f00d223";
        assertEquals(Integer.valueOf(1), r.toolDebugMissing);
        assertEquals("idx=0, hasCustom=true, customSchema=true, hasFunction=false, functionSchema=false", r.toolDebugSummary);
        assertEquals("[{\"functionDeclarations\":[{\"name\":\"get_weather\",\"description\":\"d\",\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}}]}]", r.toolDebugPayload);
        String expected = "{\"project\":\"p1\",\"model\":\"gemini-2.5-flash\",\"request\":{"
                + "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"use tool\"}]}],"
                + "\"tools\":[{\"functionDeclarations\":[{\"name\":\"get_weather\",\"description\":\"d\",\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}}]}],"
                + "\"generationConfig\":{\"thinkingConfig\":{\"includeThoughts\":true,\"thinkingBudget\":16000}},"
                + "\"systemInstruction\":{\"role\":\"user\",\"parts\":[{\"text\":\"" + jesc(SYS) + "\"}]},"
                + "\"sessionId\":\"" + sid + "\"},"
                + "\"requestType\":\"agent\",\"userAgent\":\"antigravity\",\"requestId\":\"agent-00000000-0000-4000-8000-000000000001\"}";
        assertEquals(expected, r.body);
    }

    @Test
    void getRequestBodyStripped() {
        Input in = base("https://cloudcode-pa.googleapis.com/v1/models/gemini-2.5-flash:streamGenerateContent",
                "GET", hdrs(), json.stringify(map("contents", list())), "p1", "antigravity");
        in.fingerprint = FP;
        PrepareResult r = AntigravityRequestPrep.prepare(in, deps());
        assertNull(r.body);
        assertNull(r.sessionId);
        assertEquals("p1", r.projectId);
        assertTrue(r.streaming);
    }

    // ---- claude ---------------------------------------------------------------------------------

    @Test
    void claudeNonThinkingUnwrapped() {
        Input in = base("https://cloudcode-pa.googleapis.com/v1/models/claude-sonnet-4-6:generateContent",
                "POST", hdrs(), json.stringify(map("contents", list(map("role", "user", "parts", list(map("text", "q")))))),
                "cp", "antigravity");
        in.fingerprint = FP;
        PrepareResult r = AntigravityRequestPrep.prepare(in, deps());

        assertEquals("claude-sonnet-4-6", r.effectiveModel);
        String sid = PLUGIN_SID + ":claude-sonnet-4-6:cp:seed-8e35c2cd3bf6641b";
        String expected = "{\"project\":\"cp\",\"model\":\"claude-sonnet-4-6\",\"request\":{"
                + "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"q\"}]}],"
                + "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"VALIDATED\"}},"
                + "\"systemInstruction\":{\"role\":\"user\",\"parts\":[{\"text\":\"" + jesc(SYS) + "\"}]},"
                + "\"sessionId\":\"" + sid + "\"},"
                + "\"requestType\":\"agent\",\"userAgent\":\"antigravity\",\"requestId\":\"agent-00000000-0000-4000-8000-000000000001\"}";
        assertEquals(expected, r.body);
    }

    @Test
    void claudeThinkingWithTools() {
        Input in = base("https://cloudcode-pa.googleapis.com/v1/models/claude-opus-4-6-thinking:generateContent",
                "POST", hdrs(), json.stringify(map(
                        "contents", list(map("role", "user", "parts", list(map("text", "use tool")))),
                        "tools", list(map("name", "get_weather", "description", "d",
                                "input_schema", map("type", "object", "properties", map("city", map("type", "string")), "required", list("city")))))),
                "cp", "antigravity");
        in.fingerprint = FP;
        in.claudeToolHardening = false;
        PrepareResult r = AntigravityRequestPrep.prepare(in, deps());

        assertEquals(map("anthropic-beta", "interleaved-thinking-2025-05-14", "authorization", "Bearer tok",
                "content-type", "application/json", "user-agent", "antigravity/9.9.9 windows/amd64"), r.headers);
        String sid = PLUGIN_SID + ":claude-opus-4-6-thinking:cp:seed-4b0458b9771a59ba";
        String expected = "{\"project\":\"cp\",\"model\":\"claude-opus-4-6-thinking\",\"request\":{"
                + "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"use tool\"}]}],"
                + "\"tools\":[{\"functionDeclarations\":[{\"name\":\"get_weather\",\"description\":\"d\",\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}}]}],"
                + "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"VALIDATED\"}},"
                + "\"generationConfig\":{\"thinkingConfig\":{\"include_thoughts\":true,\"thinking_budget\":32768},\"maxOutputTokens\":64000},"
                + "\"systemInstruction\":{\"parts\":[{\"text\":\"" + jesc(SYS + "\n\n" + HINT) + "\"}],\"role\":\"user\"},"
                + "\"sessionId\":\"" + sid + "\"},"
                + "\"requestType\":\"agent\",\"userAgent\":\"antigravity\",\"requestId\":\"agent-00000000-0000-4000-8000-000000000001\"}";
        assertEquals(expected, r.body);
        assertEquals("decl=get_weather,src=function/custom,hasSchema=y", r.toolDebugSummary);
    }

    @Test
    void claudeNonThinkingToolsHardening() {
        Input in = base("https://cloudcode-pa.googleapis.com/v1/models/claude-sonnet-4-6:generateContent",
                "POST", hdrs(), json.stringify(map(
                        "contents", list(map("role", "user", "parts", list(map("text", "q")))),
                        "tools", list(map("name", "lookup", "description", "d",
                                "input_schema", map("type", "object", "properties", map("q", map("type", "string")), "required", list("q")))))),
                "cp", "gemini-cli");
        in.claudeToolHardening = true;
        PrepareResult r = AntigravityRequestPrep.prepare(in, deps());

        String sid = PLUGIN_SID + ":claude-sonnet-4-6:cp:seed-396f28325db27983";
        String expected = "{\"project\":\"cp\",\"model\":\"claude-sonnet-4-6\",\"request\":{"
                + "\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"q\"}]}],"
                + "\"tools\":[{\"functionDeclarations\":[{\"name\":\"lookup\",\"description\":\"d\\n\\n⚠️ STRICT PARAMETERS: q (string, REQUIRED).\",\"parameters\":{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}}]}],"
                + "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"VALIDATED\"}},"
                + "\"systemInstruction\":{\"role\":\"user\",\"parts\":[{\"text\":\"" + jesc(TOOL) + "\"}]},"
                + "\"sessionId\":\"" + sid + "\"}}";
        assertEquals(expected, r.body);
    }

    @Test
    void claudeThinkingForceRecovery() {
        Deps d = deps();
        RequestTestDoubles.MapStore store = (RequestTestDoubles.MapStore) d.signatureStore;
        Input in = base("https://cloudcode-pa.googleapis.com/v1/models/claude-opus-4-6-thinking:generateContent",
                "POST", hdrs(), json.stringify(map("contents", list(
                        map("role", "user", "parts", list(map("text", "start"))),
                        map("role", "model", "parts", list(map("functionCall", map("name", "f", "args", map())))),
                        map("role", "user", "parts", list(map("functionResponse", map("name", "f", "response", map()))))))),
                "cp", "gemini-cli");
        in.forceThinkingRecovery = true;
        PrepareResult r = AntigravityRequestPrep.prepare(in, d);

        assertEquals("Thinking recovery: retrying with fresh turn (API error)", r.thinkingRecoveryMessage);
        String sid = PLUGIN_SID + ":claude-opus-4-6-thinking:cp:seed-cced28c6dc3f99c2";
        assertTrue(store.deleted.contains(sid), "store.delete called with signatureSessionKey");
        String expected = "{\"project\":\"cp\",\"model\":\"claude-opus-4-6-thinking\",\"request\":{"
                + "\"contents\":["
                + "{\"role\":\"user\",\"parts\":[{\"text\":\"start\"}]},"
                + "{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"f\",\"args\":{},\"id\":\"tool-call-1\"},\"thought_signature\":\"skip_thought_signature_validator\",\"thoughtSignature\":\"skip_thought_signature_validator\"}]},"
                + "{\"parts\":[{\"functionResponse\":{\"name\":\"f\",\"response\":{},\"id\":\"tool-call-1\"}}],\"role\":\"user\"},"
                + "{\"role\":\"model\",\"parts\":[{\"text\":\"[Tool execution completed.]\"}]},"
                + "{\"role\":\"user\",\"parts\":[{\"text\":\"[Continue]\"}]}],"
                + "\"toolConfig\":{\"functionCallingConfig\":{\"mode\":\"VALIDATED\"}},"
                + "\"generationConfig\":{\"thinkingConfig\":{\"include_thoughts\":true,\"thinking_budget\":32768},\"maxOutputTokens\":64000},"
                + "\"sessionId\":\"" + sid + "\"}}";
        assertEquals(expected, r.body);
    }

    // ---- generateSyntheticProjectId -------------------------------------------------------------

    @Test
    void generateSyntheticProjectId() {
        assertEquals("swift-spark-00000",
                AntigravityRequestPrep.generateSyntheticProjectId(RequestTestDoubles.counterIds(), RequestTestDoubles.fixedRandom(0.5)));
    }

    // ---- json string escape matching TestJsonCodec.writeString ----------------------------------

    private static String jesc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
