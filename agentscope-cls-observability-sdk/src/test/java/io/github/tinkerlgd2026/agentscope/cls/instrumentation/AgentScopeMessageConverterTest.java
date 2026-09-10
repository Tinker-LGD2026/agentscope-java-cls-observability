package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentScopeMessageConverterTest {

    @Test
    void convertsAgentScopeMessagesToClsRolePartsSchema() {
        Msg user =
                Msg.builder()
                        .name("alice")
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("hello").build()))
                        .build();
        Msg assistant =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder().thinking("reason").build(),
                                        ToolUseBlock.builder()
                                                .id("call-1")
                                                .name("search")
                                                .input(Map.of("query", "weather"))
                                                .build(),
                                        new ToolResultBlock(
                                                "call-1",
                                                "search",
                                                TextBlock.builder().text("sunny").build())))
                        .build();

        List<Map<String, Object>> converted =
                new AgentScopeMessageConverter().convert(List.of(user, assistant));

        assertThat(converted).hasSize(2);
        assertThat(converted.get(0))
                .containsEntry("role", "user")
                .containsEntry("name", "alice");
        assertThat(converted.get(0).get("parts"))
                .isEqualTo(List.of(Map.of("type", "text", "content", "hello")));
        assertThat(converted.get(1).get("parts"))
                .isEqualTo(
                        List.of(
                                Map.of("type", "reasoning", "content", "reason"),
                                Map.of(
                                        "type",
                                        "tool_call",
                                        "id",
                                        "call-1",
                                        "name",
                                        "search",
                                        "arguments",
                                        Map.of("query", "weather")),
                                Map.of(
                                        "type",
                                        "tool_call_response",
                                        "id",
                                        "call-1",
                                        "result",
                                        List.of(
                                                Map.of(
                                                        "type",
                                                        "text",
                                                        "content",
                                                        "sunny")))));
    }
}
