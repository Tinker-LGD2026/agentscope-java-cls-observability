package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvocationLifecycleTest {
    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider =
                SdkTracerProvider.builder()
                        .setResource(
                                Resource.builder()
                                        .put("service.name", "svc")
                                        .put("host.name", "host")
                                        .build())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void terminalEndsChildrenBeforeParentsExactlyOnce() {
        InvocationLifecycle lifecycle = new InvocationLifecycle();
        Span entry = span("entry");
        Span agent = span("agent");
        Span step = span("step");
        Span chat = span("chat");
        Span tool = span("tool");
        lifecycle.registerEntry(entry);
        lifecycle.registerAgent("agent-1", agent);
        lifecycle.registerStep("agent-1", "step-1", step);
        lifecycle.registerChat("step-1", chat);
        lifecycle.registerTool("step-1", "call-1", tool);

        lifecycle.terminal(TerminalOutcome.NORMAL, true, null);
        lifecycle.terminal(TerminalOutcome.ERROR, false, new IllegalStateException("late"));

        List<SpanData> finished = exporter.getFinishedSpanItems();
        assertThat(finished).hasSize(5);
        assertThat(finished.stream().map(SpanData::getName).toList())
                .containsExactly("chat", "tool", "step", "agent", "entry");
        SpanData entryData = finished.get(4);
        assertThat(entryData.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(
                        entryData.getAttributes()
                                .get(io.opentelemetry.api.common.AttributeKey.booleanKey(
                                        "gen_ai.turn.completed")))
                .isTrue();
        assertThat(
                        entryData.getAttributes()
                                .get(
                                        io.opentelemetry.api.common.AttributeKey.stringKey(
                                                "gen_ai.turn.finish_reason")))
                .isEqualTo("normal");
        assertThat(lifecycle.lateTerminalSignals()).isEqualTo(1);
    }

    @Test
    void cancelledMapsToErrorWithSafeMarkerWithoutExceptionEvent() {
        InvocationLifecycle lifecycle = new InvocationLifecycle();
        lifecycle.registerEntry(span("entry"));

        lifecycle.terminal(TerminalOutcome.CANCELLED, false, null);

        SpanData entry = exporter.getFinishedSpanItems().get(0);
        assertThat(entry.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(entry.getStatus().getDescription()).isEqualTo("cancelled");
        assertThat(entry.getEvents()).isEmpty();
        assertThat(
                        entry.getAttributes()
                                .get(
                                        io.opentelemetry.api.common.AttributeKey.booleanKey(
                                                "gen_ai.incomplete")))
                .isTrue();
    }

    @Test
    void awaitTimeoutMapsToUnsetWithEmptyMessage() {
        InvocationLifecycle lifecycle = new InvocationLifecycle();
        lifecycle.registerEntry(span("entry"));

        lifecycle.terminal(TerminalOutcome.AWAIT_TIMEOUT, false, null);

        SpanData entry = exporter.getFinishedSpanItems().get(0);
        assertThat(entry.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(entry.getStatus().getDescription()).isEmpty();
        assertThat(
                        entry.getAttributes()
                                .get(
                                        io.opentelemetry.api.common.AttributeKey.stringKey(
                                                "gen_ai.turn.finish_reason")))
                .isEqualTo("await_timeout");
    }

    @Test
    void errorRecordsExceptionEventOnlyForRealThrowable() {
        InvocationLifecycle lifecycle = new InvocationLifecycle();
        lifecycle.registerEntry(span("entry"));

        lifecycle.terminal(TerminalOutcome.ERROR, false, new IllegalStateException("boom"));

        SpanData entry = exporter.getFinishedSpanItems().get(0);
        assertThat(entry.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(entry.getEvents()).hasSize(1);
        assertThat(
                        entry.getAttributes()
                                .get(
                                        io.opentelemetry.api.common.AttributeKey.stringKey(
                                                "error.type")))
                .isEqualTo("java.lang.IllegalStateException");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            value = TerminalOutcome.class,
            names = {"DENIED", "MAX_ITERS", "INTERRUPTED"})
    void controlledOutcomesStayOkButIncomplete(TerminalOutcome outcome) {
        InvocationLifecycle lifecycle = new InvocationLifecycle();
        lifecycle.registerEntry(span("entry"));

        lifecycle.terminal(outcome, true, null);

        SpanData entry = exporter.getFinishedSpanItems().get(0);
        assertThat(entry.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(
                        entry.getAttributes()
                                .get(
                                        io.opentelemetry.api.common.AttributeKey.booleanKey(
                                                "gen_ai.incomplete")))
                .isTrue();
    }

    @Test
    void incompleteCompletionWhenNoResultAndNoControlOutcome() {
        InvocationLifecycle lifecycle = new InvocationLifecycle();
        lifecycle.registerEntry(span("entry"));

        lifecycle.terminal(TerminalOutcome.INCOMPLETE_COMPLETION, false, null);

        SpanData entry = exporter.getFinishedSpanItems().get(0);
        assertThat(entry.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(
                        entry.getAttributes()
                                .get(
                                        io.opentelemetry.api.common.AttributeKey.booleanKey(
                                                "gen_ai.turn.completed")))
                .isFalse();
    }

    private Span span(String name) {
        return provider.get("test").spanBuilder(name).startSpan();
    }
}
