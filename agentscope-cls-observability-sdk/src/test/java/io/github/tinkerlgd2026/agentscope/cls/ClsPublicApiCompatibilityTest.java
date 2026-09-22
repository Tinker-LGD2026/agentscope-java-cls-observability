package io.github.tinkerlgd2026.agentscope.cls;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ClsPublicApiCompatibilityTest {

    @Test
    void retainsExistingBuilderAndRuntimeMethods() throws Exception {
        assertThat(ClsObservabilityConfig.class.getMethod("builder")).isNotNull();
        assertThat(ClsObservabilityConfig.Builder.class.getMethod("reactorContextHookEnabled", boolean.class))
                .isNotNull();
        assertThat(ClsObservabilityConfig.Builder.class.getMethod("contentCaptureMode", ContentCaptureMode.class))
                .isNotNull();
        assertThat(ClsAgentObservability.class.getMethod("flush", Duration.class)).isNotNull();
        assertThat(ClsAgentObservability.class.getMethod("close")).isNotNull();
    }

    @Test
    void exposesAdditiveZeroThreeApi() throws Exception {
        assertThat(ReactorContextMode.values())
                .containsExactly(
                        ReactorContextMode.PRIVATE,
                        ReactorContextMode.BRIDGE,
                        ReactorContextMode.LEGACY_HOOK);
        assertThat(Arrays.stream(ClsObservabilityConfig.Builder.class.getMethods())
                        .map(method -> method.getName()))
                .contains(
                        "providerPayloadCaptureMode",
                        "truncatePreviewBytes",
                        "hitlWaitTimeout",
                        "shutdownTimeout",
                        "exportTimeout",
                        "maxExportBatchBytes",
                        "maxExportBatchCount",
                        "producerLinger",
                        "reactorContextMode",
                        "hostTraceLinkEnabled",
                        "maxInvocationCaptureMemoryBytes",
                        "maxCaptureMemoryBytes",
                        "maxProducerBufferBytes");
        assertThat(ClsDetailedTelemetrySnapshot.class.getRecordComponents()).hasSize(13);
    }
}
