package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.Random;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic seams for the request-prep tests. The {@link Recovery} double implements the
 * {@link AntigravityRequestPrep.ThinkingRecovery} seam (analyze/needs/close) so the {@code prepare}
 * spine's recovery wiring can be exercised end-to-end.
 */
final class RequestTestDoubles {
    private RequestTestDoubles() {
    }

    // Real sha256 hex (MessageDigest is JVM-only test code, never transpiled).
    static AntigravityRequestKeys.Hasher sha256() {
        return input -> {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] bytes = md.digest(input.getBytes("UTF-8"));
                StringBuilder sb = new StringBuilder();
                for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    static String uuidAt(long n) {
        return "00000000-0000-4000-8000-" + String.format("%012x", n);
    }

    /** Monotonic UUID counter standing in for {@code crypto.randomUUID}, deterministic per case. */
    static AntigravityRequestPrep.IdGenerator counterIds() {
        return new AntigravityRequestPrep.IdGenerator() {
            private long n = 0;

            @Override
            public String randomUuid() {
                n += 1;
                return uuidAt(n);
            }
        };
    }

    static Random fixedRandom(double v) {
        return () -> v;
    }

    /** getCachedSignature(sessionId, text) backed by a Map (key = sessionId + "\u0000" + text). */
    static final class MapLookup implements AntigravityThinkingBlocks.CachedSignatureLookup {
        final Map<String, String> map = new LinkedHashMap<>();

        void put(String sessionId, String text, String signature) {
            map.put(sessionId + "\u0000" + text, signature);
        }

        @Override
        public String get(String sessionId, String text) {
            return map.get(sessionId + "\u0000" + text);
        }
    }

    /** defaultSignatureStore stand-in ({@code {text,signature}} entries); records deletes. */
    static final class MapStore implements AntigravityRequestSignatures.SignatureStore {
        final Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        final List<String> deleted = new ArrayList<>();

        void set(String key, String text, String signature) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("text", text);
            entry.put("signature", signature);
            map.put(key, entry);
        }

        @Override
        public Map<String, Object> get(String key) {
            return map.get(key);
        }

        @Override
        public boolean has(String key) {
            return map.containsKey(key);
        }

        @Override
        public void delete(String key) {
            deleted.add(key);
            map.remove(key);
        }
    }

    // ---- ThinkingRecovery seam implementation (test-only) --------------------

    static final class Recovery implements AntigravityRequestPrep.ThinkingRecovery {
        @Override
        public Object analyzeConversationState(List<Object> contents) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("inToolLoop", false);
            state.put("turnStartIdx", -1);
            state.put("turnHasThinking", false);
            state.put("lastModelIdx", -1);
            state.put("lastModelHasThinking", false);
            state.put("lastModelHasToolCalls", false);
            if (contents == null || contents.isEmpty()) return state;

            int lastRealUserIdx = -1;
            for (int i = 0; i < contents.size(); i++) {
                Object msg = contents.get(i);
                if (role(msg).equals("user") && !isToolResultMessage(msg)) lastRealUserIdx = i;
            }

            int turnStartIdx = -1;
            boolean turnHasThinking = false;
            int lastModelIdx = -1;
            boolean lastModelHasThinking = false;
            boolean lastModelHasToolCalls = false;
            for (int i = 0; i < contents.size(); i++) {
                Object msg = contents.get(i);
                String role = role(msg);
                if (role.equals("model") || role.equals("assistant")) {
                    boolean hasThinking = messageHasThinking(msg);
                    boolean hasToolCalls = messageHasToolCalls(msg);
                    if (i > lastRealUserIdx && turnStartIdx == -1) {
                        turnStartIdx = i;
                        turnHasThinking = hasThinking;
                    }
                    lastModelIdx = i;
                    lastModelHasToolCalls = hasToolCalls;
                    lastModelHasThinking = hasThinking;
                }
            }
            state.put("turnStartIdx", turnStartIdx);
            state.put("turnHasThinking", turnHasThinking);
            state.put("lastModelIdx", lastModelIdx);
            state.put("lastModelHasThinking", lastModelHasThinking);
            state.put("lastModelHasToolCalls", lastModelHasToolCalls);

            Object lastMsg = contents.get(contents.size() - 1);
            if (role(lastMsg).equals("user") && isToolResultMessage(lastMsg)) {
                state.put("inToolLoop", true);
            }
            return state;
        }

        @Override
        public boolean needsThinkingRecovery(Object stateObj) {
            Map<String, Object> state = (Map<String, Object>) stateObj;
            return Boolean.TRUE.equals(state.get("inToolLoop")) && !Boolean.TRUE.equals(state.get("turnHasThinking"));
        }

        @Override
        public List<Object> closeToolLoopForThinking(List<Object> contents) {
            List<Object> stripped = stripAllThinkingBlocks(contents);
            int toolResultCount = countTrailingToolResults(stripped);
            String syntheticModelContent;
            if (toolResultCount == 0) syntheticModelContent = "[Processing previous context.]";
            else if (toolResultCount == 1) syntheticModelContent = "[Tool execution completed.]";
            else syntheticModelContent = "[" + toolResultCount + " tool executions completed.]";

            List<Object> out = new ArrayList<>(stripped);
            out.add(role_parts("model", syntheticModelContent));
            out.add(role_parts("user", "[Continue]"));
            return out;
        }

        private static Map<String, Object> role_parts(String role, String text) {
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("text", text);
            List<Object> parts = new ArrayList<>();
            parts.add(part);
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("role", role);
            msg.put("parts", parts);
            return msg;
        }

        private static List<Object> stripAllThinkingBlocks(List<Object> contents) {
            List<Object> out = new ArrayList<>();
            for (Object content : contents) {
                if (!(content instanceof Map)) {
                    out.add(content);
                    continue;
                }
                Map<String, Object> c = (Map<String, Object>) content;
                if (c.get("parts") instanceof List) {
                    List<Object> filtered = new ArrayList<>();
                    for (Object p : (List<Object>) c.get("parts")) {
                        if (!isThinkingPart(p)) filtered.add(p);
                    }
                    if (filtered.isEmpty() && !((List<Object>) c.get("parts")).isEmpty()) {
                        out.add(content);
                    } else {
                        Map<String, Object> nc = new LinkedHashMap<>(c);
                        nc.put("parts", filtered);
                        out.add(nc);
                    }
                    continue;
                }
                if (c.get("content") instanceof List) {
                    List<Object> filtered = new ArrayList<>();
                    for (Object b : (List<Object>) c.get("content")) {
                        String t = b instanceof Map ? String.valueOf(((Map<String, Object>) b).get("type")) : "";
                        if (!"thinking".equals(t) && !"redacted_thinking".equals(t)) filtered.add(b);
                    }
                    if (filtered.isEmpty() && !((List<Object>) c.get("content")).isEmpty()) {
                        out.add(content);
                    } else {
                        Map<String, Object> nc = new LinkedHashMap<>(c);
                        nc.put("content", filtered);
                        out.add(nc);
                    }
                    continue;
                }
                out.add(content);
            }
            return out;
        }

        private static int countTrailingToolResults(List<Object> contents) {
            int count = 0;
            for (int i = contents.size() - 1; i >= 0; i--) {
                Object msg = contents.get(i);
                String role = role(msg);
                if (role.equals("user")) {
                    int fr = 0;
                    if (msg instanceof Map && ((Map<String, Object>) msg).get("parts") instanceof List) {
                        for (Object p : (List<Object>) ((Map<String, Object>) msg).get("parts")) {
                            if (isFunctionResponsePart(p)) fr++;
                        }
                    }
                    if (fr > 0) count += fr;
                    else break;
                } else if (role.equals("model") || role.equals("assistant")) {
                    break;
                }
            }
            return count;
        }

        private static boolean isThinkingPart(Object part) {
            if (!(part instanceof Map)) return false;
            Map<String, Object> p = (Map<String, Object>) part;
            return Boolean.TRUE.equals(p.get("thought")) || "thinking".equals(p.get("type")) || "redacted_thinking".equals(p.get("type"));
        }

        private static boolean isFunctionResponsePart(Object part) {
            return part instanceof Map && ((Map<String, Object>) part).containsKey("functionResponse");
        }

        private static boolean isFunctionCallPart(Object part) {
            return part instanceof Map && ((Map<String, Object>) part).containsKey("functionCall");
        }

        private static boolean isToolResultMessage(Object msg) {
            if (!(msg instanceof Map) || !"user".equals(((Map<String, Object>) msg).get("role"))) return false;
            Object parts = ((Map<String, Object>) msg).get("parts");
            if (!(parts instanceof List)) return false;
            for (Object p : (List<Object>) parts) {
                if (isFunctionResponsePart(p)) return true;
            }
            return false;
        }

        private static boolean messageHasThinking(Object msg) {
            if (!(msg instanceof Map)) return false;
            Map<String, Object> m = (Map<String, Object>) msg;
            if (m.get("parts") instanceof List) {
                for (Object p : (List<Object>) m.get("parts")) if (isThinkingPart(p)) return true;
                return false;
            }
            if (m.get("content") instanceof List) {
                for (Object b : (List<Object>) m.get("content")) {
                    String t = b instanceof Map ? String.valueOf(((Map<String, Object>) b).get("type")) : "";
                    if ("thinking".equals(t) || "redacted_thinking".equals(t)) return true;
                }
                return false;
            }
            return false;
        }

        private static boolean messageHasToolCalls(Object msg) {
            if (!(msg instanceof Map)) return false;
            Map<String, Object> m = (Map<String, Object>) msg;
            if (m.get("parts") instanceof List) {
                for (Object p : (List<Object>) m.get("parts")) if (isFunctionCallPart(p)) return true;
                return false;
            }
            if (m.get("content") instanceof List) {
                for (Object b : (List<Object>) m.get("content")) {
                    if (b instanceof Map && "tool_use".equals(((Map<String, Object>) b).get("type"))) return true;
                }
                return false;
            }
            return false;
        }

        private static String role(Object msg) {
            return msg instanceof Map ? String.valueOf(((Map<String, Object>) msg).get("role")) : "";
        }
    }
}
