package io.github.tinkerlgd2026.agentscope.cls.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tinkerlgd2026.agentscope.cls.exporter.ClsBatchSpanProcessor;
import io.github.tinkerlgd2026.agentscope.cls.exporter.EncodedSpanQueue;
import io.github.tinkerlgd2026.agentscope.cls.exporter.SpanRecordExporter;
import io.github.tinkerlgd2026.agentscope.cls.internal.CaptureMemoryPool;
import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanEncoder;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanValidator;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Custom SpanSink 0.2 contract: at most 256 records per call, never Tencent slices. */
class CustomSpanSinkContractTest {

    @Test
    void customSinkReceivesAtMost256RecordsPerExportCall() {
        RecordingSink sink = new RecordingSink();
        TelemetryCounters counters = new TelemetryCounters();
        ClsBatchSpanProcessor processor =
                new ClsBatchSpanProcessor(
                        new ClsSpanEncoder(new ObjectMapper()),
                        new ClsSpanValidator(new ObjectMapper()),
                        new EncodedSpanQueue(new CaptureMemoryPool(64 << 20), 4096),
                        new SpanRecordExporter(sink, counters),
                        counters,
                        Duration.ofHours(1),
                        64,
                        Long.MAX_VALUE,
                        Executors.newSingleThreadScheduledExecutor());
        SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(
                                Resource.builder()
                                        .put("service.name", "svc")
                                        .put("host.name", "host")
                                        .build())
                        .addSpanProcessor(processor)
                        .build();
        var tracer = provider.get("test");
        for (int index = 0; index < 300; index++) {
            Span span =
                    tracer.spanBuilder("chat " + index)
                            .setAttribute("gen_ai.span.kind", "chat")
                            .setAttribute("gen_ai.operation.name", "chat")
                            .setAttribute("gen_ai.agent.type", "agentscope-java")
                            .setAttribute("gen_ai.session.id", "s")
                            .setAttribute("gen_ai.turn.id", "t")
                            .setAttribute("gen_ai.user.id", "u")
                            .setAttribute("gen_ai.user.name", "U")
                            .startSpan();
            span.end();
        }

        assertThat(processor.forceFlush().isSuccess()).isTrue();

        assertThat(sink.batchSizes).isNotEmpty();
        assertThat(sink.batchSizes).allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(256));
        assertThat(sink.batchSizes.stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(counters.snapshot().acceptedSpans());
        assertThat(processor.shutdown().isSuccess()).isTrue();
    }

    private static final class RecordingSink implements SpanSink {
        private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<Void> export(List<ClsSpanRecord> records) {
            batchSizes.add(records.size());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Boolean> flush(Duration timeout) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void close() {}
    }
}
