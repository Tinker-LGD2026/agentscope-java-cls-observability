package io.github.tinkerlgd2026.agentscope.cls.internal;

import java.time.Duration;
import java.util.function.LongSupplier;

/** One monotonic absolute deadline shared by all stages of an operation. */
public final class DeadlineBudget {
    private final long deadlineNanos;
    private final LongSupplier ticker;

    private DeadlineBudget(long deadlineNanos, LongSupplier ticker) {
        this.deadlineNanos = deadlineNanos;
        this.ticker = ticker;
    }

    public static DeadlineBudget start(Duration timeout) {
        return start(timeout, System::nanoTime);
    }

    public static DeadlineBudget start(Duration timeout, LongSupplier ticker) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("deadline timeout must be positive");
        }
        if (ticker == null) {
            throw new IllegalArgumentException("deadline ticker is required");
        }
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException exception) {
            timeoutNanos = Long.MAX_VALUE;
        }
        long now = ticker.getAsLong();
        return new DeadlineBudget(now + timeoutNanos, ticker);
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public Duration remaining() {
        long remaining = deadlineNanos - ticker.getAsLong();
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    public boolean expired() {
        return remaining().isZero();
    }

}
