package io.github.tinkerlgd2026.agentscope.cls.internal;

import io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Adapts a {@link SpanSink} into deadline-bounded coordinator stages. The barrier stage hands
 * the remaining budget to the sink and never blocks past it; a sink that ignores the budget is
 * left running (the coordinator never invokes a later sink stage concurrently).
 */
public final class SinkLifecycleAdapter {
    private final SpanSink sink;

    public SinkLifecycleAdapter(SpanSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** Flushes the sink with the remaining stage budget; false on timeout/failure. */
    public LifecycleCoordinator.Stage barrierStage() {
        return budget -> {
            Duration remaining = budget.remaining();
            if (remaining.isZero()) {
                return false;
            }
            try {
                return Boolean.TRUE.equals(
                        sink.flush(remaining)
                                .toCompletableFuture()
                                .get(Math.max(1L, remaining.toMillis()), TimeUnit.MILLISECONDS));
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception failure) {
                return false;
            }
        };
    }
}
