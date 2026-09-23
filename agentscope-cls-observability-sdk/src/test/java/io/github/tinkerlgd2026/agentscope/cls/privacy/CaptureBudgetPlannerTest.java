package io.github.tinkerlgd2026.agentscope.cls.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaptureBudgetPlannerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void keepsFinalTextToolIdentitiesAndRecentUserBeforeLowerPriorityPayloads() throws Exception {
        CaptureBudgetPlanner planner = new CaptureBudgetPlanner(JSON, 320);
        List<Map<String, Object>> messages =
                List.of(
                        message("user", part("text", "content", "early-user-" + "e".repeat(300))),
                        message("assistant", part("provider_payload", "payload", "provider-" + "p".repeat(300))),
                        message("user", part("text", "content", "recent-user")),
                        message(
                                "assistant",
                                Map.of(
                                        "type", "tool_call",
                                        "id", "call-1",
                                        "name", "search",
                                        "state", "pending",
                                        "arguments", "arguments-" + "a".repeat(300)),
                                part("reasoning", "content", "reasoning-" + "r".repeat(300)),
                                part("text", "content", "final-answer")));

        JsonNode result = planner.capture(messages).value().orElseThrow();
        String encoded = result.toString();

        assertThat(JSON.writeValueAsBytes(result).length).isLessThanOrEqualTo(320);
        assertThat(encoded)
                .contains("final-answer", "call-1", "search", "pending", "recent-user")
                .doesNotContain("early-user", "provider-", "reasoning-", "arguments-");
    }

    @Test
    void keepsNewestToolIdentitiesInOriginalOrderWhenAllCannotFit() throws Exception {
        CaptureBudgetPlanner planner = new CaptureBudgetPlanner(JSON, 256);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            parts.add(
                    Map.of(
                            "type", "tool_call",
                            "id", "call-" + index,
                            "name", "tool-" + index,
                            "state", "pending"));
        }

        JsonNode result = planner.capture(List.of(message("assistant", parts))).value().orElseThrow();
        String encoded = result.toString();

        assertThat(JSON.writeValueAsBytes(result).length).isLessThanOrEqualTo(256);
        assertThat(encoded).contains("call-11");
        assertThat(encoded.indexOf("call-10")).isLessThan(encoded.indexOf("call-11"));
    }

    @Test
    void reportsExactRetainedBytesWithoutInventingServiceChunks() throws Exception {
        CaptureBudgetPlanner.Result result =
                new CaptureBudgetPlanner(JSON, 256)
                        .capture(List.of(message("assistant", part("text", "content", "😀".repeat(200)))));
        JsonNode value = result.value().orElseThrow();

        assertThat(result.retainedBytes()).isEqualTo(JSON.writeValueAsBytes(value).length);
        assertThat(result.retainedBytes()).isLessThanOrEqualTo(256);
        assertThat(value.toString()).doesNotContain("chunk", "companion_log");
        assertThat(result.truncated()).isTrue();
    }

    @SafeVarargs
    private static Map<String, Object> message(
            String role, Map<String, Object>... parts) {
        return Map.of("role", role, "parts", List.of(parts));
    }

    private static Map<String, Object> message(
            String role, List<Map<String, Object>> parts) {
        return Map.of("role", role, "parts", parts);
    }

    private static Map<String, Object> part(String type, String key, Object value) {
        return Map.of("type", type, key, value);
    }
}
