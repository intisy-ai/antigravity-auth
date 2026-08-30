package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsInterface;

/**
 * The host's id minting, which the stream mapper uses rather than baking entropy into the bundle.
 *
 * @implNote Two methods rather than one taking a kind, because the host mints them with different
 * prefixes and a kind argument would move that decision into the transpiled side.
 */
@TsInterface
public interface AntigravityStreamIdsShape {

    /**
     * A fresh id for one assistant message.
     *
     * @return the message id
     */
    String newMessageId();

    /**
     * A fresh id for one tool call.
     *
     * @return the tool-call id
     */
    String newToolId();
}
