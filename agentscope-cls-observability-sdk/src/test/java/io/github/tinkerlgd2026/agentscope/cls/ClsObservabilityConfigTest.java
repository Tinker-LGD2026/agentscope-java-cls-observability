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
        assertThat(config.maxContentBytes()).isEqualTo(950_000);
    }

    @Test
    void defaultsReasoningCaptureToOffIndependentlyOfContentCapture() {
        ClsObservabilityConfig config =
                ClsObservabilityConfig.fromEnvironment(
                        Map.of("CLS_CONTENT_CAPTURE", "truncate"));

        assertThat(config.contentCaptureMode()).isEqualTo(ContentCaptureMode.TRUNCATE);
        assertThat(config.reasoningCaptureMode()).isEqualTo(ContentCaptureMode.OFF);
    }

    @Test
    void parsesExplicitReasoningCaptureMode() {
        ClsObservabilityConfig config =
                ClsObservabilityConfig.fromEnvironment(
                        Map.of("CLS_REASONING_CAPTURE", "hash"));

        assertThat(config.reasoningCaptureMode()).isEqualTo(ContentCaptureMode.HASH);
    }

    @Test
    void rejectsInvalidReasoningCaptureMode() {
        assertThatThrownBy(
                        () ->
                                ClsObservabilityConfig.fromEnvironment(
                                        Map.of("CLS_REASONING_CAPTURE", "raw")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLS_REASONING_CAPTURE must be one of");
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
        Map<String, String> environment = cloudEnvironment();
        environment.put("CLS_SECRET_ID", "sensitive-secret-id-value");
        environment.put("CLS_SECRET_KEY", "sensitive-secret-key-value");
        environment.put("CLS_SECRET_TOKEN", "sensitive-token-value");
        ClsObservabilityConfig config = ClsObservabilityConfig.fromEnvironment(environment);

        assertThat(config.toString())
                .doesNotContain(
                        "sensitive-secret-id-value",
                        "sensitive-secret-key-value",
                        "sensitive-token-value")
                .contains("credentialsConfigured=true", "reasoningCaptureMode=OFF");
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
    void acceptsLegacyContentBudgetAndRejectsValuesAboveIt() {
        assertThat(
                        ClsObservabilityConfig.builder()
                                .maxContentBytes(1_100_000)
                                .build()
                                .maxContentBytes())
                .isEqualTo(1_000_000);
        assertThatThrownBy(
                        () ->
                                ClsObservabilityConfig.builder()
                                        .maxContentBytes(1_100_001)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1100000");
    }

    @Test
    void usesSafeZeroThreeDefaults() {
        ClsObservabilityConfig config = ClsObservabilityConfig.builder().build();

        assertThat(config.providerPayloadCaptureMode()).isEqualTo(ContentCaptureMode.OFF);
        assertThat(config.truncatePreviewBytes()).isEqualTo(4096);
        assertThat(config.hitlWaitTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.shutdownTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(config.exportTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.maxExportBatchBytes()).isEqualTo(4 * 1024 * 1024);
        assertThat(config.maxExportBatchCount()).isEqualTo(256);
        assertThat(config.producerLinger()).isEqualTo(Duration.ofMillis(200));
        assertThat(config.reactorContextMode()).isEqualTo(ReactorContextMode.PRIVATE);
        assertThat(config.hostTraceLinkEnabled()).isTrue();
        assertThat(config.maxInvocationCaptureMemoryBytes()).isEqualTo(8L * 1024 * 1024);
        assertThat(config.maxCaptureMemoryBytes()).isEqualTo(64L * 1024 * 1024);
        assertThat(config.maxProducerBufferBytes()).isEqualTo(64 * 1024 * 1024);
    }

    @Test
    void parsesAllZeroThreeEnvironmentValues() {
        Map<String, String> env = new HashMap<>();
        env.put("CLS_PROVIDER_PAYLOAD_CAPTURE", "full");
        env.put("CLS_TRUNCATE_PREVIEW_BYTES", "8192");
        env.put("CLS_HITL_WAIT_TIMEOUT_MS", "120000");
        env.put("CLS_SHUTDOWN_TIMEOUT_MS", "60000");
        env.put("CLS_EXPORT_TIMEOUT_MS", "20000");
        env.put("CLS_MAX_EXPORT_BATCH_BYTES", "3145728");
        env.put("CLS_MAX_EXPORT_BATCH_COUNT", "128");
        env.put("CLS_PRODUCER_LINGER_MS", "500");
        env.put("CLS_REACTOR_CONTEXT_MODE", "bridge");
        env.put("CLS_HOST_TRACE_LINK_ENABLED", "false");
        env.put("CLS_MAX_INVOCATION_CAPTURE_MEMORY_BYTES", "4194304");
        env.put("CLS_MAX_CAPTURE_MEMORY_BYTES", "33554432");
        env.put("CLS_MAX_PRODUCER_BUFFER_BYTES", "16777216");

        ClsObservabilityConfig config = ClsObservabilityConfig.fromEnvironment(env);

        assertThat(config.providerPayloadCaptureMode()).isEqualTo(ContentCaptureMode.FULL);
        assertThat(config.truncatePreviewBytes()).isEqualTo(8192);
        assertThat(config.hitlWaitTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(config.shutdownTimeout()).isEqualTo(Duration.ofMinutes(1));
        assertThat(config.exportTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(config.maxExportBatchBytes()).isEqualTo(3 * 1024 * 1024);
        assertThat(config.maxExportBatchCount()).isEqualTo(128);
        assertThat(config.producerLinger()).isEqualTo(Duration.ofMillis(500));
        assertThat(config.reactorContextMode()).isEqualTo(ReactorContextMode.BRIDGE);
        assertThat(config.hostTraceLinkEnabled()).isFalse();
        assertThat(config.maxInvocationCaptureMemoryBytes()).isEqualTo(4L * 1024 * 1024);
        assertThat(config.maxCaptureMemoryBytes()).isEqualTo(32L * 1024 * 1024);
        assertThat(config.maxProducerBufferBytes()).isEqualTo(16 * 1024 * 1024);
    }

    @Test
    void resolvesLegacyAndNewReactorSettingsDeterministically() {
        assertThat(ClsObservabilityConfig.builder().reactorContextHookEnabled(false).build()
                        .reactorContextMode())
                .isEqualTo(ReactorContextMode.PRIVATE);
        assertThat(ClsObservabilityConfig.builder().reactorContextHookEnabled(true).build()
                        .reactorContextMode())
                .isEqualTo(ReactorContextMode.LEGACY_HOOK);
        assertThat(ClsObservabilityConfig.builder()
                        .reactorContextMode(ReactorContextMode.PRIVATE)
                        .reactorContextHookEnabled(false)
                        .build()
                        .reactorContextMode())
                .isEqualTo(ReactorContextMode.PRIVATE);
        assertThatThrownBy(
                        () ->
                                ClsObservabilityConfig.builder()
                                        .reactorContextMode(ReactorContextMode.BRIDGE)
                                        .reactorContextHookEnabled(false)
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reactor context");
        assertThatThrownBy(
                        () ->
                                ClsObservabilityConfig.fromEnvironment(
                                        Map.of(
                                                "CLS_REACTOR_CONTEXT_MODE", "private",
                                                "CLS_REACTOR_CONTEXT_HOOK", "true")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reactor context");
    }

    @Test
    void clampsLegacyContentBudgetToClsFieldLimit() {
        assertThat(ClsObservabilityConfig.builder().maxContentBytes(1_100_000).build()
                        .maxContentBytes())
                .isEqualTo(1_000_000);
        assertThat(ClsObservabilityConfig.fromEnvironment(
                                Map.of("CLS_MAX_CONTENT_BYTES", "1100000"))
                        .maxContentBytes())
                .isEqualTo(1_000_000);
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
