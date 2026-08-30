package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsInterface;

/**
 * The host's set of thinking-text hashes already shown, which survives an SSE reconnect.
 *
 * @implNote Held by the host rather than the stream transformer because a reconnect builds a new
 * transformer for the same session, and a per-transformer set would show every thought twice.
 */
@TsInterface
public interface AntigravityThoughtDedupShape {

    /**
     * Whether a thought has already been shown.
     *
     * @param hash the thinking text's hash
     * @return true when it was shown before
     */
    boolean has(String hash);

    /**
     * Records that a thought has been shown.
     *
     * @param hash the thinking text's hash
     */
    void add(String hash);
}
