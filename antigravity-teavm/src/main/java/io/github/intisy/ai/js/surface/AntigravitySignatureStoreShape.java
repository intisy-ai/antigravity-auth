package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsInterface;
import io.github.intisy.ai.tsemit.TsNullable;

/**
 * The host's thinking-signature store, as the request preparer and the response transform reach it.
 *
 * @implNote The host store is keyed by one string and holds a text/signature pair, so {@code get}
 * answers with that pair as JSON while {@code set} takes the two halves apart. Both spellings are
 * the store's own, not a shape invented for this boundary.
 */
@TsInterface
public interface AntigravitySignatureStoreShape {

    /**
     * The signature pair held under one key.
     *
     * @param key the signature key
     * @return the {@code text}/{@code signature} pair as JSON, or null when nothing is held
     */
    @TsNullable(asNull = true)
    String get(String key);

    /**
     * Whether a key holds a signature.
     *
     * @param key the signature key
     * @return true when one is held
     */
    boolean has(String key);

    /**
     * Drops whatever one key holds.
     *
     * @param key the signature key
     */
    void delete(String key);

    /**
     * Records the signature a thinking block was returned with.
     *
     * @param sessionKey the key to hold it under
     * @param text the thinking text the signature covers
     * @param signature the signature the upstream returned
     */
    void set(String sessionKey, String text, String signature);
}
