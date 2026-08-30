package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsFn;

/** The host's on-disk signature-cache write. */
@TsFn
public interface AntigravityCacheSignatureFn {

    /**
     * Tells the host a signature is worth keeping on disk.
     *
     * @param sessionKey the key to hold it under
     * @param text the thinking text the signature covers
     * @param signature the signature the upstream returned
     */
    void onCacheSignature(String sessionKey, String text, String signature);
}
