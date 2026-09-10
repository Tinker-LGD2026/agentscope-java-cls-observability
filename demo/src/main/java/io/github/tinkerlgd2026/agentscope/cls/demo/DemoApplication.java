package io.github.tinkerlgd2026.agentscope.cls.demo;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.github.tinkerlgd2026.agentscope.cls.ClsAgentObservability;
import io.github.tinkerlgd2026.agentscope.cls.ClsInvocationContext;
import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Flux;

public final class DemoApplication {
    private DemoApplication() {}

    public static void main(String[] args) {
        try (ClsAgentObservability observability =
                ClsAgentObservability.create(offlineConfig())) {
            String reply = run(observability);
            if (!observability.flush(Objects.requireNonNull(Duration.ofSeconds(5)))) {
                System.err.println("Telemetry warning: local flush did not complete");
            }
            System.err.println("Agent reply: " + reply);
            System.err.println("Telemetry: " + observability.snapshot());
        }
    }

    static ClsObservabilityConfig offlineConfig() {
        return ClsObservabilityConfig.builder()
                .transportMode(ClsObservabilityConfig.TransportMode.CONSOLE)
                .serviceName("agentscope-cls-offline-demo")
                .build();
    }

    public static String run(ClsAgentObservability observability) {
        if (observability == null) {
            throw new IllegalArgumentException("observability is required");
        }
        RuntimeContext context =
                RuntimeContext.builder()
                        .sessionId("demo-session")
                        .userId("demo-user")
                        .put(
                                ClsInvocationContext.class,
                                new ClsInvocationContext(
                                        "Demo User", null, "agentscope-java", "java-sdk"))
                        .build();
        try (ReActAgent agent =
                ReActAgent.builder()
                        .name("cls-demo-agent")
                        .sysPrompt("Return the deterministic local demo response.")
                        .model(new LocalModel())
                        .middleware(observability.middleware())
                        .maxIters(2)
                        .build()) {
            Msg request =
                    Msg.builder()
                            .name("demo-user")
                            .role(MsgRole.USER)
                            .textContent("Run the local CLS trace demo.")
                            .build();
            Msg response =
                    agent.call(List.of(request), context)
                            .block(Objects.requireNonNull(Duration.ofSeconds(5)));
            if (response == null) {
                throw new IllegalStateException("local model returned no response");
            }
            return response.getTextContent();
        }
    }

    private static final class LocalModel implements Model {
        @Override
        public String getModelName() {
            return "local-deterministic-model";
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            ChatResponse response =
                    ChatResponse.builder()
                            .id("local-response")
                            .content(List.of(TextBlock.builder().text("Local demo completed.").build()))
                            .usage(new ChatUsage(8, 4, 0, 0.01))
                            .metadata(Map.of("provider", "local"))
                            .finishReason("stop")
                            .build();
            return Flux.just(response);
        }
    }
}
