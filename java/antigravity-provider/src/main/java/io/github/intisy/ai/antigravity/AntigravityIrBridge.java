package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.ir.IrRequest;
import io.github.intisy.ai.ir.IrThinking;
import io.github.intisy.ai.ir.translators.anthropic.AnthropicTranslator;
import io.github.intisy.ai.ir.translators.gemini.GeminiTranslator;
import io.github.intisy.ai.shared.spi.JsonCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SP-2 successor to the deleted {@code AntigravityFormatBridge} ({@code src/plugin/anthropic-
 * bridge.ts}'s {@code anthropicToGemini}/{@code supportsThinking}/{@code isAnthropicMessages}):
 * bridges antigravity's inbound Anthropic Messages request to the Gemini {@code generateContent}
 * body it hands to {@link AntigravityRequestPrep}, now via core-ir's canonical IR instead of a
 * bespoke hand-rolled JSON->JSON mapping.
 *
 * <p>Flow: {@code AnthropicTranslator.decodeRequest} (inbound Anthropic wire -&gt; {@link
 * IrRequest}) -&gt; {@link #resolveThinkingBudget} (antigravity's own effort/tier-&gt;budget VALUE
 * resolution, applied to the neutral {@code IrRequest.thinking} -- the FORMAT mapping from there
 * into Gemini's {@code generationConfig.thinkingConfig} is core-ir's) -&gt; {@code
 * GeminiTranslator.encodeRequest} (IR -&gt; Gemini body). The resulting Gemini body still passes
 * through {@link AntigravityRequestPrep}'s full pipeline (tool-hardening, schema cleaning, the
 * REAL thinking-tier resolution via {@link AntigravityThinkingConfig}, signature caching, ...)
 * exactly as a native-Gemini request would -- this bridge only produces the same ROUGH Gemini
 * shape the old {@code anthropicToGemini} did, not the final wire body.
 *
 * <h2>Thinking budget: value vs format</h2>
 * The old bridge read {@code thinking.budget_tokens} / {@code output_config.effort} directly off
 * the raw Anthropic JSON and wrote a Gemini {@code thinkingConfig.thinkingBudget} by hand. That
 * effort-&gt;budget TABLE and the disabled/precedence rules are antigravity-specific business
 * logic (not part of any vendor's neutral wire format), so they stay here, operating on {@code
 * IrRequest.thinking} -- while the actual key names/shape of {@code generationConfig.thinkingConfig}
 * are produced by {@code GeminiTranslator} itself.
 */
public final class AntigravityIrBridge {

    // anthropic-bridge.ts:74 -- effort -> Gemini thinkingBudget (xhigh/max clamp to high=32768).
    // KEPT: this table is antigravity's own tier->budget VALUE policy, not format translation.
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

    // ---- IR-based anthropicToGemini ---------------------------------------------------------------

    /**
     * Decodes an inbound Anthropic Messages body to the canonical IR via core-ir's {@link
     * AnthropicTranslator}. {@code routingJson} is the routing-SPI {@link JsonCodec} every call
     * site already carries (bridged to core-ir's own JsonCodec via {@link IrJsonCodecAdapter}).
     */
    public static IrRequest decodeAnthropicToIr(JsonCodec routingJson, String anthropicBodyJson) {
        io.github.intisy.ai.ir.spi.JsonCodec irJson = new IrJsonCodecAdapter(routingJson);
        String wire = JsCoercion.isTruthy(anthropicBodyJson) ? anthropicBodyJson : "{}";
        return new AnthropicTranslator(irJson).decodeRequest(wire);
    }

    /** Encodes an {@link IrRequest} to a Gemini {@code generateContent} body via core-ir's {@link GeminiTranslator}. */
    public static String encodeIrToGemini(JsonCodec routingJson, IrRequest ir) {
        io.github.intisy.ai.ir.spi.JsonCodec irJson = new IrJsonCodecAdapter(routingJson);
        return new GeminiTranslator(irJson).encodeRequest(ir);
    }

    /**
     * Full replacement for the deleted {@code AntigravityFormatBridge.anthropicToGemini}: decode
     * -&gt; resolve the antigravity-specific thinking budget -&gt; encode. Returns the Gemini body
     * as a JSON string (the old function returned a {@code Map}; every call site immediately
     * stringified it anyway).
     */
    public static String anthropicToGemini(JsonCodec routingJson, String anthropicBodyJson, String model) {
        IrRequest ir = decodeAnthropicToIr(routingJson, anthropicBodyJson);
        resolveThinkingBudget(ir, model);
        return encodeIrToGemini(routingJson, ir);
    }

    // ---- thinking budget resolution (anthropic-bridge.ts:74-90) -----------------------------------

    /**
     * Applies antigravity's effort/explicit-budget precedence onto {@code ir.thinking}, mirroring
     * the old bridge exactly: an explicit {@code thinking.budget_tokens} wins; else {@code
     * output_config.effort} looked up in {@link #EFFORT_BUDGET}; {@code thinking.type === "disabled"}
     * always suppresses; the result only survives when {@link #supportsThinking(Object)} is true
     * for {@code model} -- otherwise {@code ir.thinking} is cleared so {@code GeminiTranslator}
     * never emits a {@code thinkingConfig} the old bridge wouldn't have (it never round-tripped
     * {@code thinking} through untouched; a {@code thinkingConfig} appeared ONLY via this
     * resolution).
     *
     * <p>{@code output_config} has no neutral IR field (it is not a real Anthropic Messages API
     * key) -- {@code AnthropicRequestCodec} stashes it verbatim in {@code IrRequest#extensions}
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
