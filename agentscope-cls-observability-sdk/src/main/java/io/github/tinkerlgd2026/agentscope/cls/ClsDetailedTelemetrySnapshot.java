package io.github.tinkerlgd2026.agentscope.cls;

/** Detailed bounded-observability health counters introduced in 0.3. */
public record ClsDetailedTelemetrySnapshot(
        long acceptedSpans,
        long invalidSpans,
        long droppedSpans,
        long exportFailedSpans,
        long exportFailedBatches,
        long captureFailures,
        long capacityDroppedParts,
        long capacityDroppedBytes,
        long duplicateMiddlewareDetections,
        long flushFailures,
        long shutdownFailures,
        long activeInvocations,
        long waitingInvocations) {}
