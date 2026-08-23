package io.github.intisy.ai.antigravity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.intisy.ai.antigravity.Fixtures.list;
import static io.github.intisy.ai.antigravity.Fixtures.map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for {@link AntigravityThinkingRecovery} and {@link
 * AntigravityResponseParse#detectErrorType}.
 */
class AntigravityThinkingRecoveryTest {

    private final AntigravityThinkingRecovery recovery = new AntigravityThinkingRecovery(new TestJsonCodec());

    // ---- fixtures --------------------------------------------------------------------------------

    private static Map<String, Object> userMsg(String text) {
        return map("role", "user", "parts", list(map("text", text)));
    }

    private static Map<String, Object> modelMsg(String text) {
        return map("role", "model", "parts", list(map("text", text)));
    }

    private static Map<String, Object> modelWithThinking(String text) {
        return map("role", "model", "parts", list(map("thought", true, "text", "thinking..."), map("text", text)));
    }

    private static Map<String, Object> modelWithToolCall(String name) {
        return map("role", "model", "parts", list(map("functionCall", map("name", name, "args", map()))));
    }

    private static Map<String, Object> modelWithThinkingAndToolCall(String name) {
        return map("role", "model", "parts", list(
                map("thought", true, "text", "reasoning..."),
                map("functionCall", map("name", name, "args", map()))));
    }

    private static Map<String, Object> toolResultMsg(String name) {
        return map("role", "user", "parts", list(map("functionResponse", map("name", name, "response", map("result", "ok")))));
    }

    // ---- analyzeConversationState ------------------------------------------------------------------

    @Test
    void analyze_emptyContents_returnsDefaultState() {
        AntigravityThinkingRecovery.ConversationState state =
                (AntigravityThinkingRecovery.ConversationState) recovery.analyzeConversationState(list());
        assertFalse(state.inToolLoop);
        assertEquals(-1, state.turnStartIdx);
        assertEquals(-1, state.lastModelIdx);
    }

    @Test
    void analyze_nullContents_returnsDefaultState() {
        AntigravityThinkingRecovery.ConversationState state =
                (AntigravityThinkingRecovery.ConversationState) recovery.analyzeConversationState(null);
        assertFalse(state.inToolLoop);
    }

    @Test
    void analyze_simpleUserModel_notInToolLoop() {
        List<Object> contents = list(userMsg("hello"), modelMsg("hi there"));
        AntigravityThinkingRecovery.ConversationState state =
                (AntigravityThinkingRecovery.ConversationState) recovery.analyzeConversationState(contents);
        assertFalse(state.inToolLoop);
        assertEquals(1, state.lastModelIdx);
        assertFalse(state.lastModelHasThinking);
        assertFalse(state.lastModelHasToolCalls);
    }

    @Test
    void analyze_thinkingInLastModelMessage() {
        List<Object> contents = list(userMsg("hello"), modelWithThinking("hi there"));
        AntigravityThinkingRecovery.ConversationState state =
                (AntigravityThinkingRecovery.ConversationState) recovery.analyzeConversationState(contents);
        assertTrue(state.lastModelHasThinking);
        assertTrue(state.turnHasThinking);
    }

    @Test
    void analyze_toolLoop_endsWithToolResult() {
        List<Object> contents = list(userMsg("do something"), modelWithToolCall("search"), toolResultMsg("search"));
        AntigravityThinkingRecovery.ConversationState state =
                (AntigravityThinkingRecovery.ConversationState) recovery.analyzeConversationState(contents);
        assertTrue(state.inToolLoop);
        assertEquals(1, state.lastModelIdx);
        assertTrue(state.lastModelHasToolCalls);
    }

    @Test
    void analyze_toolLoop_multipleToolResults() {
        List<Object> contents = list(
                userMsg("do two things"),
                map("role", "model", "parts", list(
                        map("functionCall", map("name", "a", "args", map())),
                        map("functionCall", map("name", "b", "args", map())))),
                map("role", "user", "parts", list(
                        map("functionResponse", map("name", "a", "response", map())),
                        map("functionResponse", map("name", "b", "response", map())))));
        AntigravityThinkingRecovery.ConversationState state =
                (AntigravityThinkingRecovery.ConversationState) recovery.analyzeConversationState(contents);
        assertTrue(state.inToolLoop);
    }

    @Test
    void analyze_notInToolLoop_whenLastMessageIsRealUser() {
        List<Object> contents = list(
                userMsg("task"), modelWithToolCall("t"), toolResultMsg("t"), modelMsg("done"), userMsg("thanks"));
        AntigravityThinkingRecovery.ConversationState state =
                (AntigravityThinkingRecovery.ConversationState) recovery.analyzeConversationState(contents);
        assertFalse(state.inToolLoop);
    }

    @Test
    void analyze_tracksTurnStart_acrossMultiStepToolLoop() {
        List<Object> contents = list(
                userMsg("first real user"),
                modelWithThinkingAndToolCall("step1"),
                toolResultMsg("step1"),
                modelWithToolCall("step2"),
                toolResultMsg("step2"));
        AntigravityThinkingRecovery.ConversationState state =
                (AntigravityThinkingRecovery.ConversationState) recovery.analyzeConversationState(contents);
        assertEquals(1, state.turnStartIdx);
        assertTrue(state.turnHasThinking);
        assertTrue(state.inToolLoop);
    }

    @Test
    void analyze_turnNotHavingThinking_whenFirstModelMsgHasNoThinking() {
        List<Object> contents = list(userMsg("go"), modelWithToolCall("t1"), toolResultMsg("t1"));
        AntigravityThinkingRecovery.ConversationState state =
                (AntigravityThinkingRecovery.ConversationState) recovery.analyzeConversationState(contents);
        assertFalse(state.turnHasThinking);
        assertTrue(state.inToolLoop);
    }

    // ---- needsThinkingRecovery ----------------------------------------------------------------------

    private static AntigravityThinkingRecovery.ConversationState state(boolean inToolLoop, boolean turnHasThinking) {
        AntigravityThinkingRecovery.ConversationState s = new AntigravityThinkingRecovery.ConversationState();
        s.inToolLoop = inToolLoop;
        s.turnHasThinking = turnHasThinking;
        return s;
    }

    @Test
    void needsRecovery_falseWhenNotInToolLoop() {
        assertFalse(recovery.needsThinkingRecovery(state(false, false)));
    }

    @Test
    void needsRecovery_falseWhenInToolLoopButTurnHadThinking() {
        assertFalse(recovery.needsThinkingRecovery(state(true, true)));
    }

    @Test
    void needsRecovery_trueWhenInToolLoopWithoutThinking() {
        assertTrue(recovery.needsThinkingRecovery(state(true, false)));
    }

    // ---- closeToolLoopForThinking -------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    void close_appendsSyntheticModelAndUserMessages() {
        List<Object> contents = list(userMsg("go"), modelWithToolCall("search"), toolResultMsg("search"));
        List<Object> result = recovery.closeToolLoopForThinking(contents);
        assertEquals(5, result.size());
        assertEquals("model", ((Map<String, Object>) result.get(3)).get("role"));
        assertEquals("user", ((Map<String, Object>) result.get(4)).get("role"));
        List<Object> lastParts = (List<Object>) ((Map<String, Object>) result.get(4)).get("parts");
        assertEquals("[Continue]", ((Map<String, Object>) lastParts.get(0)).get("text"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void close_stripsThinkingBlocksFromPriorMessages() {
        List<Object> contents = list(userMsg("hello"), modelWithThinking("response"), toolResultMsg("t"));
        List<Object> result = recovery.closeToolLoopForThinking(contents);
        for (Object msgObj : result) {
            Map<String, Object> msg = (Map<String, Object>) msgObj;
            if (!"model".equals(msg.get("role"))) continue;
            for (Object partObj : (List<Object>) msg.get("parts")) {
                assertFalse(Boolean.TRUE.equals(((Map<String, Object>) partObj).get("thought")));
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void close_usesSingularMessage_forSingleToolResult() {
        List<Object> contents = list(userMsg("go"), modelWithToolCall("t"), toolResultMsg("t"));
        List<Object> result = recovery.closeToolLoopForThinking(contents);
        Map<String, Object> syntheticModel = (Map<String, Object>) result.get(result.size() - 2);
        List<Object> parts = (List<Object>) syntheticModel.get("parts");
        assertEquals("[Tool execution completed.]", ((Map<String, Object>) parts.get(0)).get("text"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void close_usesPluralMessage_forMultipleToolResults() {
        List<Object> contents = list(
                userMsg("go"),
                map("role", "model", "parts", list(
                        map("functionCall", map("name", "a", "args", map())),
                        map("functionCall", map("name", "b", "args", map())))),
                map("role", "user", "parts", list(
                        map("functionResponse", map("name", "a", "response", map())),
                        map("functionResponse", map("name", "b", "response", map())))));
        List<Object> result = recovery.closeToolLoopForThinking(contents);
        Map<String, Object> syntheticModel = (Map<String, Object>) result.get(result.size() - 2);
        List<Object> parts = (List<Object>) syntheticModel.get("parts");
        assertEquals("[2 tool executions completed.]", ((Map<String, Object>) parts.get(0)).get("text"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void close_usesFallbackMessage_whenNoToolResultsPresent() {
        List<Object> contents = list(userMsg("go"), modelMsg("working..."));
        List<Object> result = recovery.closeToolLoopForThinking(contents);
        Map<String, Object> syntheticModel = (Map<String, Object>) result.get(result.size() - 2);
        List<Object> parts = (List<Object>) syntheticModel.get("parts");
        assertEquals("[Processing previous context.]", ((Map<String, Object>) parts.get(0)).get("text"));
    }

    @Test
    void close_doesNotMutateOriginalContentsArray() {
        List<Object> contents = list(userMsg("go"), modelWithToolCall("t"), toolResultMsg("t"));
        String before = new TestJsonCodec().stringify(contents);
        recovery.closeToolLoopForThinking(contents);
        String after = new TestJsonCodec().stringify(contents);
        assertEquals(before, after);
    }

    // ---- looksLikeCompactedThinkingTurn ---------------------------------------------------------

    @Test
    void compacted_falseForNull() {
        assertFalse(recovery.looksLikeCompactedThinkingTurn(null));
    }

    @Test
    void compacted_falseForNoParts() {
        assertFalse(recovery.looksLikeCompactedThinkingTurn(map("role", "model", "parts", list())));
    }

    @Test
    void compacted_falseWithoutFunctionCalls() {
        assertFalse(recovery.looksLikeCompactedThinkingTurn(modelMsg("just text")));
    }

    @Test
    void compacted_falseWhenThinkingAlongsideFunctionCall() {
        Map<String, Object> msg = map("role", "model", "parts", list(
                map("thought", true, "text", "thinking"),
                map("functionCall", map("name", "t", "args", map()))));
        assertFalse(recovery.looksLikeCompactedThinkingTurn(msg));
    }

    @Test
    void compacted_falseWhenTextPrecedesFunctionCall() {
        Map<String, Object> msg = map("role", "model", "parts", list(
                map("text", "I will now call the tool."),
                map("functionCall", map("name", "t", "args", map()))));
        assertFalse(recovery.looksLikeCompactedThinkingTurn(msg));
    }

    @Test
    void compacted_trueForBareFunctionCall_withNoPrecedingText() {
        assertTrue(recovery.looksLikeCompactedThinkingTurn(modelWithToolCall("search")));
    }

    // ---- detectErrorType (recovery.test.ts) ---------------------------------------------------------

    @Test
    void detectError_toolUseWithoutToolResult() {
        assertEquals("tool_result_missing", AntigravityResponseParse.detectErrorType(
                "messages.105: `tool_use` ids were found without `tool_result` blocks immediately after: tool-call-59"));
    }

    @Test
    void detectError_toolUseToolResultMismatch() {
        assertEquals("tool_result_missing", AntigravityResponseParse.detectErrorType(
                "Each `tool_use` block must have a corresponding `tool_result` block in the next message."));
    }

    @Test
    void detectError_toolUseWithoutMatchingToolResult() {
        assertEquals("tool_result_missing", AntigravityResponseParse.detectErrorType("tool_use without matching tool_result"));
    }

    @Test
    void detectError_thinkingFirstBlock() {
        assertEquals("thinking_block_order", AntigravityResponseParse.detectErrorType("thinking must be the first block in the message"));
    }

    @Test
    void detectError_thinkingMustStartWith() {
        assertEquals("thinking_block_order", AntigravityResponseParse.detectErrorType("Response must start with thinking block"));
    }

    @Test
    void detectError_thinkingPreceeding() {
        assertEquals("thinking_block_order", AntigravityResponseParse.detectErrorType("thinking block preceeding tool use is required"));
    }

    @Test
    void detectError_thinkingExpectedFound() {
        assertEquals("thinking_block_order", AntigravityResponseParse.detectErrorType("Expected thinking block but found text"));
    }

    @Test
    void detectError_thinkingDisabled() {
        assertEquals("thinking_disabled_violation", AntigravityResponseParse.detectErrorType(
                "thinking is disabled for this model and cannot contain thinking blocks"));
    }

    @Test
    void detectError_nullForPromptTooLong() {
        assertNull(AntigravityResponseParse.detectErrorType("Prompt is too long"));
    }

    @Test
    void detectError_nullForContextLengthExceeded() {
        assertNull(AntigravityResponseParse.detectErrorType("context length exceeded"));
    }

    @Test
    void detectError_nullForGenericErrors() {
        assertNull(AntigravityResponseParse.detectErrorType("Something went wrong"));
        assertNull(AntigravityResponseParse.detectErrorType("Unknown error"));
        assertNull(AntigravityResponseParse.detectErrorType(null));
    }

    @Test
    void detectError_nullForRateLimit() {
        assertNull(AntigravityResponseParse.detectErrorType("Rate limit exceeded. Retry after 5s"));
    }

    @Test
    void detectError_nullForDebugExpectedFoundMetadata() {
        assertNull(AntigravityResponseParse.detectErrorType(
                "Request contains an invalid argument. [Debug Info] Requested Model: antigravity-claude-opus-4-6-thinking Tool Debug Summary: expected=1 found=0"));
    }

    @Test
    void detectError_promptTooLongPatterns() {
        assertNull(AntigravityResponseParse.detectErrorType("Prompt is too long"));
        assertNull(AntigravityResponseParse.detectErrorType("prompt is too long for this model"));
        assertNull(AntigravityResponseParse.detectErrorType("The prompt is too long"));
    }

    @Test
    void detectError_contextLengthPatterns() {
        assertNull(AntigravityResponseParse.detectErrorType("context length exceeded"));
        assertNull(AntigravityResponseParse.detectErrorType("context_length_exceeded"));
        assertNull(AntigravityResponseParse.detectErrorType("maximum context length"));
        assertNull(AntigravityResponseParse.detectErrorType("exceeds the maximum context window"));
    }

    @Test
    void detectError_toolPairingPatterns() {
        assertEquals("tool_result_missing", AntigravityResponseParse.detectErrorType(
                "tool_use ids were found without tool_result blocks immediately after"));
        assertEquals("tool_result_missing", AntigravityResponseParse.detectErrorType(
                "Each tool_use block must have a corresponding tool_result"));
        assertEquals("tool_result_missing", AntigravityResponseParse.detectErrorType(
                "tool_use without matching tool_result"));
    }
}
