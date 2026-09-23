package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * HITL / external-execution control state machine for one lease. Timeout tombstones persist
 * until the outer terminal; the first late result rotates exactly one new generation and all
 * sibling tombstones reuse it.
 */
final class ControlEventTracker {
    @FunctionalInterface
    interface TerminalReducer {
        void terminate(TerminalOutcome outcome, boolean resultObserved);
    }

    record TerminalResolution(TerminalOutcome outcome, boolean completed) {}

    private final ControlScheduler scheduler;
    private final Duration waitTimeout;
    private final InvocationLease lease;
    private final InvocationLifecycle initialGeneration;
    private final TerminalReducer terminal;
    private final int maxRecords;
    private final LongSupplier ticker;
    private final InvocationCaptureBudget budget;

    private final LinkedHashMap<String, ControlRecord> records = new LinkedHashMap<>();
    private final Map<String, InvocationCaptureBudget.Reservation> reservations =
            new HashMap<>();
    private final Map<String, ControlRecord> activeByKey = new HashMap<>();
    private final Map<String, Deque<String>> secondaryIndex = new HashMap<>();
    private final Map<String, InvocationLifecycle> generations = new HashMap<>();
    private final Map<String, ControlScheduler.Cancellable> timers = new HashMap<>();

    private boolean resultObserved;
    private boolean terminated;
    private @Nullable TerminalOutcome pendingControlOutcome;
    private long waitCount;
    private long totalWaitNanos;
    private final AtomicLong duplicateWaits = new AtomicLong();
    private final AtomicLong ambiguousCorrelations = new AtomicLong();
    private final AtomicLong unmatchedResults = new AtomicLong();
    private final AtomicLong conflictingControlEvents = new AtomicLong();
    private final AtomicLong duplicateControlEvents = new AtomicLong();

    ControlEventTracker(
            ControlScheduler scheduler,
            Duration waitTimeout,
            InvocationLease lease,
            InvocationLifecycle initialGeneration,
            TerminalReducer terminal,
            int maxRecords,
            LongSupplier ticker,
            InvocationCaptureBudget budget) {
        this(
                scheduler,
                waitTimeout,
                lease,
                initialGeneration,
                terminal,
                maxRecords,
                ticker,
                budget,
                null);
    }

    ControlEventTracker(
            ControlScheduler scheduler,
            Duration waitTimeout,
            InvocationLease lease,
            InvocationLifecycle initialGeneration,
            TerminalReducer terminal,
            int maxRecords,
            LongSupplier ticker,
            InvocationCaptureBudget budget,
            java.util.function.@org.jspecify.annotations.Nullable Consumer<InvocationLifecycle>
                    onRotation) {
        this.onRotation = onRotation;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.waitTimeout = Objects.requireNonNull(waitTimeout, "waitTimeout");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.initialGeneration = Objects.requireNonNull(initialGeneration, "initialGeneration");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        if (maxRecords <= 0) {
            throw new IllegalArgumentException("maxRecords must be positive");
        }
        this.maxRecords = maxRecords;
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.budget = Objects.requireNonNull(budget, "budget");
        generations.put(initialGeneration.generationId(), initialGeneration);
    }

    private final java.util.function.@org.jspecify.annotations.Nullable Consumer<
            InvocationLifecycle> onRotation;

    synchronized void onWait(
            ControlRecord.Kind kind, String agentId, String replyId, List<String> toolCallIds) {
        if (terminated || lease.frozen()) {
            return;
        }
        InvocationLifecycle generation = lease.current();
        ControlRecord record =
                new ControlRecord(
                        generation.generationId(), agentId, kind, replyId, toolCallIds,
                        ticker.getAsLong());
        if (activeByKey.containsKey(record.key())) {
            duplicateWaits.incrementAndGet();
            return;
        }
        if (records.size() >= maxRecords) {
            terminateGeneration(TerminalOutcome.CONTROL_CAPACITY);
            return;
        }
        Optional<InvocationCaptureBudget.Reservation> reservation =
                budget.reserve(ControlRecord.ESTIMATED_BYTES);
        if (reservation.isEmpty()) {
            terminateGeneration(TerminalOutcome.CONTROL_CAPACITY);
            return;
        }
        records.put(record.key(), record);
        reservations.put(record.key(), reservation.orElseThrow());
        activeByKey.put(record.key(), record);
        secondaryIndex.computeIfAbsent(record.secondaryKey(), ignored -> new ArrayDeque<>())
                .addLast(record.key());
        timers.put(
                record.key(),
                scheduler.schedule(() -> onTimeout(record.key()), waitTimeout));
    }

    void onResult(ControlRecord.Kind kind, String replyId) {
        InvocationLifecycle rotatedToNotify = null;
        synchronized (this) {
            ControlRecord record = earliestMatch(kind, replyId);
            if (record == null) {
                unmatchedResults.incrementAndGet();
                return;
            }
            if (record.tombstone()) {
                // Late result: rotate exactly once per old generation; siblings reuse it.
                InvocationLifecycle oldGeneration = generations.get(record.generationId());
                if (oldGeneration != null) {
                    InvocationLifecycle rotated = lease.rotateFrom(oldGeneration);
                    if (rotated != null) {
                        generations.putIfAbsent(rotated.generationId(), rotated);
                        record.markTombstone(rotated.generationId());
                        rotatedToNotify = rotated;
                    }
                }
                removeRecord(record);
            } else {
                ControlScheduler.Cancellable timer = timers.remove(record.key());
                if (timer != null) {
                    timer.cancel();
                }
                activeByKey.remove(record.key());
                removeRecord(record);
                waitCount++;
                totalWaitNanos += Math.max(0L, ticker.getAsLong() - record.createdNanos());
            }
        }
        // Listener runs outside the monitor: span creation must not hold the tracker lock.
        if (rotatedToNotify != null && onRotation != null) {
            onRotation.accept(rotatedToNotify);
        }
    }

    synchronized void onControlOutcome(TerminalOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (pendingControlOutcome == null) {
            pendingControlOutcome = outcome;
        } else if (pendingControlOutcome == outcome) {
            duplicateControlEvents.incrementAndGet();
        } else {
            conflictingControlEvents.incrementAndGet();
        }
    }

    synchronized void onAgentResult() {
        resultObserved = true;
    }

    synchronized TerminalResolution resolveOnComplete() {
        TerminalOutcome outcome =
                pendingControlOutcome != null
                        ? pendingControlOutcome
                        : resultObserved
                                ? TerminalOutcome.NORMAL
                                : TerminalOutcome.INCOMPLETE_COMPLETION;
        return new TerminalResolution(outcome, resultObserved);
    }

    /** Outer terminal/shutdown: cancel all timers; tombstones stay queryable until close. */
    synchronized void cancelAll() {
        timers.values().forEach(ControlScheduler.Cancellable::cancel);
        timers.clear();
        reservations.values().forEach(InvocationCaptureBudget.Reservation::close);
        reservations.clear();
    }

    synchronized boolean waiting() {
        return !activeByKey.isEmpty();
    }

    synchronized long waitCount() {
        return waitCount;
    }

    synchronized long totalWaitNanos() {
        return totalWaitNanos;
    }

    long duplicateWaitCount() {
        return duplicateWaits.get();
    }

    long ambiguousCorrelationCount() {
        return ambiguousCorrelations.get();
    }

    long unmatchedResultCount() {
        return unmatchedResults.get();
    }

    long conflictingControlEventCount() {
        return conflictingControlEvents.get();
    }

    long duplicateControlEventCount() {
        return duplicateControlEvents.get();
    }

    private void onTimeout(String key) {
        synchronized (this) {
            if (terminated || !activeByKey.containsKey(key)) {
                return;
            }
            terminateGeneration(TerminalOutcome.AWAIT_TIMEOUT);
        }
    }

    /** First terminal wins: cancel timers, tombstone all active waits, reduce the outcome. */
    private void terminateGeneration(TerminalOutcome outcome) {
        if (terminated) {
            return;
        }
        terminated = true;
        timers.values().forEach(ControlScheduler.Cancellable::cancel);
        timers.clear();
        for (ControlRecord record : activeByKey.values()) {
            record.markTombstone(null);
        }
        activeByKey.clear();
        // Capacity loss must never claim a completed turn, even if a result was seen first.
        boolean completed = outcome != TerminalOutcome.CONTROL_CAPACITY && resultObserved;
        terminal.terminate(outcome, completed);
    }

    private @Nullable ControlRecord earliestMatch(ControlRecord.Kind kind, String replyId) {
        Deque<String> keys = secondaryIndex.get(kind + "|" + TelemetryKey.normalize(replyId));
        if (keys == null) {
            return null;
        }
        // A result carries no generation/agent: pick the earliest created matching record.
        ControlRecord first = keys.isEmpty() ? null : records.get(keys.peekFirst());
        if (first != null && keys.size() > 1) {
            ambiguousCorrelations.incrementAndGet();
        }
        return first;
    }

    private void removeRecord(ControlRecord record) {
        records.remove(record.key());
        removeFromSecondary(record);
        InvocationCaptureBudget.Reservation reservation = reservations.remove(record.key());
        if (reservation != null) {
            reservation.close();
        }
    }

    private void removeFromSecondary(ControlRecord record) {
        Deque<String> keys = secondaryIndex.get(record.secondaryKey());
        if (keys != null) {
            keys.remove(record.key());
            if (keys.isEmpty()) {
                secondaryIndex.remove(record.secondaryKey());
            }
        }
    }

    /** replyId normalization shared by wait/result paths without failing on odd input. */
    private static final class TelemetryKey {
        private static String normalize(String replyId) {
            try {
                return io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryValueNormalizer
                        .bounded(replyId, ControlRecord.ID_MAX_BYTES);
            } catch (RuntimeException exception) {
                return "";
            }
        }
    }
}
