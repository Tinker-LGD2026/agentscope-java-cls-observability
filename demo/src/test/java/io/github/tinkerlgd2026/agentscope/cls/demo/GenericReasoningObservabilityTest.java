package io.github.tinkerlgd2026.agentscope.cls.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.github.tinkerlgd2026.agentscope.cls.ClsAgentObservability;
import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.transport.InMemorySpanSink;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class GenericReasoningObservabilityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void capturesGenericThinkingBlocksWithoutProviderMetadata() throws Exception {
        TraceResult result = run(ContentCaptureMode.TRUNCATE);

        assertThat(result.kinds()).contains("entry", "agent", "step", "chat");
        JsonNode chat = result.chatAttributes();
        String output = chat.path("gen_ai.output.messages").toString();
        assertThat(output)
                .contains("reasoning", "inspect constraints", "text", "final answer");
        assertThat(output.indexOf("inspect constraints"))
                .isLessThan(output.indexOf("final answer"));
        assertThat(result.allAttributes())
                .doesNotContain("must-not-export", "opaque", "reasoning_output_tokens");
        assertThat(chat.path("agentscope.reasoning.present").asBoolean()).isTrue();
    }

    @Test
    void defaultsToReasoningOffWhileKeepingMetricsAndFinalAnswer() throws Exception {
        ClsObservabilityConfig defaults = ClsObservabilityConfig.builder().build();
        assertThat(defaults.reasoningCaptureMode()).isEqualTo(ContentCaptureMode.OFF);

        TraceResult result = run(null);
        JsonNode chat = result.chatAttributes();
        String output = chat.path("gen_ai.output.messages").toString();

        assertThat(output).contains("final answer").doesNotContain("inspect constraints");
        assertThat(chat.path("agentscope.reasoning.present").asBoolean()).isTrue();
        assertThat(chat.path("agentscope.reasoning.capture_mode").asText()).isEqualTo("off");
    }

    private static TraceResult run(ContentCaptureMode reasoningMode) throws Exception {
        InMemorySpanSink sink = new InMemorySpanSink();
        ClsObservabilityConfig.Builder config =
                ClsObservabilityConfig.builder()
                        .contentCaptureMode(ContentCaptureMode.TRUNCATE);
        if (reasoningMode != null) {
            config.reasoningCaptureMode(reasoningMode);
        }
        try (ClsAgentObservability observability =
                        ClsAgentObservability.create(config.build(), sink);
                ReActAgent agent =
                        ReActAgent.builder()
                                .name("generic-reasoning-agent")
                                .sysPrompt("Answer directly without tools.")
                                .model(new ReasoningModel())
                                .middleware(observability.middleware())
                                .maxIters(2)
                                .build()) {
            Msg request =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .name("Example User")
                            .textContent("answer the request")
                            .build();
            Msg response =
                    agent.call(
                                    List.of(request),
                                    RuntimeContext.builder()
                                            .sessionId("reasoning-session")
                                            .userId("reasoning-user")
                                            .build())
                            .block(Duration.ofSeconds(5));
            assertThat(response).isNotNull();
            assertThat(response.getTextContent()).isEqualTo("final answer");
            assertThat(observability.flush(Duration.ofSeconds(5))).isTrue();
        }

        List<ClsSpanRecord> records = sink.records();
        Map<ClsSpanRecord, JsonNode> decoded =
                records.stream()
                        .collect(
                                Collectors.toMap(
                                        record -> record,
                                        record -> {
                                            try {
                                                return JSON.readTree(record.attribute());
                                            } catch (Exception exception) {
                                                throw new IllegalArgumentException(exception);
                                            }
                                        }));
        Set<String> kinds =
                decoded.values().stream()
                        .map(node -> node.path("gen_ai.span.kind").asText())
                        .collect(Collectors.toSet());
        JsonNode chat =
                decoded.values().stream()
                        .filter(node -> "chat".equals(node.path("gen_ai.span.kind").asText()))
                        .findFirst()
                        .orElseThrow();
        String allAttributes =
                records.stream().map(ClsSpanRecord::attribute).collect(Collectors.joining("\n"));
        return new TraceResult(kinds, chat, allAttributes);
    }

    private static final class ReasoningModel implements Model {
        @Override
        public String getModelName() {
            return "generic-reasoning-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages,
                List<ToolSchema> tools,
                GenerateOptions options) {
            return Flux.just(
                    ChatResponse.builder()
                            .id("reasoning-response")
                            .content(
                                    List.of(
                                            ThinkingBlock.builder()
                                                    .thinking("inspect constraints")
                                                    .metadata(
                                                            Map.of(
                                                                    "signature", "must-not-export",
                                                                    "encrypted", "opaque"))
                                                    .build(),
                                            TextBlock.builder().text("final answer").build()))
                            .usage(new ChatUsage(10, 6, 0.1))
                            .metadata(Map.of("provider", "generic-test"))
                            .finishReason("stop")
                            .build());
        }
    }

    private record TraceResult(Set<String> kinds, JsonNode chatAttributes, String allAttributes) {}
}
