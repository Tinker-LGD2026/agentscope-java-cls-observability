package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProviderPayloadMapperTest {

    @Test
    void mapsEveryAgentScopeBlockWithExplicitWhitelistedFields() {
        ProviderPayloadMapper mapper = new ProviderPayloadMapper();
        List<Map<String, Object>> mapped =
                List.of(
                        mapper.map(TextBlock.builder().text("answer").build()),
                        mapper.map(
                                ThinkingBlock.builder()
                                        .thinking("plan")
                                        .metadata(Map.of("signature", "opaque"))
                                        .build()),
                        mapper.map(
                                new ToolUseBlock(
                                        "call-1",
                                        "search",
                                        Map.of("query", "weather"),
                                        "provider-content",
                                        Map.of("thought_signature", "sig"))),
                        mapper.map(
                                new ToolResultBlock(
                                        "call-1",
                                        "search",
                                        List.of(TextBlock.builder().text("sunny").build()),
                                        Map.of("provider", "result-meta"),
                                        ToolResultState.SUCCESS)),
                        mapper.map(new ImageBlock(new URLSource("https://example.test/a.png", "image/png"))),
                        mapper.map(new AudioBlock(new Base64Source("audio/wav", "QUJD"))),
                        mapper.map(VideoBlock.builder().source(new URLSource("https://example.test/v.mp4")).fps(24F).build()),
                        mapper.map(DataBlock.builder().id("data-1").name("blob").source(new Base64Source("application/octet-stream", "AAEC")).build()),
                        mapper.map(new HintBlock("hint-1", "use cache", "provider")));

        assertThat(mapped).extracting(value -> value.get("type"))
                .containsExactly(
                        "text", "thinking", "tool_use", "tool_result", "image", "audio", "video", "data", "hint");
        assertThat(mapped.toString())
                .contains("signature", "provider-content", "result-meta", "https://example.test/a.png", "QUJD", "AAEC");
        assertThat(mapped.get(4)).containsKey("source");
        assertThat(mapped.get(6)).containsEntry("fps", 24F);
    }

    @Test
    void mapsMessageMetadataTimestampAndGenerateReason() {
        Msg message =
                Msg.builder()
                        .id("msg-1")
                        .name("assistant")
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("done").build())
                        .metadata(Map.of("provider_request_id", "req-1"))
                        .build();

        Map<String, Object> mapped = new ProviderPayloadMapper().map(message);

        assertThat(mapped)
                .containsEntry("id", "msg-1")
                .containsEntry("name", "assistant")
                .containsEntry("role", "assistant")
                .containsKey("timestamp")
                .containsKey("metadata")
                .containsKey("content");
    }

    @Test
    void ordinaryMessageConversionStillExcludesProviderMetadata() {
        Msg message =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ThinkingBlock.builder()
                                        .thinking("visible")
                                        .metadata(Map.of("signature", "private-signature"))
                                        .build())
                        .metadata(Map.of("request", "private-request"))
                        .build();

        AgentScopeMessageConverter converter = new AgentScopeMessageConverter();
        String semantic = converter.convert(List.of(message)).toString();
        String provider = converter.providerPayload(message).toString();

        assertThat(semantic).contains("visible").doesNotContain("private-signature", "private-request");
        assertThat(provider).contains("private-signature", "private-request");
    }
}
