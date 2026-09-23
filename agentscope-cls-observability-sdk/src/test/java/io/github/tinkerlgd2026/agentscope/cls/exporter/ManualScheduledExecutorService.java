package io.github.tinkerlgd2026.agentscope.cls.exporter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Deterministic single-threaded scheduler for tests: tasks run only when advanced. */
public final class ManualScheduledExecutorService extends AbstractExecutorService
        implements ScheduledExecutorService {
    private final PriorityQueue<ManualTask> tasks = new PriorityQueue<>();
    private final List<RuntimeException> taskFailures = new ArrayList<>();
    private long nowNanos;
    private boolean shutdown;

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return enqueue(command, unit.toNanos(delay), -1);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return enqueueTask(() -> callable.call(), unit.toNanos(delay), -1);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
            Runnable command, long initialDelay, long period, TimeUnit unit) {
        return enqueue(command, unit.toNanos(initialDelay), unit.toNanos(period));
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
            Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return enqueue(command, unit.toNanos(initialDelay), unit.toNanos(delay));
    }

    @Override
    public void execute(Runnable command) {
        enqueue(command, 0, -1);
    }

    public void advance(Duration duration) {
        nowNanos += duration.toNanos();
        runDue();
    }

    public int runPending() {
        return runDue();
    }

    public int pendingCount() {
        return tasks.size();
    }

    public void assertNoTaskFailures() {
        if (!taskFailures.isEmpty()) {
            throw new AssertionError("scheduled task failed", taskFailures.get(0));
        }
    }

    private int runDue() {
        int ran = 0;
        while (!tasks.isEmpty() && tasks.peek().triggerNanos <= nowNanos) {
            ManualTask task = tasks.poll();
            if (task.cancelled) {
                continue;
            }
            ran++;
            try {
                task.command.runUnchecked();
                task.done = true;
            } catch (RuntimeException failure) {
                task.done = true;
                taskFailures.add(failure);
            }
            if (task.periodNanos >= 0 && !task.cancelled) {
                task.triggerNanos = nowNanos + task.periodNanos;
                tasks.add(task);
            }
        }
        return ran;
    }

    private <V> ScheduledFuture<V> enqueue(
            Runnable command, long delayNanos, long periodNanos) {
        return enqueueTask(command::run, delayNanos, periodNanos);
    }

    private <V> ScheduledFuture<V> enqueueTask(
            ManualCallable command, long delayNanos, long periodNanos) {
        if (shutdown) {
            throw new IllegalStateException("scheduler is shut down");
        }
        ManualTask task = new ManualTask(command, nowNanos + delayNanos, periodNanos);
        tasks.add(task);
        @SuppressWarnings("unchecked")
        ScheduledFuture<V> future = (ScheduledFuture<V>) task;
        return future;
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> pending = new ArrayList<>(tasks.size());
        tasks.forEach(task -> pending.add(task.command::runUnchecked));
        tasks.clear();
        return pending;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return isTerminated();
    }

    @FunctionalInterface
    private interface ManualCallable {
        void call() throws Exception;

        default void runUnchecked() {
            try {
                call();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private final class ManualTask
            implements ScheduledFuture<Object>, Comparable<Delayed> {
        private final ManualCallable command;
        private final long periodNanos;
        private long triggerNanos;
        private boolean cancelled;
        private boolean done;

        private ManualTask(ManualCallable command, long triggerNanos, long periodNanos) {
            this.command = command;
            this.triggerNanos = triggerNanos;
            this.periodNanos = periodNanos;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(triggerNanos - nowNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other instanceof ManualTask task) {
                return Long.compare(triggerNanos, task.triggerNanos);
            }
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }

        @Override
        public Object get() {
            done = true;
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return get();
        }
    }

    public static ManualScheduledExecutorService create() {
        return new ManualScheduledExecutorService();
    }

    @Override
    public String toString() {
        return "ManualScheduledExecutorService[pending=" + tasks.size() + "]";
    }

}
