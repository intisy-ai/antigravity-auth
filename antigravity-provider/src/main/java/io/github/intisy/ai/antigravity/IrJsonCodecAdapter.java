package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.JsonCodec;

/**
 * Adapts core-proxy's {@code routing} {@link JsonCodec} SPI (parse/stringify) to core-ir's own,
 * structurally identical {@link io.github.intisy.ai.ir.spi.JsonCodec}. Every call site in
 * this module already carries a routing {@code JsonCodec} (GsonJsonCodec on the JVM,
 * SimpleJsonCodec from the TeaVM export); this lets {@link AntigravityGeminiSseBridge} hand that
 * same instance to core-ir's {@code GeminiTranslator} instead of duplicating a codec.
 */
public final class IrJsonCodecAdapter implements io.github.intisy.ai.ir.spi.JsonCodec {
    private final JsonCodec delegate;

    /**
     * One adapter over a codec the caller already holds.
     *
     * @param delegate the routing codec every call site in this module carries
     */
    public IrJsonCodecAdapter(JsonCodec delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object parse(String json) {
        return delegate.parse(json);
    }

    @Override
    public String stringify(Object value) {
        return delegate.stringify(value);
    }
}
