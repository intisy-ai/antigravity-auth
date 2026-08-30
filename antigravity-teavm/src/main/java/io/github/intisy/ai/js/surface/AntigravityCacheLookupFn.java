package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsFn;
import io.github.intisy.ai.tsemit.TsNullable;

/** The host's signature-cache read. */
@TsFn
public interface AntigravityCacheLookupFn {

    /**
     * The signature the cache holds for one thinking text.
     *
     * @param sessionId the session the cache is keyed by
     * @param text the thinking text
     * @return the signature, or null when nothing is held
     */
    @TsNullable(asNull = true)
    String get(String sessionId, String text);
}
