package io.github.tinkerlgd2026.agentscope.cls.demo.travel;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.ReActAgent;
import io.github.tinkerlgd2026.agentscope.cls.ClsAgentObservability;
import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig;
import io.github.tinkerlgd2026.agentscope.cls.ClsTelemetrySnapshot;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

/**
 * Live DeepSeek → CLS verification gate. Skipped unless the environment explicitly opts in
 * with credentials; no secret is ever committed to the repository.
 *
 * <p>Required environment: {@code CLS_LIVE_VERIFY=true}, {@code DEEPSEEK_API_KEY},
 * {@code CLS_TRANSPORT=cloud}, {@code CLS_ENDPOINT}, {@code CLS_TOPIC_ID},
 * {@code CLS_SECRET_ID}, {@code CLS_SECRET_KEY}.
 */
class LiveTravelVerificationTest {

    @Test
    void liveTurnDeliversCleanTelemetry() {
        Map<String, String> environment = System.getenv();
        Assumptions.assumeTrue(
                "true".equals(environment.get("CLS_LIVE_VERIFY")),
                "live verification disabled (set CLS_LIVE_VERIFY=true)");
        for (String key :
                new String[] {
                    "DEEPSEEK_API_KEY",
                    "CLS_ENDPOINT",
                    "CLS_TOPIC_ID",
                    "CLS_SECRET_ID",
                    "CLS_SECRET_KEY"
                }) {
            String value = environment.get(key);
            Assumptions.assumeTrue(
                    value != null && !value.isBlank(), "missing live credential " + key);
        }

        TravelDemoSettings settings = TravelDemoSettings.fromEnvironment(environment);
        ClsObservabilityConfig clsConfig =
                ClsObservabilityConfig.fromEnvironment(environment);
        try (TravelPlannerApplication.ModelResources modelResources =
                        TravelPlannerApplication.createModel(
                                settings.deepSeekApiKey(), settings.reasoningEnabled());
                ClsAgentObservability observability =
                        ClsAgentObservability.create(clsConfig)) {
            clsConfig.destroyCredentials();
            OpenMeteoWeatherTool weatherTool = new OpenMeteoWeatherTool();
            try (ReActAgent planner =
                    TravelPlannerApplication.createPlannerAgent(
                            modelResources.model(), observability, weatherTool)) {
                String answer =
                        TravelPlannerApplication.runTurn(
                                planner,
                                settings.newRuntimeContext(),
                                settings.userName(),
                                settings.firstPrompt(),
                                Duration.ofMinutes(4));
                assertThat(answer).isNotBlank();
            }
            assertThat(observability.shutdown(Duration.ofSeconds(45))).isTrue();
            ClsTelemetrySnapshot snapshot = observability.snapshot();
            assertThat(snapshot.acceptedSpans()).isPositive();
            assertThat(snapshot.invalidSpans()).isZero();
            assertThat(snapshot.exportFailures()).isZero();
            assertThat(snapshot.droppedSpans()).isZero();
        } finally {
            Schedulers.shutdownNow();
        }
    }
}
