package io.github.tinkerlgd2026.agentscope.cls;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ClsAgentObservabilityTest {

    @Test
    void ownsResourcesWithoutReplacingGlobalOpenTelemetry() {
        Object globalBefore = GlobalOpenTelemetry.get();
        RecordingSink sink = new RecordingSink();
        ClsAgentObservability observability =
                ClsAgentObservability.create(ClsObservabilityConfig.builder().build(), sink);

        assertThat(observability.middleware()).isNotNull();
        assertThat(GlobalOpenTelemetry.get()).isSameAs(globalBefore);
        assertThat(observability.flush(Duration.ofSeconds(1))).isTrue();
        assertThat(observability.snapshot())
                .isEqualTo(new ClsTelemetrySnapshot(0, 0, 0, 0));

        observability.close();
        observability.close();
        assertThat(sink.closeCalls).isEqualTo(1);
    }

    @Test
    void flushReturnsFalseInsteadOfPropagatingTelemetryFailures() {
        ClsAgentObservability observability =
                ClsAgentObservability.create(
                        ClsObservabilityConfig.builder().build(), new FailingFlushSink());

        assertThat(observability.flush(Duration.ofMillis(100))).isFalse();
        observability.close();
    }

    @Test
    void flushTimeoutAlsoBoundsBlockingCustomSink() throws Exception {
        BlockingFlushSink sink = new BlockingFlushSink();
        ClsAgentObservability observability =
                ClsAgentObservability.create(ClsObservabilityConfig.builder().build(), sink);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> observability.flush(Duration.ofMillis(50)));
            assertThat(result.get(500, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            sink.release.countDown();
            observability.close();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCloseDoesNotWaitForLongRunningFlushTimeout() throws Exception {
        BlockingFlushSink sink = new BlockingFlushSink();
        ClsAgentObservability observability =
                ClsAgentObservability.create(ClsObservabilityConfig.builder().build(), sink);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var flushing = executor.submit(() -> observability.flush(Duration.ofMinutes(5)));
            assertThat(sink.entered.await(1, TimeUnit.SECONDS)).isTrue();
            var closing = executor.submit(observability::close);

            closing.get(1, TimeUnit.SECONDS);
            assertThat(flushing.get(1, TimeUnit.SECONDS)).isFalse();
        } finally {
            sink.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsUnboundedFlushTimeouts() {
        ClsAgentObservability observability =
                ClsAgentObservability.create(
                        ClsObservabilityConfig.builder().build(), new RecordingSink());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> observability.flush(Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10 minutes");
        observability.close();
    }

    @Test
    void closeNeverPropagatesTelemetryFailuresToHost() {
        ClsAgentObservability observability =
                ClsAgentObservability.create(
                        ClsObservabilityConfig.builder().build(), new FailingSink());

        observability.close();
        observability.close();

        assertThat(observability.snapshot().exportFailures()).isGreaterThan(0);
    }

    private static final class BlockingFlushSink implements SpanSink {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public CompletionStage<Void> export(List<ClsSpanRecord> records) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Boolean> flush(Duration timeout) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return CompletableFuture.completedFuture(false);
            }
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void close() {
            release.countDown();
        }
    }

    private static final class FailingFlushSink implements SpanSink {

        @Override
        public CompletionStage<Void> export(List<ClsSpanRecord> records) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Boolean> flush(Duration timeout) {
            throw new IllegalStateException("sink flush failed");
        }

        @Override
        public void close() {}
    }

    private static final class FailingSink implements SpanSink {

        @Override
        public CompletionStage<Void> export(List<ClsSpanRecord> records) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Boolean> flush(Duration timeout) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void close() {
            throw new IllegalStateException("sink close failed");
        }
    }

    private static final class RecordingSink implements SpanSink {
        private int closeCalls;

        @Override
        public CompletionStage<Void> export(List<ClsSpanRecord> records) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Boolean> flush(Duration timeout) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
