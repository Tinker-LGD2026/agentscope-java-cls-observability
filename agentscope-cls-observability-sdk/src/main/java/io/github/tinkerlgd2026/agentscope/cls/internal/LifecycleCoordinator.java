package io.github.tinkerlgd2026.agentscope.cls.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Arbitrates flush/shutdown/close for the facade. Flush is single-flight in RUNNING; the first
 * shutdown atomically enters DRAINING and creates the unique shutdown chain; every stage of the
 * chain shares one absolute monotonic deadline split into 40/65/85/100 milestones; a stuck sink
 * stage is never invoked concurrently with a later stage; CLOSE_FAILED resumes from the first
 * stage that did not complete successfully.
 */
public final class LifecycleCoordinator {

    /** A stage bounded by an absolute deadline budget. Returns true on success. */
    @FunctionalInterface
    public interface Stage {
        boolean run(DeadlineBudget budget);
    }

    /** Ordered shutdown stages. beginDrain/freeze are unconditional; stages may not block past
     * their budget, but a stage that does is never overlapped by the next stage. */
    public record ShutdownStages(
            Runnable beginDrain,
            Stage awaitQuiescence,
            Runnable freeze,
            Stage flushProcessor,
            Stage sinkBarrier,
            Stage closeProvider) {
        public ShutdownStages {
            Objects.requireNonNull(beginDrain, "beginDrain");
            Objects.requireNonNull(awaitQuiescence, "awaitQuiescence");
            Objects.requireNonNull(freeze, "freeze");
            Objects.requireNonNull(flushProcessor, "flushProcessor");
            Objects.requireNonNull(sinkBarrier, "sinkBarrier");
            Objects.requireNonNull(closeProvider, "closeProvider");
        }
    }

    public enum State {
        RUNNING,
        DRAINING,
        CLOSE_FAILED,
        CLOSED
    }

    private final ExecutorService executor;
    private final Stage flushStage;
    private final ShutdownStages stages;
    private final LongSupplier ticker;
    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
    private final AtomicInteger flushWaiters = new AtomicInteger();
    private final AtomicReference<FlushFlight> flushFlight = new AtomicReference<>();
    private final AtomicReference<ShutdownFlight> shutdownFlight = new AtomicReference<>();

    public LifecycleCoordinator(
            ExecutorService executor, Stage flushStage, ShutdownStages stages) {
        this(executor, flushStage, stages, System::nanoTime);
    }

    public LifecycleCoordinator(
            ExecutorService executor,
            Stage flushStage,
            ShutdownStages stages,
            LongSupplier ticker) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.flushStage = Objects.requireNonNull(flushStage, "flushStage");
        this.stages = Objects.requireNonNull(stages, "stages");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    State state() {
        return state.get();
    }

    /**
     * Merges concurrent callers into one shared flush operation. Each caller waits with its own
     * deadline; a caller timeout never cancels the shared operation. Returns false after
     * DRAINING and true after CLOSED.
     */
    public boolean flush(Duration callerTimeout) {
        requirePositive(callerTimeout);
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current != State.RUNNING) {
            return false;
        }
        FlushFlight flight = flushFlight.get();
        if (flight == null) {
            FlushFlight created = new FlushFlight();
            if (!flushFlight.compareAndSet(null, created)) {
                flight = flushFlight.get();
            } else {
                flight = created;
                // The shared operation keeps the first caller's timeout as its internal
                // deadline; later callers never widen or shrink it.
                DeadlineBudget operationBudget = DeadlineBudget.start(callerTimeout, ticker);
                submit(created.outcome, () -> created.runStage(flushStage, operationBudget));
            }
        }
        if (flight == null) {
            return false;
        }
        return await(flight.outcome, callerTimeout);
    }

    /**
     * First call enters DRAINING and starts the unique shutdown chain; later calls wait on the
     * same flight. After CLOSE_FAILED with no stage task running, the next call resumes from
     * the first incomplete stage with a fresh absolute deadline.
     */
    public boolean shutdown(Duration callerTimeout) {
        requirePositive(callerTimeout);
        ShutdownFlight flight;
        while (true) {
            if (state.get() == State.CLOSED) {
                return true;
            }
            flight = shutdownFlight.get();
            if (flight == null) {
                if (!state.compareAndSet(State.RUNNING, State.DRAINING)) {
                    continue;
                }
                ShutdownFlight created = new ShutdownFlight();
                if (!shutdownFlight.compareAndSet(null, created)) {
                    // Another thread published the flight and owns the chain; stay DRAINING.
                    continue;
                }
                submitChain(created, 0, callerTimeout);
                flight = created;
                break;
            }
            if (state.get() == State.CLOSE_FAILED && flight.taskDone() && !flight.succeeded()) {
                ShutdownFlight resumed = new ShutdownFlight();
                resumed.checkpoint.set(flight.checkpoint.get());
                if (shutdownFlight.compareAndSet(flight, resumed)) {
                    submitChain(resumed, resumed.checkpoint.get(), callerTimeout);
                    flight = resumed;
                    break;
                }
                continue;
            }
            break;
        }
        return await(flight.outcome, callerTimeout);
    }

    private void submitChain(ShutdownFlight flight, int fromStage, Duration timeout) {
        submit(
                flight.outcome,
                () -> {
                    boolean ok = runChain(flight, fromStage, timeout);
                    flight.completed = true;
                    return ok;
                });
    }

    private void submit(CompletableFuture<Boolean> outcome, BooleanSupplier task) {
        try {
            executor.submit(
                    () -> {
                        boolean ok = false;
                        try {
                            ok = task.getAsBoolean();
                        } catch (RuntimeException | Error failure) {
                            ok = false;
                        }
                        outcome.complete(ok);
                    });
        } catch (RejectedExecutionException rejection) {
            outcome.complete(false);
        }
    }

    /** Runs stages from {@code fromStage}; stops at the first failed or budget-expired stage. */
    private boolean runChain(ShutdownFlight flight, int fromStage, Duration timeout) {
        DeadlineBudget absolute = DeadlineBudget.start(timeout, ticker);
        long startNanos = absolute.deadlineNanos() - timeout.toNanos();
        long totalNanos = timeout.toNanos();
        long m40 = startNanos + totalNanos * 2 / 5;
        long m65 = startNanos + totalNanos * 13 / 20;
        long m85 = startNanos + totalNanos * 17 / 20;
        long m100 = absolute.deadlineNanos();

        int step = fromStage;
        if (step == 0) {
            stages.beginDrain().run();
            flight.checkpoint.set(1);
            step = 1;
        }
        if (step == 1) {
            // An in-flight flush from before DRAINING shares this absolute deadline.
            DeadlineBudget quiescenceCap = capped(m40);
            if (quiescenceCap == null || !awaitActiveFlush(quiescenceCap)) {
                return fail(flight, 1);
            }
            boolean quiet = stages.awaitQuiescence().run(quiescenceCap);
            if (!quiet) {
                stages.freeze().run();
            }
            flight.checkpoint.set(2);
            step = 2;
        }
        if (step == 2) {
            DeadlineBudget cap = capped(m65);
            if (cap == null || !awaitActiveFlush(cap) || !stages.flushProcessor().run(cap)) {
                return fail(flight, 2);
            }
            flight.checkpoint.set(3);
            step = 3;
        }
        if (step == 3) {
            DeadlineBudget cap = capped(m85);
            if (cap == null || !awaitActiveFlush(cap) || !stages.sinkBarrier().run(cap)) {
                return fail(flight, 3);
            }
            flight.checkpoint.set(4);
            step = 4;
        }
        if (step == 4) {
            DeadlineBudget cap = capped(m100);
            if (cap == null || !awaitActiveFlush(cap) || !stages.closeProvider().run(cap)) {
                return fail(flight, 4);
            }
            flight.checkpoint.set(5);
        }
        return complete(flight, true);
    }

    private boolean fail(ShutdownFlight flight, int checkpoint) {
        flight.checkpoint.set(checkpoint);
        return complete(flight, false);
    }

    private boolean complete(ShutdownFlight flight, boolean ok) {
        if (ok) {
            state.set(State.CLOSED);
        } else {
            state.compareAndSet(State.DRAINING, State.CLOSE_FAILED);
        }
        flight.succeeded = ok;
        return ok;
    }

    /**
     * Waits for an in-flight single-flight flush inside {@code cap}. A still-running flush
     * means no sink stage may start: the caller stage fails instead of invoking the sink
     * concurrently.
     */
    private boolean awaitActiveFlush(DeadlineBudget cap) {
        FlushFlight active = flushFlight.get();
        if (active == null) {
            return true;
        }
        long millis = cap.remaining().toMillis();
        try {
            active.outcome.get(Math.max(1L, millis), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException timeout) {
            return false;
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException failure) {
            return true; // completed with failure: the sink is free
        }
    }

    /** Returns a budget capped at {@code milestoneNanos}, or null when the cap has passed. */
    private @Nullable DeadlineBudget capped(long milestoneNanos) {
        long remaining = milestoneNanos - ticker.getAsLong();
        if (remaining <= 0) {
            return null;
        }
        return DeadlineBudget.start(Duration.ofNanos(remaining), ticker);
    }

    /**
     * Polls the outcome against the caller's own deadline. Polling (instead of one blocking
     * get) keeps the wait bounded by the caller budget even when the ticker is virtual.
     */
    private boolean await(CompletableFuture<Boolean> outcome, Duration callerTimeout) {
        DeadlineBudget wait = DeadlineBudget.start(callerTimeout, ticker);
        flushWaiters.incrementAndGet();
        try {
            while (true) {
                long millis = wait.remaining().toMillis();
                if (millis <= 0) {
                    // The shared operation continues for the remaining callers.
                    return outcome.getNow(null) == Boolean.TRUE;
                }
                try {
                    Boolean result = outcome.get(Math.min(10L, millis), TimeUnit.MILLISECONDS);
                    return Boolean.TRUE.equals(result);
                } catch (TimeoutException slice) {
                    // Re-evaluate the caller deadline.
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (ExecutionException failure) {
                    return false;
                }
            }
        } finally {
            flushWaiters.decrementAndGet();
        }
    }

    /** Visible for tests: callers currently waiting on a shared operation. */
    int flushWaiters() {
        return flushWaiters.get();
    }

    private static void requirePositive(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }

    private final class FlushFlight {
        private final CompletableFuture<Boolean> outcome = new CompletableFuture<>();

        private boolean runStage(Stage stage, DeadlineBudget budget) {
            try {
                return stage.run(budget);
            } finally {
                flushFlight.compareAndSet(this, null);
            }
        }
    }

    private static final class ShutdownFlight {
        private final CompletableFuture<Boolean> outcome = new CompletableFuture<>();
        private final AtomicInteger checkpoint = new AtomicInteger();
        private volatile boolean succeeded;
        private volatile boolean completed;

        private boolean taskDone() {
            return completed;
        }

        private boolean succeeded() {
            return succeeded;
        }
    }
}
