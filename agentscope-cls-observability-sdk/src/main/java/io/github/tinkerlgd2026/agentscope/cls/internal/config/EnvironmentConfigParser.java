package io.github.tinkerlgd2026.agentscope.cls.internal.config;

import static io.github.tinkerlgd2026.agentscope.cls.internal.config.ConfigBounds.*;

import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig;
import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig.TransportMode;
import io.github.tinkerlgd2026.agentscope.cls.ReactorContextMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Parses only the caller-supplied environment map into a configuration builder. */
public final class EnvironmentConfigParser {
    private EnvironmentConfigParser() {}

    public static ClsObservabilityConfig parse(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String endpoint = clean(environment.get("CLS_ENDPOINT"));
        String topicId = clean(environment.get("CLS_TOPIC_ID"));
        String secretId = clean(environment.get("CLS_SECRET_ID"));
        String secretKey = clean(environment.get("CLS_SECRET_KEY"));
        String transport = clean(environment.get("CLS_TRANSPORT"));
        boolean anyCloudValue =
                endpoint != null || topicId != null || secretId != null || secretKey != null;
        boolean cloud = "cloud".equalsIgnoreCase(transport) || (transport == null && anyCloudValue);
        if (transport != null
                && !"cloud".equalsIgnoreCase(transport)
                && !"console".equalsIgnoreCase(transport)) {
            throw new IllegalArgumentException("CLS_TRANSPORT must be console or cloud");
        }
        if (cloud && (endpoint == null || topicId == null || secretId == null || secretKey == null)) {
            throw new IllegalArgumentException("CLS cloud configuration is incomplete");
        }

        ClsObservabilityConfig.Builder builder =
                ClsObservabilityConfig.builder()
                        .transportMode(cloud ? TransportMode.CLOUD : TransportMode.CONSOLE)
                        .serviceName(defaultIfBlank(environment.get("CLS_SERVICE_NAME"), DEFAULT_SERVICE_NAME))
                        .deploymentEnvironment(clean(environment.get("CLS_DEPLOYMENT_ENVIRONMENT")))
                        .contentCaptureMode(ContentCaptureMode.parse(environment.get("CLS_CONTENT_CAPTURE")))
                        .reasoningCaptureMode(
                                ContentCaptureMode.parse(
                                        environment.get("CLS_REASONING_CAPTURE"),
                                        "CLS_REASONING_CAPTURE"))
                        .providerPayloadCaptureMode(
                                ContentCaptureMode.parse(
                                        environment.get("CLS_PROVIDER_PAYLOAD_CAPTURE"),
                                        "CLS_PROVIDER_PAYLOAD_CAPTURE"))
                        .maxContentBytes(
                                parseInt(
                                        environment.get("CLS_MAX_CONTENT_BYTES"),
                                        "CLS_MAX_CONTENT_BYTES",
                                        DEFAULT_MAX_CONTENT_BYTES,
                                        MIN_CONTENT_BYTES,
                                        LEGACY_MAX_CONTENT_BYTES))
                        .truncatePreviewBytes(
                                parseInt(
                                        environment.get("CLS_TRUNCATE_PREVIEW_BYTES"),
                                        "CLS_TRUNCATE_PREVIEW_BYTES",
                                        DEFAULT_TRUNCATE_PREVIEW_BYTES,
                                        MIN_TRUNCATE_PREVIEW_BYTES,
                                        MAX_TRUNCATE_PREVIEW_BYTES))
                        .hitlWaitTimeout(
                                duration(
                                        environment,
                                        "CLS_HITL_WAIT_TIMEOUT_MS",
                                        DEFAULT_HITL_WAIT_TIMEOUT_MS,
                                        MIN_HITL_WAIT_TIMEOUT_MS,
                                        MAX_HITL_WAIT_TIMEOUT_MS))
                        .shutdownTimeout(
                                duration(
                                        environment,
                                        "CLS_SHUTDOWN_TIMEOUT_MS",
                                        DEFAULT_SHUTDOWN_TIMEOUT_MS,
                                        MIN_LIFECYCLE_TIMEOUT_MS,
                                        MAX_LIFECYCLE_TIMEOUT_MS))
                        .exportTimeout(
                                duration(
                                        environment,
                                        "CLS_EXPORT_TIMEOUT_MS",
                                        DEFAULT_EXPORT_TIMEOUT_MS,
                                        MIN_LIFECYCLE_TIMEOUT_MS,
                                        MAX_LIFECYCLE_TIMEOUT_MS))
                        .maxExportBatchBytes(
                                parseInt(
                                        environment.get("CLS_MAX_EXPORT_BATCH_BYTES"),
                                        "CLS_MAX_EXPORT_BATCH_BYTES",
                                        DEFAULT_MAX_EXPORT_BATCH_BYTES,
                                        MIN_MAX_EXPORT_BATCH_BYTES,
                                        MAX_MAX_EXPORT_BATCH_BYTES))
                        .maxExportBatchCount(
                                parseInt(
                                        environment.get("CLS_MAX_EXPORT_BATCH_COUNT"),
                                        "CLS_MAX_EXPORT_BATCH_COUNT",
                                        DEFAULT_MAX_EXPORT_BATCH_COUNT,
                                        MIN_MAX_EXPORT_BATCH_COUNT,
                                        MAX_MAX_EXPORT_BATCH_COUNT))
                        .producerLinger(
                                duration(
                                        environment,
                                        "CLS_PRODUCER_LINGER_MS",
                                        DEFAULT_PRODUCER_LINGER_MS,
                                        MIN_PRODUCER_LINGER_MS,
                                        MAX_PRODUCER_LINGER_MS))
                        .hostTraceLinkEnabled(
                                parseBoolean(
                                        environment.get("CLS_HOST_TRACE_LINK_ENABLED"),
                                        "CLS_HOST_TRACE_LINK_ENABLED",
                                        DEFAULT_HOST_TRACE_LINK_ENABLED))
                        .maxInvocationCaptureMemoryBytes(
                                parseLong(
                                        environment.get("CLS_MAX_INVOCATION_CAPTURE_MEMORY_BYTES"),
                                        "CLS_MAX_INVOCATION_CAPTURE_MEMORY_BYTES",
                                        DEFAULT_MAX_INVOCATION_CAPTURE_MEMORY_BYTES,
                                        MIN_MAX_INVOCATION_CAPTURE_MEMORY_BYTES,
                                        MAX_MAX_INVOCATION_CAPTURE_MEMORY_BYTES))
                        .maxCaptureMemoryBytes(
                                parseLong(
                                        environment.get("CLS_MAX_CAPTURE_MEMORY_BYTES"),
                                        "CLS_MAX_CAPTURE_MEMORY_BYTES",
                                        DEFAULT_MAX_CAPTURE_MEMORY_BYTES,
                                        MIN_MAX_CAPTURE_MEMORY_BYTES,
                                        MAX_MAX_CAPTURE_MEMORY_BYTES))
                        .maxProducerBufferBytes(
                                parseInt(
                                        environment.get("CLS_MAX_PRODUCER_BUFFER_BYTES"),
                                        "CLS_MAX_PRODUCER_BUFFER_BYTES",
                                        DEFAULT_MAX_PRODUCER_BUFFER_BYTES,
                                        MIN_MAX_PRODUCER_BUFFER_BYTES,
                                        MAX_MAX_PRODUCER_BUFFER_BYTES))
                        .exportScheduleDelay(
                                duration(
                                        environment,
                                        "CLS_EXPORT_SCHEDULE_DELAY_MS",
                                        DEFAULT_EXPORT_SCHEDULE_DELAY_MS,
                                        MIN_EXPORT_SCHEDULE_DELAY_MS,
                                        MAX_EXPORT_SCHEDULE_DELAY_MS))
                        .maxQueueSize(
                                parseInt(
                                        environment.get("CLS_MAX_QUEUE_SIZE"),
                                        "CLS_MAX_QUEUE_SIZE",
                                        DEFAULT_MAX_QUEUE_SIZE,
                                        MIN_MAX_QUEUE_SIZE,
                                        MAX_MAX_QUEUE_SIZE));

        String newMode = clean(environment.get("CLS_REACTOR_CONTEXT_MODE"));
        String legacyHook = clean(environment.get("CLS_REACTOR_CONTEXT_HOOK"));
        if (newMode != null) {
            builder.reactorContextMode(parseReactorMode(newMode));
        }
        if (legacyHook != null) {
            builder.reactorContextHookEnabled(
                    parseBoolean(legacyHook, "CLS_REACTOR_CONTEXT_HOOK", false));
        }
        if (cloud) {
            builder.endpoint(Objects.requireNonNull(endpoint))
                    .topicId(Objects.requireNonNull(topicId))
                    .secretId(Objects.requireNonNull(secretId).toCharArray())
                    .secretKey(Objects.requireNonNull(secretKey).toCharArray());
            String token = clean(environment.get("CLS_SECRET_TOKEN"));
            if (token != null) {
                builder.secretToken(token.toCharArray());
            }
        }
        return builder.build();
    }

    private static ReactorContextMode parseReactorMode(String value) {
        try {
            return ReactorContextMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "CLS_REACTOR_CONTEXT_MODE must be private, bridge, or legacy_hook",
                    exception);
        }
    }

    private static Duration duration(
            Map<String, String> environment,
            String name,
            long fallback,
            long minimum,
            long maximum) {
        return Duration.ofMillis(parseLong(environment.get(name), name, fallback, minimum, maximum));
    }

    private static int parseInt(
            String raw, String name, int fallback, int minimum, int maximum) {
        long parsed = parseLong(raw, name, fallback, minimum, maximum);
        return Math.toIntExact(parsed);
    }

    private static long parseLong(
            String raw, String name, long fallback, long minimum, long maximum) {
        String value = clean(raw);
        if (value == null) {
            return fallback;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static boolean parseBoolean(String raw, String name, boolean fallback) {
        String value = clean(raw);
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned == null ? fallback : cleaned;
    }
}
