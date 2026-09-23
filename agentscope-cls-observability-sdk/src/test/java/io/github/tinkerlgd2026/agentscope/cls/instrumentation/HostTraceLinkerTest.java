package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

class HostTraceLinkerTest {
    private static final SpanContext HOST =
            SpanContext.create(
                    "0123456789abcdef0123456789abcdef",
                    "0123456789abcdef",
                    TraceFlags.getSampled(),
                    TraceState.getDefault());

    @Test
    void linksValidHostContextAndStaysRoot() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = provider(exporter);

        new HostTraceLinker(true)
                .apply(provider.get("test").spanBuilder("entry"), HOST)
                .startSpan()
                .end();

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getParentSpanId())
                .isEqualTo(SpanContext.getInvalid().getSpanId());
        assertThat(span.getLinks()).hasSize(1);
        assertThat(span.getLinks().get(0).getSpanContext().getTraceId())
                .isEqualTo(HOST.getTraceId());
        // CLS trace stays independent.
        assertThat(span.getTraceId()).isNotEqualTo(HOST.getTraceId());
        provider.close();
    }

    @Test
    void disabledOrInvalidHostProducesNoLink() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = provider(exporter);

        new HostTraceLinker(false)
                .apply(provider.get("test").spanBuilder("off"), HOST)
                .startSpan()
                .end();
        new HostTraceLinker(true)
                .apply(provider.get("test").spanBuilder("invalid"), SpanContext.getInvalid())
                .startSpan()
                .end();
        new HostTraceLinker(true)
                .apply(provider.get("test").spanBuilder("absent"), null)
                .startSpan()
                .end();

        assertThat(exporter.getFinishedSpanItems())
                .allSatisfy(span -> assertThat(span.getLinks()).isEmpty());
        provider.close();
    }

    @Test
    void hostSnapshotIsWrittenOnceAndNeverHoldsClsSpan() {
        reactor.util.context.Context initial = reactor.util.context.Context.empty();
        reactor.util.context.Context written =
                HostTraceLinker.writeSnapshot(initial, reactor.util.context.Context.empty());
        // First write captures (possibly invalid) host; second write must not overwrite.
        reactor.util.context.Context second =
                HostTraceLinker.writeSnapshot(written, reactor.util.context.Context.empty());
        assertThat(second).isSameAs(written);
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
}
