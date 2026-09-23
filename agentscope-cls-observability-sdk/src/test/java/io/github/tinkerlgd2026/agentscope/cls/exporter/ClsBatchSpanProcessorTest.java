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
        assertThat(fixture.records()).isEmpty();

        fixture.scheduler.advance(SCHEDULE_DELAY);

        assertThat(fixture.records()).hasSize(1);
        assertThat(fixture.counters.snapshot().acceptedSpans()).isEqualTo(1);
        assertThat(fixture.pool.usedBytes()).isZero();
        fixture.scheduler.assertNoTaskFailures();
    }

    @Test
    void countThresholdTriggersImmediateExport() {
        Fixture fixture = new Fixture(2, Long.MAX_VALUE, 1 << 20);
        fixture.endSpan("one");
        assertThat(fixture.records()).isEmpty();

        fixture.endSpan("two");
        fixture.scheduler.runPending();

        assertThat(fixture.records()).hasSize(2);
        fixture.scheduler.assertNoTaskFailures();
    }

    @Test
    void byteThresholdTriggersImmediateExport() {
        Fixture fixture = new Fixture(256, 1, 1 << 20);
        fixture.endSpan("one");
        fixture.scheduler.runPending();

        assertThat(fixture.records()).hasSize(1);
        fixture.scheduler.assertNoTaskFailures();
    }

    @Test
    void forceFlushDrainsImmediatelyWithoutScheduler() {
        Fixture fixture = new Fixture(256, Long.MAX_VALUE, 1 << 20);
        fixture.endSpan("one");

        assertThat(fixture.processor.forceFlush().isSuccess()).isTrue();

        assertThat(fixture.records()).hasSize(1);
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

        assertThat(fixture.records()).hasSize(1);
        assertThat(fixture.counters.snapshot().invalidSpans()).isEqualTo(1);
        assertThat(fixture.counters.snapshot().acceptedSpans()).isEqualTo(1);
        fixture.scheduler.assertNoTaskFailures();
    }

    @Test
    void shutdownDrainsReleasesAndIgnoresLateSpans() {
        Fixture fixture = new Fixture(256, Long.MAX_VALUE, 1 << 20);
        fixture.endSpan("one");

        assertThat(fixture.processor.shutdown().isSuccess()).isTrue();

        assertThat(fixture.records()).hasSize(1);
        assertThat(fixture.pool.usedBytes()).isZero();
        assertThat(fixture.sinkClosed()).isTrue();

        fixture.endSpan("late");
        assertThat(fixture.records()).hasSize(1);
        assertThat(fixture.counters.snapshot().droppedSpans()).isEqualTo(1);
    }

    @Test
    void hangingExportDoesNotBlockForceFlushBeyondTimeout() {
        Fixture fixture = new Fixture(256, Long.MAX_VALUE, 1 << 20, new HangingSink());
        fixture.endSpan("one");
        fixture.processor.prepareFlush(Duration.ofMillis(50));

        long started = System.nanoTime();
        boolean success = fixture.processor.forceFlush().isSuccess();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertThat(success).isFalse();
        assertThat(elapsedMillis).isLessThan(5_000L);
        // The abandoned batch reservation was released and counted as dropped.
        assertThat(fixture.pool.usedBytes()).isZero();
        assertThat(fixture.counters.snapshot().droppedSpans()).isEqualTo(1);
    }

    @Test
    void concurrentSpanEndsWithinPermitCapacityExportEverything() throws Exception {
        // Two workers match the two encoder permits: no fail-closed drops expected.
        ConcurrentRun run = runConcurrentSpans(2, 50);

        assertThat(run.processor().forceFlush().isSuccess()).isTrue();
        assertThat(run.counters().snapshot().droppedSpans()).isZero();
        assertThat(run.sink().records()).hasSize(100);
        assertThat(run.pool().usedBytes()).isZero();
        assertThat(run.processor().shutdown().isSuccess()).isTrue();
    }

    @Test
    void encoderContentionDropsAreCountedAndLeaveNoResidualMemory() throws Exception {
        // More workers than encoder permits: fail-closed drops are allowed but must be
        // accurately counted and leave no memory behind.
        ConcurrentRun run = runConcurrentSpans(4, 50);

        assertThat(run.processor().forceFlush().isSuccess()).isTrue();
        var snapshot = run.counters().snapshot();
        assertThat(snapshot.acceptedSpans() + snapshot.droppedSpans()).isEqualTo(200);
        assertThat(run.sink().records()).hasSize((int) snapshot.acceptedSpans());
        assertThat(run.pool().usedBytes()).isZero();
        assertThat(run.processor().shutdown().isSuccess()).isTrue();
    }

    private ConcurrentRun runConcurrentSpans(int threads, int spansPerThread) throws Exception {
        TelemetryCounters counters = new TelemetryCounters();
        CaptureMemoryPool pool = new CaptureMemoryPool(64 << 20);
        EncodedSpanQueue queue = new EncodedSpanQueue(pool, 4096);
        InMemorySpanSink sink = new InMemorySpanSink();
        java.util.concurrent.ScheduledExecutorService realScheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        ClsBatchSpanProcessor processor =
                new ClsBatchSpanProcessor(
                        new ClsSpanEncoder(JSON),
                        new ClsSpanValidator(JSON),
                        queue,
                        new SpanRecordExporter(sink, counters),
                        counters,
                        Duration.ofHours(1),
                        64,
                        Long.MAX_VALUE,
                        realScheduler);
        SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(
                                Resource.builder()
                                        .put("service.name", "svc")
                                        .put("host.name", "host")
                                        .build())
                        .addSpanProcessor(processor)
                        .build();
        Tracer tracer = provider.get("test");
        Thread[] workers = new Thread[threads];
        for (int index = 0; index < threads; index++) {
            int worker = index;
            workers[index] =
                    new Thread(
                            () -> {
                                for (int spanIndex = 0; spanIndex < spansPerThread; spanIndex++) {
                                    Span span =
                                            tracer.spanBuilder("chat w" + worker + "-" + spanIndex)
                                                    .setAttribute("gen_ai.span.kind", "chat")
                                                    .setAttribute("gen_ai.operation.name", "chat")
                                                    .setAttribute(
                                                            "gen_ai.agent.type", "agentscope-java")
                                                    .setAttribute("gen_ai.session.id", "s")
                                                    .setAttribute("gen_ai.turn.id", "t")
                                                    .setAttribute("gen_ai.user.id", "u")
                                                    .setAttribute("gen_ai.user.name", "U")
                                                    .startSpan();
                                    span.end();
                                }
                            });
        }
        for (Thread workerThread : workers) {
            workerThread.start();
        }
        for (Thread workerThread : workers) {
            workerThread.join();
        }
        return new ConcurrentRun(processor, counters, pool, sink);
    }

    private record ConcurrentRun(
            ClsBatchSpanProcessor processor,
            TelemetryCounters counters,
            CaptureMemoryPool pool,
            InMemorySpanSink sink) {}

    private static final class Fixture {
        private final TelemetryCounters counters = new TelemetryCounters();
        private final CaptureMemoryPool pool;
        private final EncodedSpanQueue queue;
        private final io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink sink;
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
                int maxBatchCount,
                long maxBatchBytes,
                long poolBytes,
                io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink customSink) {
            this(maxBatchCount, maxBatchBytes, poolBytes, 4096, 2, customSink);
        }

        private Fixture(
                int maxBatchCount, long maxBatchBytes, long poolBytes, int maxRecords, int encoders) {
            this(maxBatchCount, maxBatchBytes, poolBytes, maxRecords, encoders, new InMemorySpanSink());
        }

        private Fixture(
                int maxBatchCount,
                long maxBatchBytes,
                long poolBytes,
                int maxRecords,
                int encoders,
                io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink sink) {
            this.sink = sink;
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

        private java.util.List<io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord>
                records() {
            return ((InMemorySpanSink) sink).records();
        }

        private boolean sinkClosed() {
            return ((InMemorySpanSink) sink).closed();
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

    private static final class HangingSink
            implements io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink {
        @Override
        public java.util.concurrent.CompletionStage<Void> export(
                java.util.List<io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord>
                        records) {
            return new java.util.concurrent.CompletableFuture<>();
        }

        @Override
        public java.util.concurrent.CompletionStage<Boolean> flush(Duration timeout) {
            return java.util.concurrent.CompletableFuture.completedFuture(true);
        }

        @Override
        public void close() {}
    }
}
