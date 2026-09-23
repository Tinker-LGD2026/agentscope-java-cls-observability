package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.github.tinkerlgd2026.agentscope.cls.ClsInvocationContext;
import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentSanitizer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Two CLS instances in one JVM must not share invocation state via Reactor context keys. */
class ContextIsolationTest {

    @Test
    void twoClsInstancesBothInstrumentWithoutSharingState() {
        InMemorySpanExporter exporterA = InMemorySpanExporter.create();
        InMemorySpanExporter exporterB = InMemorySpanExporter.create();
        SdkTracerProvider providerA = provider(exporterA);
        SdkTracerProvider providerB = provider(exporterB);
        TelemetryCounters countersA = new TelemetryCounters();
        TelemetryCounters countersB = new TelemetryCounters();
        ClsTracingMiddleware first = middleware(providerA, countersA);
        ClsTracingMiddleware second = middleware(providerB, countersB);
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("assistant");
        when(agent.getAgentId()).thenReturn("agent-1");

        Flux<io.agentscope.core.event.AgentEvent> flow =
                Flux.just(
                        new AgentResultEvent(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("done")
                                                                .build()))
                                        .build()));
        // Instance A wraps the chain produced by instance B (A/B order).
        first.onAgent(
                        agent,
                        validContext(),
                        new AgentInput(List.of()),
                        inputA ->
                                second.onAgent(agent, validContext(), inputA, inputB -> flow))
                .collectList()
                .block(Duration.ofSeconds(5));

        // Both instances observe the same logical invocation independently.
        assertThat(exporterA.getFinishedSpanItems()).isNotEmpty();
        assertThat(exporterB.getFinishedSpanItems()).isNotEmpty();
        assertThat(countersA.detailedSnapshot(0, 0).duplicateMiddlewareDetections()).isZero();
        // Instance B sees no invocation state from instance A's keys: it creates its own
        // entry span (an entry span exists in both exporters).
        long entriesA = entries(exporterA);
        long entriesB = entries(exporterB);
        assertThat(entriesA).isEqualTo(1);
        assertThat(entriesB).isEqualTo(1);

        first.close();
        second.close();
        providerA.close();
        providerB.close();
    }

    @Test
    void duplicateRegistrationOfSameInstanceIsDetectedAndSkipped() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = provider(exporter);
        TelemetryCounters counters = new TelemetryCounters();
        ClsTracingMiddleware middleware = middleware(provider, counters);
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("assistant");
        when(agent.getAgentId()).thenReturn("agent-1");
        Flux<io.agentscope.core.event.AgentEvent> flow =
                Flux.just(
                        new AgentResultEvent(
                                Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text("done")
                                                                .build()))
                                        .build()));

        // The same instance wraps the same callback chain twice with identical objects.
        middleware.onAgent(
                        agent,
                        validContext(),
                        new AgentInput(List.of()),
                        input ->
                                middleware.onAgent(agent, validContext(), input, ignored -> flow))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(counters.detailedSnapshot(0, 0).duplicateMiddlewareDetections())
                .isEqualTo(1);
        // Exactly one entry span: the duplicate did not double-instrument.
        assertThat(entries(exporter)).isEqualTo(1);

        middleware.close();
        provider.close();
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

    private static ClsTracingMiddleware middleware(
            SdkTracerProvider provider, TelemetryCounters counters) {
        return new ClsTracingMiddleware(
                provider.get("test"),
                new ContentSanitizer(
                        new ObjectMapper(), ContentCaptureMode.TRUNCATE, 32 * 1024),
                () -> true,
                counters);
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
                .sessionId("session-iso")
                .userId("user-1")
                .put(
                        ClsInvocationContext.class,
                        new ClsInvocationContext(
                                "User One", null, "agentscope-java", "java-sdk"))
                .build();
    }
}
