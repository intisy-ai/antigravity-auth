package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.shared.spi.Clock;
import io.github.intisy.ai.shared.spi.Logger;
import io.github.intisy.ai.shared.spi.Random;

import java.util.ArrayList;
import java.util.List;

/** Deterministic {@link Clock}/{@link Random}/{@link Logger} test doubles. */
final class TestDoubles {
    private TestDoubles() {
    }

    static Clock fixedClock(long now) {
        return () -> now;
    }

    /** Always returns the same {@code [0, 1)} value, standing in for {@code Math.random}. */
    static Random fixedRandom(double value) {
        return () -> value;
    }

    /** A {@link Logger} that records every message (stands in for {@code console.warn}). */
    static final class CapturingLogger implements Logger {
        final List<String> messages = new ArrayList<>();

        @Override
        public void log(String msg) {
            messages.add(msg);
        }
    }
}
