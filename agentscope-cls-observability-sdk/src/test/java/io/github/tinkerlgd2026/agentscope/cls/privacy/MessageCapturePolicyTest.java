package io.github.tinkerlgd2026.agentscope.cls.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    void ordinaryToolArgumentsCannotMasqueradeAsPrecomputedHash() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.HASH, ContentCaptureMode.OFF, 4096);
        Map<String, Object> attackerArguments =
                Map.of(
                        "sha256", "a".repeat(64),
                        "original_bytes", 7L,
                        "query", "weather");

        String encoded =
                policy.capture(
                                messages(
                                        Map.of(
                                                "type", "tool_call",
                                                "id", "call-1",
                                                "name", "search",
                                                "arguments", attackerArguments)),
                                false)
                        .value()
                        .orElseThrow()
                        .toString();

        assertThat(encoded)
                .doesNotContain("\"sha256\":\"" + "a".repeat(64))
                .contains("sha256", "original_bytes");
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

    @Test
    void precomputedHashesOnlyKeepWhitelistedFields() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.HASH, ContentCaptureMode.HASH, 4096);
        Map<String, Object> unsafe = new LinkedHashMap<>();
        unsafe.put("type", "reasoning_hash");
        unsafe.put("sha256", "a".repeat(64));
        unsafe.put("original_bytes", 10L);
        unsafe.put("content", "secret-plan");
        unsafe.put("metadata", Map.of("signature", "provider-signature"));

        String encoded = policy.capture(messages(unsafe), false).value().orElseThrow().toString();

        assertThat(encoded)
                .contains("reasoning_hash", "a".repeat(64), "original_bytes")
                .doesNotContain("secret-plan", "provider-signature", "metadata", "content");
    }

    @Test
    void toolResponsePreservesAvailableIdentityAndFiltersNestedReasoning() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.TRUNCATE, ContentCaptureMode.OFF, 4096);
        Map<String, Object> response =
                Map.of(
                        "type", "tool_call_response",
                        "id", "call-1",
                        "name", "search",
                        "result", List.of(
                                Map.of("type", "reasoning", "content", "private-plan"),
                                Map.of("type", "text", "content", "sunny")));

        String encoded = policy.capture(messages(response), false).value().orElseThrow().toString();

        assertThat(encoded)
                .contains("call-1", "search", "sunny")
                .doesNotContain("private-plan");
    }

    @Test
    void nullMessagesAndRolesNeverEscapeTheTelemetryBoundary() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.TRUNCATE, ContentCaptureMode.OFF, 4096);
        List<Map<String, Object>> input = new ArrayList<>();
        input.add(null);
        Map<String, Object> nullRole = new LinkedHashMap<>();
        nullRole.put("role", null);
        nullRole.put("parts", List.of(Map.of("type", "text", "content", "answer")));
        input.add(nullRole);

        String encoded = policy.capture(input, true).value().orElseThrow().toString();

        assertThat(encoded).contains("unknown", "answer");
        assertThat(policy.capture(null, true).value()).isEmpty();
    }

    @Test
    void unhashableContentFailsClosedWithoutEscaping() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.TRUNCATE, ContentCaptureMode.OFF, 4096);

        MessageCapturePolicy.CapturedMessages captured =
                policy.capture(
                        messages(Map.of("type", "text", "content", new ExplosiveValue())),
                        true);

        assertThat(captured.value()).isEmpty();
        assertThat(captured.observableHash()).isEmpty();
    }

    @Test
    void boundedSelectionSkipsExcessLowPriorityPayloadsAndKeepsFinalText() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.FULL, ContentCaptureMode.FULL, 4096);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            parts.add(
                    Map.of(
                            "type", "reasoning",
                            "content", index == 0 ? new ExplosiveValue() : "plan-" + index));
        }
        parts.add(Map.of("type", "text", "content", "final-answer"));

        String encoded =
                policy.capture(
                                List.of(Map.of("role", "assistant", "parts", parts)),
                                true)
                        .value()
                        .orElseThrow()
                        .toString();
        MessageCapturePolicy.CapturedMessages captured =
                policy.capture(List.of(Map.of("role", "assistant", "parts", parts)), true);

        assertThat(encoded).contains("final-answer");
        assertThat(captured.observableHash()).isEmpty();
    }

    @Test
    void contentQuotaKeepsLatestTextAndOmitsIncompleteObservableHash() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.TRUNCATE, ContentCaptureMode.OFF, 4096);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            parts.add(Map.of("type", "text", "content", "old-" + index));
        }
        parts.add(Map.of("type", "text", "content", "final-answer"));

        MessageCapturePolicy.CapturedMessages captured =
                policy.capture(List.of(Map.of("role", "assistant", "parts", parts)), true);

        assertThat(captured.value().orElseThrow().toString()).contains("final-answer");
        assertThat(captured.observableHash()).isEmpty();
    }

    @Test
    void finalBudgetKeepsATypePreservingTruncatedLatestTextWhenItAloneIsLong() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.FULL, ContentCaptureMode.OFF, 256);

        JsonNode captured =
                policy.capture(
                                messages(
                                        Map.of(
                                                "type", "text",
                                                "content", "final-answer-" + "x".repeat(2_000))),
                                false)
                        .value()
                        .orElseThrow();

        assertThat(captured.at("/0/parts/0/type").asText()).isEqualTo("text");
        assertThat(captured.toString()).contains("final-answer");
        assertThat(captured.toString()).doesNotContain("content_summary");
    }

    @Test
    void toolOnlyBudgetBoundsAnOversizedLatestIdentityWithoutDroppingIt() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.FULL, ContentCaptureMode.OFF, 256);

        JsonNode captured =
                policy.capture(
                                messages(
                                        Map.of(
                                                "type", "tool_call",
                                                "id", "call-final-" + "x".repeat(2_000),
                                                "name", "search-final-" + "y".repeat(2_000),
                                                "arguments", Map.of())),
                                false)
                        .value()
                        .orElseThrow();

        assertThat(captured.at("/0/parts/0/type").asText()).isEqualTo("tool_call");
        assertThat(captured.at("/0/parts/0/id").asText()).startsWith("call-final");
        assertThat(captured.at("/0/parts/0/name").asText()).startsWith("search-final");
        assertThat(captured.toString()).doesNotContain("content_summary");
    }

    @Test
    void finalBudgetDropsOlderTextUntilLatestAnswerFits() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.FULL, ContentCaptureMode.OFF, 256);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int index = 0; index < 15; index++) {
            parts.add(Map.of("type", "text", "content", ("old-" + index).repeat(50)));
        }
        parts.add(Map.of("type", "text", "content", "final-answer"));

        String encoded =
                policy.capture(List.of(Map.of("role", "assistant", "parts", parts)), false)
                        .value()
                        .orElseThrow()
                        .toString();

        assertThat(encoded).contains("final-answer");
    }

    @Test
    void toolOnlyBudgetKeepsNewestIdentityWhenAllToolIdentitiesDoNotFit() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.FULL, ContentCaptureMode.OFF, 256);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            parts.add(
                    Map.of(
                            "type", "tool_call",
                            "id", "call-" + index + "-" + "x".repeat(40),
                            "name", "search-" + index + "-" + "y".repeat(40),
                            "arguments", Map.of()));
        }

        String encoded =
                policy.capture(List.of(Map.of("role", "assistant", "parts", parts)), false)
                        .value()
                        .orElseThrow()
                        .toString();

        assertThat(encoded).contains("call-7", "search-7");
    }

    @Test
    void toolOnlyBudgetKeepsIdentityAfterDroppingReasoningAndArguments() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.FULL, ContentCaptureMode.FULL, 256);

        String encoded =
                policy.capture(
                                messages(
                                        Map.of(
                                                "type", "reasoning",
                                                "content", "private-plan".repeat(100)),
                                        Map.of(
                                                "type", "tool_call",
                                                "id", "call-final",
                                                "name", "search",
                                                "arguments", "private-argument".repeat(100))),
                                false)
                        .value()
                        .orElseThrow()
                        .toString();

        assertThat(encoded)
                .contains("tool_call", "call-final", "search")
                .doesNotContain("private-plan", "private-argument");
    }

    @Test
    void finalBudgetPreservesTextAndToolIdentityBeforeToolArguments() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.FULL, ContentCaptureMode.OFF, 256);

        String encoded =
                policy.capture(
                                messages(
                                        Map.of(
                                                "type", "tool_call",
                                                "id", "call-1",
                                                "name", "search",
                                                "arguments", "private-argument".repeat(100)),
                                        Map.of(
                                                "type", "text",
                                                "content", "final-answer")),
                                false)
                        .value()
                        .orElseThrow()
                        .toString();

        assertThat(encoded)
                .contains("final-answer", "tool_call", "call-1", "search")
                .doesNotContain("private-argument");
    }

    @Test
    void finalBudgetPreservesShortTextBeforeLongReasoningForAgentResults() {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.FULL, ContentCaptureMode.FULL, 256);

        String encoded =
                policy.capture(
                                messages(
                                        Map.of(
                                                "type", "reasoning",
                                                "content", "private-plan".repeat(100)),
                                        Map.of(
                                                "type", "text",
                                                "content", "final-answer")),
                                false)
                        .value()
                        .orElseThrow()
                        .toString();

        assertThat(encoded).contains("final-answer").doesNotContain("private-plan");
    }

    @Test
    void finalCaptureHonoursHardByteBudget() throws Exception {
        MessageCapturePolicy policy =
                new MessageCapturePolicy(
                        JSON, ContentCaptureMode.FULL, ContentCaptureMode.FULL, 256);

        JsonNode captured =
                policy.capture(
                                messages(
                                        Map.of("type", "reasoning", "content", "中".repeat(500)),
                                        Map.of("type", "text", "content", "answer")),
                                false)
                        .value()
                        .orElseThrow();

        assertThat(JSON.writeValueAsBytes(captured).length).isLessThanOrEqualTo(256);
    }

    private static final class ExplosiveValue {
        public String getValue() {
            throw new IllegalStateException("boom");
        }
    }

    @SafeVarargs
    private static List<Map<String, Object>> messages(Map<String, Object>... parts) {
        return List.of(Map.of("role", "assistant", "parts", List.of(parts)));
    }
}
