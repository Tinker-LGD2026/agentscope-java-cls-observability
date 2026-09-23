package io.github.tinkerlgd2026.agentscope.cls.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deterministic coordinator tests: virtual nano clock plus gated stages; no sleeps anywhere.
 */
class LifecycleCoordinatorTest {
    private static final Duration TOTAL = Duration.ofSeconds(100);

    private ExecutorService executor;
    private ExecutorService callers;
    private AtomicLong nanos;
    private CompletableFuture<Boolean> flushGate;
    private AtomicInteger flushEntries;
    private List<String> stageOrder;
    private ConcurrentLinkedQueue<Duration> stageBudgets;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "coordinator-test");
            thread.setDaemon(true);
            return thread;
        });
        nanos = new AtomicLong();
        flushGate = new CompletableFuture<>();
        flushEntries = new AtomicInteger();
        stageOrder = new CopyOnWriteArrayList<>();
        stageBudgets = new ConcurrentLinkedQueue<>();
        callers =
                Executors.newCachedThreadPool(
                        runnable -> {
                            Thread thread = new Thread(runnable, "coordinator-test-caller");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        callers.shutdownNow();
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.yield();
        }
    }

    private LifecycleCoordinator.Stage flushStage() {
        return budget -> {
            flushEntries.incrementAndGet();
            stageOrder.add("flush");
            stageBudgets.add(budget.remaining());
            return awaitGate(flushGate);
        };
    }

    private LifecycleCoordinator.Stage gatedStage(
            String name, CompletableFuture<Boolean> gate) {
        return budget -> {
            stageOrder.add(name);
            stageBudgets.add(budget.remaining());
            return awaitGate(gate);
        };
    }

    private static boolean awaitGate(CompletableFuture<Boolean> gate) {
        try {
            return Boolean.TRUE.equals(gate.get(5, TimeUnit.SECONDS));
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception failure) {
            return false;
        }
    }

    private LifecycleCoordinator.Stage instantStage(String name, boolean result) {
        return budget -> {
            stageOrder.add(name);
            stageBudgets.add(budget.remaining());
            return result;
        };
    }

    private LifecycleCoordinator.ShutdownStages stages(
            LifecycleCoordinator.Stage quiescence,
            Runnable freeze,
            LifecycleCoordinator.Stage flushProcessor,
            LifecycleCoordinator.Stage sinkBarrier,
            LifecycleCoordinator.Stage closeProvider) {
        return new LifecycleCoordinator.ShutdownStages(
                () -> stageOrder.add("beginDrain"),
                quiescence,
                freeze,
                flushProcessor,
                sinkBarrier,
                closeProvider);
    }

    private LifecycleCoordinator.ShutdownStages happyStages() {
        CompletableFuture<Boolean> open = CompletableFuture.completedFuture(true);
        return stages(
                gatedStage("quiescence", open),
                () -> stageOrder.add("freeze"),
                gatedStage("flushProcessor", open),
                gatedStage("sinkBarrier", open),
                gatedStage("closeProvider", open));
    }

    private LifecycleCoordinator coordinator(LifecycleCoordinator.ShutdownStages stages) {
        return new LifecycleCoordinator(executor, flushStage(), stages, nanos::get);
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        LifecycleCoordinator coordinator = coordinator(happyStages());

        assertThatThrownBy(() -> coordinator.flush(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.flush(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.shutdown(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.shutdown(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentFlushIsSingleFlight() throws Exception {
        LifecycleCoordinator coordinator = coordinator(happyStages());

        Future<Boolean> first = callers.submit(() -> coordinator.flush(TOTAL));
        awaitCondition(() -> flushEntries.get() == 1);
        Future<Boolean> second = callers.submit(() -> coordinator.flush(TOTAL));
        // Both callers are parked on the same flight before it completes.
        awaitCondition(() -> coordinator.waiters() == 2);
        flushGate.complete(true);

        assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(flushEntries.get()).isEqualTo(1);
    }

    @Test
    void flushCallerTimeoutDoesNotCancelSharedOperation() throws Exception {
        LifecycleCoordinator coordinator = coordinator(happyStages());

        Future<Boolean> slowCaller = callers.submit(() -> coordinator.flush(TOTAL));
        awaitCondition(() -> flushEntries.get() == 1);
        // Second caller joins with a shorter personal deadline.
        Future<Boolean> impatient =
                callers.submit(() -> coordinator.flush(Duration.ofSeconds(30)));
        awaitCondition(() -> coordinator.waiters() == 2);
        // Its deadline expires while the shared flush keeps running.
        nanos.addAndGet(Duration.ofSeconds(50).toNanos());
        assertThat(impatient.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(flushEntries.get()).isEqualTo(1);

        flushGate.complete(true);
        assertThat(slowCaller.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shutdownWaitsForInFlightFlushBeforeProcessorStage() throws Exception {
        LifecycleCoordinator coordinator = coordinator(happyStages());

        Future<Boolean> flushing = callers.submit(() -> coordinator.flush(TOTAL));
        awaitCondition(() -> flushEntries.get() == 1);
        Future<Boolean> shutdown = callers.submit(() -> coordinator.shutdown(TOTAL));
        awaitCondition(() -> stageOrder.contains("beginDrain"));
        assertThat(stageOrder).doesNotContain("flushProcessor");

        flushGate.complete(true);
        assertThat(flushing.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(shutdown.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(stageOrder)
                .containsSubsequence("beginDrain", "quiescence")
                .containsSubsequence("flushProcessor", "sinkBarrier", "closeProvider");
        assertThat(stageOrder.indexOf("flush"))
                .isLessThan(stageOrder.indexOf("flushProcessor"));
    }

    @Test
    void flushAfterClosedReturnsTrueWithoutTouchingStages() throws Exception {
        LifecycleCoordinator coordinator = coordinator(happyStages());

        assertThat(coordinator.shutdown(TOTAL)).isTrue();
        // CLOSED: flush is a no-op success for idempotent shutdown callers.
        assertThat(coordinator.flush(TOTAL)).isTrue();
        assertThat(flushEntries.get()).isZero();
    }

    @Test
    void flushDuringShutdownReturnsFalse() throws Exception {
        CompletableFuture<Boolean> barrierGate = new CompletableFuture<>();
        LifecycleCoordinator.ShutdownStages stages =
                stages(
                        instantStage("quiescence", true),
                        () -> stageOrder.add("freeze"),
                        instantStage("flushProcessor", true),
                        gatedStage("sinkBarrier", barrierGate),
                        instantStage("closeProvider", true));
        LifecycleCoordinator coordinator = coordinator(stages);

        Future<Boolean> shutdown = callers.submit(() -> coordinator.shutdown(TOTAL));
        awaitCondition(() -> stageOrder.contains("sinkBarrier"));
        // DRAINING: new flush calls are rejected without touching the sink.
        assertThat(coordinator.flush(TOTAL)).isFalse();
        assertThat(flushEntries.get()).isZero();

        barrierGate.complete(true);
        assertThat(shutdown.get(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void milestoneBudgetsAndFreezeAtFortyPercent() {
        CompletableFuture<Boolean> open = CompletableFuture.completedFuture(true);
        AtomicInteger freezeCalls = new AtomicInteger();
        LifecycleCoordinator.ShutdownStages stages =
                stages(
                        budget -> {
                            stageOrder.add("quiescence");
                            stageBudgets.add(budget.remaining());
                            // Leases still active at the 40% milestone.
                            nanos.addAndGet(Duration.ofSeconds(50).toNanos());
                            return false;
                        },
                        () -> {
                            stageOrder.add("freeze");
                            freezeCalls.incrementAndGet();
                        },
                        gatedStage("flushProcessor", open),
                        gatedStage("sinkBarrier", open),
                        gatedStage("closeProvider", open));
        LifecycleCoordinator coordinator = coordinator(stages);

        assertThat(coordinator.shutdown(TOTAL)).isTrue();

        assertThat(freezeCalls.get()).isEqualTo(1);
        assertThat(stageOrder)
                .containsExactly(
                        "beginDrain",
                        "quiescence",
                        "freeze",
                        "flushProcessor",
                        "sinkBarrier",
                        "closeProvider");
        // Caps: quiescence ≤ 40s; flush ≤ 65-50=15s; barrier ≤ 85-50=35s; close ≤ 100-50=50s.
        assertThat(stageBudgets)
                .containsExactly(
                        Duration.ofSeconds(40),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(35),
                        Duration.ofSeconds(50));
    }

    @Test
    void earlyCompletionPassesTimeForwardToLaterStages() {
        LifecycleCoordinator coordinator = coordinator(happyStages());

        assertThat(coordinator.shutdown(TOTAL)).isTrue();

        // Nothing consumed time: every stage sees the full milestone budget.
        assertThat(stageBudgets)
                .containsExactly(
                        Duration.ofSeconds(40),
                        Duration.ofSeconds(65),
                        Duration.ofSeconds(85),
                        Duration.ofSeconds(100));
        assertThat(stageOrder).doesNotContain("freeze");
    }

    @Test
    void stuckSinkStageNeverInvokesNextStageConcurrentlyAndShutdownRetries() throws Exception {
        CountDownLatch barrierEntered = new CountDownLatch(1);
        CountDownLatch barrierRelease = new CountDownLatch(1);
        AtomicInteger barrierEntries = new AtomicInteger();
        AtomicInteger closeEntries = new AtomicInteger();
        LifecycleCoordinator.ShutdownStages stages =
                stages(
                        instantStage("quiescence", true),
                        () -> stageOrder.add("freeze"),
                        instantStage("flushProcessor", true),
                        budget -> {
                            barrierEntries.incrementAndGet();
                            stageOrder.add("sinkBarrier");
                            barrierEntered.countDown();
                            try {
                                // Ignores interruption and outlives the stage milestone.
                                barrierRelease.await();
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                            return true;
                        },
                        budget -> {
                            closeEntries.incrementAndGet();
                            stageOrder.add("closeProvider");
                            return true;
                        });
        LifecycleCoordinator coordinator = coordinator(stages);

        Future<Boolean> first = callers.submit(() -> coordinator.shutdown(TOTAL));
        assertThat(barrierEntered.await(5, TimeUnit.SECONDS)).isTrue();
        // First caller's deadline expires while the sink stage is stuck.
        nanos.addAndGet(Duration.ofSeconds(150).toNanos());
        assertThat(first.get(5, TimeUnit.SECONDS)).isFalse();
        // A second shutdown only waits on the same flight: no repeated sink invocation.
        Future<Boolean> second =
                callers.submit(() -> coordinator.shutdown(Duration.ofSeconds(30)));
        awaitCondition(() -> coordinator.waiters() == 1);
        nanos.addAndGet(Duration.ofSeconds(150).toNanos());
        assertThat(second.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(barrierEntries.get()).isEqualTo(1);
        assertThat(closeEntries.get()).isZero();

        // The stuck stage finally completes; the expired budget stops the chain before the
        // close stage, leaving the flight in CLOSE_FAILED.
        barrierRelease.countDown();
        awaitCondition(
                () -> coordinator.state() == LifecycleCoordinator.State.CLOSE_FAILED);
        assertThat(closeEntries.get()).isZero();

        // CLOSE_FAILED with no stage task running: retry resumes after the completed barrier
        // stage with a fresh budget and closes exactly once.
        nanos.set(0);
        assertThat(coordinator.shutdown(TOTAL)).isTrue();
        assertThat(barrierEntries.get()).isEqualTo(1);
        assertThat(closeEntries.get()).isEqualTo(1);
        assertThat(stageOrder.stream().filter("flushProcessor"::equals).count()).isEqualTo(1);
    }

    @Test
    void closeFailureStopsChainAndRetryResumesFromFailedStage() throws Exception {
        AtomicInteger barrierCalls = new AtomicInteger();
        CompletableFuture<Boolean> open = CompletableFuture.completedFuture(true);
        LifecycleCoordinator.ShutdownStages stages =
                stages(
                        instantStage("quiescence", true),
                        () -> stageOrder.add("freeze"),
                        gatedStage("flushProcessor", open),
                        budget -> {
                            stageOrder.add("sinkBarrier");
                            return barrierCalls.incrementAndGet() > 1;
                        },
                        instantStage("closeProvider", true));
        LifecycleCoordinator coordinator = coordinator(stages);

        assertThat(coordinator.shutdown(TOTAL)).isFalse();
        assertThat(stageOrder).doesNotContain("closeProvider");

        // Retry: barrier stage is re-invoked, close stage runs once the barrier succeeds.
        assertThat(coordinator.shutdown(TOTAL)).isTrue();
        assertThat(barrierCalls.get()).isEqualTo(2);
        assertThat(stageOrder).contains("closeProvider");
    }

    @Test
    void throwingStageFailsChainAndAllowsRetryFromCheckpoint() {
        AtomicInteger barrierCalls = new AtomicInteger();
        CompletableFuture<Boolean> open = CompletableFuture.completedFuture(true);
        LifecycleCoordinator.ShutdownStages stages =
                stages(
                        instantStage("quiescence", true),
                        () -> stageOrder.add("freeze"),
                        gatedStage("flushProcessor", open),
                        budget -> {
                            stageOrder.add("sinkBarrier");
                            if (barrierCalls.incrementAndGet() == 1) {
                                throw new IllegalStateException("sink exploded");
                            }
                            return true;
                        },
                        instantStage("closeProvider", true));
        LifecycleCoordinator coordinator = coordinator(stages);

        // The throwing stage fails the chain into CLOSE_FAILED instead of wedging DRAINING.
        assertThat(coordinator.shutdown(TOTAL)).isFalse();
        awaitCondition(
                () -> coordinator.state() == LifecycleCoordinator.State.CLOSE_FAILED);
        assertThat(stageOrder).doesNotContain("closeProvider");

        // Retry resumes at the failed stage and completes.
        assertThat(coordinator.shutdown(TOTAL)).isTrue();
        assertThat(barrierCalls.get()).isEqualTo(2);
        assertThat(stageOrder).contains("closeProvider");
    }

    @Test
    void rejectedChainSubmissionFailsClosedInsteadOfWedging() {
        LifecycleCoordinator coordinator = coordinator(happyStages());
        executor.shutdownNow(); // every submission is rejected from here on

        assertThat(coordinator.shutdown(TOTAL)).isFalse();
        assertThat(coordinator.state()).isEqualTo(LifecycleCoordinator.State.CLOSE_FAILED);
        // Retry is also rejected but never wedges: it fails fast again.
        assertThat(coordinator.shutdown(TOTAL)).isFalse();
        assertThat(coordinator.state()).isEqualTo(LifecycleCoordinator.State.CLOSE_FAILED);
    }

    @Test
    void racingFlushCallersShareOneFlight() throws Exception {
        LifecycleCoordinator coordinator = coordinator(happyStages());
        int callerCount = 8;
        List<Future<Boolean>> results = new java.util.ArrayList<>();
        for (int index = 0; index < callerCount; index++) {
            results.add(callers.submit(() -> coordinator.flush(TOTAL)));
        }
        awaitCondition(() -> coordinator.waiters() == callerCount);
        flushGate.complete(true);

        for (Future<Boolean> result : results) {
            assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(flushEntries.get()).isEqualTo(1);
    }

    @Test
    void closedIsIdempotentAndNeverRepeatsStages() {
        LifecycleCoordinator coordinator = coordinator(happyStages());

        assertThat(coordinator.shutdown(TOTAL)).isTrue();
        assertThat(coordinator.shutdown(TOTAL)).isTrue();
        assertThat(coordinator.shutdown(TOTAL)).isTrue();

        assertThat(stageOrder.stream().filter("closeProvider"::equals).count()).isEqualTo(1);
        assertThat(stageOrder.stream().filter("beginDrain"::equals).count()).isEqualTo(1);
    }
}
