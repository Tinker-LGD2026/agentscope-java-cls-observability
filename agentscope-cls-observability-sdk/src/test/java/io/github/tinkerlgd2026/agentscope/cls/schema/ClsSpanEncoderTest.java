package io.github.tinkerlgd2026.agentscope.cls.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
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
import org.junit.jupiter.api.Test;

class ClsSpanEncoderTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void encodesRequiredClsFieldsWithExactTypes() throws Exception {
        CapturingExporter capture = new CapturingExporter();
        Resource resource =
                Resource.builder()
                        .put("service.name", "test-service")
                        .put("host.name", "test-host")
                        .build();
        try (SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(resource)
                        .addSpanProcessor(SimpleSpanProcessor.create(capture))
                        .build()) {
            Tracer tracer = provider.get("test");
            Span span =
                    tracer.spanBuilder("chat model-x")
                            .setSpanKind(SpanKind.CLIENT)
                            .setAttribute("gen_ai.span.kind", "chat")
                            .setAttribute("gen_ai.operation.name", "chat")
                            .setAttribute("gen_ai.agent.type", "agentscope-java")
                            .setAttribute("gen_ai.session.id", "session-1")
                            .setAttribute("gen_ai.turn.id", "turn-1")
                            .setAttribute("gen_ai.user.id", "user-1")
                            .setAttribute("gen_ai.user.name", "User One")
                            .setAttribute("gen_ai.request.model", "model-x")
                            .setAttribute("gen_ai.usage.input_tokens", 10L)
                            .setAttribute("gen_ai.usage.output_tokens", 4L)
                            .startSpan();
            span.setStatus(StatusCode.OK);
            span.end();
        }

        ClsSpanRecord record = new ClsSpanEncoder(JSON).encode(capture.single());
        JsonNode attributes = JSON.readTree(record.attribute());
        JsonNode resources = JSON.readTree(record.resource());

        assertThat(record.traceID()).matches("[0-9a-f]{32}");
        assertThat(record.spanID()).matches("[0-9a-f]{16}");
        assertThat(record.parentSpanID()).isEmpty();
        assertThat(record.kind()).isEqualTo("client");
        assertThat(record.start()).matches("[0-9]+");
        assertThat(record.end()).matches("[0-9]+");
        assertThat(record.duration()).matches("[0-9]+");
        assertThat(record.statusCode()).isEqualTo("OK");
        assertThat(attributes.get("gen_ai.usage.input_tokens").isIntegralNumber()).isTrue();
        assertThat(attributes.get("gen_ai.usage.input_tokens").asLong()).isEqualTo(10L);
        assertThat(resources.get("service.name").asText()).isEqualTo("test-service");
        assertThat(resources.get("host.name").asText()).isEqualTo("test-host");
        assertThat(record.links()).isEqualTo("[]");
        assertThat(record.logs()).isEqualTo("[]");
        assertThat(record.fields())
                .containsEntry("statusMessage", "")
                .containsEntry("traceState", "");
        assertThat(new ClsSpanValidator(JSON).validate(record)).isEmpty();
    }

    @Test
    void encodesAndValidatesReasoningAttributeTypes() throws Exception {
        CapturingExporter capture = new CapturingExporter();
        Resource resource =
                Resource.builder()
                        .put("service.name", "test-service")
                        .put("host.name", "test-host")
                        .build();
        try (SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(resource)
                        .addSpanProcessor(SimpleSpanProcessor.create(capture))
                        .build()) {
            Span span =
                    provider.get("test").spanBuilder("chat model-x")
                            .setAttribute(ClsFields.SPAN_KIND, "chat")
                            .setAttribute(ClsFields.OPERATION_NAME, "chat")
                            .setAttribute(ClsFields.AGENT_TYPE, "agentscope-java")
                            .setAttribute(ClsFields.SESSION_ID, "session-1")
                            .setAttribute(ClsFields.TURN_ID, "turn-1")
                            .setAttribute(ClsFields.USER_ID, "user-1")
                            .setAttribute(ClsFields.USER_NAME, "User One")
                            .setAttribute(ClsFields.REASONING_PRESENT, true)
                            .setAttribute(ClsFields.REASONING_BLOCK_COUNT, 2L)
                            .setAttribute(ClsFields.REASONING_OUTPUT_BYTES, 1024L)
                            .setAttribute(ClsFields.REASONING_DURATION_MS, 50L)
                            .setAttribute(ClsFields.REASONING_TTFT_MS, 10L)
                            .setAttribute(ClsFields.REASONING_CAPTURE_MODE, "truncate")
                            .setAttribute(ClsFields.REASONING_TRUNCATED, false)
                            .setAttribute(ClsFields.REASONING_MALFORMED_EVENTS, 0L)
                            .setAttribute(ClsFields.RESPONSE_TTFT_MS, 80L)
                            .setAttribute(
                                    "gen_ai.output.messages",
                                    "[{\"role\":\"assistant\",\"parts\":["
                                            + "{\"type\":\"reasoning\",\"content\":\"plan\"},"
                                            + "{\"type\":\"reasoning_hash\","
                                            + "\"sha256\":\"" + "a".repeat(64) + "\","
                                            + "\"original_bytes\":4}]}]")
                            .startSpan();
            span.end();
        }

        ClsSpanRecord record = new ClsSpanEncoder(JSON).encode(capture.single());
        JsonNode attributes = JSON.readTree(record.attribute());

        assertThat(attributes.get(ClsFields.REASONING_PRESENT).isBoolean()).isTrue();
        assertThat(attributes.get(ClsFields.REASONING_PRESENT).asBoolean()).isTrue();
        assertThat(attributes.get(ClsFields.REASONING_BLOCK_COUNT).asLong()).isEqualTo(2L);
        assertThat(attributes.get(ClsFields.REASONING_OUTPUT_BYTES).asLong()).isEqualTo(1024L);
        assertThat(attributes.get(ClsFields.REASONING_DURATION_MS).asLong()).isEqualTo(50L);
        assertThat(attributes.get(ClsFields.REASONING_TTFT_MS).asLong()).isEqualTo(10L);
        assertThat(attributes.get(ClsFields.REASONING_CAPTURE_MODE).asText())
                .isEqualTo("truncate");
        assertThat(attributes.get(ClsFields.REASONING_TRUNCATED).isBoolean()).isTrue();
        assertThat(attributes.get(ClsFields.REASONING_TRUNCATED).asBoolean()).isFalse();
        assertThat(attributes.get(ClsFields.REASONING_MALFORMED_EVENTS).asLong()).isZero();
        assertThat(attributes.get(ClsFields.RESPONSE_TTFT_MS).asLong()).isEqualTo(80L);
        assertThat(new ClsSpanValidator(JSON).validate(record)).isEmpty();
    }

    @Test
    void validatorRejectsInvalidReasoningTypesModeAndHashEnvelope() {
        String attributes =
                "{\"gen_ai.span.kind\":\"chat\",\"gen_ai.operation.name\":\"chat\","
                        + "\"gen_ai.agent.type\":\"agentscope-java\","
                        + "\"gen_ai.session.id\":\"s\",\"gen_ai.turn.id\":\"t\","
                        + "\"gen_ai.user.id\":\"u\",\"gen_ai.user.name\":\"U\","
                        + "\"agentscope.reasoning.present\":\"yes\","
                        + "\"agentscope.reasoning.block_count\":\"two\","
                        + "\"agentscope.reasoning.capture_mode\":\"raw\","
                        + "\"gen_ai.output.messages\":[{\"role\":\"assistant\",\"parts\":["
                        + "{\"type\":\"reasoning_hash\",\"sha256\":\"bad\","
                        + "\"original_bytes\":-1}]}]}";
        ClsSpanRecord record = validRecord(attributes);

        assertThat(new ClsSpanValidator(JSON).validate(record))
                .contains(
                        "attribute.agentscope.reasoning.present must be a boolean",
                        "attribute.agentscope.reasoning.block_count must be an integer",
                        "attribute.agentscope.reasoning.capture_mode is unsupported",
                        "attribute.gen_ai.output.messages reasoning_hash.sha256 is invalid",
                        "attribute.gen_ai.output.messages reasoning_hash.original_bytes must be non-negative");
    }

    @Test
    void validatorRejectsNestedInvalidReasoningAndUnsafeSummaryObjects() {
        String attributes =
                "{\"gen_ai.span.kind\":\"chat\",\"gen_ai.operation.name\":\"chat\","
                        + "\"gen_ai.agent.type\":\"agentscope-java\","
                        + "\"gen_ai.session.id\":\"s\",\"gen_ai.turn.id\":\"t\","
                        + "\"gen_ai.user.id\":\"u\",\"gen_ai.user.name\":\"U\","
                        + "\"gen_ai.input.messages\":[{\"role\":\"assistant\",\"parts\":["
                        + "{\"type\":\"tool_call_response\",\"result\":["
                        + "{\"type\":\"reasoning_hash\",\"sha256\":\"bad\","
                        + "\"original_bytes\":-1}]}]}],"
                        + "\"gen_ai.output.messages\":[{\"role\":\"assistant\",\"parts\":["
                        + "{\"type\":\"reasoning\",\"content\":{\"secret\":\"plaintext\"}}]}]}";

        assertThat(new ClsSpanValidator(JSON).validate(validRecord(attributes)))
                .contains(
                        "attribute.gen_ai.input.messages reasoning_hash.sha256 is invalid",
                        "attribute.gen_ai.input.messages reasoning_hash.original_bytes must be non-negative",
                        "attribute.gen_ai.output.messages reasoning.content summary is invalid");
    }

    @Test
    void validatorAcceptsSafeReasoningSummaryAndUnknownFuturePart() {
        String attributes =
                "{\"gen_ai.span.kind\":\"chat\",\"gen_ai.operation.name\":\"chat\","
                        + "\"gen_ai.agent.type\":\"agentscope-java\","
                        + "\"gen_ai.session.id\":\"s\",\"gen_ai.turn.id\":\"t\","
                        + "\"gen_ai.user.id\":\"u\",\"gen_ai.user.name\":\"U\","
                        + "\"gen_ai.output.messages\":[{\"role\":\"assistant\",\"parts\":["
                        + "{\"type\":\"reasoning\",\"content\":{\"truncated\":true,"
                        + "\"original_bytes\":400,\"preview\":\"safe\"}},"
                        + "{\"type\":\"future_part\",\"payload\":{\"x\":1}}]}]}";

        assertThat(new ClsSpanValidator(JSON).validate(validRecord(attributes))).isEmpty();
    }

    @Test
    void validatorRejectsIntegersOutsideSignedLongRange() {
        String attributes =
                "{\"gen_ai.span.kind\":\"chat\",\"gen_ai.operation.name\":\"chat\","
                        + "\"gen_ai.agent.type\":\"agentscope-java\","
                        + "\"gen_ai.session.id\":\"s\",\"gen_ai.turn.id\":\"t\","
                        + "\"gen_ai.user.id\":\"u\",\"gen_ai.user.name\":\"U\","
                        + "\"agentscope.reasoning.output_bytes\":-18446744073709551616,"
                        + "\"gen_ai.output.messages\":[{\"role\":\"assistant\",\"parts\":["
                        + "{\"type\":\"reasoning_hash\",\"sha256\":\"" + "a".repeat(64)
                        + "\",\"original_bytes\":18446744073709551616}]}]}";

        assertThat(new ClsSpanValidator(JSON).validate(validRecord(attributes)))
                .contains(
                        "attribute.agentscope.reasoning.output_bytes must fit a signed 64-bit integer",
                        "attribute.gen_ai.output.messages reasoning_hash.original_bytes must fit a signed 64-bit integer");
    }

    @Test
    void restoresStructuredJsonForMessageAndToolAttributes() throws Exception {
        CapturingExporter capture = new CapturingExporter();
        Resource resource =
                Resource.builder()
                        .put("service.name", "test-service")
                        .put("host.name", "test-host")
                        .build();
        try (SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(resource)
                        .addSpanProcessor(SimpleSpanProcessor.create(capture))
                        .build()) {
            Span span =
                    provider.get("test").spanBuilder("chat model-x")
                            .setAttribute("gen_ai.span.kind", "chat")
                            .setAttribute("gen_ai.operation.name", "chat")
                            .setAttribute("gen_ai.agent.type", "agentscope-java")
                            .setAttribute("gen_ai.session.id", "session-1")
                            .setAttribute("gen_ai.turn.id", "turn-1")
                            .setAttribute("gen_ai.user.id", "user-1")
                            .setAttribute("gen_ai.user.name", "User One")
                            .setAttribute(
                                    "gen_ai.input.messages",
                                    "[{\"role\":\"user\",\"parts\":[]}]")
                            .setAttribute(
                                    "gen_ai.tool.call.arguments", "{\"query\":\"safe\"}")
                            .startSpan();
            span.end();
        }

        JsonNode attributes =
                JSON.readTree(new ClsSpanEncoder(JSON).encode(capture.single()).attribute());

        assertThat(attributes.get("gen_ai.input.messages").isArray()).isTrue();
        assertThat(attributes.get("gen_ai.tool.call.arguments").isObject()).isTrue();
    }

    @Test
    void validatorRejectsMissingCommonAgentAttributes() {
        ClsSpanRecord record =
                new ClsSpanRecord(
                        "0123456789abcdef0123456789abcdef",
                        "0123456789abcdef",
                        "",
                        "chat model-x",
                        "client",
                        "100",
                        "200",
                        "100",
                        "OK",
                        "",
                        "{\"gen_ai.span.kind\":\"chat\",\"gen_ai.operation.name\":\"chat\"}",
                        "{\"service.name\":\"svc\",\"host.name\":\"host\"}",
                        "",
                        "[]",
                        "[]");

        assertThat(new ClsSpanValidator(JSON).validate(record))
                .contains(
                        "attribute.gen_ai.agent.type is required",
                        "attribute.gen_ai.session.id is required",
                        "attribute.gen_ai.turn.id is required",
                        "attribute.gen_ai.user.id is required",
                        "attribute.gen_ai.user.name is required");
    }

    @Test
    void validatorRejectsInvalidEnumsAndConditionalFieldTypes() {
        ClsSpanRecord record =
                new ClsSpanRecord(
                        "0123456789abcdef0123456789abcdef",
                        "0123456789abcdef",
                        "",
                        "execute_tool search",
                        "invalid-kind",
                        "100",
                        "200",
                        "100",
                        "INVALID",
                        "",
                        "{\"gen_ai.span.kind\":\"tool\",\"gen_ai.operation.name\":\"execute_tool\",\"gen_ai.agent.type\":\"agentscope-java\",\"gen_ai.session.id\":\"s\",\"gen_ai.turn.id\":\"t\",\"gen_ai.user.id\":\"u\",\"gen_ai.user.name\":\"U\",\"gen_ai.input.messages\":{},\"gen_ai.tool.call.duration_ms\":\"1\"}",
                        "{\"service.name\":\"svc\",\"host.name\":\"host\"}",
                        "",
                        "[]",
                        "[]");

        assertThat(new ClsSpanValidator(JSON).validate(record))
                .contains(
                        "kind is unsupported",
                        "statusCode is unsupported",
                        "attribute.gen_ai.input.messages must be a JSON array",
                        "attribute.gen_ai.tool.call.id is required",
                        "attribute.gen_ai.tool.name is required",
                        "attribute.gen_ai.tool.type is required",
                        "attribute.gen_ai.tool.call.duration_ms must be an integer");
    }

    @Test
    void cropsOversizedAttributeResourceLinksAndLogsToDeterministicEnvelopes() throws Exception {
        CapturingExporter capture = new CapturingExporter();
        Resource resource =
                Resource.builder()
                        .put("service.name", "test-service")
                        .put("host.name", "test-host")
                        .put("custom.resource", "r".repeat(70_000))
                        .build();
        try (SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(resource)
                        .addSpanProcessor(SimpleSpanProcessor.create(capture))
                        .build()) {
            Span span =
                    provider.get("test").spanBuilder("chat model-x")
                            .setAttribute(ClsFields.SPAN_KIND, "chat")
                            .setAttribute(ClsFields.OPERATION_NAME, "chat")
                            .setAttribute(ClsFields.AGENT_TYPE, "agentscope-java")
                            .setAttribute(ClsFields.SESSION_ID, "session-1")
                            .setAttribute(ClsFields.TURN_ID, "turn-1")
                            .setAttribute(ClsFields.USER_ID, "user-1")
                            .setAttribute(ClsFields.USER_NAME, "User One")
                            .setAttribute("custom.big", "中".repeat(400_000))
                            .startSpan();
            for (int index = 0; index < 100; index++) {
                span.addLink(
                        span.getSpanContext(),
                        io.opentelemetry.api.common.Attributes.of(
                                AttributeKey.stringKey("link.attr"), "l".repeat(2_000)));
            }
            for (int index = 0; index < 100; index++) {
                span.addEvent(
                        "event-" + index,
                        io.opentelemetry.api.common.Attributes.of(
                                AttributeKey.stringKey("event.attr"), "e".repeat(4_000)));
            }
            span.end();
        }

        ClsSpanRecord record = new ClsSpanEncoder(JSON).encode(capture.single());

        assertThat(utf8(record.attribute()))
                .isLessThanOrEqualTo(ClsFieldLimits.DEFAULT_ATTRIBUTE_MAX_BYTES);
        assertThat(utf8(record.resource())).isLessThanOrEqualTo(ClsFieldLimits.RESOURCE_MAX_BYTES);
        assertThat(utf8(record.links())).isLessThanOrEqualTo(ClsFieldLimits.LINKS_MAX_BYTES);
        assertThat(utf8(record.logs())).isLessThanOrEqualTo(ClsFieldLimits.LOGS_MAX_BYTES);
        assertThat(Utf8LogItemSizer.size(record.fields()))
                .isLessThanOrEqualTo(ClsFieldLimits.RECORD_MAX_BYTES);
        JsonNode attribute = JSON.readTree(record.attribute());
        assertThat(attribute.path("truncated").asBoolean()).isTrue();
        assertThat(attribute.path("sha256").asText()).matches("[0-9a-f]{64}");
        assertThat(attribute.path("original_bytes").asLong()).isGreaterThan(1_000_000L);
        assertThat(new ClsSpanValidator(JSON).validate(record)).isEmpty();
    }

    @Test
    void keepsFieldsAtExactLimitsUnchanged() {
        String small = "{\"service.name\":\"svc\",\"host.name\":\"host\"}";
        ClsSpanRecord record =
                new ClsSpanRecord(
                        "0123456789abcdef0123456789abcdef",
                        "0123456789abcdef",
                        "",
                        "chat model-x",
                        "client",
                        "100",
                        "200",
                        "100",
                        "OK",
                        "",
                        VALID_MINIMAL_ATTRIBUTES,
                        small,
                        "",
                        "[]",
                        "[]");

        BoundedFieldEncoder.Result result =
                new BoundedFieldEncoder(JSON, ClsFieldLimits.DEFAULT_ATTRIBUTE_MAX_BYTES)
                        .encode(record);

        assertThat(result.accepted()).isTrue();
        assertThat(result.record()).isSameAs(record);
    }

    @Test
    void rejectsNonCroppableInvalidIdsAndTimes() {
        ClsSpanRecord badTrace =
                new ClsSpanRecord(
                        "zzzz",
                        "0123456789abcdef",
                        "",
                        "chat model-x",
                        "client",
                        "100",
                        "200",
                        "100",
                        "OK",
                        "",
                        VALID_MINIMAL_ATTRIBUTES,
                        "{\"service.name\":\"svc\",\"host.name\":\"host\"}",
                        "",
                        "[]",
                        "[]");

        BoundedFieldEncoder.Result result =
                new BoundedFieldEncoder(JSON, ClsFieldLimits.DEFAULT_ATTRIBUTE_MAX_BYTES)
                        .encode(badTrace);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejection()).contains("traceID");
    }

    @Test
    void rejectsRecordAboveHardLimitEvenAfterCropping() {
        String bigName = "n".repeat(ClsFieldLimits.NAME_MAX_BYTES);
        ClsSpanRecord record =
                new ClsSpanRecord(
                        "0123456789abcdef0123456789abcdef",
                        "0123456789abcdef",
                        "",
                        bigName,
                        "client",
                        "100",
                        "200",
                        "100",
                        "OK",
                        "m".repeat(ClsFieldLimits.STATUS_MESSAGE_MAX_BYTES),
                        VALID_MINIMAL_ATTRIBUTES,
                        "{\"service.name\":\"svc\",\"host.name\":\"host\"}",
                        "t".repeat(ClsFieldLimits.TRACE_STATE_MAX_BYTES),
                        "[]",
                        "[]");

        BoundedFieldEncoder.Result result =
                new BoundedFieldEncoder(JSON, ClsFieldLimits.DEFAULT_ATTRIBUTE_MAX_BYTES, 8_192)
                        .encode(record);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejection()).contains("record");
    }

    private static final String VALID_MINIMAL_ATTRIBUTES =
            "{\"gen_ai.span.kind\":\"chat\",\"gen_ai.operation.name\":\"chat\","
                    + "\"gen_ai.agent.type\":\"agentscope-java\","
                    + "\"gen_ai.session.id\":\"s\",\"gen_ai.turn.id\":\"t\","
                    + "\"gen_ai.user.id\":\"u\",\"gen_ai.user.name\":\"U\"}";

    private static int utf8(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static ClsSpanRecord validRecord(String attributes) {
        return new ClsSpanRecord(
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                "",
                "chat model-x",
                "client",
                "100",
                "200",
                "100",
                "OK",
                "",
                attributes,
                "{\"service.name\":\"svc\",\"host.name\":\"host\"}",
                "",
                "[]",
                "[]");
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

        SpanData single() {
            assertThat(spans).hasSize(1);
            return spans.get(0);
        }
    }
}
