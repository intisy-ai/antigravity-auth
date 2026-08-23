package io.github.intisy.ai.antigravity;

import io.github.intisy.ai.api.seam.Clock;
import io.github.intisy.ai.api.seam.Logger;
import io.github.intisy.ai.api.seam.Random;

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

    /** A {@link Logger} that records every message, at whatever level it was written. */
    static final class CapturingLogger implements Logger {
        final List<String> messages = new ArrayList<>();

        @Override
        public void info(String message) {
            messages.add(message);
        }

        @Override
        public void warn(String message) {
            messages.add(message);
        }

        @Override
        public void debug(String message) {
            messages.add(message);
        }

        @Override
        public void error(String message) {
            messages.add(message);
        }

        @Override
        public void error(String message, Object cause) {
            messages.add(message);
        }
    }
}
