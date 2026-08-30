package io.github.intisy.ai.js.surface;

import io.github.intisy.ai.tsemit.TsInterface;

/**
 * One streamed response's line-by-line transform, held open across its lines.
 *
 * @implNote Stateful, because the thought and sent buffers and the debug-injected flag persist for
 * the whole response. The host owns the framing that splits the bytes into lines; every decision
 * about a line is made here.
 */
@TsInterface
public interface AntigravitySseTransformHandle {

    /**
     * What the host should emit in place of one upstream line.
     *
     * @param line the upstream SSE line, without its terminator
     * @return the line to emit
     */
    String handle(String line);
}
