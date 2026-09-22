package io.github.tinkerlgd2026.agentscope.cls;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.middleware.MiddlewareBase;
import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig.TransportMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.transport.InMemorySpanSink;
import io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class ClsPublicApiCompatibilityTest {

    @Test
    void retainsExactZeroTwoConfigAndFacadeSignatures() throws Exception {
        method(ClsObservabilityConfig.class, "builder", ClsObservabilityConfig.Builder.class);
        method(ClsObservabilityConfig.class, "fromEnvironment", ClsObservabilityConfig.class, Map.class);
        method(ClsObservabilityConfig.class, "transportMode", TransportMode.class);
        method(ClsObservabilityConfig.class, "endpoint", URI.class);
        method(ClsObservabilityConfig.class, "topicId", String.class);
        method(ClsObservabilityConfig.class, "secretId", char[].class);
        method(ClsObservabilityConfig.class, "secretKey", char[].class);
        method(ClsObservabilityConfig.class, "secretToken", Optional.class);
        method(ClsObservabilityConfig.class, "destroyCredentials", void.class);
        method(ClsObservabilityConfig.class, "reactorContextHookEnabled", boolean.class);
        method(ClsObservabilityConfig.class, "exportScheduleDelay", Duration.class);
        method(ClsObservabilityConfig.class, "maxQueueSize", int.class);
        method(ClsObservabilityConfig.class, "serviceName", String.class);
        method(ClsObservabilityConfig.class, "deploymentEnvironment", String.class);
        method(ClsObservabilityConfig.class, "contentCaptureMode", ContentCaptureMode.class);
        method(ClsObservabilityConfig.class, "reasoningCaptureMode", ContentCaptureMode.class);
        method(ClsObservabilityConfig.class, "maxContentBytes", int.class);

        Class<?> builder = ClsObservabilityConfig.Builder.class;
        method(builder, "transportMode", builder, TransportMode.class);
        method(builder, "endpoint", builder, String.class);
        method(builder, "topicId", builder, String.class);
        method(builder, "secretId", builder, char[].class);
        method(builder, "secretKey", builder, char[].class);
        method(builder, "secretToken", builder, char[].class);
        method(builder, "serviceName", builder, String.class);
        method(builder, "deploymentEnvironment", builder, String.class);
        method(builder, "contentCaptureMode", builder, ContentCaptureMode.class);
        method(builder, "reasoningCaptureMode", builder, ContentCaptureMode.class);
        method(builder, "maxContentBytes", builder, int.class);
        method(builder, "reactorContextHookEnabled", builder, boolean.class);
        method(builder, "exportScheduleDelay", builder, Duration.class);
        method(builder, "maxQueueSize", builder, int.class);
        method(builder, "build", ClsObservabilityConfig.class);

        method(ClsAgentObservability.class, "create", ClsAgentObservability.class, ClsObservabilityConfig.class);
        method(ClsAgentObservability.class, "create", ClsAgentObservability.class, ClsObservabilityConfig.class, SpanSink.class);
        method(ClsAgentObservability.class, "middleware", MiddlewareBase.class);
        method(ClsAgentObservability.class, "flush", boolean.class, Duration.class);
        method(ClsAgentObservability.class, "snapshot", ClsTelemetrySnapshot.class);
        method(ClsAgentObservability.class, "close", void.class);

        method(ClsInvocationContext.class, "defaults", ClsInvocationContext.class);
        method(ClsInvocationContext.class, "userName", String.class);
        method(ClsInvocationContext.class, "turnId", String.class);
        method(ClsInvocationContext.class, "agentType", String.class);
        method(ClsInvocationContext.class, "entryType", String.class);
        assertThat(
                        ClsInvocationContext.class.getConstructor(
                                String.class, String.class, String.class, String.class))
                .isNotNull();

        assertThat(
                        ClsTelemetrySnapshot.class.getConstructor(
                                long.class, long.class, long.class, long.class))
                .isNotNull();
        method(ClsTelemetrySnapshot.class, "acceptedSpans", long.class);
        method(ClsTelemetrySnapshot.class, "invalidSpans", long.class);
        method(ClsTelemetrySnapshot.class, "exportFailures", long.class);
        method(ClsTelemetrySnapshot.class, "droppedSpans", long.class);

        method(SpanSink.class, "export", CompletionStage.class, List.class);
        method(SpanSink.class, "flush", CompletionStage.class, Duration.class);
        method(SpanSink.class, "close", void.class);
        method(InMemorySpanSink.class, "records", List.class);
        method(ClsSpanRecord.class, "fields", Map.class);
    }

    @Test
    void exposesExactAdditiveZeroThreeConfigApi() throws Exception {
        assertThat(ReactorContextMode.values())
                .containsExactly(
                        ReactorContextMode.PRIVATE,
                        ReactorContextMode.BRIDGE,
                        ReactorContextMode.LEGACY_HOOK);
        Class<?> config = ClsObservabilityConfig.class;
        Class<?> builder = ClsObservabilityConfig.Builder.class;

        method(config, "providerPayloadCaptureMode", ContentCaptureMode.class);
        method(config, "truncatePreviewBytes", int.class);
        method(config, "hitlWaitTimeout", Duration.class);
        method(config, "shutdownTimeout", Duration.class);
        method(config, "exportTimeout", Duration.class);
        method(config, "maxExportBatchBytes", int.class);
        method(config, "maxExportBatchCount", int.class);
        method(config, "producerLinger", Duration.class);
        method(config, "reactorContextMode", ReactorContextMode.class);
        method(config, "hostTraceLinkEnabled", boolean.class);
        method(config, "maxInvocationCaptureMemoryBytes", long.class);
        method(config, "maxCaptureMemoryBytes", long.class);
        method(config, "maxProducerBufferBytes", int.class);

        method(builder, "providerPayloadCaptureMode", builder, ContentCaptureMode.class);
        method(builder, "truncatePreviewBytes", builder, int.class);
        method(builder, "hitlWaitTimeout", builder, Duration.class);
        method(builder, "shutdownTimeout", builder, Duration.class);
        method(builder, "exportTimeout", builder, Duration.class);
        method(builder, "maxExportBatchBytes", builder, int.class);
        method(builder, "maxExportBatchCount", builder, int.class);
        method(builder, "producerLinger", builder, Duration.class);
        method(builder, "reactorContextMode", builder, ReactorContextMode.class);
        method(builder, "hostTraceLinkEnabled", builder, boolean.class);
        method(builder, "maxInvocationCaptureMemoryBytes", builder, long.class);
        method(builder, "maxCaptureMemoryBytes", builder, long.class);
        method(builder, "maxProducerBufferBytes", builder, int.class);

        assertThat(ClsDetailedTelemetrySnapshot.class.getRecordComponents()).hasSize(13);
    }

    private static Method method(
            Class<?> owner, String name, Class<?> returnType, Class<?>... parameterTypes)
            throws Exception {
        Method method = owner.getMethod(name, parameterTypes);
        assertThat(method.getReturnType()).as(owner.getSimpleName() + "." + name).isEqualTo(returnType);
        return method;
    }
}
