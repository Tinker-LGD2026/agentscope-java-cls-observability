package io.github.tinkerlgd2026.agentscope.cls.exporter;

import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends drained record batches to the SpanSink and releases reservations on completion. */
public final class SpanRecordExporter implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpanRecordExporter.class);

    private final SpanSink sink;
    private final TelemetryCounters counters;

    public SpanRecordExporter(SpanSink sink, TelemetryCounters counters) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.counters = Objects.requireNonNull(counters, "counters");
    }

    public CompletionStage<Boolean> export(EncodedSpanQueue.Batch batch) {
        Objects.requireNonNull(batch, "batch");
        CompletionStage<Void> stage;
        try {
            stage = sink.export(batch.records());
        } catch (RuntimeException exception) {
            batch.close();
            counters.exportFailed(batch.records().size());
            LOGGER.warn("CLS span export failed: {}", exception.getClass().getSimpleName());
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        stage.whenComplete(
                (ignored, error) -> {
                    batch.close();
                    if (error == null) {
                        counters.accepted(batch.records().size());
                        result.complete(true);
                    } else {
                        counters.exportFailed(batch.records().size());
                        LOGGER.warn(
                                "CLS span export failed: {}",
                                error.getClass().getSimpleName());
                        result.complete(false);
                    }
                });
        return result;
    }

    public CompletionStage<Boolean> flush(Duration timeout) {
        try {
            return sink.flush(timeout);
        } catch (RuntimeException exception) {
            LOGGER.warn("CLS span flush failed: {}", exception.getClass().getSimpleName());
            return CompletableFuture.completedFuture(false);
        }
    }

    @Override
    public void close() {
        try {
            sink.close();
        } catch (RuntimeException exception) {
            counters.exportFailed(1);
            LOGGER.warn("CLS span sink close failed: {}", exception.getClass().getSimpleName());
        }
    }
}
