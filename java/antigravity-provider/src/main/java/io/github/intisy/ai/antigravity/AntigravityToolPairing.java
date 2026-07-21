package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.JsonCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Anthropic/Gemini tool-pairing helpers: {@code fixToolResponseGrouping},
 * {@code findOrphanedToolUseIds}, {@code fixClaudeToolPairing} with its private
 * {@code removeOrphanedToolUse}, {@code validateAndFixClaudeToolPairing}, {@code injectParameterSignatures}
 * with its private {@code formatTypeHint}, {@code injectToolHardeningInstruction},
 * {@code assignToolIdsToContents}, {@code matchResponseIdsToContents} and {@code applyToolPairingFixes}.
 * The response/usage/error-parse half lives in {@link AntigravityResponseParse}.
 *
 * <p>These functions enforce the Anthropic tool_use/tool_result and Gemini functionCall/functionResponse
 * pairing rules: an orphaned call or a mis-grouped response makes the upstream API reject the request
 * (400), so ordering/grouping/id-matching matter exactly.
 *
 * <h2>Injected edges</h2>
 * <ul>
 *   <li><b>JsonCodec SPI</b> is used only for {@code JSON.stringify}: inside {@code formatTypeHint}
 *       (enum/const value rendering), threaded through {@code injectParameterSignatures}. The
 *       pairing/grouping functions are pure tree walks (no JSON (re)parse), so they take no codec.</li>
 *   <li><b>IdGenerator: NOT needed.</b> Tool ids are assigned with a deterministic monotonic COUNTER
 *       ({@code tool-call-${++n}}), never {@code crypto}/UUID, so {@code assignToolIdsToContents} uses a
 *       plain {@code int} counter (the {@code Date.now()} edge lives in
 *       {@code createSyntheticErrorResponse}, in {@link AntigravityResponseParse} via the Clock SPI).</li>
 *   <li><b>Store SPI (signature cache): NOT reached.</b> {@code injectParameterSignatures} works purely
 *       from the tool schema; it never touches {@code getCachedSignature}/{@code cacheSignature}.</li>
 * </ul>
 *
 * <p>Copy-vs-mutate fidelity: {@code assignToolIdsToContents}/{@code matchResponseIdsToContents}/
 * {@code fixClaudeToolPairing}/{@code injectParameterSignatures} build NEW arrays/maps;
 * {@code fixToolResponseGrouping} MUTATES the recovered response parts in place (reassigning
 * {@code functionResponse.id}/{@code name}); {@code injectToolHardeningInstruction}/
 * {@code applyToolPairingFixes} MUTATE the payload in place.
 *
 * <p>Deviations (none reachable by valid payloads): (1) the {@code log.debug}/{@code console.warn}
 * diagnostics (auto-repair notices, the nuclear-option warning) are omitted (no bearing on returned data,
 * no Logger edge). (2) Where a container is read via property access on a possibly-non-object, this class
 * requires a {@link Map} (a {@code null} content entry is passed through here); every such case is invalid
 * input. TeaVM-transpilable.
 */
public final class AntigravityToolPairing {

    /** Default parameter-signature template (⚠ + variation selector). */
    static final String DEFAULT_SIGNATURE_TEMPLATE = "\n\n⚠️ STRICT PARAMETERS: {params}.";

    private AntigravityToolPairing() {
    }

    // ---- fixToolResponseGrouping -----------------------------------

    private static final class PendingGroup {
        final List<String> ids;
        final List<String> funcNames;
        int insertAfterIdx;

        PendingGroup(List<String> ids, List<String> funcNames, int insertAfterIdx) {
            this.ids = ids;
            this.funcNames = funcNames;
            this.insertAfterIdx = insertAfterIdx;
        }
    }

    /**
     * Groups Gemini functionCalls with their functionResponses, recovering from stripped/mismatched ids
     * (exact id -> name -> {@code unknown_function} -> first available), and synthesizing recovered
     * placeholders when no response survives.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> fixToolResponseGrouping(List<Object> contents) {
        if (contents == null || contents.isEmpty()) {
            return contents;
        }

        List<Object> newContents = new ArrayList<>();
        List<PendingGroup> pendingGroups = new ArrayList<>();
        Map<String, Object> collectedResponses = new LinkedHashMap<>();

        for (Object contentRaw : contents) {
            if (!(contentRaw instanceof Map)) {
                newContents.add(contentRaw);
                continue;
            }
            Map<String, Object> content = (Map<String, Object>) contentRaw;
            Object role = content.get("role");
            List<Object> parts = content.get("parts") instanceof List ? (List<Object>) content.get("parts") : new ArrayList<>();

            List<Object> responseParts = new ArrayList<>();
            for (Object p : parts) {
                if (p instanceof Map && JsCoercion.isTruthy(((Map<?, ?>) p).get("functionResponse"))) {
                    responseParts.add(p);
                }
            }

            if (!responseParts.isEmpty()) {
                for (Object respRaw : responseParts) {
                    Map<String, Object> fr = (Map<String, Object>) ((Map<String, Object>) respRaw).get("functionResponse");
                    Object idVal = fr.get("id");
                    String respId = JsCoercion.isTruthy(idVal) ? String.valueOf(idVal) : "";
                    if (!respId.isEmpty() && !collectedResponses.containsKey(respId)) {
                        collectedResponses.put(respId, respRaw);
                    }
                }

                for (int i = pendingGroups.size() - 1; i >= 0; i--) {
                    PendingGroup group = pendingGroups.get(i);
                    if (allCollected(group.ids, collectedResponses)) {
                        List<Object> groupResponses = new ArrayList<>();
                        for (String id : group.ids) {
                            groupResponses.add(collectedResponses.remove(id));
                        }
                        Map<String, Object> userMsg = new LinkedHashMap<>();
                        userMsg.put("parts", groupResponses);
                        userMsg.put("role", "user");
                        newContents.add(userMsg);
                        pendingGroups.remove(i);
                        break;
                    }
                }
                continue;
            }

            if ("model".equals(role)) {
                List<Object> funcCalls = new ArrayList<>();
                for (Object p : parts) {
                    if (p instanceof Map && JsCoercion.isTruthy(((Map<?, ?>) p).get("functionCall"))) {
                        funcCalls.add(p);
                    }
                }
                newContents.add(content);

                if (!funcCalls.isEmpty()) {
                    List<String> callIds = new ArrayList<>();
                    List<String> funcNames = new ArrayList<>();
                    for (Object fcRaw : funcCalls) {
                        Map<String, Object> fc = (Map<String, Object>) ((Map<String, Object>) fcRaw).get("functionCall");
                        Object idVal = fc.get("id");
                        String id = JsCoercion.isTruthy(idVal) ? String.valueOf(idVal) : "";
                        if (!id.isEmpty()) {
                            callIds.add(id);
                        }
                        Object nameVal = fc.get("name");
                        funcNames.add(JsCoercion.isTruthy(nameVal) ? String.valueOf(nameVal) : "");
                    }
                    if (!callIds.isEmpty()) {
                        pendingGroups.add(new PendingGroup(callIds, funcNames, newContents.size() - 1));
                    }
                }
            } else {
                newContents.add(content);
            }
        }

        // Descending by insertAfterIdx so later inserts don't shift earlier indices.
        Collections.sort(pendingGroups, (a, b) -> Integer.compare(b.insertAfterIdx, a.insertAfterIdx));

        for (PendingGroup group : pendingGroups) {
            List<Object> groupResponses = new ArrayList<>();

            for (int i = 0; i < group.ids.size(); i++) {
                String expectedId = group.ids.get(i);
                String expectedName = i < group.funcNames.size() && group.funcNames.get(i) != null ? group.funcNames.get(i) : "";

                if (collectedResponses.containsKey(expectedId)) {
                    groupResponses.add(collectedResponses.remove(expectedId));
                } else if (!collectedResponses.isEmpty()) {
                    String matchedId = null;

                    for (Map.Entry<String, Object> e : collectedResponses.entrySet()) {
                        Map<String, Object> fr = functionResponseOf(e.getValue());
                        Object nameVal = fr != null ? fr.get("name") : null;
                        String orphanName = JsCoercion.isTruthy(nameVal) ? String.valueOf(nameVal) : "";
                        if (orphanName.equals(expectedName)) {
                            matchedId = e.getKey();
                            break;
                        }
                    }

                    if (matchedId == null) {
                        for (Map.Entry<String, Object> e : collectedResponses.entrySet()) {
                            Map<String, Object> fr = functionResponseOf(e.getValue());
                            if (fr != null && "unknown_function".equals(fr.get("name"))) {
                                matchedId = e.getKey();
                                break;
                            }
                        }
                    }

                    if (matchedId == null) {
                        matchedId = collectedResponses.keySet().iterator().next();
                    }

                    Object orphanResp = collectedResponses.remove(matchedId);
                    Map<String, Object> fr = functionResponseOf(orphanResp);
                    fr.put("id", expectedId);
                    if ("unknown_function".equals(fr.get("name")) && !expectedName.isEmpty()) {
                        fr.put("name", expectedName);
                    }
                    groupResponses.add(orphanResp);
                } else {
                    Map<String, Object> resultError = new LinkedHashMap<>();
                    resultError.put("error", "Tool response was lost during context processing. "
                            + "This is a recovered placeholder.");
                    resultError.put("recovered", true);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("result", resultError);
                    Map<String, Object> fr = new LinkedHashMap<>();
                    fr.put("name", expectedName.isEmpty() ? "unknown_function" : expectedName);
                    fr.put("response", result);
                    fr.put("id", expectedId);
                    Map<String, Object> placeholder = new LinkedHashMap<>();
                    placeholder.put("functionResponse", fr);
                    groupResponses.add(placeholder);
                }
            }

            if (!groupResponses.isEmpty()) {
                Map<String, Object> userMsg = new LinkedHashMap<>();
                userMsg.put("parts", groupResponses);
                userMsg.put("role", "user");
                newContents.add(group.insertAfterIdx + 1, userMsg);
            }
        }

        return newContents;
    }

    private static boolean allCollected(List<String> ids, Map<String, Object> collected) {
        for (String id : ids) {
            if (!collected.containsKey(id)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> functionResponseOf(Object respPart) {
        Object fr = respPart instanceof Map ? ((Map<String, Object>) respPart).get("functionResponse") : null;
        return fr instanceof Map ? (Map<String, Object>) fr : null;
    }

    // ---- findOrphanedToolUseIds ------------------------------------

    /**
     * The set of Anthropic {@code tool_use} ids that have no matching {@code tool_result}, in
     * first-seen order.
     */
    public static Set<String> findOrphanedToolUseIds(List<Object> messages) {
        Set<String> toolUseIds = new LinkedHashSet<>();
        Set<String> toolResultIds = new LinkedHashSet<>();

        for (Object msgRaw : messages) {
            if (!(msgRaw instanceof Map)) {
                continue;
            }
            Object content = ((Map<?, ?>) msgRaw).get("content");
            if (!(content instanceof List)) {
                continue;
            }
            for (Object blockRaw : (List<?>) content) {
                if (!(blockRaw instanceof Map)) {
                    continue;
                }
                Map<?, ?> block = (Map<?, ?>) blockRaw;
                if ("tool_use".equals(block.get("type")) && JsCoercion.isTruthy(block.get("id"))) {
                    toolUseIds.add(String.valueOf(block.get("id")));
                }
                if ("tool_result".equals(block.get("type")) && JsCoercion.isTruthy(block.get("tool_use_id"))) {
                    toolResultIds.add(String.valueOf(block.get("tool_use_id")));
                }
            }
        }

        Set<String> orphans = new LinkedHashSet<>();
        for (String id : toolUseIds) {
            if (!toolResultIds.contains(id)) {
                orphans.add(id);
            }
        }
        return orphans;
    }

    // ---- fixClaudeToolPairing --------------------------------------

    private static final class ToolUseInfo {
        final String name;
        final int msgIndex;

        ToolUseInfo(String name, int msgIndex) {
            this.name = name;
            this.msgIndex = msgIndex;
        }
    }

    /**
     * Injects placeholder {@code tool_result} blocks for orphaned {@code tool_use}s, merged into the
     * following user message when present, else a new user message after the assistant turn.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> fixClaudeToolPairing(List<Object> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        Map<String, ToolUseInfo> toolUseMap = new LinkedHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            Object msgRaw = messages.get(i);
            if (msgRaw instanceof Map && "assistant".equals(((Map<?, ?>) msgRaw).get("role"))
                    && ((Map<?, ?>) msgRaw).get("content") instanceof List) {
                for (Object blockRaw : (List<Object>) ((Map<?, ?>) msgRaw).get("content")) {
                    if (blockRaw instanceof Map) {
                        Map<?, ?> block = (Map<?, ?>) blockRaw;
                        if ("tool_use".equals(block.get("type")) && JsCoercion.isTruthy(block.get("id"))) {
                            Object nameVal = block.get("name");
                            String name = JsCoercion.isTruthy(nameVal) ? String.valueOf(nameVal) : "tool-" + toolUseMap.size();
                            toolUseMap.put(String.valueOf(block.get("id")), new ToolUseInfo(name, i));
                        }
                    }
                }
            }
        }

        Set<String> toolResultIds = new LinkedHashSet<>();
        for (Object msgRaw : messages) {
            if (msgRaw instanceof Map && "user".equals(((Map<?, ?>) msgRaw).get("role"))
                    && ((Map<?, ?>) msgRaw).get("content") instanceof List) {
                for (Object blockRaw : (List<Object>) ((Map<?, ?>) msgRaw).get("content")) {
                    if (blockRaw instanceof Map) {
                        Map<?, ?> block = (Map<?, ?>) blockRaw;
                        if ("tool_result".equals(block.get("type")) && JsCoercion.isTruthy(block.get("tool_use_id"))) {
                            toolResultIds.add(String.valueOf(block.get("tool_use_id")));
                        }
                    }
                }
            }
        }

        // orphans grouped by originating assistant message index (insertion order preserved).
        Map<Integer, List<String[]>> orphansByMsgIndex = new LinkedHashMap<>();
        int orphanCount = 0;
        for (Map.Entry<String, ToolUseInfo> e : toolUseMap.entrySet()) {
            if (!toolResultIds.contains(e.getKey())) {
                orphanCount++;
                List<String[]> existing = orphansByMsgIndex.get(e.getValue().msgIndex);
                if (existing == null) {
                    existing = new ArrayList<>();
                    orphansByMsgIndex.put(e.getValue().msgIndex, existing);
                }
                existing.add(new String[] {e.getKey(), e.getValue().name});
            }
        }

        if (orphanCount == 0) {
            return messages;
        }

        List<Object> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            result.add(messages.get(i));

            List<String[]> orphansForMsg = orphansByMsgIndex.get(i);
            if (orphansForMsg != null && !orphansForMsg.isEmpty()) {
                Object nextMsgRaw = i + 1 < messages.size() ? messages.get(i + 1) : null;
                if (nextMsgRaw instanceof Map && "user".equals(((Map<?, ?>) nextMsgRaw).get("role"))
                        && ((Map<?, ?>) nextMsgRaw).get("content") instanceof List) {
                    Map<String, Object> nextMsg = (Map<String, Object>) nextMsgRaw;
                    List<Object> merged = new ArrayList<>();
                    for (String[] o : orphansForMsg) {
                        merged.add(placeholderToolResult(o[0], o[1]));
                    }
                    merged.addAll((List<Object>) nextMsg.get("content"));
                    nextMsg.put("content", merged);
                } else {
                    List<Object> placeholders = new ArrayList<>();
                    for (String[] o : orphansForMsg) {
                        placeholders.add(placeholderToolResult(o[0], o[1]));
                    }
                    Map<String, Object> userMsg = new LinkedHashMap<>();
                    userMsg.put("role", "user");
                    userMsg.put("content", placeholders);
                    result.add(userMsg);
                }
            }
        }

        return result;
    }

    private static Map<String, Object> placeholderToolResult(String toolUseId, String name) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", toolUseId);
        block.put("content", "[Tool \"" + name + "\" execution was cancelled or failed]");
        block.put("is_error", true);
        return block;
    }

    // ---- removeOrphanedToolUse -------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Object> removeOrphanedToolUse(List<Object> messages, Set<String> orphanIds) {
        List<Object> mapped = new ArrayList<>();
        for (Object msgRaw : messages) {
            if (msgRaw instanceof Map && "assistant".equals(((Map<?, ?>) msgRaw).get("role"))
                    && ((Map<?, ?>) msgRaw).get("content") instanceof List) {
                Map<String, Object> msg = (Map<String, Object>) msgRaw;
                List<Object> filtered = new ArrayList<>();
                for (Object blockRaw : (List<Object>) msg.get("content")) {
                    boolean isOrphanToolUse = blockRaw instanceof Map
                            && "tool_use".equals(((Map<?, ?>) blockRaw).get("type"))
                            && orphanIds.contains(String.valueOf(((Map<?, ?>) blockRaw).get("id")));
                    if (!isOrphanToolUse) {
                        filtered.add(blockRaw);
                    }
                }
                Map<String, Object> newMsg = new LinkedHashMap<>(msg);
                newMsg.put("content", filtered);
                mapped.add(newMsg);
            } else {
                mapped.add(msgRaw);
            }
        }

        List<Object> result = new ArrayList<>();
        for (Object msgRaw : mapped) {
            boolean emptyAssistant = msgRaw instanceof Map
                    && "assistant".equals(((Map<?, ?>) msgRaw).get("role"))
                    && ((Map<?, ?>) msgRaw).get("content") instanceof List
                    && ((List<?>) ((Map<?, ?>) msgRaw).get("content")).isEmpty();
            if (!emptyAssistant) {
                result.add(msgRaw);
            }
        }
        return result;
    }

    // ---- validateAndFixClaudeToolPairing ---------------------------

    /**
     * Gentle placeholder fix first; if orphans remain, the nuclear option removes the orphaned
     * {@code tool_use} blocks (and now-empty assistant turns).
     */
    public static List<Object> validateAndFixClaudeToolPairing(List<Object> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        List<Object> fixed = fixClaudeToolPairing(messages);
        Set<String> orphanIds = findOrphanedToolUseIds(fixed);
        if (orphanIds.isEmpty()) {
            return fixed;
        }
        // console.warn diagnostic omitted (no Logger edge; no bearing on returned data).
        return removeOrphanedToolUse(fixed, orphanIds);
    }

    // ---- injectParameterSignatures + formatTypeHint ----------------

    /** Uses the default signature template. */
    public static List<Object> injectParameterSignatures(JsonCodec json, List<Object> tools) {
        return injectParameterSignatures(json, tools, DEFAULT_SIGNATURE_TEMPLATE);
    }

    /**
     * Appends an explicit "STRICT PARAMETERS: ..." signature (built from each declaration's parameter
     * schema) to tool descriptions, to curb parameter hallucination. Declarations already carrying the
     * marker, or with no properties/schema, are left untouched.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> injectParameterSignatures(JsonCodec json, List<Object> tools, String promptTemplate) {
        if (tools == null) {
            return tools;
        }

        List<Object> result = new ArrayList<>();
        for (Object toolRaw : tools) {
            if (!(toolRaw instanceof Map)) {
                result.add(toolRaw);
                continue;
            }
            Map<String, Object> tool = (Map<String, Object>) toolRaw;
            Object declarationsRaw = tool.get("functionDeclarations");
            if (!(declarationsRaw instanceof List)) {
                result.add(tool);
                continue;
            }

            List<Object> newDeclarations = new ArrayList<>();
            for (Object declRaw : (List<Object>) declarationsRaw) {
                newDeclarations.add(injectSignatureIntoDeclaration(json, declRaw, promptTemplate));
            }

            Map<String, Object> newTool = new LinkedHashMap<>(tool);
            newTool.put("functionDeclarations", newDeclarations);
            result.add(newTool);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object injectSignatureIntoDeclaration(JsonCodec json, Object declRaw, String promptTemplate) {
        if (!(declRaw instanceof Map)) {
            return declRaw;
        }
        Map<String, Object> decl = (Map<String, Object>) declRaw;

        Object desc = decl.get("description");
        if (desc instanceof String && ((String) desc).contains("STRICT PARAMETERS:")) {
            return decl;
        }

        Object schemaRaw = JsCoercion.firstTruthy(decl.get("parameters"), decl.get("parametersJsonSchema"));
        if (!JsCoercion.isTruthy(schemaRaw) || !(schemaRaw instanceof Map)) {
            return decl;
        }
        Map<String, Object> schema = (Map<String, Object>) schemaRaw;

        List<Object> required = schema.get("required") instanceof List ? (List<Object>) schema.get("required") : new ArrayList<>();
        Map<String, Object> properties = schema.get("properties") instanceof Map
                ? (Map<String, Object>) schema.get("properties") : new LinkedHashMap<>();

        if (properties.isEmpty()) {
            return decl;
        }

        List<String> paramList = new ArrayList<>();
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            String propName = e.getKey();
            String typeHint = formatTypeHint(json, e.getValue(), 0);
            boolean isRequired = required.contains(propName);
            paramList.add(propName + " (" + typeHint + (isRequired ? ", REQUIRED" : "") + ")");
        }

        String sigStr = promptTemplate.replace("{params}", join(paramList, ", "));

        Object descriptionVal = JsCoercion.firstTruthy(decl.get("description"), "");
        String baseDescription = descriptionVal instanceof String ? (String) descriptionVal : "";

        Map<String, Object> newDecl = new LinkedHashMap<>(decl);
        newDecl.put("description", baseDescription + sigStr);
        return newDecl;
    }

    @SuppressWarnings("unchecked")
    private static String formatTypeHint(JsonCodec json, Object propDataRaw, int depth) {
        Map<String, Object> propData = propDataRaw instanceof Map ? (Map<String, Object>) propDataRaw : new LinkedHashMap<>();
        String type = propData.get("type") != null ? String.valueOf(propData.get("type")) : "unknown";

        Object enumRaw = propData.get("enum");
        if (JsCoercion.isTruthy(enumRaw) && enumRaw instanceof List) {
            List<Object> enumVals = (List<Object>) enumRaw;
            if (enumVals.size() <= 5) {
                List<String> rendered = new ArrayList<>();
                for (Object v : enumVals) {
                    rendered.add(json.stringify(v));
                }
                return "string ENUM[" + join(rendered, ", ") + "]";
            }
            return "string ENUM[" + enumVals.size() + " options]";
        }

        // An explicit `const: null` (valid JSON Schema) still renders, so key presence (not non-null)
        // is the gate.
        if (propData.containsKey("const")) {
            return "string CONST=" + json.stringify(propData.get("const"));
        }

        if ("array".equals(type)) {
            Object itemsRaw = propData.get("items");
            if (itemsRaw instanceof Map) {
                Map<String, Object> items = (Map<String, Object>) itemsRaw;
                String itemType = items.get("type") != null ? String.valueOf(items.get("type")) : "unknown";
                if ("object".equals(itemType)) {
                    Object nestedProps = items.get("properties");
                    List<Object> nestedReq = items.get("required") instanceof List ? (List<Object>) items.get("required") : new ArrayList<>();
                    if (nestedProps instanceof Map && depth < 1) {
                        return "ARRAY_OF_OBJECTS[" + join(renderNested((Map<String, Object>) nestedProps, nestedReq), ", ") + "]";
                    }
                    return "ARRAY_OF_OBJECTS";
                }
                return "ARRAY_OF_" + itemType.toUpperCase();
            }
            return "ARRAY";
        }

        if ("object".equals(type)) {
            Object nestedProps = propData.get("properties");
            List<Object> nestedReq = propData.get("required") instanceof List ? (List<Object>) propData.get("required") : new ArrayList<>();
            if (nestedProps instanceof Map && depth < 1) {
                return "object{" + join(renderNested((Map<String, Object>) nestedProps, nestedReq), ", ") + "}";
            }
        }

        return type;
    }

    private static List<String> renderNested(Map<String, Object> nestedProps, List<Object> nestedReq) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : nestedProps.entrySet()) {
            Object dRaw = e.getValue();
            Object t = dRaw instanceof Map ? ((Map<?, ?>) dRaw).get("type") : null;
            String tStr = t != null ? String.valueOf(t) : "unknown";
            String req = nestedReq.contains(e.getKey()) ? " REQUIRED" : "";
            out.add(e.getKey() + ": " + tStr + req);
        }
        return out;
    }

    // ---- injectToolHardeningInstruction ----------------------------

    /**
     * Prepends a tool-hardening system instruction to {@code payload.systemInstruction} (unshift into
     * existing parts / wrap a string / create fresh), skipping when the "CRITICAL TOOL USAGE
     * INSTRUCTIONS" marker is already present. Mutates {@code payload}.
     */
    @SuppressWarnings("unchecked")
    public static void injectToolHardeningInstruction(Map<String, Object> payload, String instructionText) {
        if (instructionText == null || instructionText.isEmpty()) {
            return;
        }

        Object existing = payload.get("systemInstruction");
        boolean existingObjWithParts = existing instanceof Map && ((Map<?, ?>) existing).containsKey("parts");

        if (existingObjWithParts) {
            Object parts = ((Map<?, ?>) existing).get("parts");
            if (parts instanceof List) {
                for (Object p : (List<?>) parts) {
                    if (p instanceof Map) {
                        Object text = ((Map<?, ?>) p).get("text");
                        if (text instanceof String && ((String) text).contains("CRITICAL TOOL USAGE INSTRUCTIONS")) {
                            return;
                        }
                    }
                }
            }
        }

        Map<String, Object> instructionPart = new LinkedHashMap<>();
        instructionPart.put("text", instructionText);

        if (JsCoercion.isTruthy(payload.get("systemInstruction"))) {
            if (existingObjWithParts) {
                Object parts = ((Map<?, ?>) existing).get("parts");
                if (parts instanceof List) {
                    ((List<Object>) parts).add(0, instructionPart);
                }
            } else if (existing instanceof String) {
                Map<String, Object> textPart = new LinkedHashMap<>();
                textPart.put("text", existing);
                payload.put("systemInstruction", systemInstruction(instructionPart, textPart));
            } else {
                payload.put("systemInstruction", systemInstruction(instructionPart));
            }
        } else {
            payload.put("systemInstruction", systemInstruction(instructionPart));
        }
    }

    private static Map<String, Object> systemInstruction(Object... parts) {
        List<Object> partList = new ArrayList<>();
        Collections.addAll(partList, parts);
        Map<String, Object> si = new LinkedHashMap<>();
        si.put("role", "user");
        si.put("parts", partList);
        return si;
    }

    // ---- assignToolIdsToContents -----------------------------------

    /** Result of {@link #assignToolIdsToContents}: the id-stamped contents, the name->ids queue map, and the counter. */
    public static final class AssignResult {
        public final Object contents;
        public final Map<String, List<String>> pendingCallIdsByName;
        public final int toolCallCounter;

        AssignResult(Object contents, Map<String, List<String>> pendingCallIdsByName, int toolCallCounter) {
            this.contents = contents;
            this.pendingCallIdsByName = pendingCallIdsByName;
            this.toolCallCounter = toolCallCounter;
        }
    }

    /**
     * Stamps a deterministic {@code tool-call-<n>} id onto every functionCall missing one, tracking the
     * assigned ids per function name for later response matching. Returns a NEW contents array (or the
     * raw value unchanged when it is not an array).
     */
    @SuppressWarnings("unchecked")
    public static AssignResult assignToolIdsToContents(Object contentsRaw) {
        Map<String, List<String>> pendingCallIdsByName = new LinkedHashMap<>();
        if (!(contentsRaw instanceof List)) {
            return new AssignResult(contentsRaw, pendingCallIdsByName, 0);
        }

        int[] counter = {0};
        List<Object> newContents = new ArrayList<>();
        for (Object contentRaw : (List<Object>) contentsRaw) {
            if (!(contentRaw instanceof Map) || !(((Map<?, ?>) contentRaw).get("parts") instanceof List)) {
                newContents.add(contentRaw);
                continue;
            }
            Map<String, Object> content = (Map<String, Object>) contentRaw;
            List<Object> newParts = new ArrayList<>();
            for (Object partRaw : (List<Object>) content.get("parts")) {
                if (partRaw instanceof Map && JsCoercion.isTruthy(((Map<?, ?>) partRaw).get("functionCall"))) {
                    Map<String, Object> part = (Map<String, Object>) partRaw;
                    Map<String, Object> call = new LinkedHashMap<>((Map<String, Object>) part.get("functionCall"));
                    if (!JsCoercion.isTruthy(call.get("id"))) {
                        call.put("id", "tool-call-" + (++counter[0]));
                    }
                    Object nameVal = call.get("name");
                    String nameKey = nameVal instanceof String ? (String) nameVal : "tool-" + counter[0];
                    String callId = String.valueOf(call.get("id"));
                    List<String> queue = pendingCallIdsByName.get(nameKey);
                    if (queue == null) {
                        queue = new ArrayList<>();
                        pendingCallIdsByName.put(nameKey, queue);
                    }
                    queue.add(callId);
                    Map<String, Object> newPart = new LinkedHashMap<>(part);
                    newPart.put("functionCall", call);
                    newParts.add(newPart);
                } else {
                    newParts.add(partRaw);
                }
            }
            Map<String, Object> newContent = new LinkedHashMap<>(content);
            newContent.put("parts", newParts);
            newContents.add(newContent);
        }

        return new AssignResult(newContents, pendingCallIdsByName, counter[0]);
    }

    // ---- matchResponseIdsToContents --------------------------------

    /**
     * Fills each id-less functionResponse's id from the pending queue for its function name (FIFO),
     * returning a NEW contents array (or the raw value unchanged when it is not an array). Mutates the
     * pending queues (shift).
     */
    @SuppressWarnings("unchecked")
    public static Object matchResponseIdsToContents(Object contentsRaw, Map<String, List<String>> pendingCallIdsByName) {
        if (!(contentsRaw instanceof List)) {
            return contentsRaw;
        }

        List<Object> result = new ArrayList<>();
        for (Object contentRaw : (List<Object>) contentsRaw) {
            if (!(contentRaw instanceof Map) || !(((Map<?, ?>) contentRaw).get("parts") instanceof List)) {
                result.add(contentRaw);
                continue;
            }
            Map<String, Object> content = (Map<String, Object>) contentRaw;
            List<Object> newParts = new ArrayList<>();
            for (Object partRaw : (List<Object>) content.get("parts")) {
                if (partRaw instanceof Map && JsCoercion.isTruthy(((Map<?, ?>) partRaw).get("functionResponse"))) {
                    Map<String, Object> part = (Map<String, Object>) partRaw;
                    Map<String, Object> resp = new LinkedHashMap<>((Map<String, Object>) part.get("functionResponse"));
                    if (!JsCoercion.isTruthy(resp.get("id")) && resp.get("name") instanceof String) {
                        List<String> queue = pendingCallIdsByName.get(resp.get("name"));
                        if (queue != null && !queue.isEmpty()) {
                            resp.put("id", queue.remove(0));
                        }
                    }
                    Map<String, Object> newPart = new LinkedHashMap<>(part);
                    newPart.put("functionResponse", resp);
                    newParts.add(newPart);
                } else {
                    newParts.add(partRaw);
                }
            }
            Map<String, Object> newContent = new LinkedHashMap<>(content);
            newContent.put("parts", newParts);
            result.add(newContent);
        }
        return result;
    }

    // ---- applyToolPairingFixes -------------------------------------

    /** Result of {@link #applyToolPairingFixes}: which of the two array shapes was rewritten. */
    public static final class PairingFixResult {
        public final boolean contentsFixed;
        public final boolean messagesFixed;

        PairingFixResult(boolean contentsFixed, boolean messagesFixed) {
            this.contentsFixed = contentsFixed;
            this.messagesFixed = messagesFixed;
        }
    }

    /**
     * For Claude requests, runs the full contents[] id-assign -> response-match -> grouping pipeline
     * and/or the messages[] pairing fix, mutating {@code payload}.
     */
    @SuppressWarnings("unchecked")
    public static PairingFixResult applyToolPairingFixes(JsonCodec json, Map<String, Object> payload, boolean isClaude) {
        boolean contentsFixed = false;
        boolean messagesFixed = false;

        if (!isClaude) {
            return new PairingFixResult(false, false);
        }

        if (payload.get("contents") instanceof List) {
            AssignResult assigned = assignToolIdsToContents(payload.get("contents"));
            Object matched = matchResponseIdsToContents(assigned.contents, assigned.pendingCallIdsByName);
            payload.put("contents", fixToolResponseGrouping((List<Object>) matched));
            contentsFixed = true;
        }

        if (payload.get("messages") instanceof List) {
            payload.put("messages", validateAndFixClaudeToolPairing((List<Object>) payload.get("messages")));
            messagesFixed = true;
        }

        return new PairingFixResult(contentsFixed, messagesFixed);
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
