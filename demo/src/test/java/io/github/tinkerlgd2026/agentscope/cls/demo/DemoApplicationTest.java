package io.github.tinkerlgd2026.agentscope.cls.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tinkerlgd2026.agentscope.cls.ClsAgentObservability;
import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig;
import io.github.tinkerlgd2026.agentscope.cls.transport.ConsoleSpanSink;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DemoApplicationTest {

    @Test
    void offlineDemoAlwaysUsesConsoleTransport() {
        assertThat(DemoApplication.offlineConfig().transportMode())
                .isEqualTo(ClsObservabilityConfig.TransportMode.CONSOLE);
    }

    @Test
    void runsOfflineAndEmitsACompleteAgentTrace() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ObjectMapper json = new ObjectMapper();
        try (ClsAgentObservability observability =
                ClsAgentObservability.create(
                        ClsObservabilityConfig.builder().build(),
                        new ConsoleSpanSink(
                                json, new PrintStream(output, true, StandardCharsets.UTF_8)))) {
            String reply = DemoApplication.run(observability);
            assertThat(reply).isEqualTo("Local demo completed.");
            assertThat(observability.flush(java.time.Duration.ofSeconds(1))).isTrue();
        }

        Set<String> kinds =
                Arrays.stream(output.toString(StandardCharsets.UTF_8).split("\\R"))
                        .filter(line -> !line.isBlank())
                        .map(
                                line -> {
                                    try {
                                        JsonNode record = json.readTree(line);
                                        return json.readTree(record.get("attribute").asText())
                                                .get("gen_ai.span.kind")
                                                .asText();
                                    } catch (Exception exception) {
                                        throw new IllegalArgumentException(exception);
                                    }
                                })
                        .collect(Collectors.toSet());

        assertThat(kinds).containsExactlyInAnyOrder("entry", "agent", "step", "chat");
    }
}
