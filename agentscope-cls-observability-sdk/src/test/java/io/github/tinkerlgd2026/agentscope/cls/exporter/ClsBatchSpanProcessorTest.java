package io.github.tinkerlgd2026.agentscope.cls.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tinkerlgd2026.agentscope.cls.internal.CaptureMemoryPool;
import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanEncoder;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanValidator;
import io.github.tinkerlgd2026.agentscope.cls.transport.InMemorySpanSink;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ClsBatchSpanProcessorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration SCHEDULE_DELAY = Duration.ofMinutes(5);

    @Test
    void smallSpanExportsOnTimeTriggerOnly() {
        Fixture fixture = new Fixture(256, Long.MAX_VALUE, 1 << 20);
        fixture.endSpan("one");
        assertThat(fixture.sink.records()).isEmpty();

        fixture.scheduler.advance(SCHEDULE_DELAY);

        assertThat(fixture.sink.records()).hasSize(1);
        assertThat(fixture.counters.snapshot().acceptedSpans()).isEqualTo(1);
        assertThat(fixture.pool.usedBytes()).isZero();
    }

    @Test
    void countThresholdTriggersImmediateExport() {
        Fixture fixture = new Fixture(2, Long.MAX_VALUE, 1 << 20);
        fixture.endSpan("one");
        assertThat(fixture.sink.records()).isEmpty();

        fixture.endSpan("two");
        fixture.scheduler.runPending();

        assertThat(fixture.sink.records()).hasSize(2);
    }

    @Test
    void byteThresholdTriggersImmediateExport() {
        Fixture fixture = new Fixture(256, 1, 1 << 20);
        fixture.endSpan("one");
        fixture.scheduler.runPending();

        assertThat(fixture.sink.records()).hasSize(1);
    }

    @Test
    void forceFlushDrainsImmediatelyWithoutScheduler() {
        Fixture fixture = new Fixture(256, Long.MAX_VALUE, 1 << 20);
        fixture.endSpan("one");

        assertThat(fixture.processor.forceFlush().isSuccess()).isTrue();

        assertThat(fixture.sink.records()).hasSize(1);
        assertThat(fixture.pool.usedBytes()).isZero();
    }

    @Test
    void queueOverflowCountsDroppedAndCapacity() {
        Fixture fixture = new Fixture(256, Long.MAX_VALUE, 1 << 20, 1);
        fixture.endSpan("one");
        fixture.endSpan("two");

        assertThat(fixture.queue.size()).isEqualTo(1);
        assertThat(fixture.counters.snapshot().droppedSpans()).isEqualTo(1);
        assertThat(fixture.counters.detailedSnapshot(0, 0).capacityDroppedParts()).isEqualTo(1);
    }

    @Test
    void encoderPermitExhaustionFailsClosed() {
        Fixture fixture = new Fixture(256, Long.MAX_VALUE, 1 << 20, 16, 0);
        fixture.endSpan("one");

        assertThat(fixture.queue.size()).isZero();
        assertThat(fixture.counters.snapshot().droppedSpans()).isEqualTo(1);
        assertThat(fixture.counters.detailedSnapshot(0, 0).capacityDroppedParts()).isEqualTo(1);
    }

    @Test
    void invalidSpanIsRejectedWithoutBlockingValidSibling() {
        Fixture fixture = new Fixture(256, Long.MAX_VALUE, 1 << 20);
        fixture.endInvalidSpan();
        fixture.endSpan("valid");
        fixture.scheduler.advance(SCHEDULE_DELAY);

        assertThat(fixture.sink.records()).hasSize(1);
        assertThat(fixture.counters.snapshot().invalidSpans()).isEqualTo(1);
        assertThat(fixture.counters.snapshot().acceptedSpans()).isEqualTo(1);
    }

    @Test
    void shutdownDrainsReleasesAndIgnoresLateSpans() {
        Fixture fixture = new Fixture(256, Long.MAX_VALUE, 1 << 20);
        fixture.endSpan("one");

        assertThat(fixture.processor.shutdown().isSuccess()).isTrue();

        assertThat(fixture.sink.records()).hasSize(1);
        assertThat(fixture.pool.usedBytes()).isZero();
        assertThat(fixture.sink.closed()).isTrue();

        fixture.endSpan("late");
        assertThat(fixture.sink.records()).hasSize(1);
        assertThat(fixture.counters.snapshot().droppedSpans()).isEqualTo(1);
    }

    private static final class Fixture {
        private final TelemetryCounters counters = new TelemetryCounters();
        private final CaptureMemoryPool pool;
        private final EncodedSpanQueue queue;
        private final InMemorySpanSink sink = new InMemorySpanSink();
        private final ManualScheduledExecutorService scheduler =
                ManualScheduledExecutorService.create();
        private final ClsBatchSpanProcessor processor;
        private final SdkTracerProvider provider;
        private final Tracer tracer;

        private Fixture(int maxBatchCount, long maxBatchBytes, long poolBytes) {
            this(maxBatchCount, maxBatchBytes, poolBytes, 4096, 2);
        }

        private Fixture(int maxBatchCount, long maxBatchBytes, long poolBytes, int maxRecords) {
            this(maxBatchCount, maxBatchBytes, poolBytes, maxRecords, 2);
        }

        private Fixture(
                int maxBatchCount, long maxBatchBytes, long poolBytes, int maxRecords, int encoders) {
            pool = new CaptureMemoryPool(poolBytes);
            queue = new EncodedSpanQueue(pool, maxRecords);
            processor =
                    new ClsBatchSpanProcessor(
                            new ClsSpanEncoder(JSON),
                            new ClsSpanValidator(JSON),
                            queue,
                            new SpanRecordExporter(sink, counters),
                            counters,
                            SCHEDULE_DELAY,
                            maxBatchCount,
                            maxBatchBytes,
                            scheduler,
                            encoders);
            provider =
                    SdkTracerProvider.builder()
                            .setResource(
                                    Resource.builder()
                                            .put("service.name", "svc")
                                            .put("host.name", "host")
                                            .build())
                            .addSpanProcessor(processor)
                            .build();
            tracer = provider.get("test");
        }

        private void endSpan(String marker) {
            Span span =
                    tracer.spanBuilder("chat " + marker)
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

        private void endInvalidSpan() {
            Span span =
                    tracer.spanBuilder("chat invalid")
                            .setAttribute("gen_ai.span.kind", "chat")
                            .setAttribute("gen_ai.operation.name", "chat")
                            .setAttribute("gen_ai.agent.type", "agentscope-java")
                            .setAttribute("gen_ai.session.id", "s")
                            .setAttribute("gen_ai.turn.id", "t")
                            .setAttribute("gen_ai.user.id", "u")
                            .startSpan();
            span.end();
        }
    }
}
