package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinkerlgd2026.agentscope.cls.exporter.ManualScheduledExecutorService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Deterministic races between control results, timeouts, and terminal signals. */
class TerminalOutcomeRaceTest {
    private static final Duration WAIT_TIMEOUT = Duration.ofMinutes(5);

    @Test
    void resultAtExactDeadlineBeforeTimerTaskWins() {
        RaceFixture fixture = new RaceFixture();
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-1", List.of());
        // Result arrives before the scheduler runs the due timer task.
        fixture.tracker.onResult(ControlRecord.Kind.USER_CONFIRM, "reply-1");
        fixture.advance(WAIT_TIMEOUT.plusNanos(1));

        assertThat(fixture.terminals).isEmpty();
        assertThat(fixture.tracker.waitCount()).isEqualTo(1);
    }

    @Test
    void timerTaskAtDeadlineBeatsLaterResult() {
        RaceFixture fixture = new RaceFixture();
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-1", List.of());
        fixture.advance(WAIT_TIMEOUT.plusNanos(1));
        fixture.tracker.onResult(ControlRecord.Kind.USER_CONFIRM, "reply-1");

        assertThat(fixture.terminals).containsExactly(TerminalOutcome.AWAIT_TIMEOUT);
        assertThat(fixture.lease.rotationCount()).isEqualTo(1);
    }

    @Test
    void concurrentResultAndTimeoutResolveExactlyOneWinner() throws Exception {
        RaceFixture fixture = new RaceFixture();
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-1", List.of());
        CountDownLatch start = new CountDownLatch(1);
        Thread resultThread =
                new Thread(
                        () -> {
                            await(start);
                            fixture.tracker.onResult(
                                    ControlRecord.Kind.USER_CONFIRM, "reply-1");
                        });
        Thread timeoutThread =
                new Thread(
                        () -> {
                            await(start);
                            fixture.fireDueTimers();
                        });
        resultThread.start();
        timeoutThread.start();
        start.countDown();
        resultThread.join();
        timeoutThread.join();

        int resolved = fixture.tracker.waitCount() > 0 ? 1 : 0;
        assertThat(resolved + fixture.terminals.size())
                .as("exactly one of result/timeout wins")
                .isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class RaceFixture {
        private final ManualScheduledExecutorService scheduler =
                ManualScheduledExecutorService.create();
        private final AtomicLong ticker = new AtomicLong();
        private final List<TerminalOutcome> terminals = new ArrayList<>();
        private final InvocationLifecycle initial = new InvocationLifecycle();
        private final InvocationLease lease = new InvocationLease(initial);
        private final ControlEventTracker tracker =
                new ControlEventTracker(
                        new ControlScheduler(scheduler),
                        WAIT_TIMEOUT,
                        lease,
                        initial,
                        (outcome, resultObserved) -> {
                            synchronized (terminals) {
                                terminals.add(outcome);
                            }
                            lease.current().terminal(outcome, resultObserved, null);
                        },
                        1024,
                        ticker::get,
                        new InvocationCaptureBudget(
                                new io.github.tinkerlgd2026.agentscope.cls.internal
                                        .CaptureMemoryPool(1 << 20),
                                1 << 20));

        private void advance(Duration duration) {
            ticker.addAndGet(duration.toNanos());
            scheduler.advance(duration);
        }

        private void fireDueTimers() {
            scheduler.advance(WAIT_TIMEOUT.plusNanos(1));
        }
    }
}
