package io.github.intisy.ai.antigravity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pure key/seed helpers: {@code buildSignatureSessionKey}, {@code hashConversationSeed},
 * {@code extractTextFromContent}, {@code extractConversationSeedFromMessages},
 * {@code extractConversationSeedFromContents}, {@code resolveConversationKey},
 * {@code resolveConversationKeyFromRequests} and {@code resolveProjectKey}.
 *
 * <h2>Injected edge: Hasher</h2>
 * {@code hashConversationSeed} is a real <b>sha256</b>, not the DJB2 {@code hashString}:
 * {@code crypto.createHash("sha256").update(seed,"utf8").digest("hex").slice(0,16)}. The sha256 is a
 * genuine crypto edge, so it is injected as the {@link Hasher} SPI (full hex out); this class only owns
 * the {@code .slice(0,16)} truncation DECISION.
 *
 * <p>JS-semantics honored: {@code buildSignatureSessionKey}'s {@code modelKey} lowercases the model
 * WITHOUT trimming (only the empty/blank check trims); {@code projectPart}/{@code conversationPart}
 * ARE trimmed. {@code resolveConversationKey}'s systemInstruction seed source is
 * {@code si?.parts ?? si ?? system ?? system_instruction} (optional-chain: a non-object {@code si}
 * yields {@code undefined} {@code parts}). {@code [...].filter(Boolean).join("|")} drops empty strings.
 * TeaVM-transpilable.
 */
public final class AntigravityRequestKeys {

    /** sha256-hex SPI for {@code hashConversationSeed} ({@code crypto.createHash("sha256")...digest("hex")}). */
    public interface Hasher {
        String sha256Hex(String input);
    }

    private AntigravityRequestKeys() {
    }

    // ---- buildSignatureSessionKey ---------------------------------------------

    public static String buildSignatureSessionKey(String sessionId, String model, String conversationKey, String projectKey) {
        String modelKey = (model != null && !model.trim().isEmpty()) ? model.toLowerCase() : "unknown";
        String projectPart = (projectKey != null && !projectKey.trim().isEmpty()) ? projectKey.trim() : "default";
        String conversationPart = (conversationKey != null && !conversationKey.trim().isEmpty()) ? conversationKey.trim() : "default";
        return sessionId + ":" + modelKey + ":" + projectPart + ":" + conversationPart;
    }

    // ---- hashConversationSeed ----------------------------------------------

    /** sha256 hex of {@code seed}, truncated to 16 chars (the {@code .slice(0,16)}). */
    public static String hashConversationSeed(Hasher hasher, String seed) {
        String hex = hasher.sha256Hex(seed);
        return hex.length() > 16 ? hex.substring(0, 16) : hex;
    }

    // ---- extractTextFromContent --------------------------------------------

    public static String extractTextFromContent(Object content) {
        if (content instanceof String) {
            return (String) content;
        }
        if (!(content instanceof List)) {
            return "";
        }
        for (Object block : JsCoercion.asList(content)) {
            if (!(block instanceof Map)) {
                continue;
            }
            Map<String, Object> m = JsCoercion.asMap(block);
            Object text = m.get("text");
            if (text instanceof String) {
                return (String) text;
            }
            if (JsCoercion.isTruthy(text) && text instanceof Map) {
                Object nested = JsCoercion.asMap(text).get("text");
                if (nested instanceof String) {
                    return (String) nested;
                }
            }
        }
        return "";
    }

    // ---- extractConversationSeedFromMessages -------------------------------

    static String extractConversationSeedFromMessages(List<Object> messages) {
        Map<String, Object> system = findByRole(messages, "system");
        List<Map<String, Object>> users = filterByRole(messages, "user");
        Map<String, Object> firstUser = users.isEmpty() ? null : users.get(0);
        Map<String, Object> lastUser = users.isEmpty() ? null : users.get(users.size() - 1);
        String systemText = system != null ? extractTextFromContent(system.get("content")) : "";
        String userText = firstUser != null ? extractTextFromContent(firstUser.get("content")) : "";
        String fallbackUserText = (userText.isEmpty() && lastUser != null) ? extractTextFromContent(lastUser.get("content")) : "";
        return joinTruthy(systemText, userText.isEmpty() ? fallbackUserText : userText);
    }

    // ---- extractConversationSeedFromContents -------------------------------

    static String extractConversationSeedFromContents(List<Object> contents) {
        List<Map<String, Object>> users = filterByRole(contents, "user");
        Map<String, Object> firstUser = users.isEmpty() ? null : users.get(0);
        Map<String, Object> lastUser = users.isEmpty() ? null : users.get(users.size() - 1);
        String primaryUser = (firstUser != null && firstUser.get("parts") instanceof List)
                ? extractTextFromContent(firstUser.get("parts")) : "";
        if (!primaryUser.isEmpty()) {
            return primaryUser;
        }
        if (lastUser != null && lastUser.get("parts") instanceof List) {
            return extractTextFromContent(lastUser.get("parts"));
        }
        return "";
    }

    // ---- resolveConversationKey -------------------------------------------

    public static String resolveConversationKey(Hasher hasher, Map<String, Object> payload) {
        Object[] candidates = {
                payload.get("conversationId"),
                payload.get("conversation_id"),
                payload.get("thread_id"),
                payload.get("threadId"),
                payload.get("chat_id"),
                payload.get("chatId"),
                payload.get("sessionId"),
                payload.get("session_id"),
                metaField(payload, "conversation_id"),
                metaField(payload, "conversationId"),
                metaField(payload, "thread_id"),
                metaField(payload, "threadId"),
        };
        for (Object candidate : candidates) {
            if (candidate instanceof String && !((String) candidate).trim().isEmpty()) {
                return ((String) candidate).trim();
            }
        }

        Object si = payload.get("systemInstruction");
        Object siParts = (si instanceof Map) ? JsCoercion.asMap(si).get("parts") : null;
        Object seedInput = JsCoercion.nullish(siParts,
                JsCoercion.nullish(si, JsCoercion.nullish(payload.get("system"), payload.get("system_instruction"))));
        String systemSeed = extractTextFromContent(seedInput);

        String messageSeed;
        if (payload.get("messages") instanceof List) {
            messageSeed = extractConversationSeedFromMessages(JsCoercion.asList(payload.get("messages")));
        } else if (payload.get("contents") instanceof List) {
            messageSeed = extractConversationSeedFromContents(JsCoercion.asList(payload.get("contents")));
        } else {
            messageSeed = "";
        }

        String seed = joinTruthy(systemSeed, messageSeed);
        if (seed.isEmpty()) {
            return null;
        }
        return "seed-" + hashConversationSeed(hasher, seed);
    }

    // ---- resolveConversationKeyFromRequests --------------------------------

    public static String resolveConversationKeyFromRequests(Hasher hasher, List<Map<String, Object>> requestObjects) {
        for (Map<String, Object> req : requestObjects) {
            String key = resolveConversationKey(hasher, req);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    // ---- resolveProjectKey ------------------------------------------------

    public static String resolveProjectKey(Object candidate, Object fallback) {
        if (candidate instanceof String && !((String) candidate).trim().isEmpty()) {
            return ((String) candidate).trim();
        }
        if (fallback instanceof String && !((String) fallback).trim().isEmpty()) {
            return ((String) fallback).trim();
        }
        return null;
    }

    // ---- helpers --------------------------------------------------------------------------------

    // `metadata?.conversation_id` optional chain: metadata must be an object.
    private static Object metaField(Map<String, Object> payload, String key) {
        Object metadata = payload.get("metadata");
        return metadata instanceof Map ? JsCoercion.asMap(metadata).get(key) : null;
    }

    private static Map<String, Object> findByRole(List<Object> items, String role) {
        for (Object item : items) {
            if (item instanceof Map && role.equals(JsCoercion.asMap(item).get("role"))) {
                return JsCoercion.asMap(item);
            }
        }
        return null;
    }

    private static List<Map<String, Object>> filterByRole(List<Object> items, String role) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map && role.equals(JsCoercion.asMap(item).get("role"))) {
                out.add(JsCoercion.asMap(item));
            }
        }
        return out;
    }

    // `[a, b].filter(Boolean).join("|")`: drops empty strings.
    private static String joinTruthy(String a, String b) {
        StringBuilder sb = new StringBuilder();
        if (!a.isEmpty()) sb.append(a);
        if (!b.isEmpty()) {
            if (sb.length() > 0) sb.append("|");
            sb.append(b);
        }
        return sb.toString();
    }
}
