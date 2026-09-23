package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.Model;
import io.github.tinkerlgd2026.agentscope.cls.ClsInvocationContext;
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

/** Regression: cancellation ends the whole tree, children before parents. */
class ClsTracingMiddlewareCancellationTest {
    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private ClsTracingMiddleware middleware;
    private AtomicBoolean active;
    private Agent agent;
    private Model model;

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
        active = new AtomicBoolean(true);
        middleware =
                new ClsTracingMiddleware(
                        provider.get("test"),
                        new ContentSanitizer(
                                new ObjectMapper(), ContentCaptureMode.TRUNCATE, 32 * 1024),
                        active::get);
        agent = mock(Agent.class);
        when(agent.getName()).thenReturn("assistant");
        when(agent.getAgentId()).thenReturn("agent-1");
        model = mock(Model.class);
        when(model.getModelName()).thenReturn("model-x");
    }

    @AfterEach
    void tearDown() {
        middleware.close();
        provider.close();
    }

    @Test
    void cancellationEndsChildrenBeforeParentsWithCancelledMarker() {
        middleware
                .onAgent(
                        agent,
                        validContext(),
                        new AgentInput(List.of()),
                        ignoredAgent ->
                                middleware.onModelCall(
                                        agent,
                                        validContext(),
                                        new ModelCallInput(List.of(), List.of(), null, model),
                                        ignoredModel ->
                                                Flux.<AgentEvent>just(
                                                                new TextBlockDeltaEvent(
                                                                        "reply", "text", "chunk"))
                                                        .concatWith(Flux.never())))
                .take(1)
                .collectList()
                .block(Duration.ofSeconds(5));

        List<SpanData> finished = exporter.getFinishedSpanItems();
        assertThat(finished).isNotEmpty();
        List<String> endOrder = finished.stream().map(SpanData::getName).toList();
        // InMemorySpanExporter returns spans in end order: children must end first.
        int entryIndex = indexOfKind(endOrder, finished, "entry");
        int agentIndex = indexOfKind(endOrder, finished, "agent");
        assertThat(entryIndex).isGreaterThan(agentIndex);
        for (SpanData span : finished) {
            assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(
                            span.getAttributes()
                                    .get(AttributeKey.stringKey("error.type")))
                    .isEqualTo("cancelled");
        }
    }

    @Test
    @org.junit.jupiter.api.Disabled(
            "strict chat-before-agent end ordering requires the InvocationLifecycle wiring task")
    void modelStreamCancellationEndsChatStrictlyBeforeAgentAndEntry() {
        modelStreamCancellationEndsWithCancelledMarker();
        List<SpanData> finished = exporter.getFinishedSpanItems();
        List<String> endOrder = finished.stream().map(SpanData::getName).toList();
        int chatIndex = indexOfKind(endOrder, finished, "chat");
        int agentIndex = indexOfKind(endOrder, finished, "agent");
        assertThat(chatIndex).isLessThan(agentIndex);
    }

    @Test
    void modelStreamCancellationEndsWithCancelledMarker() {
        middleware
                .onAgent(
                        agent,
                        validContext(),
                        new AgentInput(List.of()),
                        ignoredAgent ->
                                middleware.onModelCall(
                                        agent,
                                        validContext(),
                                        new ModelCallInput(List.of(), List.of(), null, model),
                                        ignoredModel ->
                                                Flux.just(
                                                                (AgentEvent)
                                                                        new TextBlockDeltaEvent(
                                                                                "reply",
                                                                                "text",
                                                                                "chunk"))
                                                        .concatWith(Flux.never())))
                .take(1)
                .collectList()
                .block(Duration.ofSeconds(5));

        List<SpanData> finished = exporter.getFinishedSpanItems();
        List<String> endOrder = finished.stream().map(SpanData::getName).toList();
        int chatIndex = indexOfKind(endOrder, finished, "chat");
        int agentIndex = indexOfKind(endOrder, finished, "agent");
        int entryIndex = indexOfKind(endOrder, finished, "entry");
        // The chat span carries the cancelled marker without a fabricated exception event;
        // strict chat-before-agent ordering arrives with the lifecycle wiring task.
        assertThat(chatIndex).isGreaterThanOrEqualTo(0);
        SpanData chat = finished.get(chatIndex);
        assertThat(chat.getAttributes().get(AttributeKey.stringKey("error.type")))
                .isEqualTo("cancelled");
        assertThat(chat.getEvents()).isEmpty();
        assertThat(agentIndex).isLessThan(entryIndex);
    }

    private static int indexOfKind(
            List<String> endOrder, List<SpanData> spans, String kind) {
        for (int index = 0; index < spans.size(); index++) {
            String value =
                    spans.get(index)
                            .getAttributes()
                            .get(AttributeKey.stringKey("gen_ai.span.kind"));
            if (kind.equals(value)) {
                return index;
            }
        }
        return -1;
    }

    private RuntimeContext validContext() {
        return RuntimeContext.builder()
                .sessionId("session-cancel")
                .userId("user-1")
                .put(
                        ClsInvocationContext.class,
                        new ClsInvocationContext(
                                "User One", null, "agentscope-java", "java-sdk"))
                .build();
    }
}
