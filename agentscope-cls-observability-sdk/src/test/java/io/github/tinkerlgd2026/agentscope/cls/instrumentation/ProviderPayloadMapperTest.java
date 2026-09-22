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
        assertThat(mapped.get(0)).containsOnlyKeys("type");
        assertThat(mapped.get(1)).containsOnlyKeys("type", "metadata");
        assertThat(mapped.get(2)).containsOnlyKeys("type", "content", "metadata", "state");
        assertThat(mapped.get(3)).containsOnlyKeys("type", "metadata", "state");
        assertThat(mapped.toString())
                .contains("signature", "provider-content", "result-meta", "https://example.test/a.png", "QUJD", "AAEC")
                .doesNotContain("weather", "sunny");
        Map<?, ?> imageSource = (Map<?, ?>) mapped.get(4).get("source");
        assertThat(imageSource.get("kind")).isEqualTo("url");
        assertThat(imageSource.containsKey("type")).isFalse();
        Map<?, ?> audioSource = (Map<?, ?>) mapped.get(5).get("source");
        assertThat(audioSource.get("kind")).isEqualTo("base64");
        assertThat(audioSource.containsKey("type")).isFalse();
        assertThat(mapped.get(6)).containsEntry("fps", 24F);
    }

    @Test
    void supportsUrlAndBase64ForEveryMediaKindAndUnknownSource() {
        ProviderPayloadMapper mapper = new ProviderPayloadMapper();
        List<Map<String, Object>> mapped =
                List.of(
                        mapper.map(new ImageBlock(new Base64Source("image/png", "IMAGE64"))),
                        mapper.map(new AudioBlock(new URLSource("https://example.test/a.wav"))),
                        mapper.map(VideoBlock.builder().source(new Base64Source("video/mp4", "VIDEO64")).build()),
                        mapper.map(new ImageBlock(new io.agentscope.core.message.Source())));

        assertThat(mapped.get(0).toString()).contains("kind=base64", "IMAGE64");
        assertThat(mapped.get(1).toString()).contains("kind=url", "a.wav");
        assertThat(mapped.get(2).toString()).contains("kind=base64", "VIDEO64");
        assertThat(mapped.get(3).toString()).contains("unsupported=true", "kind=Source");
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
                .containsKey("timestamp")
                .containsKey("generate_reason")
                .containsEntry("msg_metadata", Map.of("provider_request_id", "req-1"))
                .doesNotContainKeys("id", "name", "role", "content", "metadata");
    }

    @Test
    void boundsLargeMetadataAndHandlesNullValues() {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("nullable", null);
        for (int index = 0; index < 300; index++) {
            metadata.put("z-key-" + index, "value-" + index);
        }
        Msg message =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("ordinary").build())
                        .metadata(metadata)
                        .build();

        ProviderPayloadMapper.MappingResult result =
                new ProviderPayloadMapper().mapBounded(message);

        assertThat(result.complete()).isFalse();
        assertThat(result.payload()).containsOnlyKeys("timestamp", "generate_reason", "msg_metadata");
        Map<?, ?> boundedMetadata = (Map<?, ?>) result.payload().get("msg_metadata");
        assertThat(boundedMetadata).hasSizeLessThanOrEqualTo(256);
    }

    @Test
    void depthLimitNeverReturnsOriginalNestedContainer() {
        Object nested = Map.of("secret", "deep-secret");
        for (int depth = 0; depth < 20; depth++) {
            nested = Map.of("next", nested);
        }
        Msg message =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("ordinary").build())
                        .metadata(Map.of("nested", nested))
                        .build();

        ProviderPayloadMapper.MappingResult result =
                new ProviderPayloadMapper().mapBounded(message);

        assertThat(result.complete()).isFalse();
        assertThat(result.payload().toString())
                .contains("[TRUNCATED]")
                .doesNotContain("deep-secret");
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
        String messageProvider = converter.providerPayload(message).toString();
        String blockProvider =
                converter.providerPayload(message.getContent().get(0)).toString();

        assertThat(semantic).contains("visible").doesNotContain("private-signature", "private-request");
        assertThat(messageProvider).contains("private-request").doesNotContain("private-signature");
        assertThat(blockProvider).contains("private-signature").doesNotContain("visible");
    }
}
