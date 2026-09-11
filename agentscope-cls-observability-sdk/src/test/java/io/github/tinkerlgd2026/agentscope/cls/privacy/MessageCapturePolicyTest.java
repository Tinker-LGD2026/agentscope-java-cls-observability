package io.github.tinkerlgd2026.agentscope.cls.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MessageCapturePolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void reasoningOffRemovesReasoningFromCaptureAndObservableHash() {
        List<Map<String, Object>> withReasoning =
                messages(
                        Map.of("type", "reasoning", "content", "secret-plan"),
                        Map.of("type", "text", "content", "answer"));
        List<Map<String, Object>> withoutReasoning =
                messages(Map.of("type", "text", "content", "answer"));
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON,
                        ContentCaptureMode.TRUNCATE,
                        ContentCaptureMode.OFF,
                        4096);

        var first = policy.capture(withReasoning, true);
        var second = policy.capture(withoutReasoning, true);

        assertThat(first.value().orElseThrow().toString()).doesNotContain("secret-plan");
        assertThat(first.observableHash()).isEqualTo(second.observableHash());
    }

    @ParameterizedTest
    @CsvSource({
        "OFF,TRUNCATE,false,true",
        "TRUNCATE,OFF,true,false",
        "TRUNCATE,HASH,true,true",
        "OFF,HASH,false,true",
        "FULL,TRUNCATE,true,true"
    })
    void appliesContentAndReasoningModesIndependently(
            ContentCaptureMode contentMode,
            ContentCaptureMode reasoningMode,
            boolean textVisible,
            boolean reasoningRepresented) {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(JSON, contentMode, reasoningMode, 4096);

        String encoded =
                policy.capture(
                                messages(
                                        Map.of(
                                                "type",
                                                "reasoning",
                                                "content",
                                                "private-plan"),
                                        Map.of(
                                                "type",
                                                "text",
                                                "content",
                                                "public-answer")),
                                false)
                        .value()
                        .map(JsonNode::toString)
                        .orElse("");

        assertThat(encoded.contains("public-answer")).isEqualTo(textVisible);
        assertThat(encoded.contains("private-plan") || encoded.contains("reasoning_hash"))
                .isEqualTo(reasoningRepresented);
    }

    @Test
    void hashModesNeverExposePlaintext() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.HASH, ContentCaptureMode.HASH, 4096);

        String encoded =
                policy.capture(
                                messages(
                                        Map.of(
                                                "type",
                                                "reasoning",
                                                "content",
                                                "private-plan"),
                                        Map.of(
                                                "type",
                                                "text",
                                                "content",
                                                "public-answer")),
                                false)
                        .value()
                        .orElseThrow()
                        .toString();

        assertThat(encoded)
                .contains("reasoning_hash", "text_hash", "sha256", "original_bytes")
                .doesNotContain("private-plan", "public-answer");
    }

    @Test
    void contentOffPreservesToolIdentityButDropsArguments() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.OFF, ContentCaptureMode.OFF, 4096);
        List<Map<String, Object>> input =
                messages(
                        Map.of(
                                "type",
                                "tool_call",
                                "id",
                                "call-1",
                                "name",
                                "search",
                                "arguments",
                                Map.of("query", "private")));

        String encoded = policy.capture(input, false).value().orElseThrow().toString();

        assertThat(encoded).contains("call-1", "search").doesNotContain("private", "query");
    }

    @SafeVarargs
    private static List<Map<String, Object>> messages(Map<String, Object>... parts) {
        return List.of(Map.of("role", "assistant", "parts", List.of(parts)));
    }
}
