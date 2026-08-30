package io.github.intisy.ai.antigravity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cleans a JSON Schema into the form Antigravity/Gemini accepts, as a phase pipeline: {@code $ref}
 * ->hint rewrite, {@code const}->{@code enum} conversion, enum hints + Gemini enum normalization,
 * {@code additionalProperties}/constraint description hints, {@code allOf} merge, {@code anyOf}/
 * {@code oneOf} flatten (incl. enum-union merge + best-option scoring), type-array flatten,
 * unsupported-keyword + {@code x-*} stripping, {@code required} cleanup and the empty-object
 * placeholder. This is the real implementation of {@link ClaudeTransforms.SchemaCleaner}
 * ({@code AntigravitySchemaCleaner::clean}).
 *
 * <p>Data model = JSON tree {@code Map<String,Object>} / {@code List<Object>}; every phase returns a
 * NEW tree and never mutates its input, so the caller's schema is untouched. The keyword lists must
 * stay exhaustive: a missed keyword is a 400 from the real API. The {@code EMPTY_SCHEMA_PLACEHOLDER_*}
 * constants are reused from {@link ClaudeTransforms} (single source, not duplicated). Pure tree-walk:
 * no SPI, Clock, Random or JSON re-parse needed. TeaVM-transpilable.
 *
 * <p>Fidelity deviation (unreachable by valid JSON Schema): wherever a value is accepted for a
 * schema/properties slot ({@code allOf} items, {@code anyOf}/{@code oneOf} options, a {@code
 * properties}/{@code required} container, an {@code appendDescriptionHint} target, a selected flatten
 * option) this code requires a {@link Map} (plain object), so a JSON array in that exact slot
 * (invalid schema) is skipped/ignored rather than having its numeric indices spread. The generic
 * tree-recursion still descends into arrays.
 */
public final class AntigravitySchemaCleaner {

    // Constraint keywords moved to description hints (order matters: the hint is built by iterating
    // this list in order).
    private static final List<String> UNSUPPORTED_CONSTRAINTS = Arrays.asList(
            "minLength", "maxLength", "exclusiveMinimum", "exclusiveMaximum",
            "pattern", "minItems", "maxItems", "format",
            "default", "examples");

    // UNSUPPORTED_CONSTRAINTS + the keywords removed after hint extraction.
    private static final Set<String> UNSUPPORTED_KEYWORDS = new LinkedHashSet<>();
    static {
        UNSUPPORTED_KEYWORDS.addAll(UNSUPPORTED_CONSTRAINTS);
        UNSUPPORTED_KEYWORDS.addAll(Arrays.asList(
                "$schema", "$defs", "definitions", "const", "$ref", "additionalProperties",
                "propertyNames", "title", "$id", "$comment"));
    }

    private AntigravitySchemaCleaner() {
    }

    // ---- cleanJSONSchemaForAntigravity -----------------------------------------------------------

    /**
     * One tool schema, with everything the upstream rejects removed.
     *
     * @param schema the schema as the caller declared it
     * @return a new tree; anything that is neither an object nor an array is answered unchanged
     */
    public static Object clean(Object schema) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }

        Object result = schema;
        result = convertRefsToHints(result);
        result = convertConstToEnum(result);
        result = addEnumHints(result);
        result = normalizeEnums(result);
        result = addAdditionalPropertiesHints(result);
        result = moveConstraintsToDescription(result);
        result = mergeAllOf(result);
        result = flattenAnyOfOneOf(result);
        result = flattenTypeArrays(result, null, null);
        result = removeUnsupportedKeywords(result, false);
        result = cleanupRequiredFields(result);
        result = addEmptySchemaPlaceholder(result);
        return result;
    }

    // ---- appendDescriptionHint -------------------------------------------------------------------

    private static Object appendDescriptionHint(Object schema, String hint) {
        if (!(schema instanceof Map)) {
            return schema;
        }
        Map<String, Object> s = JsCoercion.asMap(schema);
        String existing = s.get("description") instanceof String ? (String) s.get("description") : "";
        String newDescription = !existing.isEmpty() ? existing + " (" + hint + ")" : hint;
        Map<String, Object> result = new LinkedHashMap<>(s);
        result.put("description", newDescription);
        return result;
    }

    // ---- convertRefsToHints ----------------------------------------------------------------------

    private static Object convertRefsToHints(Object schema) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }
        if (schema instanceof List) {
            return mapList(JsCoercion.asList(schema), AntigravitySchemaCleaner::convertRefsToHints);
        }

        Map<String, Object> s = JsCoercion.asMap(schema);
        if (s.get("$ref") instanceof String) {
            String refVal = (String) s.get("$ref");
            String defName = refVal.contains("/") ? lastPathSegment(refVal) : refVal;
            String hint = "See: " + defName;
            String existingDesc = s.get("description") instanceof String ? (String) s.get("description") : "";
            String newDescription = !existingDesc.isEmpty() ? existingDesc + " (" + hint + ")" : hint;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "object");
            result.put("description", newDescription);
            return result;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : s.entrySet()) {
            result.put(e.getKey(), convertRefsToHints(e.getValue()));
        }
        return result;
    }

    // JS `refVal.split("/").pop()`: last segment, keeping a trailing empty segment.
    private static String lastPathSegment(String refVal) {
        String[] parts = refVal.split("/", -1);
        return parts[parts.length - 1];
    }

    // ---- convertConstToEnum ----------------------------------------------------------------------

    private static Object convertConstToEnum(Object schema) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }
        if (schema instanceof List) {
            return mapList(JsCoercion.asList(schema), AntigravitySchemaCleaner::convertConstToEnum);
        }

        Map<String, Object> s = JsCoercion.asMap(schema);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : s.entrySet()) {
            String key = e.getKey();
            if ("const".equals(key) && !JsCoercion.isTruthy(s.get("enum"))) {
                List<Object> en = new ArrayList<>();
                en.add(e.getValue());
                result.put("enum", en);
            } else {
                result.put(key, convertConstToEnum(e.getValue()));
            }
        }
        return result;
    }

    // ---- addEnumHints ----------------------------------------------------------------------------

    private static Object addEnumHints(Object schema) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }
        if (schema instanceof List) {
            return mapList(JsCoercion.asList(schema), AntigravitySchemaCleaner::addEnumHints);
        }

        Map<String, Object> result = new LinkedHashMap<>(JsCoercion.asMap(schema));

        if (result.get("enum") instanceof List) {
            List<Object> en = JsCoercion.asList(result.get("enum"));
            if (en.size() > 1 && en.size() <= 10) {
                result = (Map<String, Object>) appendDescriptionHint(result, "Allowed: " + joinJs(en, ", "));
            }
        }

        for (String key : new ArrayList<>(result.keySet())) {
            if ("enum".equals(key)) {
                continue;
            }
            Object value = result.get(key);
            if (value instanceof Map || value instanceof List) {
                result.put(key, addEnumHints(value));
            }
        }
        return result;
    }

    // ---- normalizeEnums --------------------------------------------------------------------------

    private static Object normalizeEnums(Object schema) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }
        if (schema instanceof List) {
            return mapList(JsCoercion.asList(schema), AntigravitySchemaCleaner::normalizeEnums);
        }

        Map<String, Object> s = JsCoercion.asMap(schema);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : s.entrySet()) {
            result.put(e.getKey(), "enum".equals(e.getKey()) ? e.getValue() : normalizeEnums(e.getValue()));
        }

        if (result.get("enum") instanceof List) {
            List<Object> en = JsCoercion.asList(result.get("enum"));
            boolean allStrings = !en.isEmpty();
            for (Object v : en) {
                if (!(v instanceof String)) {
                    allStrings = false;
                    break;
                }
            }
            boolean typeUndefined = !result.containsKey("type");
            if (allStrings && (typeUndefined || "string".equals(result.get("type")))) {
                result.put("type", "string"); // enum is valid only on a STRING-typed property
            } else {
                String vals = joinJs(en, ", ");
                Object desc = result.get("description");
                boolean hasAllowed = desc instanceof String && ((String) desc).contains("Allowed: " + vals);
                if (!hasAllowed) {
                    result = (Map<String, Object>) appendDescriptionHint(result, "Allowed: " + vals);
                }
                result.remove("enum");
            }
        }
        return result;
    }

    // ---- addAdditionalPropertiesHints ------------------------------------------------------------

    private static Object addAdditionalPropertiesHints(Object schema) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }
        if (schema instanceof List) {
            return mapList(JsCoercion.asList(schema), AntigravitySchemaCleaner::addAdditionalPropertiesHints);
        }

        Map<String, Object> result = new LinkedHashMap<>(JsCoercion.asMap(schema));

        if (Boolean.FALSE.equals(result.get("additionalProperties"))) {
            result = (Map<String, Object>) appendDescriptionHint(result, "No extra properties allowed");
        }

        for (String key : new ArrayList<>(result.keySet())) {
            if ("additionalProperties".equals(key)) {
                continue;
            }
            Object value = result.get(key);
            if (value instanceof Map || value instanceof List) {
                result.put(key, addAdditionalPropertiesHints(value));
            }
        }
        return result;
    }

    // ---- moveConstraintsToDescription ------------------------------------------------------------

    private static Object moveConstraintsToDescription(Object schema) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }
        if (schema instanceof List) {
            return mapList(JsCoercion.asList(schema), AntigravitySchemaCleaner::moveConstraintsToDescription);
        }

        Map<String, Object> result = new LinkedHashMap<>(JsCoercion.asMap(schema));

        for (String constraint : UNSUPPORTED_CONSTRAINTS) {
            Object v = result.get(constraint);
            // Keep only a present, non-null, non-object primitive (in JS terms null/Map/List are all
            // typeof "object" or undefined, so they are excluded).
            if (v != null && !(v instanceof Map) && !(v instanceof List)) {
                result = (Map<String, Object>) appendDescriptionHint(result, constraint + ": " + jsString(v));
            }
        }

        for (String key : new ArrayList<>(result.keySet())) {
            Object value = result.get(key);
            if (value instanceof Map || value instanceof List) {
                result.put(key, moveConstraintsToDescription(value));
            }
        }
        return result;
    }

    // ---- mergeAllOf ------------------------------------------------------------------------------

    private static Object mergeAllOf(Object schema) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }
        if (schema instanceof List) {
            return mapList(JsCoercion.asList(schema), AntigravitySchemaCleaner::mergeAllOf);
        }

        Map<String, Object> result = new LinkedHashMap<>(JsCoercion.asMap(schema));

        if (result.get("allOf") instanceof List) {
            Map<String, Object> merged = new LinkedHashMap<>();
            List<Object> mergedRequired = new ArrayList<>();

            for (Object itemObj : JsCoercion.asList(result.get("allOf"))) {
                if (!(itemObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> item = JsCoercion.asMap(itemObj);

                if (item.get("properties") instanceof Map) {
                    Map<String, Object> mp = merged.get("properties") instanceof Map
                            ? new LinkedHashMap<>(JsCoercion.asMap(merged.get("properties"))) : new LinkedHashMap<>();
                    mp.putAll(JsCoercion.asMap(item.get("properties")));
                    merged.put("properties", mp);
                }

                if (item.get("required") instanceof List) {
                    for (Object req : JsCoercion.asList(item.get("required"))) {
                        if (!mergedRequired.contains(req)) {
                            mergedRequired.add(req);
                        }
                    }
                }

                for (Map.Entry<String, Object> e : item.entrySet()) {
                    String key = e.getKey();
                    if (!"properties".equals(key) && !"required".equals(key) && !merged.containsKey(key)) {
                        merged.put(key, e.getValue());
                    }
                }
            }

            if (merged.get("properties") instanceof Map) {
                Map<String, Object> rp = result.get("properties") instanceof Map
                        ? new LinkedHashMap<>(JsCoercion.asMap(result.get("properties"))) : new LinkedHashMap<>();
                rp.putAll(JsCoercion.asMap(merged.get("properties")));
                result.put("properties", rp);
            }
            if (!mergedRequired.isEmpty()) {
                List<Object> existingRequired = result.get("required") instanceof List
                        ? JsCoercion.asList(result.get("required")) : new ArrayList<Object>();
                Set<Object> deduped = new LinkedHashSet<>();
                deduped.addAll(existingRequired);
                deduped.addAll(mergedRequired);
                result.put("required", new ArrayList<>(deduped));
            }

            for (Map.Entry<String, Object> e : merged.entrySet()) {
                String key = e.getKey();
                if (!"properties".equals(key) && !"required".equals(key) && !result.containsKey(key)) {
                    result.put(key, e.getValue());
                }
            }

            result.remove("allOf");
        }

        for (String key : new ArrayList<>(result.keySet())) {
            Object value = result.get(key);
            if (value instanceof Map || value instanceof List) {
                result.put(key, mergeAllOf(value));
            }
        }
        return result;
    }

    // ---- scoreSchemaOption -----------------------------------------------------------------------

    private static final class ScoreResult {
        final int score;
        final Object typeName;

        ScoreResult(int score, Object typeName) {
            this.score = score;
            this.typeName = typeName;
        }
    }

    private static ScoreResult scoreSchemaOption(Object schemaObj) {
        if (!(schemaObj instanceof Map)) {
            return new ScoreResult(0, "unknown");
        }
        Map<String, Object> schema = JsCoercion.asMap(schemaObj);
        Object type = schema.get("type");

        if ("object".equals(type) || JsCoercion.isTruthy(schema.get("properties"))) {
            return new ScoreResult(3, "object");
        }
        if ("array".equals(type) || JsCoercion.isTruthy(schema.get("items"))) {
            return new ScoreResult(2, "array");
        }
        if (JsCoercion.isTruthy(type) && !"null".equals(type)) {
            return new ScoreResult(1, type);
        }
        return new ScoreResult(0, JsCoercion.isTruthy(type) ? type : "null");
    }

    // ---- tryMergeEnumFromUnion -------------------------------------------------------------------

    private static List<Object> tryMergeEnumFromUnion(List<Object> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }

        List<Object> enumValues = new ArrayList<>();

        for (Object optionObj : options) {
            if (!(optionObj instanceof Map)) {
                return null;
            }
            Map<String, Object> option = JsCoercion.asMap(optionObj);

            if (option.containsKey("const")) { // present const, even when null
                enumValues.add(jsString(option.get("const")));
                continue;
            }

            if (option.get("enum") instanceof List && JsCoercion.asList(option.get("enum")).size() == 1) {
                enumValues.add(jsString(JsCoercion.asList(option.get("enum")).get(0)));
                continue;
            }

            if (option.get("enum") instanceof List && !JsCoercion.asList(option.get("enum")).isEmpty()) {
                for (Object val : JsCoercion.asList(option.get("enum"))) {
                    enumValues.add(jsString(val));
                }
                continue;
            }

            if (JsCoercion.isTruthy(option.get("properties")) || JsCoercion.isTruthy(option.get("items"))
                    || JsCoercion.isTruthy(option.get("anyOf")) || JsCoercion.isTruthy(option.get("oneOf"))
                    || JsCoercion.isTruthy(option.get("allOf"))) {
                return null;
            }

            if (JsCoercion.isTruthy(option.get("type")) && !JsCoercion.isTruthy(option.get("const"))
                    && !JsCoercion.isTruthy(option.get("enum"))) {
                return null;
            }
        }

        return enumValues.isEmpty() ? null : enumValues;
    }

    // ---- flattenAnyOfOneOf -----------------------------------------------------------------------

    private static Object flattenAnyOfOneOf(Object schema) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }
        if (schema instanceof List) {
            return mapList(JsCoercion.asList(schema), AntigravitySchemaCleaner::flattenAnyOfOneOf);
        }

        Map<String, Object> result = new LinkedHashMap<>(JsCoercion.asMap(schema));

        for (String unionKey : new String[] {"anyOf", "oneOf"}) {
            if (result.get(unionKey) instanceof List && !JsCoercion.asList(result.get(unionKey)).isEmpty()) {
                List<Object> options = JsCoercion.asList(result.get(unionKey));
                String parentDesc = result.get("description") instanceof String ? (String) result.get("description") : "";

                List<Object> mergedEnum = tryMergeEnumFromUnion(options);
                if (mergedEnum != null) {
                    Map<String, Object> newResult = new LinkedHashMap<>(result);
                    newResult.remove(unionKey);
                    newResult.put("type", "string");
                    newResult.put("enum", mergedEnum);
                    if (!parentDesc.isEmpty()) {
                        newResult.put("description", parentDesc);
                    }
                    result = newResult;
                    continue;
                }

                int bestIdx = 0;
                int bestScore = -1;
                List<Object> allTypes = new ArrayList<>();

                for (int i = 0; i < options.size(); i++) {
                    ScoreResult sr = scoreSchemaOption(options.get(i));
                    if (JsCoercion.isTruthy(sr.typeName)) {
                        allTypes.add(sr.typeName);
                    }
                    if (sr.score > bestScore) {
                        bestScore = sr.score;
                        bestIdx = i;
                    }
                }

                Object selectedObj = flattenAnyOfOneOf(options.get(bestIdx));
                Map<String, Object> selected;
                if (selectedObj instanceof Map) {
                    selected = new LinkedHashMap<>(JsCoercion.asMap(selectedObj));
                } else {
                    // Falsy fallback to `{ type: "string" }`; a truthy non-object selected option is
                    // invalid schema (see class deviation).
                    selected = new LinkedHashMap<>();
                    selected.put("type", "string");
                }

                if (!parentDesc.isEmpty()) {
                    String childDesc = selected.get("description") instanceof String ? (String) selected.get("description") : "";
                    if (!childDesc.isEmpty() && !childDesc.equals(parentDesc)) {
                        selected.put("description", parentDesc + " (" + childDesc + ")");
                    } else if (childDesc.isEmpty()) {
                        selected.put("description", parentDesc);
                    }
                }

                if (allTypes.size() > 1) {
                    Set<Object> uniqueTypes = new LinkedHashSet<>(allTypes);
                    selected = (Map<String, Object>) appendDescriptionHint(selected,
                            "Accepts: " + joinJs(new ArrayList<>(uniqueTypes), " | "));
                }

                Map<String, Object> rest = new LinkedHashMap<>(result);
                rest.remove(unionKey);
                rest.remove("description");
                rest.putAll(selected);
                result = rest;
            }
        }

        for (String key : new ArrayList<>(result.keySet())) {
            Object value = result.get(key);
            if (value instanceof Map || value instanceof List) {
                result.put(key, flattenAnyOfOneOf(value));
            }
        }
        return result;
    }

    // ---- flattenTypeArrays -----------------------------------------------------------------------

    private static Object flattenTypeArrays(Object schema, Map<String, List<Object>> nullableFields, String currentPath) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }
        if (schema instanceof List) {
            List<Object> arr = JsCoercion.asList(schema);
            List<Object> out = new ArrayList<>();
            for (int idx = 0; idx < arr.size(); idx++) {
                out.add(flattenTypeArrays(arr.get(idx), nullableFields, pathBase(currentPath) + "[" + idx + "]"));
            }
            return out;
        }

        Map<String, Object> result = new LinkedHashMap<>(JsCoercion.asMap(schema));
        Map<String, List<Object>> localNullableFields = nullableFields != null ? nullableFields : new LinkedHashMap<String, List<Object>>();
        boolean topLevel = nullableFields == null; // null only on the top-level call

        if (result.get("type") instanceof List) {
            List<Object> types = JsCoercion.asList(result.get("type"));
            boolean hasNull = types.contains("null");
            List<Object> nonNullTypes = new ArrayList<>();
            for (Object t : types) {
                if (!"null".equals(t) && JsCoercion.isTruthy(t)) {
                    nonNullTypes.add(t);
                }
            }

            Object firstType = !nonNullTypes.isEmpty() ? nonNullTypes.get(0) : "string";
            result.put("type", firstType);

            if (nonNullTypes.size() > 1) {
                result = (Map<String, Object>) appendDescriptionHint(result, "Accepts: " + joinJs(nonNullTypes, " | "));
            }
            if (hasNull) {
                result = (Map<String, Object>) appendDescriptionHint(result, "nullable");
            }
        }

        if (result.get("properties") instanceof Map) {
            Map<String, Object> props = JsCoercion.asMap(result.get("properties"));
            Map<String, Object> newProps = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : props.entrySet()) {
                String propKey = e.getKey();
                String propPath = (currentPath != null && !currentPath.isEmpty())
                        ? currentPath + ".properties." + propKey : "properties." + propKey;
                Object processed = flattenTypeArrays(e.getValue(), localNullableFields, propPath);
                newProps.put(propKey, processed);

                if (processed instanceof Map) {
                    Object d = JsCoercion.asMap(processed).get("description");
                    if (d instanceof String && ((String) d).contains("nullable")) {
                        String objectPath = (currentPath != null && !currentPath.isEmpty()) ? currentPath : "";
                        List<Object> existing = localNullableFields.get(objectPath);
                        if (existing == null) {
                            existing = new ArrayList<>();
                        }
                        existing.add(propKey);
                        localNullableFields.put(objectPath, existing);
                    }
                }
            }
            result.put("properties", newProps);
        }

        if (result.get("required") instanceof List && topLevel) {
            List<Object> nullableAtRoot = localNullableFields.get("");
            if (nullableAtRoot == null) {
                nullableAtRoot = new ArrayList<>();
            }
            if (!nullableAtRoot.isEmpty()) {
                List<Object> filtered = new ArrayList<>();
                for (Object r : JsCoercion.asList(result.get("required"))) {
                    if (!nullableAtRoot.contains(r)) {
                        filtered.add(r);
                    }
                }
                if (filtered.isEmpty()) {
                    result.remove("required");
                } else {
                    result.put("required", filtered);
                }
            }
        }

        for (String key : new ArrayList<>(result.keySet())) {
            if ("properties".equals(key)) {
                continue;
            }
            Object value = result.get(key);
            if (value instanceof Map || value instanceof List) {
                result.put(key, flattenTypeArrays(value, localNullableFields, pathBase(currentPath) + "." + key));
            }
        }
        return result;
    }

    // JS `currentPath || ""`.
    private static String pathBase(String currentPath) {
        return (currentPath != null && !currentPath.isEmpty()) ? currentPath : "";
    }

    // ---- removeUnsupportedKeywords ---------------------------------------------------------------

    private static Object removeUnsupportedKeywords(Object schema, boolean insideProperties) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }
        if (schema instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : JsCoercion.asList(schema)) {
                out.add(removeUnsupportedKeywords(item, false));
            }
            return out;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : JsCoercion.asMap(schema).entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();

            if (!insideProperties && UNSUPPORTED_KEYWORDS.contains(key)) {
                continue;
            }
            // Vendor-extension keywords (`x-*`, e.g. `x-mcp-header`) are not valid Gemini Schema
            // fields; strip only inside schema bodies, never among property NAMES.
            if (!insideProperties && key.length() >= 2 && key.regionMatches(true, 0, "x-", 0, 2)) {
                continue;
            }

            if (value instanceof Map || value instanceof List) {
                if ("properties".equals(key) && value instanceof Map) {
                    Map<String, Object> propertiesResult = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> p : JsCoercion.asMap(value).entrySet()) {
                        propertiesResult.put(p.getKey(), removeUnsupportedKeywords(p.getValue(), false));
                    }
                    result.put(key, propertiesResult);
                } else {
                    result.put(key, removeUnsupportedKeywords(value, false));
                }
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    // ---- cleanupRequiredFields -------------------------------------------------------------------

    private static Object cleanupRequiredFields(Object schema) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }
        if (schema instanceof List) {
            return mapList(JsCoercion.asList(schema), AntigravitySchemaCleaner::cleanupRequiredFields);
        }

        Map<String, Object> result = new LinkedHashMap<>(JsCoercion.asMap(schema));

        if (result.get("required") instanceof List && result.get("properties") instanceof Map) {
            List<Object> required = JsCoercion.asList(result.get("required"));
            Map<String, Object> props = JsCoercion.asMap(result.get("properties"));
            List<Object> validRequired = new ArrayList<>();
            for (Object req : required) {
                if (props.containsKey(req)) {
                    validRequired.add(req);
                }
            }
            if (validRequired.isEmpty()) {
                result.remove("required");
            } else if (validRequired.size() != required.size()) {
                result.put("required", validRequired);
            }
        }

        for (String key : new ArrayList<>(result.keySet())) {
            Object value = result.get(key);
            if (value instanceof Map || value instanceof List) {
                result.put(key, cleanupRequiredFields(value));
            }
        }
        return result;
    }

    // ---- addEmptySchemaPlaceholder ---------------------------------------------------------------

    private static Object addEmptySchemaPlaceholder(Object schema) {
        if (!(schema instanceof Map) && !(schema instanceof List)) {
            return schema;
        }
        if (schema instanceof List) {
            return mapList(JsCoercion.asList(schema), AntigravitySchemaCleaner::addEmptySchemaPlaceholder);
        }

        Map<String, Object> result = new LinkedHashMap<>(JsCoercion.asMap(schema));

        if ("object".equals(result.get("type"))) {
            boolean hasProperties = result.get("properties") instanceof Map
                    && !JsCoercion.asMap(result.get("properties")).isEmpty();

            if (!hasProperties) {
                Map<String, Object> placeholder = new LinkedHashMap<>();
                placeholder.put("type", "boolean");
                placeholder.put("description", ClaudeTransforms.EMPTY_SCHEMA_PLACEHOLDER_DESCRIPTION);
                Map<String, Object> props = new LinkedHashMap<>();
                props.put(ClaudeTransforms.EMPTY_SCHEMA_PLACEHOLDER_NAME, placeholder);
                result.put("properties", props);
                List<Object> req = new ArrayList<>();
                req.add(ClaudeTransforms.EMPTY_SCHEMA_PLACEHOLDER_NAME);
                result.put("required", req);
            }
        }

        for (String key : new ArrayList<>(result.keySet())) {
            Object value = result.get(key);
            if (value instanceof Map || value instanceof List) {
                result.put(key, addEmptySchemaPlaceholder(value));
            }
        }
        return result;
    }

    // ---- shared helpers --------------------------------------------------------------------------

    private interface Mapper {
        Object apply(Object item);
    }

    private static List<Object> mapList(List<Object> items, Mapper mapper) {
        List<Object> out = new ArrayList<>();
        for (Object item : items) {
            out.add(mapper.apply(item));
        }
        return out;
    }

    // JS `values.map(v => String(v)).join(sep)`.
    private static String joinJs(List<Object> values, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(jsString(values.get(i)));
        }
        return sb.toString();
    }

    // JS String(x) for the string/number/boolean/null values encountered here.
    private static String jsString(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        return String.valueOf(v);
    }
}
