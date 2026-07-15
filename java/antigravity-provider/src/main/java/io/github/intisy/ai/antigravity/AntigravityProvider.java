package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.routing.HandlerCtx;
import io.github.intisy.ai.shared.routing.Provider;
import io.github.intisy.ai.shared.spi.http.HttpRequest;
import io.github.intisy.ai.shared.spi.http.HttpResponse;

import java.util.HashMap;
import java.util.List;

/**
 * Phase 1 skeleton of antigravity-auth's JVM {@link Provider}: discoverable + routable (proves
 * {@code ServiceLoader} discovery and model-map routing work end to end), but {@link #handle}
 * does NOT talk to any upstream yet -- that is Phase 2+ (self-assembled {@code AccountManager} +
 * {@code AntigravityHandleOrchestrator} wiring, see
 * {@code docs/superpowers/plans/2026-07-15-jvm-antigravity-claude-providers.md}). Until then this
 * returns a well-formed, canned Anthropic-messages response so callers get a valid body shape to
 * build against.
 *
 * <p>Shape discipline mirrors {@code StubProvider}/{@code EchoProvider}: no gson, no reflection,
 * no {@code java.net}/{@code java.nio} -- hand-rolled JSON string building only, so the jar stays
 * thin ({@code compileOnly project(":routing")}, nothing from {@code :jvm}) and this class stays
 * TeaVM-eligible alongside the rest of {@code :antigravity-provider}.
 *
 * <p>Registered via {@code META-INF/services/io.github.intisy.ai.shared.routing.Provider} so a
 * JVM host discovers it purely through {@code ServiceLoader} (see
 * {@code io.github.intisy.ai.jvm.provider.ProviderRegistry} in ai-java).
 */
public final class AntigravityProvider implements Provider {

    /** The provider id this instance serves; matches the {@code provider} field in a model-map assignment. */
    public static final String ID = "antigravity";

    private static final String SKELETON_TEXT =
            "Antigravity JVM provider skeleton (Phase 1) - real upstream serving is wired in a later phase";

    private static final String DEFAULT_MODEL_FALLBACK = "antigravity-default";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public HttpResponse handle(HttpRequest request, HandlerCtx ctx) {
        String servedModel = resolveModel(ctx);

        HttpResponse response = new HttpResponse();
        response.status = 200;
        response.headers = new HashMap<>();
        response.headers.put("content-type", "application/json");
        response.body = anthropicMessageBody(servedModel);
        return response;
    }

    // ctx.model (the tier-resolved assignment) wins when present -- mirrors StubProvider/
    // EchoProvider precedence. Absent that, fall back to the first entry of the already-ported
    // AntigravityCatalog's Gemini CLI model list (cheap, self-contained, no network) rather than a
    // bare literal, so the skeleton's default model id is at least a real antigravity-known one.
    private static String resolveModel(HandlerCtx ctx) {
        if (ctx != null && ctx.model != null && !ctx.model.isEmpty()) {
            return ctx.model;
        }
        List<AntigravityCatalog.GeminiCliModel> models = AntigravityCatalog.GEMINI_CLI_MODELS;
        if (!models.isEmpty() && models.get(0).id != null) {
            return models.get(0).id;
        }
        return DEFAULT_MODEL_FALLBACK;
    }

    // { id, type, role, model, content:[{type,text}], stop_reason, stop_sequence,
    //   usage:{input_tokens, output_tokens} } -- the non-streaming Anthropic messages shape.
    private static String anthropicMessageBody(String model) {
        String text = SKELETON_TEXT + " (served by " + model + ")";
        return "{"
                + "\"id\":\"msg_antigravity_skeleton_0001\","
                + "\"type\":\"message\","
                + "\"role\":\"assistant\","
                + "\"model\":" + quote(model) + ","
                + "\"content\":[{\"type\":\"text\",\"text\":" + quote(text) + "}],"
                + "\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":18}"
                + "}";
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
