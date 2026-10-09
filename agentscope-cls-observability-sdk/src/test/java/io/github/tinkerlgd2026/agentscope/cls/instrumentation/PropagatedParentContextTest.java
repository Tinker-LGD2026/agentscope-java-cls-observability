package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parent contexts published by the middleware must carry a non-recording PropagatedSpan, not
 * the live recording span. APM javaagents built on the OTel javaagent (ARMS 5.1.x AgentScope
 * plugin) instrument live spans and can invalidate their SpanContext once the span round-trips
 * through a Context, silently re-rooting children onto fresh traces.
 */
class PropagatedParentContextTest {

    @Test
    void storedParentReferenceIsNonRecordingButKeepsIdentityAndLinkability() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(Resource.empty())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        Span parent = provider.get("test").spanBuilder("parent").startSpan();

        Context stored = ClsTracingMiddleware.storeParentReference(parent, Context.root());

        Span retrieved = Span.fromContext(stored);
        assertThat(retrieved).isNotSameAs(parent);
        assertThat(retrieved.isRecording()).isFalse();
        assertThat(retrieved.getSpanContext()).isEqualTo(parent.getSpanContext());

        // A child built from the propagated reference lands on the same trace with the
        // correct parent span id, exactly as with a live span parent.
        Span child =
                provider.get("test").spanBuilder("child").setParent(stored).startSpan();
        child.end();
        parent.end();

        List<SpanData> finished = exporter.getFinishedSpanItems();
        SpanData childData =
                finished.stream()
                        .filter(span -> span.getName().equals("child"))
                        .findFirst()
                        .orElseThrow();
        SpanData parentData =
                finished.stream()
                        .filter(span -> span.getName().equals("parent"))
                        .findFirst()
                        .orElseThrow();
        assertThat(childData.getTraceId()).isEqualTo(parentData.getTraceId());
        assertThat(childData.getParentSpanId()).isEqualTo(parentData.getSpanId());

        provider.close();
    }

    @Test
    void nestedPropagationChainsKeepSingleTrace() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(Resource.empty())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        Span entry = provider.get("test").spanBuilder("entry").startSpan();
        Context entryContext = ClsTracingMiddleware.storeParentReference(entry, Context.root());
        Span agent = provider.get("test").spanBuilder("agent").setParent(entryContext).startSpan();
        Context agentContext = ClsTracingMiddleware.storeParentReference(agent, entryContext);
        Span chat = provider.get("test").spanBuilder("chat").setParent(agentContext).startSpan();
        chat.end();
        agent.end();
        entry.end();

        List<SpanData> finished = exporter.getFinishedSpanItems();
        SpanData entryData = byName(finished, "entry");
        SpanData agentData = byName(finished, "agent");
        SpanData chatData = byName(finished, "chat");
        assertThat(agentData.getTraceId()).isEqualTo(entryData.getTraceId());
        assertThat(agentData.getParentSpanId()).isEqualTo(entryData.getSpanId());
        assertThat(chatData.getTraceId()).isEqualTo(entryData.getTraceId());
        assertThat(chatData.getParentSpanId()).isEqualTo(agentData.getSpanId());

        provider.close();
    }

    private static SpanData byName(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
