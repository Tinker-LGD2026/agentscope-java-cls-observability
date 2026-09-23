package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.github.tinkerlgd2026.agentscope.cls.ClsInvocationContext;
import io.github.tinkerlgd2026.agentscope.cls.ReactorContextMode;
import io.github.tinkerlgd2026.agentscope.cls.internal.CaptureMemoryPool;
import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentSanitizer;
import io.github.tinkerlgd2026.agentscope.cls.privacy.MessageCapturePolicy;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Coexistence matrix: host OTel spans, wrap order, publishOn, nested agents, Global OTel. */
class MiddlewareCoexistenceTest {

    @Test
    void hostSpanIsLinkedButClsTraceStaysIndependent() {
        InMemorySpanExporter clsExporter = InMemorySpanExporter.create();
        InMemorySpanExporter hostExporter = InMemorySpanExporter.create();
        SdkTracerProvider clsProvider = provider(clsExporter);
        SdkTracerProvider hostProvider = provider(hostExporter);
        TelemetryCounters counters = new TelemetryCounters();
        ClsTracingMiddleware middleware = middleware(clsProvider, counters);
        Agent agent = agent("assistant", "agent-1");

        Span host = hostProvider.get("host").spanBuilder("host-entry").startSpan();
        try (Scope ignored = host.makeCurrent()) {
            run(middleware.onAgent(agent, validContext(), new AgentInput(List.of()), in -> flow()));
        } finally {
            host.end();
        }

        SpanData entry = singleEntry(clsExporter);
        assertThat(entry.getLinks()).hasSize(1);
        assertThat(entry.getLinks().get(0).getSpanContext().getTraceId())
                .isEqualTo(host.getSpanContext().getTraceId());
        assertThat(entry.getTraceId()).isNotEqualTo(host.getSpanContext().getTraceId());
        assertThat(entry.getParentSpanContext().isValid()).isFalse();

        middleware.close();
        clsProvider.close();
        hostProvider.close();
    }

    @Test
    void hostLinkDisabledProducesNoLink() {
        InMemorySpanExporter clsExporter = InMemorySpanExporter.create();
        InMemorySpanExporter hostExporter = InMemorySpanExporter.create();
        SdkTracerProvider clsProvider = provider(clsExporter);
        SdkTracerProvider hostProvider = provider(hostExporter);
        TelemetryCounters counters = new TelemetryCounters();
        ClsTracingMiddleware middleware = middleware(clsProvider, counters, false);
        Agent agent = agent("assistant", "agent-1");

        Span host = hostProvider.get("host").spanBuilder("host-entry").startSpan();
        try (Scope ignored = host.makeCurrent()) {
            run(middleware.onAgent(agent, validContext(), new AgentInput(List.of()), in -> flow()));
        } finally {
            host.end();
        }

        assertThat(singleEntry(clsExporter).getLinks()).isEmpty();

        middleware.close();
        clsProvider.close();
        hostProvider.close();
    }

    @Test
    void reverseWrapOrderAlsoIsolatesInstances() {
        InMemorySpanExporter exporterA = InMemorySpanExporter.create();
        InMemorySpanExporter exporterB = InMemorySpanExporter.create();
        SdkTracerProvider providerA = provider(exporterA);
        SdkTracerProvider providerB = provider(exporterB);
        ClsTracingMiddleware instanceA = middleware(providerA, new TelemetryCounters());
        ClsTracingMiddleware instanceB = middleware(providerB, new TelemetryCounters());
        Agent agent = agent("assistant", "agent-1");

        // B/A order: instance B wraps the chain produced by instance A.
        run(
                instanceB.onAgent(
                        agent,
                        validContext(),
                        new AgentInput(List.of()),
                        in ->
                                instanceA.onAgent(
                                        agent, validContext(), in, ignored -> flow())));

        assertThat(entries(exporterA)).isEqualTo(1);
        assertThat(entries(exporterB)).isEqualTo(1);

        instanceA.close();
        instanceB.close();
        providerA.close();
        providerB.close();
    }

    @Test
    void nestedAgentIsNotDuplicateAndParentsToOuterAgentSpan() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = provider(exporter);
        TelemetryCounters counters = new TelemetryCounters();
        ClsTracingMiddleware middleware = middleware(provider, counters);
        Agent outer = agent("outer", "agent-outer");
        Agent inner = agent("inner", "agent-inner");

        run(
                middleware.onAgent(
                        outer,
                        validContext(),
                        new AgentInput(List.of()),
                        outerInput ->
                                middleware.onAgent(
                                        inner,
                                        validContext(),
                                        new AgentInput(List.of()),
                                        innerInput -> flow())));

        assertThat(counters.detailedSnapshot(0, 0).duplicateMiddlewareDetections()).isZero();
        assertThat(entries(exporter)).isEqualTo(1);
        List<SpanData> agentSpans =
                exporter.getFinishedSpanItems().stream()
                        .filter(span -> span.getName().startsWith("invoke_agent"))
                        .toList();
        assertThat(agentSpans).hasSize(2);
        SpanData innerSpan =
                agentSpans.stream()
                        .filter(span -> span.getName().equals("invoke_agent inner"))
                        .findFirst()
                        .orElseThrow();
        SpanData outerSpan =
                agentSpans.stream()
                        .filter(span -> span.getName().equals("invoke_agent outer"))
                        .findFirst()
                        .orElseThrow();
        assertThat(innerSpan.getParentSpanId()).isEqualTo(outerSpan.getSpanId());
        assertThat(innerSpan.getTraceId()).isEqualTo(outerSpan.getTraceId());

        middleware.close();
        provider.close();
    }

    @Test
    void publishOnBetweenCallbacksKeepsInstanceContext() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = provider(exporter);
        TelemetryCounters counters = new TelemetryCounters();
        ClsTracingMiddleware middleware = middleware(provider, counters);
        Agent agent = agent("assistant", "agent-1");

        run(
                middleware.onAgent(
                        agent,
                        validContext(),
                        new AgentInput(List.of()),
                        input ->
                                middleware
                                        .onReasoning(
                                                agent,
                                                validContext(),
                                                new ReasoningInput(List.of(), List.of(), null),
                                                ignored ->
                                                        Flux.just(
                                                                new TextBlockDeltaEvent(
                                                                        "reply-1",
                                                                        "block-1",
                                                                        "chunk")))
                                        .publishOn(Schedulers.parallel())));

        assertThat(
                        exporter.getFinishedSpanItems().stream()
                                .map(SpanData::getName)
                                .filter(name -> name.startsWith("react round_")))
                .isNotEmpty();
        assertThat(counters.detailedSnapshot(0, 0).duplicateMiddlewareDetections()).isZero();

        middleware.close();
        provider.close();
    }

    @Test
    void thirdPartyMiddlewareAndClsBothObserveAndGlobalOpenTelemetryIsUntouched() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = provider(exporter);
        TelemetryCounters counters = new TelemetryCounters();
        ClsTracingMiddleware middleware = middleware(provider, counters);
        Agent agent = agent("assistant", "agent-1");
        AtomicInteger thirdPartyEvents = new AtomicInteger();
        io.opentelemetry.api.OpenTelemetry globalBefore = GlobalOpenTelemetry.get();

        // A third-party middleware that simply observes events between CLS and the runtime.
        run(
                middleware.onAgent(
                        agent,
                        validContext(),
                        new AgentInput(List.of()),
                        input ->
                                flow().doOnNext(event -> thirdPartyEvents.incrementAndGet())));

        assertThat(thirdPartyEvents.get()).isEqualTo(1);
        assertThat(entries(exporter)).isEqualTo(1);
        assertThat(GlobalOpenTelemetry.get()).isSameAs(globalBefore);

        middleware.close();
        provider.close();
    }

    private static void run(Flux<AgentEvent> flux) {
        flux.collectList().block(Duration.ofSeconds(5));
    }

    private static Flux<AgentEvent> flow() {
        return Flux.just(
                new AgentResultEvent(
                        Msg.builder()
                                .role(MsgRole.ASSISTANT)
                                .content(List.of(TextBlock.builder().text("done").build()))
                                .build()));
    }

    private static Agent agent(String name, String agentId) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(name);
        when(agent.getAgentId()).thenReturn(agentId);
        return agent;
    }

    private static long entries(InMemorySpanExporter exporter) {
        return exporter.getFinishedSpanItems().stream()
                .filter(
                        span ->
                                "entry"
                                        .equals(
                                                span.getAttributes()
                                                        .get(
                                                                io.opentelemetry.api.common
                                                                        .AttributeKey.stringKey(
                                                                        "gen_ai.span.kind"))))
                .count();
    }

    private static SpanData singleEntry(InMemorySpanExporter exporter) {
        List<SpanData> entries =
                exporter.getFinishedSpanItems().stream()
                        .filter(
                                span ->
                                        "entry"
                                                .equals(
                                                        span.getAttributes()
                                                                .get(
                                                                        io.opentelemetry.api
                                                                                .common
                                                                                .AttributeKey
                                                                                .stringKey(
                                                                                "gen_ai.span.kind"))))
                        .toList();
        assertThat(entries).hasSize(1);
        return entries.get(0);
    }

    private static ClsTracingMiddleware middleware(
            SdkTracerProvider provider, TelemetryCounters counters) {
        return middleware(provider, counters, true);
    }

    private static ClsTracingMiddleware middleware(
            SdkTracerProvider provider, TelemetryCounters counters, boolean hostTraceLinkEnabled) {
        ObjectMapper mapper = new ObjectMapper();
        ContentSanitizer sanitizer =
                new ContentSanitizer(mapper, ContentCaptureMode.TRUNCATE, 32 * 1024);
        return new ClsTracingMiddleware(
                provider.get("test"),
                sanitizer,
                new MessageCapturePolicy(
                        mapper, sanitizer.mode(), ContentCaptureMode.OFF, sanitizer.maxBytes()),
                sanitizer.mode(),
                ContentCaptureMode.OFF,
                ContentCaptureMode.OFF,
                Math.min(4096, sanitizer.maxBytes()),
                sanitizer.maxBytes(),
                new CaptureMemoryPool(Math.max(sanitizer.maxBytes(), 8L * 1024 * 1024)),
                sanitizer.maxBytes(),
                () -> true,
                counters,
                mapper,
                ReactorContextMode.PRIVATE,
                hostTraceLinkEnabled,
                Duration.ofMinutes(10));
    }

    private static SdkTracerProvider provider(InMemorySpanExporter exporter) {
        return SdkTracerProvider.builder()
                .setResource(
                        Resource.builder()
                                .put("service.name", "svc")
                                .put("host.name", "host")
                                .build())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }

    private static RuntimeContext validContext() {
        return RuntimeContext.builder()
                .sessionId("session-coexist")
                .userId("user-1")
                .put(
                        ClsInvocationContext.class,
                        new ClsInvocationContext(
                                "User One", null, "agentscope-java", "java-sdk"))
                .build();
    }
}
