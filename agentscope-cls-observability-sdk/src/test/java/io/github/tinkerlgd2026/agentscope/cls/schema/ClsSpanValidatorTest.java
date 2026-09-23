package io.github.tinkerlgd2026.agentscope.cls.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ClsSpanValidatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VALID_ATTRIBUTES =
            "{\"gen_ai.span.kind\":\"chat\",\"gen_ai.operation.name\":\"chat\","
                    + "\"gen_ai.agent.type\":\"agentscope-java\","
                    + "\"gen_ai.session.id\":\"s\",\"gen_ai.turn.id\":\"t\","
                    + "\"gen_ai.user.id\":\"u\",\"gen_ai.user.name\":\"U\"}";

    @Test
    void rejectsMalformedMessageShapes() {
        String attributes =
                withMessages(
                        "\"gen_ai.input.messages\":["
                                + "\"not-an-object\","
                                + "{\"parts\":[]},"
                                + "{\"role\":\"user\",\"parts\":{}}," 
                                + "{\"role\":\"user\",\"parts\":[\"not-an-object\"]}]");

        assertThat(validator().validate(record(attributes)))
                .contains(
                        "attribute.gen_ai.input.messages message must be an object",
                        "attribute.gen_ai.input.messages message.role is required",
                        "attribute.gen_ai.input.messages message.parts must be a JSON array",
                        "attribute.gen_ai.input.messages part must be an object");
    }

    @Test
    void rejectsInvalidKnownPartPayloads() {
        String attributes =
                withMessages(
                        "\"gen_ai.output.messages\":[{\"role\":\"assistant\",\"parts\":["
                                + "{\"type\":\"text\",\"content\":42},"
                                + "{\"type\":\"text_hash\",\"sha256\":\"bad\",\"original_bytes\":-1},"
                                + "{\"type\":\"tool_call\",\"id\":7,\"name\":\"search\"},"
                                + "{\"type\":\"tool_call_response\",\"id\":\"c\",\"result\":{}}]}]");

        assertThat(validator().validate(record(attributes)))
                .contains(
                        "attribute.gen_ai.output.messages text.content must be a string or truncated summary",
                        "attribute.gen_ai.output.messages text_hash.sha256 is invalid",
                        "attribute.gen_ai.output.messages text_hash.original_bytes must be non-negative",
                        "attribute.gen_ai.output.messages tool_call.id must be a string",
                        "attribute.gen_ai.output.messages tool_call_response.result must be a JSON array");
    }

    @Test
    void rejectsToolResultNestedBeyondDepthLimit() {
        StringBuilder nested = new StringBuilder("{\"type\":\"text\",\"content\":\"leaf\"}");
        for (int depth = 0; depth < 17; depth++) {
            nested.insert(0, "{\"type\":\"tool_call_response\",\"id\":\"c\",\"result\":[");
            nested.append("]}");
        }
        String attributes =
                withMessages(
                        "\"gen_ai.input.messages\":[{\"role\":\"tool\",\"parts\":["
                                + nested
                                + "]}]");

        assertThat(validator().validate(record(attributes)))
                .contains(
                        "attribute.gen_ai.input.messages tool_call_response.result exceeds maximum nesting depth");
    }

    @Test
    void rejectsMalformedProviderPayloadEnvelopes() {
        String attributes =
                withMessages(
                        "\"gen_ai.output.messages\":[{\"role\":\"assistant\",\"parts\":["
                                + "{\"type\":\"provider_payload\",\"provider_payload\":\"not-object\"},"
                                + "{\"type\":\"provider_payload\",\"provider_payload\":{\"mode\":\"raw\",\"complete\":true}},"
                                + "{\"type\":\"provider_payload\",\"provider_payload\":{\"mode\":\"full\"}},"
                                + "{\"type\":\"provider_payload\",\"provider_payload\":{\"mode\":\"off\",\"complete\":true,\"payload\":\"secret-plaintext\"}},"
                                + "{\"type\":\"provider_payload\",\"provider_payload\":{\"mode\":\"hash\",\"complete\":true,\"sha256\":\"bad\",\"original_bytes\":-1}},"
                                + "{\"type\":\"provider_payload\",\"provider_payload\":{\"mode\":\"truncate\",\"complete\":true,\"payload\":{},\"retained_bytes\":-2}},"
                                + "{\"type\":\"provider_payload\",\"provider_payload\":{\"mode\":\"hash\",\"complete\":true,\"sha256\":\""
                                + "a".repeat(64)
                                + "\",\"original_bytes\":18446744073709551616,\"capture_error\":\"yes\"}}]}]");

        assertThat(validator().validate(record(attributes)))
                .contains(
                        "attribute.gen_ai.output.messages provider_payload envelope must be an object",
                        "attribute.gen_ai.output.messages provider_payload.mode is unsupported",
                        "attribute.gen_ai.output.messages provider_payload.complete must be a boolean",
                        "attribute.gen_ai.output.messages provider_payload off mode must not contain a plaintext payload",
                        "attribute.gen_ai.output.messages provider_payload.sha256 is invalid",
                        "attribute.gen_ai.output.messages provider_payload.original_bytes must be non-negative",
                        "attribute.gen_ai.output.messages provider_payload.payload must be a string",
                        "attribute.gen_ai.output.messages provider_payload.retained_bytes must be non-negative",
                        "attribute.gen_ai.output.messages provider_payload.original_bytes must fit a signed 64-bit integer",
                        "attribute.gen_ai.output.messages provider_payload.capture_error must be a boolean");
    }

    @Test
    void acceptsEveryWellFormedKnownPart() {
        String attributes =
                withMessages(
                        "\"gen_ai.output.messages\":[{\"role\":\"assistant\",\"parts\":["
                                + "{\"type\":\"text\",\"content\":\"answer\"},"
                                + "{\"type\":\"text\",\"content\":{\"truncated\":true,\"original_bytes\":10,\"preview\":\"ans\"}},"
                                + "{\"type\":\"text_hash\",\"sha256\":\""
                                + "a".repeat(64)
                                + "\",\"original_bytes\":10},"
                                + "{\"type\":\"reasoning\",\"content\":\"plan\"},"
                                + "{\"type\":\"reasoning_hash\",\"sha256\":\""
                                + "b".repeat(64)
                                + "\",\"original_bytes\":4},"
                                + "{\"type\":\"tool_call\",\"id\":\"c1\",\"name\":\"search\",\"state\":\"success\",\"arguments\":{\"q\":\"x\"}},"
                                + "{\"type\":\"tool_call_response\",\"id\":\"c1\",\"name\":\"search\",\"result\":[{\"type\":\"text\",\"content\":\"ok\"}]},"
                                + "{\"type\":\"provider_payload\",\"provider_payload\":{\"mode\":\"off\",\"complete\":true}},"
                                + "{\"type\":\"provider_payload\",\"provider_payload\":{\"mode\":\"hash\",\"complete\":true,\"sha256\":\""
                                + "c".repeat(64)
                                + "\",\"original_bytes\":12}},"
                                + "{\"type\":\"provider_payload\",\"provider_payload\":{\"mode\":\"truncate\",\"complete\":true,\"payload\":\"preview\",\"retained_bytes\":7,\"original_bytes\":20}},"
                                + "{\"type\":\"provider_payload\",\"provider_payload\":{\"mode\":\"full\",\"complete\":true,\"payload\":{\"raw\":1}}},"
                                + "{\"type\":\"provider_payload\",\"provider_payload\":{\"mode\":\"hash\",\"complete\":false,\"sha256\":\""
                                + "d".repeat(64)
                                + "\",\"original_bytes_at_least\":99,\"capture_error\":true}},"
                                + "{\"type\":\"future_part\",\"payload\":{\"x\":1}}]}]");

        assertThat(validator().validate(record(attributes))).isEmpty();
    }

    @Test
    void rejectsInvalidTurnHitlPartialAndCaptureFieldTypes() {
        String attributes =
                VALID_ATTRIBUTES.substring(0, VALID_ATTRIBUTES.length() - 1)
                        + ",\"gen_ai.turn.completed\":\"yes\""
                        + ",\"gen_ai.turn.finish_reason\":\"exploded\""
                        + ",\"gen_ai.incomplete\":\"no\""
                        + ",\"gen_ai.turn.resume_from_turn_id\":7"
                        + ",\"gen_ai.hitl.wait_count\":-1"
                        + ",\"gen_ai.hitl.total_wait_ms\":\"soon\""
                        + ",\"gen_ai.partial_failure\":\"maybe\""
                        + ",\"gen_ai.failed_tool_count\":-3"
                        + ",\"agentscope.capture.truncated\":\"true\""
                        + ",\"agentscope.capture.hash_complete\":1"
                        + ",\"agentscope.capture.original_bytes\":-1"
                        + ",\"agentscope.capture.retained_bytes\":\"many\""
                        + ",\"agentscope.capture.capacity_dropped_parts\":1.5"
                        + ",\"agentscope.capture.capacity_dropped_bytes\":-9"
                        + ",\"agentscope.capture.failure_count\":-1}";

        assertThat(validator().validate(record(attributes)))
                .contains(
                        "attribute.gen_ai.turn.completed must be a boolean",
                        "attribute.gen_ai.turn.finish_reason is unsupported",
                        "attribute.gen_ai.incomplete must be a boolean",
                        "attribute.gen_ai.turn.resume_from_turn_id must be a string",
                        "attribute.gen_ai.hitl.wait_count must be non-negative",
                        "attribute.gen_ai.hitl.total_wait_ms must be an integer",
                        "attribute.gen_ai.partial_failure must be a boolean",
                        "attribute.gen_ai.failed_tool_count must be non-negative",
                        "attribute.agentscope.capture.truncated must be a boolean",
                        "attribute.agentscope.capture.hash_complete must be a boolean",
                        "attribute.agentscope.capture.original_bytes must be non-negative",
                        "attribute.agentscope.capture.retained_bytes must be an integer",
                        "attribute.agentscope.capture.capacity_dropped_parts must be an integer",
                        "attribute.agentscope.capture.capacity_dropped_bytes must be non-negative",
                        "attribute.agentscope.capture.failure_count must be non-negative");
    }

    @Test
    void acceptsValidTurnHitlPartialAndCaptureFields() {
        String attributes =
                VALID_ATTRIBUTES.substring(0, VALID_ATTRIBUTES.length() - 1)
                        + ",\"gen_ai.turn.completed\":true"
                        + ",\"gen_ai.turn.finish_reason\":\"await_timeout\""
                        + ",\"gen_ai.incomplete\":true"
                        + ",\"gen_ai.turn.resume_from_turn_id\":\"turn-0\""
                        + ",\"gen_ai.hitl.wait_count\":2"
                        + ",\"gen_ai.hitl.total_wait_ms\":1200"
                        + ",\"gen_ai.partial_failure\":true"
                        + ",\"gen_ai.failed_tool_count\":1"
                        + ",\"agentscope.capture.truncated\":true"
                        + ",\"agentscope.capture.hash_complete\":false"
                        + ",\"agentscope.capture.original_bytes\":100"
                        + ",\"agentscope.capture.retained_bytes\":50"
                        + ",\"agentscope.capture.capacity_dropped_parts\":1"
                        + ",\"agentscope.capture.capacity_dropped_bytes\":20"
                        + ",\"agentscope.capture.failure_count\":0}";

        assertThat(validator().validate(record(attributes))).isEmpty();
    }

    @Test
    void acceptsValidTruncatedFieldEnvelopesAndRejectsMalformedOnes() {
        ClsSpanRecord cropped =
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
                        "{\"truncated\":true,\"original_bytes\":2000000,\"sha256\":\""
                                + "a".repeat(64)
                                + "\",\"preview\":\"{\\\"gen_ai\"}",
                        "{\"truncated\":true,\"original_bytes\":70000,\"sha256\":\""
                                + "b".repeat(64)
                                + "\",\"preview\":\"{\\\"service\"}",
                        "",
                        "{\"truncated\":true,\"original_bytes\":140000,\"sha256\":\""
                                + "c".repeat(64)
                                + "\",\"preview\":\"[{\\\"traceID\"}",
                        "{\"truncated\":true,\"original_bytes\":270000,\"sha256\":\""
                                + "d".repeat(64)
                                + "\",\"preview\":\"[{\\\"name\"}");

        assertThat(validator().validate(cropped)).isEmpty();

        ClsSpanRecord malformed =
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
                        "{\"truncated\":true,\"original_bytes\":-1,\"sha256\":\"bad\",\"preview\":1}",
                        "{\"service.name\":\"svc\",\"host.name\":\"host\"}",
                        "",
                        "[]",
                        "[]");

        assertThat(validator().validate(malformed))
                .contains("attribute is not a valid truncated field envelope");
    }

    @Test
    void rejectsPlainObjectsForLinksAndLogs() {
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
                        VALID_ATTRIBUTES,
                        "{\"service.name\":\"svc\",\"host.name\":\"host\"}",
                        "",
                        "{\"x\":1}",
                        "{\"y\":2}");

        assertThat(validator().validate(record))
                .contains(
                        "links must encode a JSON array",
                        "logs must encode a JSON array");
    }

    @Test
    void rejectsPlaintextPayloadInHashModeEnvelope() {
        String attributes =
                withMessages(
                        "\"gen_ai.output.messages\":[{\"role\":\"assistant\",\"parts\":["
                                + "{\"type\":\"provider_payload\",\"provider_payload\":{"
                                + "\"mode\":\"hash\",\"complete\":true,\"sha256\":\""
                                + "a".repeat(64)
                                + "\",\"original_bytes\":5,\"payload\":\"secret-plaintext\"}}]}]");

        assertThat(validator().validate(record(attributes)))
                .contains(
                        "attribute.gen_ai.output.messages provider_payload hash mode must not contain a plaintext payload");
    }

    private static String withMessages(String keyAndValue) {
        return VALID_ATTRIBUTES.substring(0, VALID_ATTRIBUTES.length() - 1)
                + ","
                + keyAndValue
                + "}";
    }

    private static ClsSpanValidator validator() {
        return new ClsSpanValidator(JSON);
    }

    private static ClsSpanRecord record(String attributes) {
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
}
