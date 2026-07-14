package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java port of antigravity-auth's {@code src/plugin/transform/gemini.ts} (T7b, Bucket A): schema
 * conversion ({@code toGeminiSchema}), model predicates, thinking-config builders, tool
 * normalization ({@code normalizeGeminiTools} / {@code wrapToolsAsFunctionDeclarations}), the
 * conversation tree walks ({@code separateParts} / {@code expandMultiFunctionCallModelTurns} /
 * {@code sanitizeGeminiContents} / {@code fixGeminiToolPairing}) and {@code applyGeminiTransforms}.
 *
 * <p>Edges honored: the TS {@code console.warn} calls become an injected core-proxy
 * {@link Logger} SPI parameter (matching T7a's Random/Clock injection style); {@code
 * process.env.OPENCODE_IMAGE_ASPECT_RATIO} in {@code buildImageGenerationConfig} becomes a plain
 * nullable {@code String} parameter. Data model = JSON tree {@code Map<String,Object>} /
 * {@code List<Object>}; the tool-normalization transforms MUTATE {@code payload.tools} in place
 * exactly as the TS assigns {@code payload.tools = ...}. TeaVM-transpilable.
 */
public final class GeminiTransforms {

    // gemini.ts:27-50 -- fields Gemini's strict protobuf-backed JSON validation rejects.
    private static final Set<String> UNSUPPORTED_SCHEMA_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "additionalProperties", "$schema", "$id", "$comment", "$ref", "$defs", "definitions",
            "const", "contentMediaType", "contentEncoding", "if", "then", "else", "not",
            "patternProperties", "unevaluatedProperties", "unevaluatedItems", "dependentRequired",
            "dependentSchemas", "propertyNames", "minContains", "maxContains"));

    // gemini.ts:196
    private static final List<String> VALID_ASPECT_RATIOS = Collections.unmodifiableList(Arrays.asList(
            "1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9"));

    private GeminiTransforms() {
    }

    // ---- toGeminiSchema (gemini.ts:52-122) ---------------------------------------------------------

    /** Port of {@code toGeminiSchema}: JSON Schema -> Gemini Schema (uppercased types, stripped fields). */
    @SuppressWarnings("unchecked")
    public static Object toGeminiSchema(Object schema) {
        if (!JsCoercion.isTruthy(schema) || !(schema instanceof Map)) {
            return schema;
        }

        Map<String, Object> inputSchema = (Map<String, Object>) schema;
        Map<String, Object> result = new LinkedHashMap<>();

        Set<String> propertyNames = new LinkedHashSet<>();
        if (inputSchema.get("properties") instanceof Map) {
            propertyNames.addAll(((Map<String, Object>) inputSchema.get("properties")).keySet());
        }

        for (Map.Entry<String, Object> e : inputSchema.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();

            if (UNSUPPORTED_SCHEMA_FIELDS.contains(key)) {
                continue;
            }

            if ("type".equals(key) && value instanceof String) {
                result.put(key, ((String) value).toUpperCase());
            } else if ("properties".equals(key) && value instanceof Map) {
                Map<String, Object> props = new LinkedHashMap<>();
                for (Map.Entry<String, Object> p : ((Map<String, Object>) value).entrySet()) {
                    props.put(p.getKey(), toGeminiSchema(p.getValue()));
                }
                result.put(key, props);
            } else if ("items".equals(key) && (value == null || value instanceof Map || value instanceof List)) {
                result.put(key, toGeminiSchema(value));
            } else if (("anyOf".equals(key) || "oneOf".equals(key) || "allOf".equals(key)) && value instanceof List) {
                List<Object> mapped = new ArrayList<>();
                for (Object item : (List<Object>) value) {
                    mapped.add(toGeminiSchema(item));
                }
                result.put(key, mapped);
            } else if ("enum".equals(key) && value instanceof List) {
                result.put(key, value);
            } else if ("default".equals(key) || "examples".equals(key)) {
                result.put(key, value);
            } else if ("required".equals(key) && value instanceof List) {
                if (!propertyNames.isEmpty()) {
                    List<Object> validRequired = new ArrayList<>();
                    for (Object prop : (List<Object>) value) {
                        if (prop instanceof String && propertyNames.contains(prop)) {
                            validRequired.add(prop);
                        }
                    }
                    if (!validRequired.isEmpty()) {
                        result.put(key, validRequired);
                    }
                } else {
                    result.put(key, value);
                }
            } else {
                result.put(key, value);
            }
        }

        if ("ARRAY".equals(result.get("type")) && !JsCoercion.isTruthy(result.get("items"))) {
            Map<String, Object> items = new LinkedHashMap<>();
            items.put("type", "STRING");
            result.put("items", items);
        }

        return result;
    }

    // ---- model predicates (gemini.ts:127-156) ------------------------------------------------------

    public static boolean isGeminiModel(String model) {
        String lower = model.toLowerCase();
        return lower.contains("gemini") && !lower.contains("claude");
    }

    public static boolean isGemini3Model(String model) {
        return model.toLowerCase().contains("gemini-3");
    }

    public static boolean isGemini25Model(String model) {
        return model.toLowerCase().contains("gemini-2.5");
    }

    public static boolean isImageGenerationModel(String model) {
        String lower = model.toLowerCase();
        return lower.contains("image") || lower.contains("imagen");
    }

    // ---- thinking config builders (gemini.ts:161-182) ----------------------------------------------

    /** Port of {@code buildGemini3ThinkingConfig}: camelCase {@code thinkingLevel} string. */
    public static Map<String, Object> buildGemini3ThinkingConfig(boolean includeThoughts, String thinkingLevel) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("includeThoughts", includeThoughts);
        config.put("thinkingLevel", thinkingLevel);
        return config;
    }

    /**
     * Port of {@code buildGemini25ThinkingConfig}: camelCase numeric {@code thinkingBudget} (only
     * emitted when it is a positive number, matching {@code typeof === "number" && > 0}).
     */
    public static Map<String, Object> buildGemini25ThinkingConfig(boolean includeThoughts, Object thinkingBudget) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("includeThoughts", includeThoughts);
        if (thinkingBudget instanceof Number && ((Number) thinkingBudget).doubleValue() > 0) {
            config.put("thinkingBudget", thinkingBudget);
        }
        return config;
    }

    /**
     * Port of {@code buildImageGenerationConfig} (gemini.ts:208-220). {@code aspectRatioEnv} is the
     * value of {@code process.env.OPENCODE_IMAGE_ASPECT_RATIO} ({@code null} when unset); the TS
     * {@code || "1:1"} default and the invalid-value {@code console.warn} are honored via the
     * injected {@link Logger}.
     */
    public static Map<String, Object> buildImageGenerationConfig(String aspectRatioEnv, Logger logger) {
        String aspectRatio = JsCoercion.isTruthy(aspectRatioEnv) ? aspectRatioEnv : "1:1";

        Map<String, Object> config = new LinkedHashMap<>();
        if (VALID_ASPECT_RATIOS.contains(aspectRatio)) {
            config.put("aspectRatio", aspectRatio);
            return config;
        }

        if (logger != null) {
            logger.log("[gemini] Invalid aspect ratio \"" + aspectRatio + "\". Using default \"1:1\". Valid values: "
                    + String.join(", ", VALID_ASPECT_RATIOS));
        }

        config.put("aspectRatio", "1:1");
        return config;
    }

    // ---- normalizeGeminiTools (gemini.ts:228-340) --------------------------------------------------

    /**
     * Port of {@code normalizeGeminiTools}. MUTATES {@code payload.tools} in place; returns a map with
     * {@code toolDebugMissing} (Integer) and {@code toolDebugSummaries} (List&lt;String&gt;).
     */
    public static Map<String, Object> normalizeGeminiTools(Map<String, Object> payload) {
        int[] toolDebugMissing = {0};
        List<Object> toolDebugSummaries = new ArrayList<>();

        if (!(payload.get("tools") instanceof List)) {
            return debugResult(toolDebugMissing[0], toolDebugSummaries);
        }

        List<Object> tools = JsCoercion.asList(payload.get("tools"));
        List<Object> newTools = new ArrayList<>();

        for (int toolIndex = 0; toolIndex < tools.size(); toolIndex++) {
            Object tool = tools.get(toolIndex);
            Map<String, Object> t = tool instanceof Map ? JsCoercion.asMap(tool) : new LinkedHashMap<String, Object>();

            if (JsCoercion.isTruthy(t.get("googleSearch")) || JsCoercion.isTruthy(t.get("googleSearchRetrieval"))) {
                newTools.add(tool);
                continue;
            }

            Map<String, Object> newTool = new LinkedHashMap<>(t);
            Object fn = newTool.get("function");
            Object custom = newTool.get("custom");

            Object schema = firstTruthyOrNull(
                    mget(fn, "input_schema"), mget(fn, "parameters"), mget(fn, "inputSchema"),
                    mget(custom, "input_schema"), mget(custom, "parameters"),
                    newTool.get("parameters"), newTool.get("input_schema"), newTool.get("inputSchema"));

            boolean schemaObjectOk = schema instanceof Map;
            if (!schemaObjectOk) {
                schema = placeholderSchema();
                toolDebugMissing[0] += 1;
            } else {
                schema = toGeminiSchema(schema);
            }

            Object nameCandidate = JsCoercion.firstTruthy(
                    newTool.get("name"), mget(fn, "name"), mget(custom, "name"), "tool-" + toolIndex);

            if (JsCoercion.isTruthy(fn) && JsCoercion.isTruthy(schema)) {
                JsCoercion.asMap(fn).put("input_schema", schema);
            }
            if (JsCoercion.isTruthy(custom) && JsCoercion.isTruthy(schema)) {
                JsCoercion.asMap(custom).put("input_schema", schema);
            }

            if (!JsCoercion.isTruthy(newTool.get("custom")) && JsCoercion.isTruthy(newTool.get("function"))) {
                Map<String, Object> fnMap = JsCoercion.asMap(newTool.get("function"));
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", JsCoercion.firstTruthy(fnMap.get("name"), nameCandidate));
                c.put("description", fnMap.get("description"));
                c.put("input_schema", schema);
                newTool.put("custom", c);
            }

            if (!JsCoercion.isTruthy(newTool.get("custom")) && !JsCoercion.isTruthy(newTool.get("function"))) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", nameCandidate);
                c.put("description", newTool.get("description"));
                c.put("input_schema", schema);
                newTool.put("custom", c);

                if (!JsCoercion.isTruthy(newTool.get("parameters"))
                        && !JsCoercion.isTruthy(newTool.get("input_schema"))
                        && !JsCoercion.isTruthy(newTool.get("inputSchema"))) {
                    newTool.put("parameters", schema);
                }
            }

            if (JsCoercion.isTruthy(newTool.get("custom"))
                    && !JsCoercion.isTruthy(JsCoercion.asMap(newTool.get("custom")).get("input_schema"))) {
                Map<String, Object> ph = new LinkedHashMap<>();
                ph.put("type", "OBJECT");
                ph.put("properties", new LinkedHashMap<>());
                JsCoercion.asMap(newTool.get("custom")).put("input_schema", ph);
                toolDebugMissing[0] += 1;
            }

            Object customNow = newTool.get("custom");
            Object functionNow = newTool.get("function");
            toolDebugSummaries.add("idx=" + toolIndex
                    + ", hasCustom=" + JsCoercion.isTruthy(customNow)
                    + ", customSchema=" + (customNow instanceof Map && JsCoercion.isTruthy(JsCoercion.asMap(customNow).get("input_schema")))
                    + ", hasFunction=" + JsCoercion.isTruthy(functionNow)
                    + ", functionSchema=" + (functionNow instanceof Map && JsCoercion.isTruthy(JsCoercion.asMap(functionNow).get("input_schema"))));

            if (JsCoercion.isTruthy(newTool.get("custom"))) {
                newTool.remove("custom");
            }

            newTools.add(newTool);
        }

        payload.put("tools", newTools);
        return debugResult(toolDebugMissing[0], toolDebugSummaries);
    }

    private static Map<String, Object> placeholderSchema() {
        Map<String, Object> ph = new LinkedHashMap<>();
        ph.put("type", "OBJECT");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> placeholder = new LinkedHashMap<>();
        placeholder.put("type", "BOOLEAN");
        placeholder.put("description", "Placeholder. Always pass true.");
        props.put("_placeholder", placeholder);
        ph.put("properties", props);
        ph.put("required", new ArrayList<>(Collections.singletonList("_placeholder")));
        return ph;
    }

    private static Map<String, Object> debugResult(int missing, List<Object> summaries) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("toolDebugMissing", missing);
        r.put("toolDebugSummaries", summaries);
        return r;
    }

    // ---- wrapToolsAsFunctionDeclarations (gemini.ts:451-572) --------------------------------------

    // gemini.ts:451-469
    private static boolean isWebSearchTool(Map<String, Object> tool) {
        if (JsCoercion.isTruthy(tool.get("googleSearch")) || JsCoercion.isTruthy(tool.get("googleSearchRetrieval"))) {
            return true;
        }
        if ("web_search_20250305".equals(tool.get("type"))) {
            return true;
        }
        Object name = tool.get("name");
        return "web_search".equals(name) || "google_search".equals(name);
    }

    /**
     * Port of {@code wrapToolsAsFunctionDeclarations}. MUTATES {@code payload.tools}; returns a map
     * with {@code wrappedFunctionCount}/{@code passthroughToolCount} (Integers). The
     * "web_search + functionDeclarations can't combine" warning goes through the injected {@link Logger}.
     */
    public static Map<String, Object> wrapToolsAsFunctionDeclarations(Map<String, Object> payload, Logger logger) {
        if (!(payload.get("tools") instanceof List) || JsCoercion.asList(payload.get("tools")).isEmpty()) {
            return wrapResult(0, 0);
        }

        List<Object> functionDeclarations = new ArrayList<>();
        List<Object> passthroughTools = new ArrayList<>();
        boolean hasWebSearchTool = false;

        for (Object toolObj : JsCoercion.asList(payload.get("tools"))) {
            Map<String, Object> tool = toolObj instanceof Map ? JsCoercion.asMap(toolObj) : new LinkedHashMap<String, Object>();

            if (JsCoercion.isTruthy(tool.get("googleSearch")) || JsCoercion.isTruthy(tool.get("googleSearchRetrieval"))
                    || JsCoercion.isTruthy(tool.get("codeExecution"))) {
                passthroughTools.add(toolObj);
                continue;
            }

            if (isWebSearchTool(tool)) {
                hasWebSearchTool = true;
                continue;
            }

            if (JsCoercion.isTruthy(tool.get("functionDeclarations"))) {
                if (tool.get("functionDeclarations") instanceof List) {
                    for (Object declObj : JsCoercion.asList(tool.get("functionDeclarations"))) {
                        Map<String, Object> decl = declObj instanceof Map ? JsCoercion.asMap(declObj) : new LinkedHashMap<String, Object>();
                        Map<String, Object> fd = new LinkedHashMap<>();
                        fd.put("name", jsString(JsCoercion.firstTruthy(decl.get("name"), "tool-" + functionDeclarations.size())));
                        fd.put("description", jsString(JsCoercion.firstTruthy(decl.get("description"), "")));
                        fd.put("parameters", JsCoercion.isTruthy(decl.get("parameters")) ? decl.get("parameters") : objectSchema());
                        functionDeclarations.add(fd);
                    }
                }
                continue;
            }

            Object fn = tool.get("function");
            Object custom = tool.get("custom");

            String name = jsString(JsCoercion.firstTruthy(
                    tool.get("name"), mget(fn, "name"), mget(custom, "name"), "tool-" + functionDeclarations.size()));

            String description = jsString(JsCoercion.firstTruthy(
                    tool.get("description"), mget(fn, "description"), mget(custom, "description"), ""));

            Object schema = JsCoercion.firstTruthy(
                    mget(fn, "input_schema"), mget(fn, "parameters"), mget(fn, "inputSchema"),
                    mget(custom, "input_schema"), mget(custom, "parameters"),
                    tool.get("parameters"), tool.get("input_schema"), tool.get("inputSchema"), objectSchema());

            Map<String, Object> fd = new LinkedHashMap<>();
            fd.put("name", name);
            fd.put("description", description);
            fd.put("parameters", schema);
            functionDeclarations.add(fd);
        }

        List<Object> finalTools = new ArrayList<>();
        if (!functionDeclarations.isEmpty()) {
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("functionDeclarations", functionDeclarations);
            finalTools.add(wrap);
        }
        finalTools.addAll(passthroughTools);

        if (hasWebSearchTool && functionDeclarations.isEmpty()) {
            Map<String, Object> gs = new LinkedHashMap<>();
            gs.put("googleSearch", new LinkedHashMap<>());
            finalTools.add(gs);
        } else if (hasWebSearchTool && !functionDeclarations.isEmpty()) {
            if (logger != null) {
                logger.log("[gemini] web_search tool detected but cannot be combined with function declarations. "
                        + "Use the explicit google_search() tool call instead.");
            }
        }

        payload.put("tools", finalTools);

        return wrapResult(functionDeclarations.size(),
                passthroughTools.size() + (hasWebSearchTool && functionDeclarations.isEmpty() ? 1 : 0));
    }

    private static Map<String, Object> objectSchema() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "OBJECT");
        s.put("properties", new LinkedHashMap<>());
        return s;
    }

    private static Map<String, Object> wrapResult(int wrapped, int passthrough) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("wrappedFunctionCount", wrapped);
        r.put("passthroughToolCount", passthrough);
        return r;
    }

    // ---- applyGeminiTransforms (gemini.ts:370-427) -----------------------------------------------

    /**
     * Port of {@code applyGeminiTransforms}. {@code options} is a JSON-tree map with {@code model},
     * {@code tierThinkingBudget}, {@code tierThinkingLevel}, {@code normalizedThinking},
     * {@code googleSearch}. MUTATES {@code payload}; returns the combined debug/wrap result map.
     */
    public static Map<String, Object> applyGeminiTransforms(Map<String, Object> payload, Map<String, Object> options, Logger logger) {
        String model = String.valueOf(options.get("model"));
        Object tierThinkingBudget = options.get("tierThinkingBudget");
        Object tierThinkingLevel = options.get("tierThinkingLevel");
        Object normalizedThinking = options.get("normalizedThinking");
        Object googleSearch = options.get("googleSearch");

        if (JsCoercion.isTruthy(normalizedThinking)) {
            Map<String, Object> nt = JsCoercion.asMap(normalizedThinking);
            boolean includeThoughts = (Boolean) JsCoercion.nullish(nt.get("includeThoughts"), Boolean.TRUE);
            Map<String, Object> thinkingConfig;

            if (JsCoercion.isTruthy(tierThinkingLevel) && isGemini3Model(model)) {
                thinkingConfig = buildGemini3ThinkingConfig(includeThoughts, String.valueOf(tierThinkingLevel));
            } else {
                Object thinkingBudget = JsCoercion.nullish(tierThinkingBudget, nt.get("thinkingBudget"));
                thinkingConfig = buildGemini25ThinkingConfig(includeThoughts, thinkingBudget);
            }

            Map<String, Object> generationConfig = payload.get("generationConfig") instanceof Map
                    ? JsCoercion.asMap(payload.get("generationConfig")) : new LinkedHashMap<String, Object>();
            generationConfig.put("thinkingConfig", thinkingConfig);
            payload.put("generationConfig", generationConfig);
        }

        if (JsCoercion.isTruthy(googleSearch) && "auto".equals(JsCoercion.asMap(googleSearch).get("mode"))) {
            List<Object> tools = payload.get("tools") instanceof List ? JsCoercion.asList(payload.get("tools")) : new ArrayList<Object>();
            if (!JsCoercion.isTruthy(payload.get("tools"))) {
                payload.put("tools", tools);
            }
            Map<String, Object> gs = new LinkedHashMap<>();
            gs.put("googleSearch", new LinkedHashMap<>());
            JsCoercion.asList(payload.get("tools")).add(gs);
        }

        Map<String, Object> result = normalizeGeminiTools(payload);
        Map<String, Object> wrapResult = wrapToolsAsFunctionDeclarations(payload, logger);

        Map<String, Object> combined = new LinkedHashMap<>(result);
        combined.put("wrappedFunctionCount", wrapResult.get("wrappedFunctionCount"));
        combined.put("passthroughToolCount", wrapResult.get("passthroughToolCount"));
        return combined;
    }

    // ---- separateParts (gemini.ts:583-607) -------------------------------------------------------

    private static final class SeparatedParts {
        final List<Object> functionCallParts = new ArrayList<>();
        final List<Object> functionResponseParts = new ArrayList<>();
        final List<Object> otherParts = new ArrayList<>();
    }

    private static SeparatedParts separateParts(List<Object> parts) {
        SeparatedParts out = new SeparatedParts();
        for (Object part : parts) {
            if (!(part instanceof Map)) {
                out.otherParts.add(part);
                continue;
            }
            Map<String, Object> p = JsCoercion.asMap(part);
            if (JsCoercion.isTruthy(p.get("functionCall")) || JsCoercion.isTruthy(p.get("function_call"))) {
                out.functionCallParts.add(part);
            } else if (JsCoercion.isTruthy(p.get("functionResponse")) || JsCoercion.isTruthy(p.get("function_response"))) {
                out.functionResponseParts.add(part);
            } else {
                out.otherParts.add(part);
            }
        }
        return out;
    }

    private static boolean isFunctionCallPart(Object part) {
        if (!(part instanceof Map)) return false;
        Map<String, Object> p = JsCoercion.asMap(part);
        return JsCoercion.isTruthy(p.get("functionCall")) || JsCoercion.isTruthy(p.get("function_call"));
    }

    // ---- expandMultiFunctionCallModelTurns (gemini.ts:614-664) -----------------------------------

    /** Port of {@code expandMultiFunctionCallModelTurns}: strict call -> response -> call -> response. */
    public static List<Object> expandMultiFunctionCallModelTurns(List<Object> contents) {
        if (contents == null || contents.isEmpty()) {
            return contents;
        }

        List<Object> result = new ArrayList<>();

        for (int i = 0; i < contents.size(); i++) {
            Object turnObj = contents.get(i);
            if (!(turnObj instanceof Map) || !"model".equals(JsCoercion.asMap(turnObj).get("role"))
                    || !(JsCoercion.asMap(turnObj).get("parts") instanceof List)) {
                result.add(turnObj);
                continue;
            }
            Map<String, Object> turn = JsCoercion.asMap(turnObj);
            List<Object> turnParts = JsCoercion.asList(turn.get("parts"));

            List<Object> fcParts = new ArrayList<>();
            for (Object p : turnParts) {
                if (isFunctionCallPart(p)) fcParts.add(p);
            }
            if (fcParts.size() <= 1) {
                result.add(turnObj);
                continue;
            }

            Object nextObj = i + 1 < contents.size() ? contents.get(i + 1) : null;
            if (!(nextObj instanceof Map) || !"user".equals(JsCoercion.asMap(nextObj).get("role"))
                    || !(JsCoercion.asMap(nextObj).get("parts") instanceof List)) {
                result.add(turnObj);
                continue;
            }
            List<Object> nextParts = JsCoercion.asList(JsCoercion.asMap(nextObj).get("parts"));

            List<Object> frParts = new ArrayList<>();
            for (Object p : nextParts) {
                if (isFunctionResponsePart(p)) frParts.add(p);
            }
            if (frParts.size() != fcParts.size()) {
                result.add(turnObj);
                continue;
            }

            List<Object> otherParts = new ArrayList<>();
            for (Object p : turnParts) {
                if (!isFunctionCallPart(p)) otherParts.add(p);
            }

            for (int j = 0; j < fcParts.size(); j++) {
                List<Object> modelParts = new ArrayList<>();
                if (j == 0 && !otherParts.isEmpty()) {
                    modelParts.addAll(otherParts);
                }
                modelParts.add(fcParts.get(j));
                Map<String, Object> modelTurn = new LinkedHashMap<>(turn);
                modelTurn.put("role", "model");
                modelTurn.put("parts", modelParts);
                result.add(modelTurn);

                Map<String, Object> userTurn = new LinkedHashMap<>();
                userTurn.put("role", "user");
                userTurn.put("parts", new ArrayList<>(Collections.singletonList(frParts.get(j))));
                result.add(userTurn);
            }

            i++;
        }

        return result;
    }

    private static boolean isFunctionResponsePart(Object part) {
        if (!(part instanceof Map)) return false;
        Map<String, Object> p = JsCoercion.asMap(part);
        return JsCoercion.isTruthy(p.get("functionResponse")) || JsCoercion.isTruthy(p.get("function_response"));
    }

    // ---- sanitizeGeminiContents (gemini.ts:684-802) ----------------------------------------------

    private static List<Object> mergeTextParts(List<Object> parts) {
        List<Object> merged = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();
        boolean hasText = false;

        for (Object part : parts) {
            if (part instanceof Map && JsCoercion.asMap(part).get("text") instanceof String) {
                String text = (String) JsCoercion.asMap(part).get("text");
                if (hasText) currentText.append("\n\n");
                currentText.append(text);
                hasText = true;
            } else {
                if (hasText) {
                    merged.add(textPart(currentText.toString()));
                    currentText.setLength(0);
                    hasText = false;
                }
                merged.add(part);
            }
        }

        if (hasText) {
            merged.add(textPart(currentText.toString()));
        }

        return merged;
    }

    private static Map<String, Object> textPart(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        return m;
    }

    /** Port of {@code sanitizeGeminiContents}: enforces strict user/model alternation + roles. */
    public static List<Object> sanitizeGeminiContents(List<Object> contentsIn) {
        if (contentsIn == null || contentsIn.isEmpty()) {
            return contentsIn;
        }

        List<Object> contents = expandMultiFunctionCallModelTurns(contentsIn);

        List<Map<String, Object>> normalized = new ArrayList<>();

        for (Object contentObj : contents) {
            if (!(contentObj instanceof Map)) continue;
            Map<String, Object> content = JsCoercion.asMap(contentObj);

            Object role = content.get("role");
            List<Object> parts = content.get("parts") instanceof List
                    ? mergeTextParts(new ArrayList<>(JsCoercion.asList(content.get("parts")))) : new ArrayList<Object>();

            if (parts.isEmpty()) continue;

            if ("assistant".equals(role)) role = "model";

            SeparatedParts sep = separateParts(parts);

            if ("model".equals(role)) {
                List<Object> modelParts = new ArrayList<>();
                modelParts.addAll(sep.otherParts);
                modelParts.addAll(sep.functionCallParts);
                if (!modelParts.isEmpty()) {
                    Map<String, Object> turn = new LinkedHashMap<>(content);
                    turn.put("role", "model");
                    turn.put("parts", modelParts);
                    normalized.add(turn);
                }
                if (!sep.functionResponseParts.isEmpty()) {
                    normalized.add(roleTurn("user", sep.functionResponseParts));
                }
            } else {
                if (!sep.functionCallParts.isEmpty()) {
                    normalized.add(roleTurn("model", sep.functionCallParts));
                }
                if (!sep.functionResponseParts.isEmpty()) {
                    normalized.add(roleTurn("user", sep.functionResponseParts));
                }
                if (!sep.otherParts.isEmpty()) {
                    Map<String, Object> turn = new LinkedHashMap<>(content);
                    turn.put("role", "user");
                    turn.put("parts", sep.otherParts);
                    normalized.add(turn);
                }
            }
        }

        if (normalized.isEmpty()) {
            return contentsIn;
        }

        List<Map<String, Object>> merged = new ArrayList<>();
        merged.add(normalized.get(0));

        for (int i = 1; i < normalized.size(); i++) {
            Map<String, Object> current = normalized.get(i);
            Map<String, Object> previous = merged.get(merged.size() - 1);

            boolean prevFunc = anyFunc(JsCoercion.asList(previous.get("parts")));
            boolean currFunc = anyFunc(JsCoercion.asList(current.get("parts")));

            if (eq(current.get("role"), previous.get("role")) && prevFunc == currFunc) {
                List<Object> combinedParts = new ArrayList<>(JsCoercion.asList(previous.get("parts")));
                combinedParts.addAll(JsCoercion.asList(current.get("parts")));
                previous.put("parts", combinedParts);
            } else {
                merged.add(current);
            }
        }

        if (!"user".equals(merged.get(0).get("role"))) {
            merged.add(0, roleTurn("user", new ArrayList<>(Collections.singletonList(textPart("acknowledged")))));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        result.add(merged.get(0));

        for (int i = 1; i < merged.size(); i++) {
            Map<String, Object> current = merged.get(i);
            Map<String, Object> previous = result.get(result.size() - 1);

            if (eq(current.get("role"), previous.get("role"))) {
                String fillerRole = "model".equals(current.get("role")) ? "user" : "model";
                result.add(roleTurn(fillerRole, new ArrayList<>(Collections.singletonList(textPart("acknowledged")))));
            }
            result.add(current);
        }

        List<Object> out = new ArrayList<>();
        for (Map<String, Object> turn : result) {
            Map<String, Object> copy = new LinkedHashMap<>(turn);
            copy.put("parts", mergeTextParts(JsCoercion.asList(turn.get("parts"))));
            out.add(copy);
        }
        return out;
    }

    private static Map<String, Object> roleTurn(String role, List<Object> parts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("parts", parts);
        return m;
    }

    private static boolean anyFunc(List<Object> parts) {
        for (Object p : parts) {
            if (!(p instanceof Map)) continue;
            Map<String, Object> m = JsCoercion.asMap(p);
            if (JsCoercion.isTruthy(m.get("functionCall")) || JsCoercion.isTruthy(m.get("functionResponse"))
                    || JsCoercion.isTruthy(m.get("function_call")) || JsCoercion.isTruthy(m.get("function_response"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    // ---- fixGeminiToolPairing (gemini.ts:811-884) ------------------------------------------------

    /** Port of {@code fixGeminiToolPairing}: injects placeholder responses for unmatched calls. */
    public static List<Object> fixGeminiToolPairing(List<Object> contents) {
        if (contents == null || contents.isEmpty()) {
            return contents;
        }

        List<Object> result = new ArrayList<>();
        for (Object cObj : contents) {
            Map<String, Object> copy = new LinkedHashMap<>();
            if (cObj instanceof Map) {
                copy.putAll(JsCoercion.asMap(cObj));
                copy.put("parts", JsCoercion.asMap(cObj).get("parts") instanceof List
                        ? new ArrayList<>(JsCoercion.asList(JsCoercion.asMap(cObj).get("parts"))) : new ArrayList<Object>());
            } else {
                copy.put("parts", new ArrayList<Object>());
            }
            result.add(copy);
        }

        Map<Integer, List<Map<String, Object>>> callsByModelIdx = new LinkedHashMap<>();

        for (int i = 0; i < result.size(); i++) {
            Map<String, Object> turn = JsCoercion.asMap(result.get(i));
            if (!"model".equals(turn.get("role"))) continue;

            List<Map<String, Object>> calls = new ArrayList<>();
            for (Object part : JsCoercion.asList(turn.get("parts"))) {
                Object fc = firstTruthyOrNull(mget(part, "functionCall"), mget(part, "function_call"));
                if (fc == null) continue;
                Map<String, Object> fcMap = JsCoercion.asMap(fc);
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("name", JsCoercion.firstTruthy(fcMap.get("name"), "unknown"));
                Object id = fcMap.get("id");
                call.put("id", JsCoercion.isTruthy(id) ? id : null);
                calls.add(call);
            }

            if (!calls.isEmpty()) {
                callsByModelIdx.put(i, calls);
            }
        }

        for (Map.Entry<Integer, List<Map<String, Object>>> entry : callsByModelIdx.entrySet()) {
            int modelIdx = entry.getKey();
            List<Map<String, Object>> calls = entry.getValue();
            int nextIdx = modelIdx + 1;

            if (nextIdx >= result.size()) {
                List<Object> responseParts = new ArrayList<>();
                for (Map<String, Object> call : calls) {
                    responseParts.add(responsePart(call, "[pending]"));
                }
                result.add(roleTurn("user", responseParts));
                continue;
            }

            Map<String, Object> nextTurn = JsCoercion.asMap(result.get(nextIdx));
            if (!"user".equals(nextTurn.get("role"))) continue;

            Set<Object> existingNames = new LinkedHashSet<>();
            Set<Object> existingIds = new LinkedHashSet<>();

            for (Object part : JsCoercion.asList(nextTurn.get("parts"))) {
                Object fr = firstTruthyOrNull(mget(part, "functionResponse"), mget(part, "function_response"));
                if (fr == null) continue;
                Map<String, Object> frMap = JsCoercion.asMap(fr);
                if (JsCoercion.isTruthy(frMap.get("name"))) existingNames.add(frMap.get("name"));
                if (JsCoercion.isTruthy(frMap.get("id"))) existingIds.add(frMap.get("id"));
            }

            for (Map<String, Object> call : calls) {
                boolean hasMatchById = call.get("id") != null && existingIds.contains(call.get("id"));
                boolean hasMatchByName = existingNames.contains(call.get("name"));
                if (!hasMatchById && !hasMatchByName) {
                    JsCoercion.asList(nextTurn.get("parts")).add(responsePart(call, "[no response received]"));
                }
            }
        }

        return result;
    }

    private static Map<String, Object> responsePart(Map<String, Object> call, String resultText) {
        Map<String, Object> fr = new LinkedHashMap<>();
        fr.put("name", call.get("name"));
        if (call.get("id") != null) {
            fr.put("id", call.get("id"));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", resultText);
        fr.put("response", response);
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("functionResponse", fr);
        return part;
    }

    // ---- shared helpers --------------------------------------------------------------------------

    // JS optional chaining `mapMaybe?.key`.
    private static Object mget(Object mapMaybe, String key) {
        return mapMaybe instanceof Map ? JsCoercion.asMap(mapMaybe).get(key) : null;
    }

    // JS `[a, b, ...].filter(Boolean)[0]` -- first truthy operand or null.
    private static Object firstTruthyOrNull(Object... values) {
        for (Object v : values) {
            if (JsCoercion.isTruthy(v)) return v;
        }
        return null;
    }

    // JS String(x) for the string/number/boolean values this port encounters.
    private static String jsString(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return (String) v;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) return Long.toString((long) d);
            return Double.toString(d);
        }
        return String.valueOf(v);
    }
}
