package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.Random;

/** Deterministic {@link Clock}/{@link Random} test doubles for the parity tests. */
final class TestDoubles {
    private TestDoubles() {
    }

    static Clock fixedClock(long now) {
        return () -> now;
    }

    /** Always returns the same {@code [0, 1)} value, mirroring the harness's {@code Math.random} stub. */
    static Random fixedRandom(double value) {
        return () -> value;
    }
}
