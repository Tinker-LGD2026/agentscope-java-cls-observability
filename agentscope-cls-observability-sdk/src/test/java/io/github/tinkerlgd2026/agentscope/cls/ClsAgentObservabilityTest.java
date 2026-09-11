package io.github.tinkerlgd2026.agentscope.cls;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink;
import io.opentelemetry.api.GlobalOpenTelemetry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
    void productionAssemblyAppliesReasoningPrivacyToChatAgentAndEntrySpans() throws Exception {
        RecordingSink sink = new RecordingSink();
        ClsObservabilityConfig config =
                ClsObservabilityConfig.builder()
                        .contentCaptureMode(ContentCaptureMode.TRUNCATE)
                        .reasoningCaptureMode(ContentCaptureMode.OFF)
                        .build();
        ClsAgentObservability observability = ClsAgentObservability.create(config, sink);
        Agent agent = org.mockito.Mockito.mock(Agent.class);
        Model model = org.mockito.Mockito.mock(Model.class);
        org.mockito.Mockito.when(agent.getName()).thenReturn("assistant");
        org.mockito.Mockito.when(agent.getAgentId()).thenReturn("agent-1");
        org.mockito.Mockito.when(model.getModelName()).thenReturn("generic-model");
        RuntimeContext context =
                RuntimeContext.builder().sessionId("session-1").userId("user-1").build();
        Msg input =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(
                                ThinkingBlock.builder().thinking("input-secret").build(),
                                TextBlock.builder().text("input-visible").build()))
                        .build();
        Msg result =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(
                                ThinkingBlock.builder().thinking("result-secret").build(),
                                TextBlock.builder().text("result-visible").build()))
                        .build();

        observability
                .middleware()
                .onAgent(
                        agent,
                        context,
                        new AgentInput(List.of(input)),
                        ignoredAgent ->
                                observability.middleware().onModelCall(
                                        agent,
                                        context,
                                        new ModelCallInput(List.of(input), List.of(), null, model),
                                        ignoredModel ->
                                                reactor.core.publisher.Flux.just(
                                                        new ThinkingBlockDeltaEvent(
                                                                "reply", "think", "chat-secret"),
                                                        new TextBlockDeltaEvent(
                                                                "reply", "text", "chat-visible"),
                                                        new AgentResultEvent(
                                                                "session-1", "reply", result))))
                .collectList()
                .block(Duration.ofSeconds(5));
        assertThat(observability.flush(Duration.ofSeconds(2))).isTrue();
        observability.close();

        ObjectMapper json = new ObjectMapper();
        Map<String, ClsSpanRecord> byKind =
                sink.records.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        record -> {
                                            try {
                                                return json.readTree(record.attribute())
                                                        .path("gen_ai.span.kind")
                                                        .asText();
                                            } catch (Exception exception) {
                                                throw new IllegalArgumentException(exception);
                                            }
                                        },
                                        record -> record,
                                        (first, ignored) -> first));
        assertThat(byKind).containsKeys("entry", "agent", "chat");
        assertThat(byKind.get("entry").attribute())
                .contains("input-visible", "result-visible")
                .doesNotContain("input-secret", "result-secret", "chat-secret");
        assertThat(byKind.get("agent").attribute())
                .contains("input-visible", "result-visible")
                .doesNotContain("input-secret", "result-secret", "chat-secret");
        assertThat(byKind.get("chat").attribute())
                .contains("input-visible", "chat-visible")
                .doesNotContain("input-secret", "result-secret", "chat-secret");
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
        private final List<ClsSpanRecord> records = new CopyOnWriteArrayList<>();
        private int closeCalls;

        @Override
        public CompletionStage<Void> export(List<ClsSpanRecord> records) {
            this.records.addAll(records);
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
