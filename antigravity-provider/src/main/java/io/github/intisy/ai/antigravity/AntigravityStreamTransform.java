package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.JsonCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pure streaming-transform halves: {@code hashString}, {@code createThoughtBuffer},
 * {@code transformStreamingPayload}, {@code deduplicateThinkingText}, {@code transformSseLine} and
 * {@code cacheThinkingSignaturesFromResponse}. The {@code createStreamingTransformer} TransformStream +
 * 45s {@code setTimeout} watchdog + encoder/decoder + synthetic-usage flush stays in TS.
 *
 * <h2>Injected seams</h2>
 * <ul>
 *   <li>{@link ThinkingPartsTransform}: the {@code transformThinkingParts} callback (real impl is
 *       {@link AntigravityThinkingBlocks#transformThinkingParts}).</li>
 *   <li>{@link AntigravityThinkingBlocks.ImageSink}: the {@code processImageData} branch in
 *       {@code deduplicateThinkingText}.</li>
 *   <li>{@link SignatureStore} + {@link CacheSignatureCallback}: the {@code signatureStore.set} write
 *       and the optional {@code onCacheSignature} callback in {@code cacheThinkingSignaturesFromResponse}.</li>
 *   <li>{@link InjectDebug}: the {@code onInjectDebug} callback (once, gated by {@link DebugState}).</li>
 *   <li>{@link JsonCodec}: all {@code JSON.parse}/{@code JSON.stringify}.</li>
 * </ul>
 *
 * <h2>Gotchas</h2>
 * <ul>
 *   <li><b>{@code >>> 0} unsigned hash</b>: {@link #hashString(String)} reproduces JS DJB2 where each
 *       {@code hash << 5} truncates its left operand via ECMAScript ToInt32 while the running {@code hash}
 *       accumulates as an IEEE-754 double, then {@code (hash >>> 0)} = ToUint32 &rarr; lowercase hex
 *       (no leading zeros).</li>
 *   <li><b>dedup delta</b>: {@code fullText.startsWith(sentText)} &rarr; emit only the suffix; the
 *       {@code displayedThinkingHashes} hash-set short-circuits a repeat to a DROP; candidates keyed by
 *       candidate index, content keyed by a running {@code thinkingIndex}; the emitted part is a COPY
 *       ({@code {...p, text, thinking}}), input never mutated.</li>
 *   <li><b>{@code transformStreamingPayload} vs {@code transformSseLine}</b>: the former only swaps
 *       {@code parsed.response} via {@code transformThinkingParts}; the latter additionally caches
 *       signatures + dedups + debug-injects.</li>
 * </ul>
 *
 * <p>Deviation (no valid-payload path): a non-{@link Map} part is treated as an empty object (no branch)
 * and a literal {@code null} part is dropped by the {@code !== null} filter. TeaVM-transpilable.
 */
public final class AntigravityStreamTransform {

    /** Injected {@code transformThinkingParts} callback ({@code (response) => transformed}). */
    public interface ThinkingPartsTransform {
        /**
         * One response with its thinking blocks rewritten into the shape a surface renders.
         *
         * @param response the response to rewrite
         * @return the rewritten response
         */
        Object transform(Object response);
    }

    /** Injected {@code signatureStore.set(sessionKey, {text, signature})} write. */
    public interface SignatureStore {
        /**
         * Records the signature a thinking block was returned with.
         *
         * @param sessionKey the key to hold it under
         * @param text the thinking text the signature covers
         * @param signature the signature the upstream returned
         */
        void set(String sessionKey, String text, String signature);
    }

    /** Injected optional {@code onCacheSignature(sessionKey, text, signature)} callback. */
    public interface CacheSignatureCallback {
        /**
         * Tells the host a signature is worth keeping on disk.
         *
         * @param sessionKey the key to hold it under
         * @param text the thinking text the signature covers
         * @param signature the signature the upstream returned
         */
        void onCacheSignature(String sessionKey, String text, String signature);
    }

    /** Injected optional {@code onInjectDebug(response, debugText) => response} callback. */
    public interface InjectDebug {
        /**
         * One response with a placeholder thinking block added, so a debug view has one to show.
         *
         * @param response the response to rewrite
         * @param debugText the placeholder text
         * @return the rewritten response
         */
        Object inject(Object response, String debugText);
    }

    /** {@code createThoughtBuffer()}: a {@code Map<number,string>} wrapper (get/set/clear). */
    public interface ThoughtBuffer {
        /**
         * The text accumulated for one candidate.
         *
         * @param index which candidate
         * @return its text so far, or {@code null} when there is none
         */
        String get(int index);

        /**
         * Records the text accumulated for one candidate.
         *
         * @param index which candidate
         * @param text the text so far
         */
        void set(int index, String text);

        /** Drops everything accumulated, at the end of a stream. */
        void clear();
    }

    /** Mutable {@code { injected: boolean }} debug-state cursor passed through {@link #transformSseLine}. */
    public static final class DebugState {
        /** Whether the placeholder has already been injected into this stream. */
        public boolean injected;

        /**
         * One stream's debug cursor.
         *
         * @param injected whether the placeholder has already been injected
         */
        public DebugState(boolean injected) {
            this.injected = injected;
        }
    }

    private static final double TWO_POW_32 = 4294967296.0; // 2^32

    private AntigravityStreamTransform() {
    }

    // ---- createThoughtBuffer ----------------------------------------------

    /**
     * A fresh buffer for one stream's accumulated thinking text.
     *
     * @return the buffer
     */
    public static ThoughtBuffer createThoughtBuffer() {
        return new MapThoughtBuffer();
    }

    private static final class MapThoughtBuffer implements ThoughtBuffer {
        private final Map<Integer, String> buffer = new LinkedHashMap<>();

        @Override
        public String get(int index) {
            return buffer.get(index);
        }

        @Override
        public void set(int index, String text) {
            buffer.put(index, text);
        }

        @Override
        public void clear() {
            buffer.clear();
        }
    }

    // ---- hashString -------------------------------------------------------

    /**
     * The hash a thinking text is deduplicated by.
     *
     * @param str the text to hash
     * @return the digest, as hex
     */
    public static String hashString(String str) {
        double hash = 5381;
        for (int i = 0; i < str.length(); i++) {
            int shifted = toInt32(hash) << 5; // JS (hash << 5): ToInt32 then shift
            hash = ((double) shifted) + hash + str.charAt(i); // charCodeAt = UTF-16 code unit
        }
        return Long.toHexString(toUint32(hash));
    }

    // ECMAScript ToUint32.
    private static long toUint32(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0L;
        double num = (x < 0 ? Math.ceil(x) : Math.floor(x)); // truncate toward zero
        double m = num % TWO_POW_32;
        if (m < 0) m += TWO_POW_32;
        return (long) m;
    }

    // ECMAScript ToInt32.
    private static int toInt32(double x) {
        long u = toUint32(x);
        if (u >= 2147483648L) u -= TWO_POW_32; // >= 2^31 -> wrap to signed
        return (int) u;
    }

    // ---- transformStreamingPayload ----------------------------------------

    /**
     * A whole buffered SSE payload with every line's response rewritten.
     *
     * @param json the codec the lines are parsed and written with
     * @param payload the whole SSE body
     * @param transform what rewrites each line's response
     * @return the rewritten payload
     */
    public static String transformStreamingPayload(JsonCodec json, String payload, ThinkingPartsTransform transform) {
        String[] lines = payload.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) result.append("\n");
            result.append(transformStreamingLine(json, lines[i], transform));
        }
        return result.toString();
    }

    private static String transformStreamingLine(JsonCodec json, String line, ThinkingPartsTransform transform) {
        if (!line.startsWith("data:")) return line;
        String jsonText = line.substring(5).trim();
        if (jsonText.isEmpty()) return line;
        try {
            Object parsed = json.parse(jsonText);
            if (parsed instanceof Map && JsCoercion.asMap(parsed).containsKey("response")) {
                Object response = JsCoercion.asMap(parsed).get("response");
                Object transformed = transform != null ? transform.transform(response) : response;
                return "data: " + json.stringify(transformed);
            }
        } catch (RuntimeException ignored) {
        }
        return line;
    }

    // ---- deduplicateThinkingText -----------------------------------------

    /**
     * {@code displayedThinkingHashes} may be {@code null}; {@code imageSink} is the
     * {@link AntigravityThinkingBlocks.ImageSink} for the inlineData branch.
     *
     * @param response the response to rewrite
     * @param sentBuffer what has already been sent for each candidate
     * @param displayedThinkingHashes what has already been shown, or {@code null} to dedupe nothing
     * @param imageSink where an inline image is written
     * @return the rewritten response
     */
    public static Object deduplicateThinkingText(Object response, ThoughtBuffer sentBuffer,
                                                 Set<String> displayedThinkingHashes,
                                                 AntigravityThinkingBlocks.ImageSink imageSink) {
        if (!(response instanceof Map)) return response;
        Map<String, Object> resp = JsCoercion.asMap(response);

        if (resp.get("candidates") instanceof List) {
            List<Object> cands = JsCoercion.asList(resp.get("candidates"));
            List<Object> newCandidates = new ArrayList<>();
            for (int index = 0; index < cands.size(); index++) {
                Object candidate = cands.get(index);
                Map<String, Object> cand = candidate instanceof Map ? JsCoercion.asMap(candidate) : null;
                if (cand == null || !JsCoercion.isTruthy(cand.get("content"))) {
                    newCandidates.add(candidate);
                    continue;
                }
                Object contentObj = cand.get("content");
                if (!(contentObj instanceof Map)) {
                    newCandidates.add(candidate);
                    continue;
                }
                Map<String, Object> content = JsCoercion.asMap(contentObj);
                if (!(content.get("parts") instanceof List)) {
                    newCandidates.add(candidate);
                    continue;
                }

                List<Object> newParts = new ArrayList<>();
                for (Object partObj : JsCoercion.asList(content.get("parts"))) {
                    Map<String, Object> p = partObj instanceof Map ? JsCoercion.asMap(partObj) : new LinkedHashMap<String, Object>();

                    if (JsCoercion.isTruthy(p.get("inlineData"))) {
                        Map<String, Object> inlineData = p.get("inlineData") instanceof Map
                                ? JsCoercion.asMap(p.get("inlineData")) : new LinkedHashMap<String, Object>();
                        String imgResult = imageSink.process(inlineData.get("mimeType"), inlineData.get("data"));
                        if (JsCoercion.isTruthy(imgResult)) {
                            Map<String, Object> textPart = new LinkedHashMap<>();
                            textPart.put("text", imgResult);
                            newParts.add(textPart);
                            continue;
                        }
                    }

                    if (Boolean.TRUE.equals(p.get("thought")) || "thinking".equals(p.get("type"))) {
                        String fullText = jsStr(JsCoercion.firstTruthy(p.get("text"), p.get("thinking"), ""));

                        if (displayedThinkingHashes != null) {
                            String hash = hashString(fullText);
                            if (displayedThinkingHashes.contains(hash)) {
                                sentBuffer.set(index, fullText);
                                newParts.add(null); // return null
                                continue;
                            }
                            displayedThinkingHashes.add(hash);
                        }

                        String sentText = orEmpty(sentBuffer.get(index));
                        if (fullText.startsWith(sentText)) {
                            String delta = fullText.substring(sentText.length());
                            sentBuffer.set(index, fullText);
                            if (JsCoercion.isTruthy(delta)) {
                                Map<String, Object> np = new LinkedHashMap<>(p);
                                np.put("text", delta);
                                np.put("thinking", delta);
                                newParts.add(np);
                                continue;
                            }
                            newParts.add(null); // return null
                            continue;
                        }

                        sentBuffer.set(index, fullText);
                        newParts.add(partObj); // return part (original)
                        continue;
                    }
                    newParts.add(partObj);
                }

                Map<String, Object> newContent = new LinkedHashMap<>(content);
                newContent.put("parts", filterNonNull(newParts));
                Map<String, Object> newCand = new LinkedHashMap<>(cand);
                newCand.put("content", newContent);
                newCandidates.add(newCand);
            }
            Map<String, Object> out = new LinkedHashMap<>(resp);
            out.put("candidates", newCandidates);
            return out;
        }

        if (resp.get("content") instanceof List) {
            int thinkingIndex = 0;
            List<Object> newContent = new ArrayList<>();
            for (Object blockObj : JsCoercion.asList(resp.get("content"))) {
                Map<String, Object> b = blockObj instanceof Map ? JsCoercion.asMap(blockObj) : null;
                if (b != null && "thinking".equals(b.get("type"))) {
                    String fullText = jsStr(JsCoercion.firstTruthy(b.get("thinking"), b.get("text"), ""));

                    if (displayedThinkingHashes != null) {
                        String hash = hashString(fullText);
                        if (displayedThinkingHashes.contains(hash)) {
                            sentBuffer.set(thinkingIndex, fullText);
                            thinkingIndex++;
                            newContent.add(null);
                            continue;
                        }
                        displayedThinkingHashes.add(hash);
                    }

                    String sentText = orEmpty(sentBuffer.get(thinkingIndex));
                    if (fullText.startsWith(sentText)) {
                        String delta = fullText.substring(sentText.length());
                        sentBuffer.set(thinkingIndex, fullText);
                        thinkingIndex++;
                        if (JsCoercion.isTruthy(delta)) {
                            Map<String, Object> nb = new LinkedHashMap<>(b);
                            nb.put("thinking", delta);
                            nb.put("text", delta);
                            newContent.add(nb);
                            continue;
                        }
                        newContent.add(null);
                        continue;
                    }

                    sentBuffer.set(thinkingIndex, fullText);
                    thinkingIndex++;
                    newContent.add(blockObj);
                    continue;
                }
                newContent.add(blockObj);
            }
            Map<String, Object> out = new LinkedHashMap<>(resp);
            out.put("content", filterNonNull(newContent));
            return out;
        }

        return response;
    }

    // ---- transformSseLine -----------------------------------------------

    /**
     * What the host should emit in place of one upstream line.
     *
     * @param json the codec the line is parsed and written with
     * @param line the upstream SSE line
     * @param signatureStore the host's signature store
     * @param thoughtBuffer what has been accumulated for each candidate
     * @param sentThinkingBuffer what has already been sent for each candidate
     * @param onCacheSignature told when a signature is worth keeping on disk
     * @param onInjectDebug adds the debug placeholder, when there is one
     * @param transformThinkingParts rewrites the thinking blocks for display
     * @param imageSink where an inline image is written
     * @param signatureSessionKey the key this session's signatures are stored under
     * @param debugText the placeholder to inject, or {@code null} for none
     * @param cacheSignatures whether this model's signatures are worth storing
     * @param displayedThinkingHashes what has already been shown, or {@code null} to dedupe nothing
     * @param debugState whether the placeholder has already been injected
     * @return the line to emit
     */
    public static String transformSseLine(JsonCodec json, String line,
                                          SignatureStore signatureStore, ThoughtBuffer thoughtBuffer,
                                          ThoughtBuffer sentThinkingBuffer,
                                          CacheSignatureCallback onCacheSignature, InjectDebug onInjectDebug,
                                          ThinkingPartsTransform transformThinkingParts,
                                          AntigravityThinkingBlocks.ImageSink imageSink,
                                          String signatureSessionKey, String debugText, boolean cacheSignatures,
                                          Set<String> displayedThinkingHashes, DebugState debugState) {
        if (!line.startsWith("data:")) return line;
        String jsonText = line.substring(5).trim();
        if (jsonText.isEmpty()) return line;

        try {
            Object parsed = json.parse(jsonText);
            if (parsed instanceof Map && JsCoercion.asMap(parsed).containsKey("response")) {
                Object respVal = JsCoercion.asMap(parsed).get("response");

                if (cacheSignatures && JsCoercion.isTruthy(signatureSessionKey)) {
                    cacheThinkingSignaturesFromResponse(respVal, signatureSessionKey, signatureStore, thoughtBuffer, onCacheSignature);
                }

                Object response = deduplicateThinkingText(respVal, sentThinkingBuffer, displayedThinkingHashes, imageSink);

                if (JsCoercion.isTruthy(debugText) && onInjectDebug != null && !debugState.injected) {
                    response = onInjectDebug.inject(response, debugText);
                    debugState.injected = true;
                }

                Object transformed = transformThinkingParts != null ? transformThinkingParts.transform(response) : response;
                return "data: " + json.stringify(transformed);
            }
        } catch (RuntimeException ignored) {
        }
        return line;
    }

    // ---- cacheThinkingSignaturesFromResponse ----------------------------

    /**
     * Stores every signature a buffered response carried, so a later turn can reuse them.
     *
     * @param response the response to read
     * @param signatureSessionKey the key this session's signatures are stored under
     * @param signatureStore the host's signature store
     * @param thoughtBuffer what has been accumulated for each candidate
     * @param onCacheSignature told when a signature is worth keeping on disk
     */
    public static void cacheThinkingSignaturesFromResponse(Object response, String signatureSessionKey,
                                                           SignatureStore signatureStore, ThoughtBuffer thoughtBuffer,
                                                           CacheSignatureCallback onCacheSignature) {
        if (!(response instanceof Map)) return;
        Map<String, Object> resp = JsCoercion.asMap(response);

        if (resp.get("candidates") instanceof List) {
            List<Object> cands = JsCoercion.asList(resp.get("candidates"));
            for (int index = 0; index < cands.size(); index++) {
                Object candidate = cands.get(index);
                Map<String, Object> cand = candidate instanceof Map ? JsCoercion.asMap(candidate) : null;
                if (cand == null || !JsCoercion.isTruthy(cand.get("content"))) continue;
                Object contentObj = cand.get("content");
                if (!(contentObj instanceof Map)) continue;
                Map<String, Object> content = JsCoercion.asMap(contentObj);
                if (!(content.get("parts") instanceof List)) continue;

                for (Object partObj : JsCoercion.asList(content.get("parts"))) {
                    Map<String, Object> p = partObj instanceof Map ? JsCoercion.asMap(partObj) : new LinkedHashMap<String, Object>();
                    if (Boolean.TRUE.equals(p.get("thought")) || "thinking".equals(p.get("type"))) {
                        String text = jsStr(JsCoercion.firstTruthy(p.get("text"), p.get("thinking"), ""));
                        if (JsCoercion.isTruthy(text)) {
                            String current = orEmpty(thoughtBuffer.get(index));
                            thoughtBuffer.set(index, current + text);
                        }
                    }
                    if (JsCoercion.isTruthy(p.get("thoughtSignature"))) {
                        String fullText = orEmpty(thoughtBuffer.get(index));
                        if (JsCoercion.isTruthy(fullText)) {
                            String signature = jsStr(p.get("thoughtSignature"));
                            if (onCacheSignature != null) onCacheSignature.onCacheSignature(signatureSessionKey, fullText, signature);
                            signatureStore.set(signatureSessionKey, fullText, signature);
                        }
                    }
                }
            }
        }

        if (resp.get("content") instanceof List) {
            int claudeBufferKey = 0; // single-stream content
            for (Object blockObj : JsCoercion.asList(resp.get("content"))) {
                Map<String, Object> b = blockObj instanceof Map ? JsCoercion.asMap(blockObj) : null;
                if (b != null && "thinking".equals(b.get("type"))) {
                    String text = jsStr(JsCoercion.firstTruthy(b.get("thinking"), b.get("text"), ""));
                    if (JsCoercion.isTruthy(text)) {
                        String current = orEmpty(thoughtBuffer.get(claudeBufferKey));
                        thoughtBuffer.set(claudeBufferKey, current + text);
                    }
                }
                if (b != null && JsCoercion.isTruthy(b.get("signature"))) {
                    String fullText = orEmpty(thoughtBuffer.get(claudeBufferKey));
                    if (JsCoercion.isTruthy(fullText)) {
                        String signature = jsStr(b.get("signature"));
                        if (onCacheSignature != null) onCacheSignature.onCacheSignature(signatureSessionKey, fullText, signature);
                        signatureStore.set(signatureSessionKey, fullText, signature);
                    }
                }
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static String orEmpty(String v) {
        return v != null ? v : "";
    }

    private static List<Object> filterNonNull(List<Object> items) {
        List<Object> out = new ArrayList<>();
        for (Object item : items) {
            if (item != null) out.add(item);
        }
        return out;
    }

    // A truthy non-string is invalid input, but String(x) keeps this total.
    private static String jsStr(Object v) {
        return v instanceof String ? (String) v : String.valueOf(v);
    }
}
