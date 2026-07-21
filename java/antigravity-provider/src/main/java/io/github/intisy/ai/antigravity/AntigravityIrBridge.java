package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrThinking;
import io.github.intisy.ai.ir.translators.gemini.GeminiTranslator;
import io.github.intisy.ai.shared.spi.JsonCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridges an antigravity request already in canonical IR to the Gemini {@code generateContent}
 * body {@link AntigravityRequestPrep} expects. The live {@code handleIr} path holds an {@link
 * IrRequest} decoded by the front-door, so this applies only antigravity's own effort/tier-&gt;budget
 * VALUE resolution ({@link #resolveThinkingBudget}, on the neutral {@code IrRequest.thinking}) and
 * then the neutral IR-&gt;Gemini encode ({@link #encodeIrToGemini}, {@code
 * GeminiTranslator.encodeRequest}). The resulting Gemini body still passes through {@link
 * AntigravityRequestPrep}'s full pipeline (tool-hardening, schema cleaning, the REAL thinking-tier
 * resolution via {@link AntigravityThinkingConfig}, signature caching, ...) exactly as a
 * native-Gemini request would; this bridge only produces the ROUGH Gemini shape, not the final
 * wire body.
 *
 * <h2>Thinking budget: value vs format</h2>
 * The {@code thinking.budget_tokens} / {@code output_config.effort} effort-&gt;budget TABLE and the
 * disabled/precedence rules are antigravity-specific business logic (not part of any vendor's
 * neutral wire format), so they stay here, operating on {@code IrRequest.thinking}, while the
 * actual key names/shape of {@code generationConfig.thinkingConfig} are produced by {@code
 * GeminiTranslator} itself.
 */
public final class AntigravityIrBridge {

    // effort -> Gemini thinkingBudget (xhigh/max clamp to high=32768). This table is antigravity's
    // own tier->budget VALUE policy, not format translation.
    private static final Map<String, Integer> EFFORT_BUDGET = new LinkedHashMap<>();

    static {
        EFFORT_BUDGET.put("low", 8192);
        EFFORT_BUDGET.put("medium", 16384);
        EFFORT_BUDGET.put("high", 32768);
        EFFORT_BUDGET.put("xhigh", 32768);
        EFFORT_BUDGET.put("max", 32768);
    }

    private static final String EXT_OUTPUT_CONFIG = "output_config";

    private AntigravityIrBridge() {
    }

    // ---- supportsThinking ------------------------------------------------------------------------

    /** {@code String(model || "").toLowerCase()} includes "thinking" or "gemini-3". */
    public static boolean supportsThinking(Object model) {
        String lower = (JsCoercion.isTruthy(model) ? jsStr(model) : "").toLowerCase();
        return lower.contains("thinking") || lower.contains("gemini-3");
    }

    // ---- IR -> Gemini encode ----------------------------------------------------------------------

    /** Encodes an {@link IrRequest} to a Gemini {@code generateContent} body via core-ir's {@link GeminiTranslator}. */
    public static String encodeIrToGemini(JsonCodec routingJson, IrRequest ir) {
        io.github.intisy.ai.ir.spi.JsonCodec irJson = new IrJsonCodecAdapter(routingJson);
        return new GeminiTranslator(irJson).encodeRequest(ir);
    }

    // ---- thinking budget resolution --------------------------------------------------------------

    /**
     * Applies antigravity's effort/explicit-budget precedence onto {@code ir.thinking}: an explicit
     * {@code thinking.budget_tokens} wins; else {@code output_config.effort} looked up in {@link
     * #EFFORT_BUDGET}; {@code thinking.type === "disabled"} always suppresses; the result only
     * survives when {@link #supportsThinking(Object)} is true for {@code model}, otherwise {@code
     * ir.thinking} is cleared so {@code GeminiTranslator} emits a {@code thinkingConfig} only when
     * this resolution produces one.
     *
     * <p>{@code output_config} has no neutral IR field (it is not a real Anthropic Messages API
     * key), so {@code AnthropicRequestCodec} stashes it verbatim in {@code IrRequest#extensions}
     * exactly like any other unrecognized top-level field, which is where this reads it from.
     */
    public static void resolveThinkingBudget(IrRequest ir, Object model) {
        boolean thinkingOff = ir.thinking != null && !ir.thinking.enabled;
        Integer budget = ir.thinking != null ? ir.thinking.budgetTokens : null;

        Object outputConfig = ir.extensions != null ? ir.extensions.get(EXT_OUTPUT_CONFIG) : null;
        Object effort = outputConfig instanceof Map ? ((Map<?, ?>) outputConfig).get("effort") : null;
        if (budget == null && JsCoercion.isTruthy(effort) && EFFORT_BUDGET.containsKey(jsStr(effort))) {
            budget = EFFORT_BUDGET.get(jsStr(effort));
        }

        if (!thinkingOff && budget != null && supportsThinking(model)) {
            IrThinking resolved = new IrThinking();
            resolved.enabled = true;
            resolved.budgetTokens = budget;
            ir.thinking = resolved;
        } else {
            ir.thinking = null;
        }
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
