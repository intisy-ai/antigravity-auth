package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of the PURE request-side format bridge in antigravity-auth's
 * {@code src/plugin/anthropic-bridge.ts} (T7d, Bucket A): {@code anthropicToGemini} (:18-97),
 * {@code supportsThinking} (:13-16), {@code isAnthropicMessages} (:204-206) and the
 * {@code EFFORT_BUDGET} table (:74). Translates an Anthropic {@code /v1/messages} body into a Gemini
 * {@code generateContent} body -- CONFIRMED pure JSON&rarr;JSON.
 *
 * <h2>Injected edge</h2>
 * The only external dependency, {@code cleanJSONSchemaForAntigravity}, is injected via the existing
 * {@link ClaudeTransforms.SchemaCleaner} functional interface (NOT a new one) -- the real impl is the
 * already-ported {@link AntigravitySchemaCleaner#clean} (pass {@code AntigravitySchemaCleaner::clean}).
 * This mirrors how {@code AntigravityProviderJs} already wires {@code REAL_CLEANER} for
 * {@link ClaudeTransforms}.
 *
 * <h2>Gotchas honored</h2>
 * <ul>
 *   <li><b>Tool pairing by NAME</b>: a pre-pass maps every {@code tool_use} id &rarr; name so a later
 *       {@code tool_result} resolves to the original tool's NAME (Gemini pairs {@code functionResponse}
 *       to {@code functionCall} by name, not id). Fallback chain
 *       {@code toolNames[tool_use_id] || tool_use_id || "tool"}.</li>
 *   <li><b>EFFORT_BUDGET precedence</b>: explicit {@code thinking.budget_tokens} (number) wins; else
 *       {@code output_config.effort} &rarr; table; {@code thinking.type === "disabled"} suppresses; only
 *       applied when {@link #supportsThinking(Object)}. {@code xhigh}/{@code max} both map to 32768.</li>
 * </ul>
 *
 * <p>Data model = JSON tree {@code Map<String,Object>} / {@code List<Object>}; the function BUILDS a
 * new Gemini body (does not mutate its input). Key insertion order mirrors the TS object-literal
 * order so a byte-identical {@code JsonCodec.stringify} matches the real TS. Disclosed deviation
 * (no valid-payload path, exercised by no fixture): where the TS reads a nested field via property
 * access on a value that would be a primitive/array, this port treats a non-{@link Map} as absent
 * rather than reproducing a JS throw. TeaVM-transpilable.
 */
public final class AntigravityFormatBridge {

    // anthropic-bridge.ts:74 -- effort -> Gemini thinkingBudget (xhigh/max clamp to high=32768).
    private static final Map<String, Integer> EFFORT_BUDGET = new LinkedHashMap<>();

    static {
        EFFORT_BUDGET.put("low", 8192);
        EFFORT_BUDGET.put("medium", 16384);
        EFFORT_BUDGET.put("high", 32768);
        EFFORT_BUDGET.put("xhigh", 32768);
        EFFORT_BUDGET.put("max", 32768);
    }

    private AntigravityFormatBridge() {
    }

    // ---- supportsThinking (anthropic-bridge.ts:13-16) --------------------------------------------

    /** {@code String(model || "").toLowerCase()} includes "thinking" or "gemini-3". */
    public static boolean supportsThinking(Object model) {
        String lower = (JsCoercion.isTruthy(model) ? jsStr(model) : "").toLowerCase();
        return lower.contains("thinking") || lower.contains("gemini-3");
    }

    // ---- isAnthropicMessages (anthropic-bridge.ts:204-206) ---------------------------------------

    /** True when {@code url} is a string containing {@code "/v1/messages"}. */
    public static boolean isAnthropicMessages(Object url) {
        return url instanceof String && ((String) url).contains("/v1/messages");
    }

    // ---- anthropicToGemini (anthropic-bridge.ts:18-97) -------------------------------------------

    /**
     * Port of {@code anthropicToGemini}. {@code cleaner} is the injected
     * {@code cleanJSONSchemaForAntigravity} (pass {@code AntigravitySchemaCleaner::clean}); {@code json}
     * backs the {@code JSON.stringify(c)} used to coalesce an object {@code tool_result} content.
     */
    public static Map<String, Object> anthropicToGemini(JsonCodec json, Map<String, Object> body, Object model, ClaudeTransforms.SchemaCleaner cleaner) {
        List<Object> contents = new ArrayList<>();

        // Pre-map every tool_use id -> name (Gemini pairs functionResponse by NAME, not id).
        Map<String, Object> toolNames = new LinkedHashMap<>();
        for (Object pmObj : messages(body)) {
            if (!(pmObj instanceof Map)) continue;
            Object pmContent = JsCoercion.asMap(pmObj).get("content");
            if (!(pmContent instanceof List)) continue;
            for (Object pbObj : JsCoercion.asList(pmContent)) {
                if (!(pbObj instanceof Map)) continue;
                Map<String, Object> pb = JsCoercion.asMap(pbObj);
                if ("tool_use".equals(pb.get("type")) && JsCoercion.isTruthy(pb.get("id"))) {
                    toolNames.put(jsStr(pb.get("id")), pb.get("name"));
                }
            }
        }

        for (Object msgObj : messages(body)) {
            Map<String, Object> msg = msgObj instanceof Map ? JsCoercion.asMap(msgObj) : new LinkedHashMap<String, Object>();
            String role = "assistant".equals(msg.get("role")) ? "model" : "user";
            List<Object> parts = new ArrayList<>();
            Object content = msg.get("content");

            if (content instanceof String) {
                if (JsCoercion.isTruthy(content)) parts.add(single("text", content));
            } else if (content instanceof List) {
                for (Object blockObj : JsCoercion.asList(content)) {
                    Map<String, Object> block = blockObj instanceof Map ? JsCoercion.asMap(blockObj) : new LinkedHashMap<String, Object>();
                    Object type = block.get("type");
                    if ("text".equals(type)) {
                        parts.add(single("text", JsCoercion.firstTruthy(block.get("text"), "")));
                    } else if ("tool_use".equals(type)) {
                        Map<String, Object> fc = new LinkedHashMap<>();
                        fc.put("name", block.get("name"));
                        fc.put("args", JsCoercion.isTruthy(block.get("input")) ? block.get("input") : new LinkedHashMap<>());
                        parts.add(single("functionCall", fc));
                    } else if ("tool_result".equals(type)) {
                        Object c = block.get("content");
                        Object text;
                        if (c instanceof String) {
                            text = c;
                        } else if (c instanceof List) {
                            text = joinToolResultTexts(JsCoercion.asList(c));
                        } else if (c != null) {
                            text = json.stringify(c);
                        } else {
                            text = "";
                        }
                        Object fnName = JsCoercion.firstTruthy(
                                toolNames.get(jsStr(block.get("tool_use_id"))), block.get("tool_use_id"), "tool");
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("result", text);
                        Map<String, Object> fr = new LinkedHashMap<>();
                        fr.put("name", fnName);
                        fr.put("response", response);
                        parts.add(single("functionResponse", fr));
                    }
                    // images and other block types are dropped (cloudcode-pa text path)
                }
            }

            if (!parts.isEmpty()) {
                Map<String, Object> contentEntry = new LinkedHashMap<>();
                contentEntry.put("role", role);
                contentEntry.put("parts", parts);
                contents.add(contentEntry);
            }
        }

        Map<String, Object> gem = new LinkedHashMap<>();
        gem.put("contents", contents);

        Object system = body.get("system");
        if (JsCoercion.isTruthy(system)) {
            String sysText;
            if (system instanceof String) {
                sysText = (String) system;
            } else if (system instanceof List) {
                sysText = joinSystemTexts(JsCoercion.asList(system));
            } else {
                sysText = "";
            }
            if (JsCoercion.isTruthy(sysText)) {
                Map<String, Object> systemInstruction = new LinkedHashMap<>();
                systemInstruction.put("parts", new ArrayList<>(java.util.Collections.singletonList(single("text", sysText))));
                gem.put("systemInstruction", systemInstruction);
            }
        }

        Map<String, Object> gc = new LinkedHashMap<>();
        if (JsCoercion.isTruthy(body.get("max_tokens"))) gc.put("maxOutputTokens", body.get("max_tokens"));
        if (body.get("temperature") != null) gc.put("temperature", body.get("temperature"));
        if (body.get("top_p") != null) gc.put("topP", body.get("top_p"));
        Object stop = body.get("stop_sequences");
        if (stop instanceof List && !JsCoercion.asList(stop).isEmpty()) gc.put("stopSequences", stop);

        Object thinking = body.get("thinking");
        boolean thinkingOff = thinking instanceof Map && "disabled".equals(JsCoercion.asMap(thinking).get("type"));
        Object budget = (thinking instanceof Map && JsCoercion.asMap(thinking).get("budget_tokens") instanceof Number)
                ? JsCoercion.asMap(thinking).get("budget_tokens") : null;
        Object oc = body.get("output_config");
        Object effort = JsCoercion.isTruthy(oc) ? (oc instanceof Map ? JsCoercion.asMap(oc).get("effort") : null) : oc;
        if (budget == null && JsCoercion.isTruthy(effort) && EFFORT_BUDGET.containsKey(jsStr(effort))) {
            budget = EFFORT_BUDGET.get(jsStr(effort));
        }
        if (!thinkingOff && budget != null && supportsThinking(model)) {
            Map<String, Object> thinkingConfig = new LinkedHashMap<>();
            thinkingConfig.put("thinkingBudget", budget);
            gc.put("thinkingConfig", thinkingConfig);
        }
        if (!gc.isEmpty()) gem.put("generationConfig", gc);

        Object tools = body.get("tools");
        if (tools instanceof List && !JsCoercion.asList(tools).isEmpty()) {
            List<Object> functionDeclarations = new ArrayList<>();
            for (Object tObj : JsCoercion.asList(tools)) {
                if (!(tObj instanceof Map)) continue;
                Map<String, Object> t = JsCoercion.asMap(tObj);
                if (!JsCoercion.isTruthy(t.get("name"))) continue;
                Object schema = JsCoercion.isTruthy(t.get("input_schema")) ? t.get("input_schema") : defaultObjectSchema();
                Object params = cleaner.clean(schema);
                Map<String, Object> fd = new LinkedHashMap<>();
                fd.put("name", t.get("name"));
                fd.put("description", JsCoercion.firstTruthy(t.get("description"), ""));
                fd.put("parameters", params);
                functionDeclarations.add(fd);
            }
            Map<String, Object> toolWrap = new LinkedHashMap<>();
            toolWrap.put("functionDeclarations", functionDeclarations);
            gem.put("tools", new ArrayList<>(java.util.Collections.singletonList(toolWrap)));
        }

        return gem;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static List<Object> messages(Map<String, Object> body) {
        Object m = body.get("messages");
        return m instanceof List ? JsCoercion.asList(m) : new ArrayList<Object>();
    }

    // tool_result array content: c.map(x => x && x.text ? x.text : "").join("").
    private static String joinToolResultTexts(List<Object> items) {
        StringBuilder sb = new StringBuilder();
        for (Object item : items) {
            Object t = item instanceof Map ? JsCoercion.asMap(item).get("text") : null;
            if (JsCoercion.isTruthy(item) && JsCoercion.isTruthy(t)) sb.append(jsStr(t));
        }
        return sb.toString();
    }

    // system array: s.map(s => s && s.text ? s.text : "").join("\n").
    private static String joinSystemTexts(List<Object> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append("\n");
            Object item = items.get(i);
            Object t = item instanceof Map ? JsCoercion.asMap(item).get("text") : null;
            if (JsCoercion.isTruthy(item) && JsCoercion.isTruthy(t)) sb.append(jsStr(t));
        }
        return sb.toString();
    }

    private static Map<String, Object> defaultObjectSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return schema;
    }

    private static Map<String, Object> single(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    private static String jsStr(Object v) {
        if (v instanceof String) return (String) v;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) return Long.toString((long) d);
            return Double.toString(d);
        }
        return String.valueOf(v);
    }
}
