package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentScopeMessageConverterTest {

    @Test
    void neverCopiesThinkingProviderMetadata() {
        Msg assistant =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        ThinkingBlock.builder()
                                                .thinking("visible reasoning")
                                                .metadata(
                                                        Map.of(
                                                                "signature", "provider-signature",
                                                                "encrypted", "opaque-ciphertext"))
                                                .build()))
                        .build();

        String converted = new AgentScopeMessageConverter().convert(List.of(assistant)).toString();

        assertThat(converted)
                .contains("visible reasoning")
                .doesNotContain("provider-signature", "opaque-ciphertext", "metadata");
    }

    @Test
    void boundsConversionWhilePreservingLatestTextAndToolIdentity() {
        List<io.agentscope.core.message.ContentBlock> blocks = new java.util.ArrayList<>();
        for (int index = 0; index < 400; index++) {
            blocks.add(ThinkingBlock.builder().thinking("old-plan-" + index).build());
        }
        blocks.add(TextBlock.builder().text("final-answer").build());
        blocks.add(
                ToolUseBlock.builder()
                        .id("call-final")
                        .name("search")
                        .input(Map.of("query", "weather"))
                        .build());
        Msg assistant = Msg.builder().role(MsgRole.ASSISTANT).content(blocks).build();

        AgentScopeMessageConverter.ConversionResult converted =
                new AgentScopeMessageConverter().convertBounded(List.of(assistant));
        String value = converted.messages().toString();

        assertThat(converted.complete()).isFalse();
        assertThat(value).contains("final-answer", "call-final", "search");
        assertThat(value.length()).isLessThan(10_000);
    }

    @Test
    void providerOffSemanticMediaDataAndHintExposeOnlyType() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                List.of(
                                        new ImageBlock(
                                                new URLSource(
                                                        "https://user:pass@example.test/private?token=x")),
                                        DataBlock.builder()
                                                .id("private-id")
                                                .name("private-name")
                                                .source(new URLSource("https://example.test/data"))
                                                .build(),
                                        new HintBlock("private-hint-id", "private-hint", "private-source")))
                        .build();

        String converted =
                new AgentScopeMessageConverter().convert(List.of(message)).toString();

        assertThat(converted)
                .contains("{type=image}", "{type=data}", "{type=hint}")
                .doesNotContain(
                        "user:pass",
                        "token=x",
                        "private-id",
                        "private-name",
                        "private-hint",
                        "private-source");
    }

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
