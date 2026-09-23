package io.github.tinkerlgd2026.agentscope.cls.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinkerlgd2026.agentscope.cls.internal.CaptureMemoryPool;
import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.transport.InMemorySpanSink;
import io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class SpanRecordExporterTest {

    @Test
    void successReleasesReservationAndCountsAccepted() {
        CaptureMemoryPool pool = new CaptureMemoryPool(1 << 20);
        EncodedSpanQueue queue = new EncodedSpanQueue(pool, 8);
        queue.offer(record("a"));
        TelemetryCounters counters = new TelemetryCounters();
        SpanRecordExporter exporter = new SpanRecordExporter(new InMemorySpanSink(), counters);

        EncodedSpanQueue.Batch batch = queue.pollBatch(8, Long.MAX_VALUE);
        Boolean result = exporter.export(batch).toCompletableFuture().join();

        assertThat(result).isTrue();
        assertThat(counters.snapshot().acceptedSpans()).isEqualTo(1);
        assertThat(pool.usedBytes()).isZero();
    }

    @Test
    void failureReleasesReservationAndCountsExportFailure() {
        CaptureMemoryPool pool = new CaptureMemoryPool(1 << 20);
        EncodedSpanQueue queue = new EncodedSpanQueue(pool, 8);
        queue.offer(record("a"));
        TelemetryCounters counters = new TelemetryCounters();
        SpanSink failing =
                new SpanSink() {
                    @Override
                    public CompletionStage<Void> export(List<ClsSpanRecord> records) {
                        return CompletableFuture.failedFuture(new IllegalStateException("boom"));
                    }

                    @Override
                    public CompletionStage<Boolean> flush(Duration timeout) {
                        return CompletableFuture.completedFuture(true);
                    }

                    @Override
                    public void close() {}
                };
        SpanRecordExporter exporter = new SpanRecordExporter(failing, counters);

        Boolean result =
                exporter.export(queue.pollBatch(8, Long.MAX_VALUE)).toCompletableFuture().join();

        assertThat(result).isFalse();
        assertThat(counters.snapshot().exportFailures()).isEqualTo(1);
        assertThat(pool.usedBytes()).isZero();
    }

    @Test
    void thrownSinkExceptionStillReleasesReservation() {
        CaptureMemoryPool pool = new CaptureMemoryPool(1 << 20);
        EncodedSpanQueue queue = new EncodedSpanQueue(pool, 8);
        queue.offer(record("a"));
        TelemetryCounters counters = new TelemetryCounters();
        SpanSink throwing =
                new SpanSink() {
                    @Override
                    public CompletionStage<Void> export(List<ClsSpanRecord> records) {
                        throw new IllegalStateException("boom");
                    }

                    @Override
                    public CompletionStage<Boolean> flush(Duration timeout) {
                        return CompletableFuture.completedFuture(true);
                    }

                    @Override
                    public void close() {}
                };
        SpanRecordExporter exporter = new SpanRecordExporter(throwing, counters);

        Boolean result =
                exporter.export(queue.pollBatch(8, Long.MAX_VALUE)).toCompletableFuture().join();

        assertThat(result).isFalse();
        assertThat(counters.snapshot().exportFailures()).isEqualTo(1);
        assertThat(pool.usedBytes()).isZero();
    }

    private static ClsSpanRecord record(String marker) {
        return new ClsSpanRecord(
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                "",
                "chat " + marker,
                "client",
                "100",
                "200",
                "100",
                "OK",
                "",
                "{\"gen_ai.span.kind\":\"chat\"}",
                "{\"service.name\":\"svc\",\"host.name\":\"host\"}",
                "",
                "[]",
                "[]");
    }
}
