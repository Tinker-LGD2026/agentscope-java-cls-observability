package io.github.tinkerlgd2026.agentscope.cls.internal;

import io.github.tinkerlgd2026.agentscope.cls.ClsDetailedTelemetrySnapshot;
import io.github.tinkerlgd2026.agentscope.cls.ClsTelemetrySnapshot;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe saturating observability health counters. */
public final class TelemetryCounters {
    private final AtomicLong acceptedSpans = new AtomicLong();
    private final AtomicLong invalidSpans = new AtomicLong();
    private final AtomicLong droppedSpans = new AtomicLong();
    private final AtomicLong exportFailedSpans = new AtomicLong();
    private final AtomicLong exportFailedBatches = new AtomicLong();
    private final AtomicLong captureFailures = new AtomicLong();
    private final AtomicReference<CapacityDrops> capacityDrops =
            new AtomicReference<>(new CapacityDrops(0, 0));
    private final AtomicLong duplicateMiddlewareDetections = new AtomicLong();
    private final AtomicLong flushFailures = new AtomicLong();
    private final AtomicLong shutdownFailures = new AtomicLong();

    public void accepted(long count) {
        add(acceptedSpans, count, "accepted span count");
    }

    public void invalid(long count) {
        add(invalidSpans, count, "invalid span count");
    }

    /** Compatibility API: counts failed spans, not failed batches. */
    public void exportFailed(long count) {
        add(exportFailedSpans, count, "export failed span count");
    }

    public void exportBatchFailed(long count) {
        add(exportFailedBatches, count, "export failed batch count");
    }

    public void dropped(long count) {
        add(droppedSpans, count, "dropped span count");
    }

    public void captureFailed(long count) {
        add(captureFailures, count, "capture failure count");
    }

    public void capacityDropped(long parts, long bytes) {
        requireNonNegative(parts, "capacity dropped part count");
        requireNonNegative(bytes, "capacity dropped byte count");
        capacityDrops.getAndUpdate(
                current ->
                        new CapacityDrops(
                                saturatingAdd(current.parts(), parts),
                                saturatingAdd(current.bytes(), bytes)));
    }

    public void duplicateMiddlewareDetected(long count) {
        add(duplicateMiddlewareDetections, count, "duplicate middleware count");
    }

    public void flushFailed(long count) {
        add(flushFailures, count, "flush failure count");
    }

    public void shutdownFailed(long count) {
        add(shutdownFailures, count, "shutdown failure count");
    }

    public ClsTelemetrySnapshot snapshot() {
        long compatibleFailures =
                saturatingAdd(
                        exportFailedSpans.get(),
                        saturatingAdd(flushFailures.get(), shutdownFailures.get()));
        return new ClsTelemetrySnapshot(
                acceptedSpans.get(), invalidSpans.get(), compatibleFailures, droppedSpans.get());
    }

    public ClsDetailedTelemetrySnapshot detailedSnapshot(long activeInvocations, long waitingInvocations) {
        requireNonNegative(activeInvocations, "active invocation gauge");
        requireNonNegative(waitingInvocations, "waiting invocation gauge");
        CapacityDrops capacity = capacityDrops.get();
        return new ClsDetailedTelemetrySnapshot(
                acceptedSpans.get(),
                invalidSpans.get(),
                droppedSpans.get(),
                exportFailedSpans.get(),
                exportFailedBatches.get(),
                captureFailures.get(),
                capacity.parts(),
                capacity.bytes(),
                duplicateMiddlewareDetections.get(),
                flushFailures.get(),
                shutdownFailures.get(),
                activeInvocations,
                waitingInvocations);
    }

    private static void add(AtomicLong counter, long delta, String name) {
        requireNonNegative(delta, name);
        counter.getAndUpdate(current -> saturatingAdd(current, delta));
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private record CapacityDrops(long parts, long bytes) {}
}
