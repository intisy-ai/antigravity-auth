package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsInterface;

/**
 * One upstream stream's decode into canonical IR events, held open across its chunks.
 *
 * @implNote Stateful, because a Gemini SSE chunk boundary falls anywhere and the mapper carries the
 * partial line plus the ids it has minted so far. The host holds one per response and never reuses
 * it.
 */
@TsInterface
public interface AntigravityIrStreamMapperHandle {

    /**
     * The IR events one raw chunk yields.
     *
     * @param chunk the upstream SSE text, which may end mid-line
     * @return the events, as a JSON array
     */
    String handle(String chunk);

    /**
     * The IR events left over once the upstream closes.
     *
     * @return the events, as a JSON array
     */
    String finish();
}
