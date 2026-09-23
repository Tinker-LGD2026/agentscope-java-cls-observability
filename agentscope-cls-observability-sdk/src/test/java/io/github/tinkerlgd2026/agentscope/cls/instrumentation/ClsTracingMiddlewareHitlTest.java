package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AllToolsDeniedEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
import io.github.tinkerlgd2026.agentscope.cls.ClsInvocationContext;
import io.github.tinkerlgd2026.agentscope.cls.ClsResumeContext;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentSanitizer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ClsTracingMiddlewareHitlTest {
    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private ClsTracingMiddleware middleware;
    private Agent agent;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider =
                SdkTracerProvider.builder()
                        .setResource(
                                Resource.builder()
                                        .put("service.name", "test-service")
                                        .put("host.name", "test-host")
                                        .build())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        middleware =
                new ClsTracingMiddleware(
                        provider.get("test"),
                        new ContentSanitizer(
                                new ObjectMapper(), ContentCaptureMode.TRUNCATE, 32 * 1024),
                        new AtomicBoolean(true)::get,
                        new io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters(),
                        new ObjectMapper(),
                        false,
                        Duration.ofMillis(200));
        agent = mock(Agent.class);
        when(agent.getName()).thenReturn("assistant");
        when(agent.getAgentId()).thenReturn("agent-1");
    }

    @AfterEach
    void tearDown() {
        middleware.close();
        provider.close();
    }

    @Test
    void confirmedWaitRecordsHitlMetricsAndNormalCompletion() {
        run(Flux.just(
                new RequireUserConfirmEvent("reply-1", toolCalls()),
                new UserConfirmResultEvent(
                        "reply-1",
                        List.of(new ConfirmResult(true, toolCalls().get(0)))),
                resultEvent()));

        SpanData entry = entrySpan();
        assertThat(entry.getAttributes().get(AttributeKey.longKey("gen_ai.hitl.wait_count")))
                .isEqualTo(1L);
        assertThat(entry.getAttributes().get(AttributeKey.longKey("gen_ai.hitl.total_wait_ms")))
                .isNotNull();
        assertThat(entry.getAttributes().get(AttributeKey.booleanKey("gen_ai.turn.completed")))
                .isTrue();
        assertThat(
                        entry.getAttributes()
                                .get(AttributeKey.stringKey("gen_ai.turn.finish_reason")))
                .isEqualTo("normal");
        assertThat(entry.getAttributes().get(AttributeKey.booleanKey("gen_ai.incomplete")))
                .isFalse();
        assertThat(entry.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    @Test
    void waitTimeoutEndsTurnWithAwaitTimeoutAndUnsetStatus() {
        // The stream stays open while waiting for the user; the 200ms wait timeout ends it.
        middleware
                .onAgent(
                        agent,
                        validContext(),
                        new AgentInput(List.of()),
                        ignored ->
                                Flux.<AgentEvent>just(
                                                new RequireUserConfirmEvent(
                                                        "reply-1", toolCalls()))
                                        .concatWith(Flux.never()))
                .subscribe();

        SpanData entry = entrySpan();
        assertThat(
                        entry.getAttributes()
                                .get(AttributeKey.stringKey("gen_ai.turn.finish_reason")))
                .isEqualTo("await_timeout");
        assertThat(entry.getAttributes().get(AttributeKey.booleanKey("gen_ai.turn.completed")))
                .isFalse();
        assertThat(entry.getAttributes().get(AttributeKey.booleanKey("gen_ai.incomplete")))
                .isTrue();
        assertThat(entry.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(entry.getStatus().getDescription()).isEmpty();
    }

    @Test
    void deniedToolsWithResultIsControlledOkButIncomplete() {
        run(Flux.just(
                new AllToolsDeniedEvent(toolCalls()),
                resultEvent()));

        SpanData entry = entrySpan();
        assertThat(
                        entry.getAttributes()
                                .get(AttributeKey.stringKey("gen_ai.turn.finish_reason")))
                .isEqualTo("denied");
        assertThat(entry.getAttributes().get(AttributeKey.booleanKey("gen_ai.turn.completed")))
                .isTrue();
        assertThat(entry.getAttributes().get(AttributeKey.booleanKey("gen_ai.incomplete")))
                .isTrue();
        assertThat(entry.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(entry.getEvents()).isEmpty();
    }

    @Test
    void resumeContextLinksNewTurnToPreviousTurnId() {
        RuntimeContext context =
                RuntimeContext.builder()
                        .sessionId("session-resume")
                        .userId("user-1")
                        .put(
                                ClsInvocationContext.class,
                                new ClsInvocationContext(
                                        "User One", null, "agentscope-java", "java-sdk"))
                        .put(ClsResumeContext.class, new ClsResumeContext("turn-old"))
                        .build();

        middleware
                .onAgent(
                        agent,
                        context,
                        new AgentInput(List.of()),
                        ignored -> Flux.just(resultEvent()))
                .collectList()
                .block(Duration.ofSeconds(5));

        SpanData entry = entrySpan();
        assertThat(
                        entry.getAttributes()
                                .get(
                                        AttributeKey.stringKey(
                                                "gen_ai.turn.resume_from_turn_id")))
                .isEqualTo("turn-old");
        assertThat(
                        entry.getAttributes()
                                .get(AttributeKey.stringKey("gen_ai.turn.finish_reason")))
                .isEqualTo("normal");
    }

    @Test
    void exceedMaxItersRoutesToControlledOutcome() {
        run(Flux.just(
                new io.agentscope.core.event.ExceedMaxItersEvent("reply-1", 10, 10),
                resultEvent()));

        SpanData entry = entrySpan();
        assertThat(
                        entry.getAttributes()
                                .get(AttributeKey.stringKey("gen_ai.turn.finish_reason")))
                .isEqualTo("max_iters");
        assertThat(entry.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(entry.getAttributes().get(AttributeKey.booleanKey("gen_ai.incomplete")))
                .isTrue();
    }

    @Test
    void requestStopRoutesToInterruptedOutcome() {
        run(Flux.just(
                new io.agentscope.core.event.RequestStopEvent("reply-1"),
                resultEvent()));

        SpanData entry = entrySpan();
        assertThat(
                        entry.getAttributes()
                                .get(AttributeKey.stringKey("gen_ai.turn.finish_reason")))
                .isEqualTo("interrupted");
    }

    @Test
    void lateResultAfterTimeoutDoesNotReviveOldSpansAndTurnStaysAwaitTimeout() {
        reactor.core.publisher.Sinks.Many<AgentEvent> sink =
                reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        middleware
                .onAgent(agent, validContext(), new AgentInput(List.of()), ignored -> sink.asFlux())
                .subscribe();
        sink.tryEmitNext(new RequireUserConfirmEvent("reply-1", toolCalls()));

        // Wait for the 200ms HITL timeout to terminate the generation.
        SpanData entry = entrySpan();
        assertThat(
                        entry.getAttributes()
                                .get(AttributeKey.stringKey("gen_ai.turn.finish_reason")))
                .isEqualTo("await_timeout");

        // Late result arrives after termination: consumed without reviving old spans.
        sink.tryEmitNext(
                new UserConfirmResultEvent(
                        "reply-1", List.of(new ConfirmResult(true, toolCalls().get(0)))));
        sink.tryEmitNext(resultEvent());
        sink.tryEmitComplete();

        long finished = exporter.getFinishedSpanItems().size();
        assertThat(
                        exporter.getFinishedSpanItems().stream()
                                .filter(
                                        span ->
                                                "entry"
                                                        .equals(
                                                                span.getAttributes()
                                                                        .get(
                                                                                AttributeKey
                                                                                        .stringKey(
                                                                                "gen_ai.span.kind"))))
                                .count())
                .isEqualTo(1);
        assertThat(finished).isGreaterThan(0);
    }

    private void run(Flux<AgentEvent> flow) {
        middleware
                .onAgent(agent, validContext(), new AgentInput(List.of()), ignored -> flow)
                .collectList()
                .block(Duration.ofSeconds(10));
    }

    private SpanData entrySpan() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<SpanData> finished = exporter.getFinishedSpanItems();
            for (SpanData span : finished) {
                if ("entry".equals(
                        span.getAttributes()
                                .get(AttributeKey.stringKey("gen_ai.span.kind")))) {
                    return span;
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        org.assertj.core.api.Assertions.fail("entry span was not finished in time");
        return null;
    }

    private static AgentResultEvent resultEvent() {
        return new AgentResultEvent(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("done").build()))
                        .build());
    }

    private static List<ToolUseBlock> toolCalls() {
        return List.of(
                ToolUseBlock.builder()
                        .id("call-1")
                        .name("search")
                        .input(java.util.Map.of("q", "x"))
                        .build());
    }

    private static RuntimeContext validContext() {
        return RuntimeContext.builder()
                .sessionId("session-hitl")
                .userId("user-1")
                .put(
                        ClsInvocationContext.class,
                        new ClsInvocationContext(
                                "User One", null, "agentscope-java", "java-sdk"))
                .build();
    }
}
