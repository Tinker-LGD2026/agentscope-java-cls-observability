package io.github.tinkerlgd2026.agentscope.cls;

/**
 * A content-free health snapshot of the observability pipeline.
 *
 * @param acceptedSpans spans acknowledged by the configured sink
 * @param invalidSpans completed spans rejected by the CLS schema validator
 * @param exportFailures spans or lifecycle operations that failed during export
 * @param droppedSpans spans skipped by SDK lifecycle, identity, or instrumentation safeguards;
 *     OpenTelemetry {@code BatchSpanProcessor} queue overflow is reported by OTel internal telemetry
 *     and is not included because its queue does not expose a public drop callback
 */
public record ClsTelemetrySnapshot(
        long acceptedSpans, long invalidSpans, long exportFailures, long droppedSpans) {}
