package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsFn;
import io.github.intisy.ai.tsemit.TsNullable;

/** The host's image writer, which answers with what should stand in the image's place. */
@TsFn
public interface AntigravityImageSinkFn {

    /**
     * Writes one inline image.
     *
     * @param mimeType the image's media type
     * @param base64Data the image bytes, base64 encoded
     * @return a link to the written file, or null when there was nothing to write
     */
    @TsNullable(asNull = true)
    String save(String mimeType, String base64Data);
}
