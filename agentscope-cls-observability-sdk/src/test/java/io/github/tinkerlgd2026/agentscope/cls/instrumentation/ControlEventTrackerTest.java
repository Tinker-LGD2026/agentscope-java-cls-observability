package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinkerlgd2026.agentscope.cls.exporter.ManualScheduledExecutorService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ControlEventTrackerTest {
    private static final Duration WAIT_TIMEOUT = Duration.ofMinutes(10);

    @Test
    void resultBeforeTimeoutCancelsTimerAndAccumulatesWaitMetrics() {
        Fixture fixture = new Fixture();
        fixture.tracker.onWait(
                ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-1", List.of("call-1"));

        fixture.advance(Duration.ofMinutes(1));
        fixture.tracker.onResult(ControlRecord.Kind.USER_CONFIRM, "reply-1");
        fixture.advance(WAIT_TIMEOUT.plusMinutes(1));

        assertThat(fixture.terminals).isEmpty();
        assertThat(fixture.tracker.waitCount()).isEqualTo(1);
        assertThat(fixture.tracker.totalWaitNanos())
                .isEqualTo(Duration.ofMinutes(1).toNanos());
        assertThat(fixture.tracker.waiting()).isFalse();
    }

    @Test
    void timeoutTerminatesGenerationAndTombstonesWaits() {
        Fixture fixture = new Fixture();
        fixture.tracker.onWait(
                ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-1", List.of());
        fixture.tracker.onWait(
                ControlRecord.Kind.EXTERNAL_EXECUTION, "agent-1", "reply-2", List.of());

        fixture.advance(WAIT_TIMEOUT.plusNanos(1));

        assertThat(fixture.terminals).containsExactly(TerminalOutcome.AWAIT_TIMEOUT);
        assertThat(fixture.initial.state()).isEqualTo(InvocationLifecycle.State.FINISHED);
        // Remaining timers cancelled: advancing further does nothing.
        fixture.advance(WAIT_TIMEOUT.multipliedBy(2));
        assertThat(fixture.terminals).hasSize(1);
        assertThat(fixture.tracker.waiting()).isFalse();
    }

    @Test
    void timeoutThenLateResultRotatesExactlyOnceAndSiblingsReuse() {
        Fixture fixture = new Fixture();
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-1", List.of());
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-2", List.of());
        fixture.advance(WAIT_TIMEOUT.plusNanos(1));

        fixture.tracker.onResult(ControlRecord.Kind.USER_CONFIRM, "reply-1");
        InvocationLifecycle rotated = fixture.lease.current();
        assertThat(rotated).isNotSameAs(fixture.initial);
        assertThat(fixture.lease.rotationCount()).isEqualTo(1);

        fixture.tracker.onResult(ControlRecord.Kind.USER_CONFIRM, "reply-2");
        assertThat(fixture.lease.current()).isSameAs(rotated);
        assertThat(fixture.lease.rotationCount()).isEqualTo(1);
    }

    @Test
    void resultBeatsTimeoutWhenResultArrivesFirst() {
        Fixture fixture = new Fixture();
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-1", List.of());
        fixture.tracker.onResult(ControlRecord.Kind.USER_CONFIRM, "reply-1");

        fixture.advance(WAIT_TIMEOUT.plusMinutes(1));

        assertThat(fixture.terminals).isEmpty();
        assertThat(fixture.initial.state()).isEqualTo(InvocationLifecycle.State.OPEN);
    }

    @Test
    void duplicateWaitDoesNotRestartTimer() {
        Fixture fixture = new Fixture();
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-1", List.of());
        fixture.advance(Duration.ofMinutes(9));
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-1", List.of());

        assertThat(fixture.tracker.duplicateWaitCount()).isEqualTo(1);
        fixture.advance(Duration.ofMinutes(2));
        // Timer was scheduled at t=0: 11 minutes elapsed, timeout fires.
        assertThat(fixture.terminals).containsExactly(TerminalOutcome.AWAIT_TIMEOUT);
    }

    @Test
    void ambiguousCorrelationPicksEarliestAndCounts() {
        Fixture fixture = new Fixture();
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-1", List.of());
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-2", "reply-1", List.of());

        fixture.tracker.onResult(ControlRecord.Kind.USER_CONFIRM, "reply-1");

        assertThat(fixture.tracker.ambiguousCorrelationCount()).isEqualTo(1);
        assertThat(fixture.tracker.waitCount()).isEqualTo(1);
        // Second wait remains active.
        assertThat(fixture.tracker.waiting()).isTrue();
    }

    @Test
    void unmatchedResultIsMalformedAndChangesNothing() {
        Fixture fixture = new Fixture();
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-1", List.of());

        fixture.tracker.onResult(ControlRecord.Kind.USER_CONFIRM, "unknown-reply");

        assertThat(fixture.tracker.unmatchedResultCount()).isEqualTo(1);
        assertThat(fixture.tracker.waitCount()).isZero();
        assertThat(fixture.tracker.waiting()).isTrue();
    }

    @Test
    void capacityOverflowTerminatesWithControlCapacity() {
        Fixture fixture = new Fixture(3);
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "r1", List.of());
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "r2", List.of());
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "r3", List.of());
        // Fourth exceeds the cap of 3.
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "r4", List.of());

        assertThat(fixture.terminals).containsExactly(TerminalOutcome.CONTROL_CAPACITY);
        assertThat(fixture.initial.state()).isEqualTo(InvocationLifecycle.State.FINISHED);
    }

    @Test
    void pendingControlOutcomeIsFirstWinsWithConflictAndDuplicateCounts() {
        Fixture fixture = new Fixture();
        fixture.tracker.onControlOutcome(TerminalOutcome.DENIED);
        fixture.tracker.onControlOutcome(TerminalOutcome.MAX_ITERS);
        fixture.tracker.onControlOutcome(TerminalOutcome.DENIED);

        assertThat(fixture.tracker.conflictingControlEventCount()).isEqualTo(1);
        assertThat(fixture.tracker.duplicateControlEventCount()).isEqualTo(1);
        assertThat(fixture.tracker.resolveOnComplete().outcome()).isEqualTo(TerminalOutcome.DENIED);
    }

    @Test
    void completeWithoutResultIsIncompleteCompletion() {
        Fixture fixture = new Fixture();

        ControlEventTracker.TerminalResolution resolution = fixture.tracker.resolveOnComplete();

        assertThat(resolution.outcome()).isEqualTo(TerminalOutcome.INCOMPLETE_COMPLETION);
        assertThat(resolution.completed()).isFalse();
    }

    @Test
    void completeWithResultAndPendingControlKeepsControlledOutcome() {
        Fixture fixture = new Fixture();
        fixture.tracker.onAgentResult();
        fixture.tracker.onControlOutcome(TerminalOutcome.INTERRUPTED);

        ControlEventTracker.TerminalResolution resolution = fixture.tracker.resolveOnComplete();

        assertThat(resolution.outcome()).isEqualTo(TerminalOutcome.INTERRUPTED);
        assertThat(resolution.completed()).isTrue();
    }

    @Test
    void noResumeCompleteAfterTimeoutCreatesNoNewGeneration() {
        Fixture fixture = new Fixture();
        fixture.tracker.onWait(ControlRecord.Kind.USER_CONFIRM, "agent-1", "reply-1", List.of());
        fixture.advance(WAIT_TIMEOUT.plusNanos(1));

        // Outer terminal arrives without any recovery result.
        fixture.tracker.cancelAll();

        assertThat(fixture.lease.rotationCount()).isZero();
    }

    private static final class Fixture {
        private final ManualScheduledExecutorService scheduler =
                ManualScheduledExecutorService.create();
        private final AtomicLong ticker = new AtomicLong();
        private final List<TerminalOutcome> terminals = new ArrayList<>();
        private final InvocationLifecycle initial = new InvocationLifecycle();
        private final InvocationLease lease = new InvocationLease(initial);
        private final ControlEventTracker tracker;

        private Fixture() {
            this(1024);
        }

        private void advance(Duration duration) {
            ticker.addAndGet(duration.toNanos());
            scheduler.advance(duration);
        }

        private Fixture(int maxControlRecords) {
            tracker =
                    new ControlEventTracker(
                            new ControlScheduler(scheduler),
                            WAIT_TIMEOUT,
                            lease,
                            initial,
                            (outcome, resultObserved) -> {
                                terminals.add(outcome);
                                lease.current().terminal(outcome, resultObserved, null);
                            },
                            maxControlRecords,
                            ticker::get,
                            new InvocationCaptureBudget(
                                    new io.github.tinkerlgd2026.agentscope.cls.internal
                                            .CaptureMemoryPool(1 << 20),
                                    1 << 20));
        }
    }
}
