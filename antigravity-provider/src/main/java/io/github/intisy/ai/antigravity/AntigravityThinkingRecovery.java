package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.JsonCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "let it crash and start again" last-resort recovery for corrupted thinking state: detect an
 * incomplete tool loop that started without thinking, then close it with synthetic model/user messages
 * so the model starts a fresh turn. Implements the {@link AntigravityRequestPrep.ThinkingRecovery} seam
 * that {@link AntigravityRequestPrep} injects, so the JVM (and later TeaVM) path gets a real
 * implementation.
 *
 * <p>{@link JsonCodec} is accepted but unused by this pure logic (no {@code JSON.parse}/
 * {@code JSON.stringify}); it is kept only so this class's constructor matches every other
 * {@code Deps}-injected seam class in this package, wired identically at every call site.
 *
 * <p>Copy-vs-mutate fidelity: {@code closeToolLoopForThinking}/{@code stripAllThinkingBlocks} build NEW
 * lists/maps; {@code analyzeConversationState} never mutates its input. TeaVM-transpilable (String/Map/
 * List only, no reflection/streams/regex).
 */
public final class AntigravityThinkingRecovery implements AntigravityRequestPrep.ThinkingRecovery {

    private final JsonCodec json;

    public AntigravityThinkingRecovery(JsonCodec json) {
        this.json = json;
    }

    /** Conversation-state snapshot. */
    public static final class ConversationState {
        public boolean inToolLoop;
        public int turnStartIdx = -1;
        public boolean turnHasThinking;
        public int lastModelIdx = -1;
        public boolean lastModelHasThinking;
        public boolean lastModelHasToolCalls;
    }

    // ---- analyzeConversationState ----------------------------------

    @Override
    public Object analyzeConversationState(List<Object> contents) {
        ConversationState state = new ConversationState();
        if (contents == null || contents.isEmpty()) {
            return state;
        }

        int lastRealUserIdx = -1;
        for (int i = 0; i < contents.size(); i++) {
            Map<String, Object> msg = asMsg(contents.get(i));
            if (msg != null && "user".equals(msg.get("role")) && !isToolResultMessage(msg)) {
                lastRealUserIdx = i;
            }
        }

        for (int i = 0; i < contents.size(); i++) {
            Map<String, Object> msg = asMsg(contents.get(i));
            if (msg == null) {
                continue;
            }
            Object role = msg.get("role");
            if ("model".equals(role) || "assistant".equals(role)) {
                boolean hasThinking = messageHasThinking(msg);
                boolean hasToolCalls = messageHasToolCalls(msg);

                if (i > lastRealUserIdx && state.turnStartIdx == -1) {
                    state.turnStartIdx = i;
                    state.turnHasThinking = hasThinking;
                }

                state.lastModelIdx = i;
                state.lastModelHasToolCalls = hasToolCalls;
                state.lastModelHasThinking = hasThinking;
            }
        }

        Map<String, Object> lastMsg = asMsg(contents.get(contents.size() - 1));
        if (lastMsg != null && "user".equals(lastMsg.get("role")) && isToolResultMessage(lastMsg)) {
            state.inToolLoop = true;
        }

        return state;
    }

    // ---- needsThinkingRecovery -------------------------------------

    @Override
    public boolean needsThinkingRecovery(Object stateObj) {
        ConversationState state = (ConversationState) stateObj;
        return state.inToolLoop && !state.turnHasThinking;
    }

    // ---- closeToolLoopForThinking ----------------------------------

    @Override
    public List<Object> closeToolLoopForThinking(List<Object> contents) {
        List<Object> stripped = stripAllThinkingBlocks(contents);
        int toolResultCount = countTrailingToolResults(stripped);

        String syntheticModelContent;
        if (toolResultCount == 0) {
            syntheticModelContent = "[Processing previous context.]";
        } else if (toolResultCount == 1) {
            syntheticModelContent = "[Tool execution completed.]";
        } else {
            syntheticModelContent = "[" + toolResultCount + " tool executions completed.]";
        }

        Map<String, Object> syntheticModel = new LinkedHashMap<>();
        syntheticModel.put("role", "model");
        syntheticModel.put("parts", singlePart(syntheticModelContent));

        Map<String, Object> syntheticUser = new LinkedHashMap<>();
        syntheticUser.put("role", "user");
        syntheticUser.put("parts", singlePart("[Continue]"));

        List<Object> result = new ArrayList<>(stripped);
        result.add(syntheticModel);
        result.add(syntheticUser);
        return result;
    }

    private static List<Object> singlePart(String text) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("text", text);
        List<Object> parts = new ArrayList<>();
        parts.add(part);
        return parts;
    }

    // ---- looksLikeCompactedThinkingTurn ----------------------------

    /** Compacted-turn detection heuristic. */
    public boolean looksLikeCompactedThinkingTurn(Object msgObj) {
        Map<String, Object> msg = asMsg(msgObj);
        if (msg == null) {
            return false;
        }
        List<Object> parts = partsOf(msg);
        if (parts.isEmpty()) {
            return false;
        }

        int firstFuncIdx = -1;
        for (int i = 0; i < parts.size(); i++) {
            if (isTruthyFunctionCall(parts.get(i))) {
                firstFuncIdx = i;
                break;
            }
        }
        if (firstFuncIdx == -1) {
            return false;
        }

        for (Object partObj : parts) {
            if (!(partObj instanceof Map)) {
                continue;
            }
            Map<?, ?> part = (Map<?, ?>) partObj;
            if (Boolean.TRUE.equals(part.get("thought")) || "thinking".equals(part.get("type"))
                    || "redacted_thinking".equals(part.get("type"))) {
                return false;
            }
        }

        // Only parts strictly before the first functionCall count toward "text announced the call".
        for (int idx = 0; idx < firstFuncIdx; idx++) {
            Object partObj = parts.get(idx);
            if (!(partObj instanceof Map)) {
                continue;
            }
            Map<?, ?> part = (Map<?, ?>) partObj;
            Object text = part.get("text");
            if (text instanceof String && !((String) text).trim().isEmpty() && !JsCoercion.isTruthy(part.get("thought"))) {
                return false;
            }
        }

        return true;
    }

    private static boolean isTruthyFunctionCall(Object partObj) {
        return partObj instanceof Map && JsCoercion.isTruthy(((Map<?, ?>) partObj).get("functionCall"));
    }

    // ---- private helpers ------------------------------------

    private static boolean isThinkingPart(Map<?, ?> part) {
        return Boolean.TRUE.equals(part.get("thought"))
                || "thinking".equals(part.get("type"))
                || "redacted_thinking".equals(part.get("type"));
    }

    // `"functionResponse" in part`: key existence, not truthiness (a present falsy value counts).
    private static boolean isFunctionResponsePart(Object partObj) {
        return partObj instanceof Map && ((Map<?, ?>) partObj).containsKey("functionResponse");
    }

    private static boolean isFunctionCallPart(Object partObj) {
        return partObj instanceof Map && ((Map<?, ?>) partObj).containsKey("functionCall");
    }

    private static boolean isToolResultMessage(Map<String, Object> msg) {
        if (!"user".equals(msg.get("role"))) {
            return false;
        }
        for (Object part : partsOf(msg)) {
            if (isFunctionResponsePart(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean messageHasThinking(Map<String, Object> msg) {
        Object partsRaw = msg.get("parts");
        if (partsRaw instanceof List) {
            for (Object part : (List<?>) partsRaw) {
                if (part instanceof Map && isThinkingPart((Map<?, ?>) part)) {
                    return true;
                }
            }
            return false;
        }
        Object contentRaw = msg.get("content");
        if (contentRaw instanceof List) {
            for (Object block : (List<?>) contentRaw) {
                if (block instanceof Map) {
                    Object type = ((Map<?, ?>) block).get("type");
                    if ("thinking".equals(type) || "redacted_thinking".equals(type)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean messageHasToolCalls(Map<String, Object> msg) {
        Object partsRaw = msg.get("parts");
        if (partsRaw instanceof List) {
            for (Object part : (List<?>) partsRaw) {
                if (isFunctionCallPart(part)) {
                    return true;
                }
            }
            return false;
        }
        Object contentRaw = msg.get("content");
        if (contentRaw instanceof List) {
            for (Object block : (List<?>) contentRaw) {
                if (block instanceof Map && "tool_use".equals(((Map<?, ?>) block).get("type"))) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> stripAllThinkingBlocks(List<Object> contents) {
        List<Object> out = new ArrayList<>();
        for (Object contentObj : contents) {
            Map<String, Object> content = asMsg(contentObj);
            if (content == null) {
                out.add(contentObj);
                continue;
            }

            Object partsRaw = content.get("parts");
            if (partsRaw instanceof List) {
                List<Object> parts = (List<Object>) partsRaw;
                List<Object> filteredParts = new ArrayList<>();
                for (Object part : parts) {
                    if (!(part instanceof Map) || !isThinkingPart((Map<?, ?>) part)) {
                        filteredParts.add(part);
                    }
                }
                // Guard: never strip down to zero parts if there were any to begin with.
                if (filteredParts.isEmpty() && !parts.isEmpty()) {
                    out.add(contentObj);
                    continue;
                }
                Map<String, Object> newContent = new LinkedHashMap<>(content);
                newContent.put("parts", filteredParts);
                out.add(newContent);
                continue;
            }

            Object contentArrRaw = content.get("content");
            if (contentArrRaw instanceof List) {
                List<Object> contentArr = (List<Object>) contentArrRaw;
                List<Object> filteredContent = new ArrayList<>();
                for (Object block : contentArr) {
                    Object type = block instanceof Map ? ((Map<?, ?>) block).get("type") : null;
                    if (!"thinking".equals(type) && !"redacted_thinking".equals(type)) {
                        filteredContent.add(block);
                    }
                }
                if (filteredContent.isEmpty() && !contentArr.isEmpty()) {
                    out.add(contentObj);
                    continue;
                }
                Map<String, Object> newContent = new LinkedHashMap<>(content);
                newContent.put("content", filteredContent);
                out.add(newContent);
                continue;
            }

            out.add(contentObj);
        }
        return out;
    }

    private static int countTrailingToolResults(List<Object> contents) {
        int count = 0;
        for (int i = contents.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = asMsg(contents.get(i));
            Object role = msg != null ? msg.get("role") : null;
            if ("user".equals(role)) {
                int functionResponses = 0;
                for (Object part : partsOf(msg)) {
                    if (isFunctionResponsePart(part)) {
                        functionResponses++;
                    }
                }
                if (functionResponses > 0) {
                    count += functionResponses;
                } else {
                    break;
                }
            } else if ("model".equals(role) || "assistant".equals(role)) {
                break;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMsg(Object obj) {
        return obj instanceof Map ? (Map<String, Object>) obj : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> partsOf(Map<String, Object> msg) {
        Object partsRaw = msg.get("parts");
        return partsRaw instanceof List ? (List<Object>) partsRaw : new ArrayList<>();
    }
}
