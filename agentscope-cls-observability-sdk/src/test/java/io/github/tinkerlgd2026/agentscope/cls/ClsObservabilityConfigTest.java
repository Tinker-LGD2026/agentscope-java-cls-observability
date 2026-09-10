package io.github.tinkerlgd2026.agentscope.cls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig.TransportMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClsObservabilityConfigTest {

    @Test
    void usesSafeConsoleDefaultsWithoutCloudConfiguration() {
        ClsObservabilityConfig config = ClsObservabilityConfig.fromEnvironment(Map.of());

        assertThat(config.transportMode()).isEqualTo(TransportMode.CONSOLE);
        assertThat(config.serviceName()).isEqualTo("agentscope-java-app");
        assertThat(config.contentCaptureMode()).isEqualTo(ContentCaptureMode.OFF);
        assertThat(config.maxContentBytes()).isEqualTo(1_100_000);
    }

    @Test
    void enablesCloudOnlyWhenAllRequiredValuesExist() {
        Map<String, String> env = cloudEnvironment();

        ClsObservabilityConfig config = ClsObservabilityConfig.fromEnvironment(env);

        assertThat(config.transportMode()).isEqualTo(TransportMode.CLOUD);
        assertThat(config.endpoint()).isEqualTo(URI.create("https://ap-test.cls.tencentcs.com"));
        assertThat(config.topicId()).isEqualTo("topic-test");
        assertThat(config.secretId()).containsExactly('i', 'd');
        assertThat(config.secretKey()).containsExactly('k', 'e', 'y');
    }

    @Test
    void rejectsPartialCloudConfiguration() {
        Map<String, String> env = new HashMap<>();
        env.put("CLS_ENDPOINT", "ap-test.cls.tencentcs.com");
        env.put("CLS_TOPIC_ID", "topic-test");

        assertThatThrownBy(() -> ClsObservabilityConfig.fromEnvironment(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLS cloud configuration is incomplete");
    }

    @Test
    void rejectsInsecureOrUntrustedEndpoints() {
        assertThatThrownBy(
                        () ->
                                ClsObservabilityConfig.builder()
                                        .transportMode(TransportMode.CLOUD)
                                        .endpoint("http://ap-test.cls.tencentcs.com")
                                        .topicId("topic-test")
                                        .secretId("id".toCharArray())
                                        .secretKey("key".toCharArray())
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");

        assertThatThrownBy(
                        () ->
                                ClsObservabilityConfig.builder()
                                        .transportMode(TransportMode.CLOUD)
                                        .endpoint("https://127.0.0.1")
                                        .topicId("topic-test")
                                        .secretId("id".toCharArray())
                                        .secretKey("key".toCharArray())
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLS domain");
    }

    @Test
    void neverRendersCredentials() {
        ClsObservabilityConfig config = ClsObservabilityConfig.fromEnvironment(cloudEnvironment());

        assertThat(config.toString()).doesNotContain("id", "key");
        assertThat(config.toString()).contains("credentialsConfigured=true");
    }

    @Test
    void returnsDefensiveCopiesOfCredentialArrays() {
        ClsObservabilityConfig config = ClsObservabilityConfig.fromEnvironment(cloudEnvironment());
        char[] first = config.secretKey();
        first[0] = 'x';

        assertThat(config.secretKey()).containsExactly('k', 'e', 'y');
    }

    @Test
    void parsesExplicitContentCaptureModeAndBudget() {
        Map<String, String> env = new HashMap<>();
        env.put("CLS_CONTENT_CAPTURE", "truncate");
        env.put("CLS_MAX_CONTENT_BYTES", "4096");

        ClsObservabilityConfig config = ClsObservabilityConfig.fromEnvironment(env);

        assertThat(config.contentCaptureMode()).isEqualTo(ContentCaptureMode.TRUNCATE);
        assertThat(config.maxContentBytes()).isEqualTo(4096);
    }

    private static Map<String, String> cloudEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("CLS_ENDPOINT", "ap-test.cls.tencentcs.com");
        env.put("CLS_TOPIC_ID", "topic-test");
        env.put("CLS_SECRET_ID", "id");
        env.put("CLS_SECRET_KEY", "key");
        return env;
    }

    @Test
    void defaultsToIsolatedRuntimeTuning() {
        ClsObservabilityConfig config = ClsObservabilityConfig.fromEnvironment(Map.of());

        assertThat(config.reactorContextHookEnabled()).isFalse();
        assertThat(config.exportScheduleDelay()).isEqualTo(Duration.ofMillis(2000));
        assertThat(config.maxQueueSize()).isEqualTo(4096);
    }

    @Test
    void parsesExplicitRuntimeTuning() {
        Map<String, String> env = new HashMap<>();
        env.put("CLS_REACTOR_CONTEXT_HOOK", "true");
        env.put("CLS_EXPORT_SCHEDULE_DELAY_MS", "500");
        env.put("CLS_MAX_QUEUE_SIZE", "1024");

        ClsObservabilityConfig config = ClsObservabilityConfig.fromEnvironment(env);

        assertThat(config.reactorContextHookEnabled()).isTrue();
        assertThat(config.exportScheduleDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(config.maxQueueSize()).isEqualTo(1024);
    }

    @Test
    void rejectsInvalidRuntimeTuning() {
        assertThatThrownBy(
                        () ->
                                ClsObservabilityConfig.fromEnvironment(
                                        Map.of("CLS_REACTOR_CONTEXT_HOOK", "yes")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLS_REACTOR_CONTEXT_HOOK");
        assertThatThrownBy(
                        () ->
                                ClsObservabilityConfig.fromEnvironment(
                                        Map.of("CLS_EXPORT_SCHEDULE_DELAY_MS", "10")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLS_EXPORT_SCHEDULE_DELAY_MS must be between");
        assertThatThrownBy(
                        () -> ClsObservabilityConfig.fromEnvironment(Map.of("CLS_MAX_QUEUE_SIZE", "16")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLS_MAX_QUEUE_SIZE must be between");
    }

    @Test
    void acceptsExpandedContentBudgetAndRejectsValuesAboveIt() {
        assertThat(
                        ClsObservabilityConfig.builder()
                                .maxContentBytes(1_100_000)
                                .build()
                                .maxContentBytes())
                .isEqualTo(1_100_000);
        assertThatThrownBy(
                        () ->
                                ClsObservabilityConfig.builder()
                                        .maxContentBytes(1_100_001)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1100000");
    }

    @Test
    void clearsCredentialsOnDemand() {
        ClsObservabilityConfig config = ClsObservabilityConfig.fromEnvironment(cloudEnvironment());

        config.destroyCredentials();

        assertThat(config.secretId()).isNull();
        assertThat(config.secretKey()).isNull();
        assertThat(config.secretToken()).isEmpty();
        assertThat(config.toString()).contains("credentialsConfigured=false");
    }
}
