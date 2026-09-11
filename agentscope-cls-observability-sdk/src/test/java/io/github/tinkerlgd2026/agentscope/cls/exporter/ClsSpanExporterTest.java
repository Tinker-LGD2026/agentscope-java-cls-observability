package io.github.tinkerlgd2026.agentscope.cls.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanEncoder;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanValidator;
import io.github.tinkerlgd2026.agentscope.cls.transport.InMemorySpanSink;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ClsSpanExporterTest {

    @Test
    void exportsValidSpanAndRejectsInvalidSpan() {
        ObjectMapper json = new ObjectMapper();
        InMemorySpanSink sink = new InMemorySpanSink();
        ClsSpanExporter exporter =
                new ClsSpanExporter(new ClsSpanEncoder(json), new ClsSpanValidator(json), sink);

        CompletableResultCode valid = exporter.export(List.of(spanData(true)));
        CompletableResultCode invalid = exporter.export(List.of(spanData(false)));

        valid.join(1, TimeUnit.SECONDS);
        invalid.join(1, TimeUnit.SECONDS);
        assertThat(valid.isSuccess()).isTrue();
        assertThat(invalid.isSuccess()).isFalse();
        assertThat(sink.records()).hasSize(1);
    }

    @Test
    void rejectsInvalidReasoningHashWithoutDroppingValidSiblingSpan() {
        ObjectMapper json = new ObjectMapper();
        InMemorySpanSink sink = new InMemorySpanSink();
        TelemetryCounters counters = new TelemetryCounters();
        ClsSpanExporter exporter =
                new ClsSpanExporter(
                        new ClsSpanEncoder(json), new ClsSpanValidator(json), sink, counters);

        CompletableResultCode result =
                exporter.export(List.of(reasoningSpanData(true), reasoningSpanData(false)));
        result.join(1, TimeUnit.SECONDS);

        assertThat(result.isSuccess()).isFalse();
        assertThat(sink.records()).singleElement().satisfies(
                record -> {
                    assertThat(record.name()).isEqualTo("chat valid-reasoning");
                    assertThat(record.attribute()).contains("a".repeat(64)).doesNotContain("bad");
                });
        assertThat(counters.snapshot().acceptedSpans()).isEqualTo(1);
        assertThat(counters.snapshot().invalidSpans()).isEqualTo(1);
    }

    @Test
    void keepsValidSpansWhenBatchContainsInvalidSpan() {
        ObjectMapper json = new ObjectMapper();
        InMemorySpanSink sink = new InMemorySpanSink();
        TelemetryCounters counters = new TelemetryCounters();
        ClsSpanExporter exporter =
                new ClsSpanExporter(
                        new ClsSpanEncoder(json), new ClsSpanValidator(json), sink, counters);

        CompletableResultCode result =
                exporter.export(List.of(spanData(true), spanData(false), spanData(true)));
        result.join(1, TimeUnit.SECONDS);

        assertThat(result.isSuccess()).isFalse();
        assertThat(sink.records()).hasSize(2);
        assertThat(counters.snapshot().invalidSpans()).isEqualTo(1);
        assertThat(counters.snapshot().acceptedSpans()).isEqualTo(2);
    }

    private static SpanData reasoningSpanData(boolean validHash) {
        CapturingExporter capture = new CapturingExporter();
        Resource resource =
                Resource.builder().put("service.name", "svc").put("host.name", "host").build();
        try (SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(resource)
                        .addSpanProcessor(SimpleSpanProcessor.create(capture))
                        .build()) {
            String hash = validHash ? "a".repeat(64) : "bad";
            Span span =
                    provider.get("test")
                            .spanBuilder(validHash ? "chat valid-reasoning" : "chat invalid-reasoning")
                            .setAttribute("gen_ai.span.kind", "chat")
                            .setAttribute("gen_ai.operation.name", "chat")
                            .setAttribute("gen_ai.agent.type", "agentscope-java")
                            .setAttribute("gen_ai.session.id", "session")
                            .setAttribute("gen_ai.turn.id", "turn")
                            .setAttribute("gen_ai.user.id", "user")
                            .setAttribute("gen_ai.user.name", "User")
                            .setAttribute(
                                    "gen_ai.output.messages",
                                    "[{\"role\":\"assistant\",\"parts\":[{"
                                            + "\"type\":\"reasoning_hash\","
                                            + "\"sha256\":\"" + hash + "\","
                                            + "\"original_bytes\":4}]}]")
                            .startSpan();
            span.end();
        }
        return capture.spans.get(0);
    }

    private static SpanData spanData(boolean valid) {
        CapturingExporter capture = new CapturingExporter();
        Resource resource =
                Resource.builder().put("service.name", "svc").put("host.name", "host").build();
        try (SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(resource)
                        .addSpanProcessor(SimpleSpanProcessor.create(capture))
                        .build()) {
            Tracer tracer = provider.get("test");
            Span span =
                    tracer.spanBuilder("chat model")
                            .setAttribute("gen_ai.span.kind", "chat")
                            .setAttribute("gen_ai.operation.name", "chat")
                            .setAttribute("gen_ai.agent.type", "agentscope-java")
                            .setAttribute("gen_ai.session.id", "session")
                            .setAttribute("gen_ai.turn.id", "turn")
                            .setAttribute("gen_ai.user.id", "user")
                            .startSpan();
            if (valid) {
                span.setAttribute("gen_ai.user.name", "User");
            }
            span.end();
        }
        return capture.spans.get(0);
    }

    private static final class CapturingExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            this.spans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
