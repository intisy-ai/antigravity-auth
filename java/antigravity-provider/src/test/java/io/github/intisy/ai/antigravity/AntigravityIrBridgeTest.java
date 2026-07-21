package io.github.intisy.ai.antigravity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link AntigravityIrBridge#supportsThinking}, the model-name predicate antigravity's
 * thinking-budget resolution keys off ("thinking" or "gemini-3", case-insensitive).
 */
class AntigravityIrBridgeTest {

    @Test
    void supportsThinking_parity() {
        assertTrue(AntigravityIrBridge.supportsThinking("claude-thinking"));
        assertTrue(AntigravityIrBridge.supportsThinking("Claude-Thinking"));
        assertTrue(AntigravityIrBridge.supportsThinking("gemini-3-pro"));
        assertTrue(AntigravityIrBridge.supportsThinking("GEMINI-3"));
        assertFalse(AntigravityIrBridge.supportsThinking("claude-sonnet"));
        assertFalse(AntigravityIrBridge.supportsThinking("gpt-oss"));
        assertFalse(AntigravityIrBridge.supportsThinking(""));
        assertFalse(AntigravityIrBridge.supportsThinking(null));
    }
}
