package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode.OFF;
import static io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode.TRUNCATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.github.tinkerlgd2026.agentscope.cls.ClsInvocationContext;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentSanitizer;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsFields;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanEncoder;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanValidator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

class ClsTracingMiddlewareTest {
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
    void createsCompleteClsSpanTreeWithOneSpanPerToolCall() {
        RuntimeContext context = validContext("session-1");
        ToolUseBlock search =
                ToolUseBlock.builder()
                        .id("call-search")
                        .name("search")
                        .input(Map.of("query", "weather"))
                        .build();
        ToolUseBlock calculator =
                ToolUseBlock.builder()
                        .id("call-calc")
                        .name("calculator")
                        .input(Map.of("expression", "1+1"))
                        .build();
        ModelCallEndEvent modelEnd =
                new ModelCallEndEvent("reply-1", new ChatUsage(12, 5, 3, 0.2));
        ToolResultEndEvent searchEnd =
                new ToolResultEndEvent(
                        "reply-1", "call-search", "search", ToolResultState.SUCCESS);
        ToolResultEndEvent calculatorEnd =
                new ToolResultEndEvent(
                        "reply-1", "call-calc", "calculator", ToolResultState.ERROR);

        Flux<AgentEvent> flow =
                middleware.onAgent(
                        agent,
                        context,
                        new AgentInput(List.of()),
                        ignoredAgent ->
                                middleware
                                        .onReasoning(
                                                agent,
                                                context,
                                                new ReasoningInput(List.of(), List.of(), null),
                                                ignoredReasoning ->
                                                        middleware
                                                                .onModelCall(
                                                                        agent,
                                                                        context,
                                                                        new ModelCallInput(
                                                                                List.of(),
                                                                                List.of(),
                                                                                null,
                                                                                model),
                                                                        ignoredModel ->
                                                                        Flux.just(
                                                                                new ToolCallStartEvent(
                                                                                        "reply-1",
                                                                                        "call-search",
                                                                                        "search"),
                                                                                modelEnd))
                                                                .publishOn(Schedulers.parallel()))
                                        .thenMany(
                                                middleware.onActing(
                                                        agent,
                                                        context,
                                                        new ActingInput(List.of(search, calculator)),
                                                        ignoredActing ->
                                                                Flux.just(
                                                                        new ToolResultTextDeltaEvent(
                                                                                "reply-1",
                                                                                "call-search",
                                                                                "search",
                                                                                "sunny"),
                                                                        searchEnd,
                                                                        calculatorEnd))));

        flow.collectList().block(Duration.ofSeconds(5));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(6);
        SpanData entry = find(spans, "entry", null);
        SpanData agentSpan = find(spans, "agent", null);
        SpanData step = find(spans, "step", null);
        SpanData chat = find(spans, "chat", null);
        SpanData searchTool = find(spans, "tool", "search");
        SpanData calculatorTool = find(spans, "tool", "calculator");

        assertThat(entry.getName()).isEqualTo("enter_application");
        assertThat(entry.getResource().getAttribute(AttributeKey.stringKey("service.name")))
                .isEqualTo("test-service");
        assertThat(entry.getParentSpanContext().isValid()).isFalse();
        assertThat(agentSpan.getParentSpanId()).isEqualTo(entry.getSpanId());
        assertThat(step.getParentSpanId()).isEqualTo(agentSpan.getSpanId());
        assertThat(chat.getParentSpanId()).isEqualTo(step.getSpanId());
        assertThat(searchTool.getParentSpanId()).isEqualTo(step.getSpanId());
        assertThat(calculatorTool.getParentSpanId()).isEqualTo(step.getSpanId());
        assertThat(step.getStartEpochNanos()).isLessThanOrEqualTo(searchTool.getStartEpochNanos());
        assertThat(step.getEndEpochNanos()).isGreaterThanOrEqualTo(searchTool.getEndEpochNanos());
        assertThat(step.getEndEpochNanos())
                .isGreaterThanOrEqualTo(calculatorTool.getEndEpochNanos());
        assertThat(chat.getAttributes().get(longKey("gen_ai.usage.input_tokens"))).isEqualTo(12L);
        assertThat(chat.getAttributes().get(longKey("gen_ai.usage.output_tokens"))).isEqualTo(5L);
        assertThat(chat.getAttributes().get(longKey("gen_ai.usage.total_tokens"))).isEqualTo(17L);
        assertThat(chat.getAttributes().get(longKey("gen_ai.usage.cache_read.input_tokens")))
                .isEqualTo(3L);
        assertThat(chat.getAttributes().get(stringKey("gen_ai.provider.name")))
                .isEqualTo("unknown");
        assertThat(chat.getAttributes().get(stringKey("gen_ai.response.model")))
                .isEqualTo("model-x");
        assertThat(
                        chat.getAttributes()
                                .get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")))
                .containsExactly("tool_calls");
        assertThat(chat.getAttributes().get(longKey("gen_ai.react.round"))).isEqualTo(1L);
        assertThat(step.getAttributes().get(stringKey("gen_ai.react.finish_reason")))
                .isEqualTo("tool_calls");
        assertThat(searchTool.getAttributes().get(stringKey("gen_ai.tool.call.result")))
                .contains("sunny");
        assertThat(searchTool.getAttributes().get(stringKey("gen_ai.provider.name")))
                .isEqualTo("unknown");
        assertThat(searchTool.getAttributes().get(stringKey("gen_ai.request.model")))
                .isEqualTo("model-x");
        assertThat(searchTool.getAttributes().get(longKey("gen_ai.react.round"))).isEqualTo(1L);
        assertThat(agentSpan.getAttributes().get(longKey("gen_ai.agent.tool_call_count")))
                .isEqualTo(2L);
        assertThat(agentSpan.getAttributes().get(longKey("gen_ai.usage.total_tokens")))
                .isEqualTo(17L);
        assertThat(calculatorTool.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(calculatorTool.getEvents()).hasSize(1);
        assertThat(calculatorTool.getEvents().get(0).getName()).isEqualTo("exception");

        ClsSpanEncoder encoder = new ClsSpanEncoder(new ObjectMapper());
        ClsSpanValidator validator = new ClsSpanValidator(new ObjectMapper());
        for (SpanData span : spans) {
            assertThat(validator.validate(encoder.encode(span))).isEmpty();
            assertThat(span.getAttributes().get(stringKey(ClsFields.SESSION_ID)))
                    .isEqualTo("session-1");
            assertThat(span.getAttributes().get(stringKey(ClsFields.USER_ID)))
                    .isEqualTo("user-1");
            assertThat(span.getAttributes().get(stringKey(ClsFields.USER_NAME)))
                    .isEqualTo("User One");
            assertThat(span.getAttributes().get(stringKey(ClsFields.TURN_ID))).isNotBlank();
        }
    }

    @Test
    void createsDifferentTraceAndTurnForEachInvocationInSameSession() {
        RuntimeContext context = validContext("session-1");

        invokeEmpty(context);
        invokeEmpty(context);

        List<SpanData> entries =
                exporter.getFinishedSpanItems().stream()
                        .filter(span -> "entry".equals(kind(span)))
                        .toList();
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).getTraceId()).isNotEqualTo(entries.get(1).getTraceId());
        assertThat(entries.get(0).getAttributes().get(stringKey(ClsFields.TURN_ID)))
                .isNotEqualTo(entries.get(1).getAttributes().get(stringKey(ClsFields.TURN_ID)));
    }

    @Test
    void preservesParentSpanWhenNestedHookStartsAfterThreadHop() {
        RuntimeContext context = validContext("session-1");

        middleware
                .onAgent(
                        agent,
                        context,
                        new AgentInput(List.of()),
                        ignored ->
                                Flux.just((AgentEvent) new AgentStartEvent("s", "r", "assistant"))
                                        .publishOn(Schedulers.parallel())
                                        .flatMap(
                                                event ->
                                                        middleware.onModelCall(
                                                                agent,
                                                                context,
                                                                new ModelCallInput(
                                                                        List.of(),
                                                                        List.of(),
                                                                        null,
                                                                        model),
                                                                modelInput ->
                                                                        Flux.just(
                                                                                new ModelCallEndEvent(
                                                                                        "r",
                                                                                        new ChatUsage(
                                                                                                1,
                                                                                                1,
                                                                                                0.1))))))
                .collectList()
                .block(Duration.ofSeconds(5));

        SpanData agentSpan = find(exporter.getFinishedSpanItems(), "agent", null);
        SpanData chat = find(exporter.getFinishedSpanItems(), "chat", null);
        assertThat(chat.getParentSpanId()).isEqualTo(agentSpan.getSpanId());
    }

    @Test
    void childAgentInheritsParentIdentityWithoutDuplicatedRuntimeContext() {
        RuntimeContext parentContext = validContext("session-1");
        Agent child = mock(Agent.class);
        when(child.getName()).thenReturn("child");
        when(child.getAgentId()).thenReturn("agent-child");

        middleware
                .onAgent(
                        agent,
                        parentContext,
                        new AgentInput(List.of()),
                        ignored ->
                                middleware.onAgent(
                                        child,
                                        RuntimeContext.empty(),
                                        new AgentInput(List.of()),
                                        childInput -> Flux.empty()))
                .collectList()
                .block();

        List<SpanData> agentSpans =
                exporter.getFinishedSpanItems().stream()
                        .filter(span -> "agent".equals(kind(span)))
                        .toList();
        assertThat(agentSpans).hasSize(2);
        assertThat(agentSpans.get(0).getTraceId()).isEqualTo(agentSpans.get(1).getTraceId());
        assertThat(agentSpans)
                .allSatisfy(
                        span ->
                                assertThat(
                                                span.getAttributes()
                                                        .get(stringKey(ClsFields.SESSION_ID)))
                                        .isEqualTo("session-1"));
    }

    @Test
    void concurrentRoundsKeepToolsUnderTheirOwnSteps() {
        RuntimeContext context = validContext("session-1");
        ToolUseBlock first =
                ToolUseBlock.builder().id("call-first").name("first").input(Map.of()).build();
        ToolUseBlock second =
                ToolUseBlock.builder().id("call-second").name("second").input(Map.of()).build();

        middleware
                .onAgent(
                        agent,
                        context,
                        new AgentInput(List.of()),
                        ignoredAgent ->
                                Flux.merge(
                                                middleware.onReasoning(
                                                        agent,
                                                        context,
                                                        new ReasoningInput(
                                                                List.of(), List.of(), null),
                                                        ignored ->
                                                                Flux.just(
                                                                        new ToolCallStartEvent(
                                                                                "reply-first",
                                                                                "call-first",
                                                                                "first"))),
                                                middleware.onReasoning(
                                                        agent,
                                                        context,
                                                        new ReasoningInput(
                                                                List.of(), List.of(), null),
                                                        ignored ->
                                                                Flux.just(
                                                                        new ToolCallStartEvent(
                                                                                "reply-second",
                                                                                "call-second",
                                                                                "second"))))
                                        .thenMany(
                                                Flux.concat(
                                                        middleware.onActing(
                                                                agent,
                                                                context,
                                                                new ActingInput(List.of(first)),
                                                                ignored ->
                                                                        Flux.just(
                                                                                new ToolResultEndEvent(
                                                                                        "reply-first",
                                                                                        "call-first",
                                                                                        "first",
                                                                                        ToolResultState.SUCCESS))),
                                                        middleware.onActing(
                                                                agent,
                                                                context,
                                                                new ActingInput(List.of(second)),
                                                                ignored ->
                                                                        Flux.just(
                                                                                new ToolResultEndEvent(
                                                                                        "reply-second",
                                                                                        "call-second",
                                                                                        "second",
                                                                                        ToolResultState.SUCCESS))))))
                .collectList()
                .block(Duration.ofSeconds(5));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        List<SpanData> steps =
                spans.stream().filter(span -> "step".equals(kind(span))).toList();
        SpanData firstTool = find(spans, "tool", "first");
        SpanData secondTool = find(spans, "tool", "second");
        assertThat(steps).hasSize(2);
        assertThat(firstTool.getParentSpanId()).isNotEqualTo(secondTool.getParentSpanId());
        assertThat(steps.stream().map(SpanData::getSpanId).toList())
                .contains(firstTool.getParentSpanId(), secondTool.getParentSpanId());
    }

    @Test
    void directCloseStopsFutureInstrumentation() {
        AgentStartEvent event = new AgentStartEvent("session-1", "reply-1", "assistant");
        middleware.close();

        List<AgentEvent> result =
                middleware
                        .onAgent(
                                agent,
                                validContext("session-1"),
                                new AgentInput(List.of()),
                                ignored -> Flux.just(event))
                        .collectList()
                        .block();

        assertThat(result).containsExactly(event);
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void coldFluxCreatedBeforeCloseDoesNotCreateSpansAfterClose() {
        AgentStartEvent event = new AgentStartEvent("session-1", "reply-1", "assistant");
        Flux<AgentEvent> cold =
                middleware.onAgent(
                        agent,
                        validContext("session-1"),
                        new AgentInput(List.of()),
                        ignored -> Flux.just(event));

        active.set(false);
        List<AgentEvent> result = cold.collectList().block();

        assertThat(result).containsExactly(event);
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void missingIdentityKeepsBusinessEventsAndProducesNoSpans() {
        RuntimeContext context = RuntimeContext.builder().sessionId("session-1").build();
        AgentStartEvent event = new AgentStartEvent("session-1", "reply-1", "assistant");

        List<AgentEvent> result =
                middleware
                        .onAgent(
                                agent,
                                context,
                                new AgentInput(List.of()),
                                ignored -> Flux.just(event))
                        .collectList()
                        .block();

        assertThat(result).containsExactly(event);
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void boundsAndFingerprintsRequestIdentityWithoutBreakingTheBusinessFlow() {
        String oversized = "客户标识".repeat(300);
        RuntimeContext context =
                RuntimeContext.builder()
                        .sessionId(oversized)
                        .userId(oversized)
                        .put(
                                ClsInvocationContext.class,
                                new ClsInvocationContext(
                                        oversized, oversized, oversized, oversized))
                        .build();

        invokeEmpty(context);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        assertThat(spans)
                .allSatisfy(
                        span -> {
                            assertBoundedIdentity(span, ClsFields.SESSION_ID, 512);
                            assertBoundedIdentity(span, ClsFields.USER_ID, 512);
                            assertBoundedIdentity(span, ClsFields.USER_NAME, 256);
                            assertBoundedIdentity(span, ClsFields.TURN_ID, 512);
                            assertBoundedIdentity(span, ClsFields.AGENT_TYPE, 128);
                        });
        SpanData entry = find(spans, "entry", null);
        assertBoundedIdentity(entry, "gen_ai.entry.type", 128);
    }

    @Test
    void synchronousBusinessErrorIsPreservedAndSpansAreEnded() {
        AssertionError businessError = new AssertionError("business failure");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                middleware
                                        .onAgent(
                                                agent,
                                                validContext("session-1"),
                                                new AgentInput(List.of()),
                                                ignored -> {
                                                    throw businessError;
                                                })
                                        .collectList()
                                        .block(Duration.ofSeconds(5)))
                .hasRootCause(businessError);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        assertThat(spans)
                .allSatisfy(
                        span ->
                                assertThat(span.getStatus().getStatusCode())
                                        .isEqualTo(StatusCode.ERROR));
    }

    @Test
    void telemetryFailuresNeverBreakBusinessStream() {
        ClsTracingMiddleware failing =
                new ClsTracingMiddleware(
                        provider.get("test"),
                        new ContentSanitizer(new ObjectMapper(), ContentCaptureMode.FULL, 1024),
                        active::get);
        RuntimeContext context = validContext("session-1");
        Model failingModel = mock(Model.class);
        when(failingModel.getModelName()).thenThrow(new IllegalStateException("metadata unavailable"));
        ModelCallEndEvent modelEnd = new ModelCallEndEvent("reply-1", new ChatUsage(3, 2, 0.1));

        List<AgentEvent> events =
                failing.onAgent(
                                agent,
                                context,
                                new AgentInput(List.of()),
                                ignoredAgent ->
                                        failing.onReasoning(
                                                agent,
                                                context,
                                                new ReasoningInput(List.of(), List.of(), null),
                                                ignoredReasoning ->
                                                        failing.onModelCall(
                                                                agent,
                                                                context,
                                                                new ModelCallInput(
                                                                        List.of(),
                                                                        List.of(),
                                                                        null,
                                                                        failingModel),
                                                                ignoredModel ->
                                                                        Flux.just(modelEnd))))
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertThat(events).containsExactly(modelEnd);
        failing.close();
    }

    @Test
    void chatSpanCapturesProviderNeutralReasoningAndTextEventsInOrder() {
        ClsTracingMiddleware capturing = middleware(TRUNCATE, TRUNCATE, 4096);
        RuntimeContext context = validContext("session-reasoning");

        capturing.onAgent(
                        agent,
                        context,
                        new AgentInput(List.of()),
                        ignoredAgent ->
                                capturing.onModelCall(
                                        agent,
                                        context,
                                        new ModelCallInput(List.of(), List.of(), null, model),
                                        ignoredModel -> Flux.just(
                                                new ThinkingBlockStartEvent("reply", "think"),
                                                new ThinkingBlockDeltaEvent("reply", "think", "inspect weather"),
                                                new ThinkingBlockEndEvent("reply", "think"),
                                                new TextBlockStartEvent("reply", "text"),
                                                new TextBlockDeltaEvent("reply", "text", "take an umbrella"),
                                                new TextBlockEndEvent("reply", "text"),
                                                new ModelCallEndEvent(
                                                        "reply", new ChatUsage(12, 8, 0, 0.2)))))
                .collectList()
                .block(Duration.ofSeconds(5));

        SpanData chat = find(exporter.getFinishedSpanItems(), "chat", null);
        String output = chat.getAttributes().get(stringKey("gen_ai.output.messages"));
        assertThat(output)
                .contains("reasoning", "inspect weather", "text", "take an umbrella");
        assertThat(output.indexOf("inspect weather"))
                .isGreaterThanOrEqualTo(0)
                .isLessThan(output.indexOf("take an umbrella"));
        assertThat(chat.getAttributes().get(AttributeKey.booleanKey("agentscope.reasoning.present")))
                .isTrue();
        assertThat(chat.getAttributes().get(longKey("agentscope.reasoning.block_count")))
                .isEqualTo(1L);
    }

    @Test
    void reasoningOffKeepsMetricsButDropsReasoningFromInputOutputAndHash() {
        ClsTracingMiddleware quietReasoning = middleware(TRUNCATE, OFF, 4096);
        RuntimeContext context = validContext("session-reasoning-off");
        io.agentscope.core.message.Msg withReasoning =
                io.agentscope.core.message.Msg.builder()
                        .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                        .content(List.of(
                                io.agentscope.core.message.ThinkingBlock.builder()
                                        .thinking("historical-secret")
                                        .build(),
                                io.agentscope.core.message.TextBlock.builder()
                                        .text("history-answer")
                                        .build()))
                        .build();
        io.agentscope.core.message.Msg withoutReasoning =
                io.agentscope.core.message.Msg.builder()
                        .role(io.agentscope.core.message.MsgRole.ASSISTANT)
                        .content(List.of(
                                io.agentscope.core.message.TextBlock.builder()
                                        .text("history-answer")
                                        .build()))
                        .build();

        invokeReasoningCall(quietReasoning, context, List.of(withReasoning));
        invokeReasoningCall(quietReasoning, context, List.of(withoutReasoning));

        List<SpanData> chats =
                exporter.getFinishedSpanItems().stream()
                        .filter(span -> "chat".equals(kind(span)))
                        .toList();
        String firstInput = chats.get(0).getAttributes().get(stringKey("gen_ai.input.messages"));
        String firstOutput = chats.get(0).getAttributes().get(stringKey("gen_ai.output.messages"));
        assertThat(firstInput).contains("history-answer").doesNotContain("historical-secret");
        assertThat(firstOutput).contains("take an umbrella").doesNotContain("inspect weather");
        assertThat(chats.get(0).getAttributes().get(stringKey("gen_ai.input.messages.hash")))
                .isEqualTo(chats.get(1).getAttributes().get(stringKey("gen_ai.input.messages.hash")));
        assertThat(chats.get(0).getAttributes()
                        .get(AttributeKey.booleanKey("agentscope.reasoning.present")))
                .isTrue();
        assertThat(chats.get(0).getAttributes().get(stringKey("agentscope.reasoning.capture_mode")))
                .isEqualTo("off");
    }

    @Test
    void unavailableReasoningUsageIsOmittedRatherThanReportedAsZero() {
        ClsTracingMiddleware capturing = middleware(TRUNCATE, TRUNCATE, 4096);
        invokeReasoningCall(capturing, validContext("session-token"), List.of());

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData chat = find(spans, "chat", null);
        SpanData agentSpan = find(spans, "agent", null);
        assertThat(chat.getAttributes().asMap().keySet())
                .doesNotContain(longKey("gen_ai.usage.reasoning_output_tokens"));
        assertThat(agentSpan.getAttributes().asMap().keySet())
                .doesNotContain(longKey("gen_ai.usage.reasoning_output_tokens"));
    }

    @Test
    void modelErrorKeepsReasoningMetricsWithoutLeakingOffContent() {
        ClsTracingMiddleware quietReasoning = middleware(TRUNCATE, OFF, 4096);
        RuntimeException businessError = new RuntimeException("model failed");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                quietReasoning
                                        .onAgent(
                                                agent,
                                                validContext("session-error"),
                                                new AgentInput(List.of()),
                                                ignoredAgent ->
                                                        quietReasoning.onModelCall(
                                                                agent,
                                                                validContext("session-error"),
                                                                new ModelCallInput(
                                                                        List.of(), List.of(), null, model),
                                                                ignoredModel ->
                                                                        Flux.concat(
                                                                                Flux.just(
                                                                                        new ThinkingBlockDeltaEvent(
                                                                                                "reply", "think", "partial-secret")),
                                                                                Flux.error(businessError))))
                                        .collectList()
                                        .block(Duration.ofSeconds(5)))
                .isSameAs(businessError);

        SpanData chat = find(exporter.getFinishedSpanItems(), "chat", null);
        assertThat(chat.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(chat.getAttributes().get(stringKey("error.type")))
                .isEqualTo("java.lang.RuntimeException");
        assertThat(chat.getAttributes().get(stringKey("gen_ai.react.finish_reason"))).isNull();
        assertThat(chat.getAttributes().get(AttributeKey.booleanKey("agentscope.reasoning.present")))
                .isTrue();
        assertThat(chat.getAttributes().get(longKey("agentscope.reasoning.output_bytes")))
                .isGreaterThan(0L);
        assertThat(chat.getAttributes().get(stringKey("gen_ai.output.messages"))).isNull();
    }

    @Test
    void modelCancellationFinalizesPartialReasoningMetrics() {
        ClsTracingMiddleware quietReasoning = middleware(TRUNCATE, OFF, 4096);

        quietReasoning
                .onAgent(
                        agent,
                        validContext("session-cancel"),
                        new AgentInput(List.of()),
                        ignoredAgent ->
                                quietReasoning.onModelCall(
                                        agent,
                                        validContext("session-cancel"),
                                        new ModelCallInput(List.of(), List.of(), null, model),
                                        ignoredModel ->
                                                Flux.just(
                                                        new ThinkingBlockDeltaEvent(
                                                                "reply", "think", "cancelled-secret"),
                                                        new TextBlockDeltaEvent(
                                                                "reply", "text", "unused"))))
                .take(1)
                .collectList()
                .block(Duration.ofSeconds(5));

        SpanData chat = find(exporter.getFinishedSpanItems(), "chat", null);
        assertThat(chat.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(chat.getAttributes().get(stringKey("error.type"))).isEqualTo("cancelled");
        assertThat(chat.getAttributes().get(stringKey("gen_ai.react.finish_reason"))).isNull();
        assertThat(chat.getAttributes().get(AttributeKey.booleanKey("agentscope.reasoning.present")))
                .isTrue();
        assertThat(chat.getAttributes().get(longKey("agentscope.reasoning.output_bytes")))
                .isGreaterThan(0L);
        assertThat(chat.getAttributes().get(stringKey("gen_ai.output.messages"))).isNull();
    }

    @Test
    void hashModeUsesCompleteStreamedModelOutput() throws Exception {
        ClsTracingMiddleware hashing =
                new ClsTracingMiddleware(
                        provider.get("test"),
                        new ContentSanitizer(new ObjectMapper(), ContentCaptureMode.HASH, 256),
                        active::get);
        RuntimeContext context = validContext("session-1");
        String prefix = "x".repeat(400);

        invokeStreamedModel(hashing, context, prefix, "first-tail");
        invokeStreamedModel(hashing, context, prefix, "second-tail");

        List<SpanData> chats =
                exporter.getFinishedSpanItems().stream()
                        .filter(span -> "chat".equals(kind(span)))
                        .toList();
        String firstJson =
                chats.get(0).getAttributes().get(stringKey("gen_ai.output.messages"));
        String secondJson =
                chats.get(1).getAttributes().get(stringKey("gen_ai.output.messages"));
        var first = new ObjectMapper().readTree(firstJson).at("/0/parts/0");
        var second = new ObjectMapper().readTree(secondJson).at("/0/parts/0");
        assertThat(first.get("sha256").asText()).isNotEqualTo(second.get("sha256").asText());
        assertThat(first.get("original_bytes").asLong())
                .isEqualTo(prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 10L);
        hashing.close();
    }

    @Test
    void payloadConversionFailureDoesNotLeaveOpenChatSpan() {
        ClsTracingMiddleware failing =
                new ClsTracingMiddleware(
                        provider.get("test"),
                        new ContentSanitizer(new ObjectMapper(), ContentCaptureMode.FULL, 1024),
                        active::get);
        RuntimeContext context = validContext("session-1");
        @SuppressWarnings("unchecked")
        List<io.agentscope.core.message.Msg> brokenMessages = mock(List.class);
        when(brokenMessages.isEmpty()).thenReturn(false);
        when(brokenMessages.iterator())
                .thenThrow(new IllegalStateException("payload conversion failed"));
        ModelCallEndEvent modelEnd = new ModelCallEndEvent("reply-1", new ChatUsage(1, 1, 0.1));

        List<AgentEvent> events =
                failing.onAgent(
                                agent,
                                context,
                                new AgentInput(List.of()),
                                ignoredAgent ->
                                        failing.onModelCall(
                                                agent,
                                                context,
                                                new ModelCallInput(
                                                        brokenMessages,
                                                        List.of(),
                                                        null,
                                                        model),
                                                ignoredModel -> Flux.just(modelEnd)))
                        .collectList()
                        .block(Duration.ofSeconds(5));

        assertThat(events).containsExactly(modelEnd);
        SpanData chat = find(exporter.getFinishedSpanItems(), "chat", null);
        assertThat(chat.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(chat.getAttributes().get(stringKey("gen_ai.input.messages.capture_error")))
                .isEqualTo("IllegalStateException");
        failing.close();
    }

    @Test
    void disabledContentCaptureSkipsPayloadCollection() {
        ClsTracingMiddleware quiet =
                new ClsTracingMiddleware(
                        provider.get("test"),
                        new ContentSanitizer(new ObjectMapper(), ContentCaptureMode.OFF, 1024),
                        active::get);
        RuntimeContext context = validContext("session-1");

        quiet.onAgent(
                        agent,
                        context,
                        new AgentInput(List.of()),
                        ignoredAgent ->
                                quiet.onModelCall(
                                        agent,
                                        context,
                                        new ModelCallInput(List.of(), List.of(), null, model),
                                        ignoredModel ->
                                                Flux.just(
                                                        new TextBlockDeltaEvent(
                                                                "reply-1", "block-1", "chunk"),
                                                        new ModelCallEndEvent(
                                                                "reply-1",
                                                                new ChatUsage(1, 1, 0.1)))))
                .collectList()
                .block(Duration.ofSeconds(5));

        SpanData chat = find(exporter.getFinishedSpanItems(), "chat", null);
        assertThat(chat.getAttributes().get(stringKey("gen_ai.input.messages"))).isNull();
        assertThat(chat.getAttributes().get(stringKey("gen_ai.output.messages"))).isNull();
        assertThat(chat.getAttributes().get(stringKey("gen_ai.input.messages.hash")))
                .isNotBlank();
        quiet.close();
    }

    @Test
    void redactsSecretsSplitAcrossToolTextDeltas() {
        ClsTracingMiddleware capturing =
                new ClsTracingMiddleware(
                        provider.get("test"),
                        new ContentSanitizer(new ObjectMapper(), ContentCaptureMode.FULL, 1024),
                        active::get);
        RuntimeContext context = validContext("session-1");
        ToolUseBlock call =
                ToolUseBlock.builder().id("call-secret").name("search").input(Map.of()).build();

        capturing.onAgent(
                        agent,
                        context,
                        new AgentInput(List.of()),
                        ignoredAgent ->
                                capturing.onActing(
                                        agent,
                                        context,
                                        new ActingInput(List.of(call)),
                                        ignoredActing ->
                                                Flux.just(
                                                        new ToolResultTextDeltaEvent(
                                                                "reply-1",
                                                                "call-secret",
                                                                "search",
                                                                "Bearer "),
                                                        new ToolResultTextDeltaEvent(
                                                                "reply-1",
                                                                "call-secret",
                                                                "search",
                                                                "aaaaaaaaaaaaaaaaaaaa"),
                                                        new ToolResultEndEvent(
                                                                "reply-1",
                                                                "call-secret",
                                                                "search",
                                                                ToolResultState.SUCCESS))))
                .collectList()
                .block(Duration.ofSeconds(5));

        SpanData tool = find(exporter.getFinishedSpanItems(), "tool", "search");
        String value = tool.getAttributes().get(stringKey("gen_ai.tool.call.result"));
        assertThat(value).contains("[REDACTED_SECRET]");
        assertThat(value).doesNotContain("aaaaaaaaaaaaaaaaaaaa");
        capturing.close();
    }

    @Test
    void toolHashMatchesCompleteCanonicalResultJson() throws Exception {
        ClsTracingMiddleware hashing =
                new ClsTracingMiddleware(
                        provider.get("test"),
                        new ContentSanitizer(new ObjectMapper(), ContentCaptureMode.HASH, 256),
                        active::get);
        RuntimeContext context = validContext("session-1");
        ToolUseBlock call =
                ToolUseBlock.builder().id("call-hash").name("search").input(Map.of()).build();

        hashing.onAgent(
                        agent,
                        context,
                        new AgentInput(List.of()),
                        ignoredAgent ->
                                hashing.onActing(
                                        agent,
                                        context,
                                        new ActingInput(List.of(call)),
                                        ignoredActing ->
                                                Flux.just(
                                                        new ToolResultTextDeltaEvent(
                                                                "reply-1",
                                                                "call-hash",
                                                                "search",
                                                                "a"),
                                                        new ToolResultTextDeltaEvent(
                                                                "reply-1",
                                                                "call-hash",
                                                                "search",
                                                                "b"),
                                                        new ToolResultEndEvent(
                                                                "reply-1",
                                                                "call-hash",
                                                                "search",
                                                                ToolResultState.SUCCESS))))
                .collectList()
                .block(Duration.ofSeconds(5));

        SpanData tool = find(exporter.getFinishedSpanItems(), "tool", "search");
        String value = tool.getAttributes().get(stringKey("gen_ai.tool.call.result"));
        var result = new ObjectMapper().readTree(value);
        byte[] canonical = "{\"parts\":[\"a\",\"b\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String expected =
                java.util.HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical));
        assertThat(result.get("sha256").asText()).isEqualTo(expected);
        assertThat(result.get("original_bytes").asInt()).isEqualTo(canonical.length);
        hashing.close();
    }

    @Test
    void boundsAccumulatedToolResultPayload() {
        ClsTracingMiddleware capturing =
                new ClsTracingMiddleware(
                        provider.get("test"),
                        new ContentSanitizer(new ObjectMapper(), ContentCaptureMode.FULL, 1024),
                        active::get);
        RuntimeContext context = validContext("session-1");
        ToolUseBlock call =
                ToolUseBlock.builder().id("call-1").name("search").input(Map.of()).build();
        List<AgentEvent> deltas = new java.util.ArrayList<>();
        deltas.add(
                new ToolResultTextDeltaEvent(
                        "reply-1", "call-1", "search", "y".repeat(1_000_000)));
        for (int index = 0; index < 5000; index++) {
            deltas.add(new ToolResultTextDeltaEvent("reply-1", "call-1", "search", "xxxxxxxxxx"));
        }
        deltas.add(new ToolResultEndEvent("reply-1", "call-1", "search", ToolResultState.SUCCESS));

        capturing
                .onAgent(
                        agent,
                        context,
                        new AgentInput(List.of()),
                        ignoredAgent ->
                                capturing.onActing(
                                        agent,
                                        context,
                                        new ActingInput(List.of(call)),
                                        ignoredActing -> Flux.fromIterable(deltas)))
                .collectList()
                .block(Duration.ofSeconds(10));

        SpanData tool = find(exporter.getFinishedSpanItems(), "tool", "search");
        String result = tool.getAttributes().get(stringKey("gen_ai.tool.call.result"));
        assertThat(result).isNotNull();
        assertThat(result.length()).isLessThan(8 * 1024);
        capturing.close();
    }

    private void invokeStreamedModel(
            ClsTracingMiddleware target, RuntimeContext context, String prefix, String suffix) {
        target.onAgent(
                        agent,
                        context,
                        new AgentInput(List.of()),
                        ignoredAgent ->
                                target.onModelCall(
                                        agent,
                                        context,
                                        new ModelCallInput(List.of(), List.of(), null, model),
                                        ignoredModel ->
                                                Flux.just(
                                                        new TextBlockDeltaEvent(
                                                                "reply-1", "block-1", prefix),
                                                        new TextBlockDeltaEvent(
                                                                "reply-1", "block-1", suffix),
                                                        new ModelCallEndEvent(
                                                                "reply-1",
                                                                new ChatUsage(1, 1, 0.1)))))
                .collectList()
                .block(Duration.ofSeconds(5));
    }

    private ClsTracingMiddleware middleware(
            ContentCaptureMode contentMode,
            ContentCaptureMode reasoningMode,
            int maxBytes) {
        ObjectMapper json = new ObjectMapper();
        return new ClsTracingMiddleware(
                provider.get("test"),
                new ContentSanitizer(json, contentMode, maxBytes),
                new io.github.tinkerlgd2026.agentscope.cls.privacy.MessageCapturePolicy(
                        json, contentMode, reasoningMode, maxBytes),
                contentMode,
                reasoningMode,
                maxBytes,
                active::get,
                new io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters(),
                json,
                false);
    }

    private void invokeReasoningCall(
            ClsTracingMiddleware target,
            RuntimeContext context,
            List<io.agentscope.core.message.Msg> inputMessages) {
        target.onAgent(
                        agent,
                        context,
                        new AgentInput(List.of()),
                        ignoredAgent ->
                                target.onModelCall(
                                        agent,
                                        context,
                                        new ModelCallInput(inputMessages, List.of(), null, model),
                                        ignoredModel -> Flux.just(
                                                new ThinkingBlockStartEvent("reply", "think"),
                                                new ThinkingBlockDeltaEvent("reply", "think", "inspect weather"),
                                                new ThinkingBlockEndEvent("reply", "think"),
                                                new TextBlockDeltaEvent("reply", "text", "take an umbrella"),
                                                new ModelCallEndEvent(
                                                        "reply", new ChatUsage(12, 8, 0, 0.2)))))
                .collectList()
                .block(Duration.ofSeconds(5));
    }

    private void invokeEmpty(RuntimeContext context) {
        middleware
                .onAgent(
                        agent,
                        context,
                        new AgentInput(List.of()),
                        ignored -> Flux.empty())
                .collectList()
                .block();
    }

    private static RuntimeContext validContext(String sessionId) {
        return RuntimeContext.builder()
                .sessionId(sessionId)
                .userId("user-1")
                .put(
                        ClsInvocationContext.class,
                        new ClsInvocationContext(
                                "User One", null, "agentscope-java", "java-sdk"))
                .build();
    }

    private static SpanData find(List<SpanData> spans, String kind, String toolName) {
        return spans.stream()
                .filter(span -> kind.equals(kind(span)))
                .filter(
                        span ->
                                toolName == null
                                        || toolName.equals(
                                                span.getAttributes()
                                                        .get(stringKey("gen_ai.tool.name"))))
                .findFirst()
                .orElseThrow();
    }

    private static String kind(SpanData span) {
        return span.getAttributes().get(stringKey(ClsFields.SPAN_KIND));
    }

    private static void assertBoundedIdentity(SpanData span, String key, int maxBytes) {
        String value = span.getAttributes().get(stringKey(key));
        assertThat(value).isNotBlank().matches(".*~[0-9a-f]{16}$");
        assertThat(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(maxBytes);
    }

    private static AttributeKey<String> stringKey(String name) {
        return AttributeKey.stringKey(name);
    }

    private static AttributeKey<Long> longKey(String name) {
        return AttributeKey.longKey(name);
    }
}
