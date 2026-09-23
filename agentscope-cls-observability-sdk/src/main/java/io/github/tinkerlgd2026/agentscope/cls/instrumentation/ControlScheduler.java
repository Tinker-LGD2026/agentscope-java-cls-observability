package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Schedules control-wait timeouts on an injected executor; cancellable per wait. */
final class ControlScheduler {
    private final ScheduledExecutorService executor;

    ControlScheduler(ScheduledExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    Cancellable schedule(Runnable task, Duration delay) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(delay, "delay");
        ScheduledFuture<?> future =
                executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }
}
