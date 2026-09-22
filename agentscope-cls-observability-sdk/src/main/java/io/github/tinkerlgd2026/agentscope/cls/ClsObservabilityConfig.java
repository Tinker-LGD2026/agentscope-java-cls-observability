package io.github.tinkerlgd2026.agentscope.cls;

import static io.github.tinkerlgd2026.agentscope.cls.internal.config.ConfigBounds.*;

import io.github.tinkerlgd2026.agentscope.cls.internal.config.EnvironmentConfigParser;
import io.github.tinkerlgd2026.agentscope.cls.internal.config.ReactorModeResolver;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Immutable configuration for the CLS observability SDK. */
public final class ClsObservabilityConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClsObservabilityConfig.class);
    private static final Pattern CLS_HOST_PATTERN =
            Pattern.compile("^[a-z0-9-]+\\.cls\\.(?:tencentcs|tencentyun)\\.com$");

    public enum TransportMode {
        CONSOLE,
        CLOUD
    }

    private final TransportMode transportMode;
    private final URI endpoint;
    private final String topicId;
    private final char[] secretId;
    private final char[] secretKey;
    private final char[] secretToken;
    private final String serviceName;
    private final String deploymentEnvironment;
    private final ContentCaptureMode contentCaptureMode;
    private final ContentCaptureMode reasoningCaptureMode;
    private final ContentCaptureMode providerPayloadCaptureMode;
    private final int maxContentBytes;
    private final int truncatePreviewBytes;
    private final ReactorContextMode reactorContextMode;
    private final Duration exportScheduleDelay;
    private final int maxQueueSize;
    private final Duration hitlWaitTimeout;
    private final Duration shutdownTimeout;
    private final Duration exportTimeout;
    private final int maxExportBatchBytes;
    private final int maxExportBatchCount;
    private final Duration producerLinger;
    private final boolean hostTraceLinkEnabled;
    private final long maxInvocationCaptureMemoryBytes;
    private final long maxCaptureMemoryBytes;
    private final int maxProducerBufferBytes;
    private volatile boolean credentialsDestroyed;

    private ClsObservabilityConfig(Builder builder) {
        transportMode = builder.transportMode;
        endpoint = builder.endpoint;
        topicId = builder.topicId;
        secretId = copy(builder.secretId);
        secretKey = copy(builder.secretKey);
        secretToken = copy(builder.secretToken);
        serviceName = builder.serviceName;
        deploymentEnvironment = builder.deploymentEnvironment;
        contentCaptureMode = builder.contentCaptureMode;
        reasoningCaptureMode = builder.reasoningCaptureMode;
        providerPayloadCaptureMode = builder.providerPayloadCaptureMode;
        if (builder.maxContentBytes > MAX_CONTENT_BYTES) {
            LOGGER.warn(
                    "CLS max content bytes {} exceeds the 0.3 CLS field limit and was clamped to {}",
                    builder.maxContentBytes,
                    MAX_CONTENT_BYTES);
        }
        maxContentBytes = Math.min(builder.maxContentBytes, MAX_CONTENT_BYTES);
        truncatePreviewBytes = builder.truncatePreviewBytes;
        reactorContextMode =
                ReactorModeResolver.resolve(
                        builder.explicitReactorContextMode, builder.legacyReactorContextHookEnabled);
        exportScheduleDelay = builder.exportScheduleDelay;
        maxQueueSize = builder.maxQueueSize;
        hitlWaitTimeout = builder.hitlWaitTimeout;
        shutdownTimeout = builder.shutdownTimeout;
        exportTimeout = builder.exportTimeout;
        maxExportBatchBytes = builder.maxExportBatchBytes;
        maxExportBatchCount = builder.maxExportBatchCount;
        producerLinger = builder.producerLinger;
        hostTraceLinkEnabled = builder.hostTraceLinkEnabled;
        maxInvocationCaptureMemoryBytes = builder.maxInvocationCaptureMemoryBytes;
        maxCaptureMemoryBytes = builder.maxCaptureMemoryBytes;
        maxProducerBufferBytes = builder.maxProducerBufferBytes;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ClsObservabilityConfig fromEnvironment(Map<String, String> environment) {
        if (environment == null) {
            throw new IllegalArgumentException("environment must not be null");
        }
        return EnvironmentConfigParser.parse(environment);
    }

    public TransportMode transportMode() {
        return transportMode;
    }

    public @Nullable URI endpoint() {
        return endpoint;
    }

    public @Nullable String topicId() {
        return topicId;
    }

    public @Nullable char[] secretId() {
        return credentialsDestroyed ? null : copy(secretId);
    }

    public @Nullable char[] secretKey() {
        return credentialsDestroyed ? null : copy(secretKey);
    }

    public Optional<char[]> secretToken() {
        return secretToken == null || credentialsDestroyed
                ? Optional.empty()
                : Optional.of(copy(secretToken));
    }

    /** Overwrites retained credential characters and prevents future reads. */
    public void destroyCredentials() {
        credentialsDestroyed = true;
        clear(secretId);
        clear(secretKey);
        clear(secretToken);
    }

    /** Deprecated compatibility view of the 0.2 process-wide hook option. */
    public boolean reactorContextHookEnabled() {
        return reactorContextMode == ReactorContextMode.LEGACY_HOOK;
    }

    public ReactorContextMode reactorContextMode() {
        return reactorContextMode;
    }

    public Duration exportScheduleDelay() {
        return exportScheduleDelay;
    }

    public int maxQueueSize() {
        return maxQueueSize;
    }

    public String serviceName() {
        return serviceName;
    }

    public @Nullable String deploymentEnvironment() {
        return deploymentEnvironment;
    }

    public ContentCaptureMode contentCaptureMode() {
        return contentCaptureMode;
    }

    public ContentCaptureMode reasoningCaptureMode() {
        return reasoningCaptureMode;
    }

    public ContentCaptureMode providerPayloadCaptureMode() {
        return providerPayloadCaptureMode;
    }

    public int maxContentBytes() {
        return maxContentBytes;
    }

    public int truncatePreviewBytes() {
        return truncatePreviewBytes;
    }

    public Duration hitlWaitTimeout() {
        return hitlWaitTimeout;
    }

    public Duration shutdownTimeout() {
        return shutdownTimeout;
    }

    public Duration exportTimeout() {
        return exportTimeout;
    }

    public int maxExportBatchBytes() {
        return maxExportBatchBytes;
    }

    public int maxExportBatchCount() {
        return maxExportBatchCount;
    }

    public Duration producerLinger() {
        return producerLinger;
    }

    public boolean hostTraceLinkEnabled() {
        return hostTraceLinkEnabled;
    }

    public long maxInvocationCaptureMemoryBytes() {
        return maxInvocationCaptureMemoryBytes;
    }

    public long maxCaptureMemoryBytes() {
        return maxCaptureMemoryBytes;
    }

    public int maxProducerBufferBytes() {
        return maxProducerBufferBytes;
    }

    @Override
    public String toString() {
        return "ClsObservabilityConfig{"
                + "transportMode=" + transportMode
                + ", endpoint=" + endpoint
                + ", topicConfigured=" + (topicId != null)
                + ", credentialsConfigured="
                + (!credentialsDestroyed && secretId != null && secretKey != null)
                + ", serviceName='" + serviceName + '\''
                + ", deploymentEnvironment='" + deploymentEnvironment + '\''
                + ", contentCaptureMode=" + contentCaptureMode
                + ", reasoningCaptureMode=" + reasoningCaptureMode
                + ", providerPayloadCaptureMode=" + providerPayloadCaptureMode
                + ", maxContentBytes=" + maxContentBytes
                + ", truncatePreviewBytes=" + truncatePreviewBytes
                + ", reactorContextMode=" + reactorContextMode
                + ", exportScheduleDelayMs=" + exportScheduleDelay.toMillis()
                + ", maxQueueSize=" + maxQueueSize
                + ", hitlWaitTimeoutMs=" + hitlWaitTimeout.toMillis()
                + ", shutdownTimeoutMs=" + shutdownTimeout.toMillis()
                + ", exportTimeoutMs=" + exportTimeout.toMillis()
                + ", maxExportBatchBytes=" + maxExportBatchBytes
                + ", maxExportBatchCount=" + maxExportBatchCount
                + ", producerLingerMs=" + producerLinger.toMillis()
                + ", hostTraceLinkEnabled=" + hostTraceLinkEnabled
                + ", maxInvocationCaptureMemoryBytes=" + maxInvocationCaptureMemoryBytes
                + ", maxCaptureMemoryBytes=" + maxCaptureMemoryBytes
                + ", maxProducerBufferBytes=" + maxProducerBufferBytes
                + '}';
    }

    private static URI normalizeEndpoint(String raw) {
        String value = clean(raw);
        if (value == null) {
            throw new IllegalArgumentException("CLS endpoint is required in cloud mode");
        }
        if (!value.contains("://")) {
            value = "https://" + value;
        }
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("CLS endpoint must use HTTPS");
            }
            String host = uri.getHost();
            if (host == null
                    || !CLS_HOST_PATTERN.matcher(host.toLowerCase(Locale.ROOT)).matches()
                    || uri.getPort() != -1
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalArgumentException("CLS endpoint must be a trusted CLS domain");
            }
            return new URI("https", host.toLowerCase(Locale.ROOT), null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("CLS endpoint is invalid", exception);
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned == null ? fallback : cleaned;
    }

    private static char[] copy(char[] value) {
        return value == null ? null : Arrays.copyOf(value, value.length);
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    public static final class Builder {
        private TransportMode transportMode = TransportMode.CONSOLE;
        private URI endpoint;
        private String topicId;
        private char[] secretId;
        private char[] secretKey;
        private char[] secretToken;
        private String serviceName = "agentscope-java-app";
        private String deploymentEnvironment;
        private ContentCaptureMode contentCaptureMode = ContentCaptureMode.OFF;
        private ContentCaptureMode reasoningCaptureMode = ContentCaptureMode.OFF;
        private ContentCaptureMode providerPayloadCaptureMode = ContentCaptureMode.OFF;
        private int maxContentBytes = DEFAULT_MAX_CONTENT_BYTES;
        private int truncatePreviewBytes = DEFAULT_TRUNCATE_PREVIEW_BYTES;
        private ReactorContextMode explicitReactorContextMode;
        private Boolean legacyReactorContextHookEnabled;
        private Duration exportScheduleDelay = Duration.ofMillis(DEFAULT_EXPORT_SCHEDULE_DELAY_MS);
        private int maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
        private Duration hitlWaitTimeout = Duration.ofMillis(DEFAULT_HITL_WAIT_TIMEOUT_MS);
        private Duration shutdownTimeout = Duration.ofMillis(DEFAULT_SHUTDOWN_TIMEOUT_MS);
        private Duration exportTimeout = Duration.ofMillis(DEFAULT_EXPORT_TIMEOUT_MS);
        private int maxExportBatchBytes = DEFAULT_MAX_EXPORT_BATCH_BYTES;
        private int maxExportBatchCount = DEFAULT_MAX_EXPORT_BATCH_COUNT;
        private Duration producerLinger = Duration.ofMillis(DEFAULT_PRODUCER_LINGER_MS);
        private boolean hostTraceLinkEnabled = true;
        private long maxInvocationCaptureMemoryBytes =
                DEFAULT_MAX_INVOCATION_CAPTURE_MEMORY_BYTES;
        private long maxCaptureMemoryBytes = DEFAULT_MAX_CAPTURE_MEMORY_BYTES;
        private int maxProducerBufferBytes = DEFAULT_MAX_PRODUCER_BUFFER_BYTES;

        private Builder() {}

        public Builder transportMode(TransportMode value) {
            transportMode = value;
            return this;
        }

        public Builder endpoint(String value) {
            endpoint = normalizeEndpoint(value);
            return this;
        }

        public Builder topicId(String value) {
            topicId = clean(value);
            return this;
        }

        public Builder secretId(char[] value) {
            secretId = copy(value);
            return this;
        }

        public Builder secretKey(char[] value) {
            secretKey = copy(value);
            return this;
        }

        public Builder secretToken(char[] value) {
            secretToken = copy(value);
            return this;
        }

        public Builder serviceName(String value) {
            serviceName = defaultIfBlank(value, "agentscope-java-app");
            return this;
        }

        public Builder deploymentEnvironment(String value) {
            deploymentEnvironment = clean(value);
            return this;
        }

        public Builder contentCaptureMode(ContentCaptureMode value) {
            contentCaptureMode = value;
            return this;
        }

        public Builder reasoningCaptureMode(ContentCaptureMode value) {
            reasoningCaptureMode = value;
            return this;
        }

        public Builder providerPayloadCaptureMode(ContentCaptureMode value) {
            providerPayloadCaptureMode = value;
            return this;
        }

        public Builder maxContentBytes(int value) {
            maxContentBytes = value;
            return this;
        }

        public Builder truncatePreviewBytes(int value) {
            truncatePreviewBytes = value;
            return this;
        }

        /** Deprecated compatibility setting from 0.2. */
        public Builder reactorContextHookEnabled(boolean value) {
            legacyReactorContextHookEnabled = value;
            return this;
        }

        public Builder reactorContextMode(ReactorContextMode value) {
            explicitReactorContextMode = value;
            return this;
        }

        public Builder exportScheduleDelay(Duration value) {
            exportScheduleDelay = value;
            return this;
        }

        public Builder maxQueueSize(int value) {
            maxQueueSize = value;
            return this;
        }

        public Builder hitlWaitTimeout(Duration value) {
            hitlWaitTimeout = value;
            return this;
        }

        public Builder shutdownTimeout(Duration value) {
            shutdownTimeout = value;
            return this;
        }

        public Builder exportTimeout(Duration value) {
            exportTimeout = value;
            return this;
        }

        public Builder maxExportBatchBytes(int value) {
            maxExportBatchBytes = value;
            return this;
        }

        public Builder maxExportBatchCount(int value) {
            maxExportBatchCount = value;
            return this;
        }

        public Builder producerLinger(Duration value) {
            producerLinger = value;
            return this;
        }

        public Builder hostTraceLinkEnabled(boolean value) {
            hostTraceLinkEnabled = value;
            return this;
        }

        public Builder maxInvocationCaptureMemoryBytes(long value) {
            maxInvocationCaptureMemoryBytes = value;
            return this;
        }

        public Builder maxCaptureMemoryBytes(long value) {
            maxCaptureMemoryBytes = value;
            return this;
        }

        public Builder maxProducerBufferBytes(int value) {
            maxProducerBufferBytes = value;
            return this;
        }

        public ClsObservabilityConfig build() {
            requireNonNull(transportMode, "transport mode");
            requireNonNull(contentCaptureMode, "content capture mode");
            requireNonNull(reasoningCaptureMode, "reasoning capture mode");
            requireNonNull(providerPayloadCaptureMode, "provider payload capture mode");
            if (serviceName == null || serviceName.length() > 128) {
                throw new IllegalArgumentException("service name must contain 1 to 128 characters");
            }
            checkRange("content byte budget", maxContentBytes, MIN_CONTENT_BYTES, LEGACY_MAX_CONTENT_BYTES);
            checkRange("truncate preview bytes", truncatePreviewBytes, MIN_TRUNCATE_PREVIEW_BYTES, MAX_TRUNCATE_PREVIEW_BYTES);
            checkDuration("export schedule delay", exportScheduleDelay, MIN_EXPORT_SCHEDULE_DELAY_MS, MAX_EXPORT_SCHEDULE_DELAY_MS);
            checkRange("max queue size", maxQueueSize, MIN_MAX_QUEUE_SIZE, MAX_MAX_QUEUE_SIZE);
            checkDuration("HITL wait timeout", hitlWaitTimeout, MIN_HITL_WAIT_TIMEOUT_MS, MAX_HITL_WAIT_TIMEOUT_MS);
            checkDuration("shutdown timeout", shutdownTimeout, MIN_LIFECYCLE_TIMEOUT_MS, MAX_LIFECYCLE_TIMEOUT_MS);
            checkDuration("export timeout", exportTimeout, MIN_LIFECYCLE_TIMEOUT_MS, MAX_LIFECYCLE_TIMEOUT_MS);
            checkRange("max export batch bytes", maxExportBatchBytes, MIN_MAX_EXPORT_BATCH_BYTES, MAX_MAX_EXPORT_BATCH_BYTES);
            checkRange("max export batch count", maxExportBatchCount, MIN_MAX_EXPORT_BATCH_COUNT, MAX_MAX_EXPORT_BATCH_COUNT);
            checkDuration("producer linger", producerLinger, MIN_PRODUCER_LINGER_MS, MAX_PRODUCER_LINGER_MS);
            checkRange("max invocation capture memory bytes", maxInvocationCaptureMemoryBytes, MIN_MAX_INVOCATION_CAPTURE_MEMORY_BYTES, MAX_MAX_INVOCATION_CAPTURE_MEMORY_BYTES);
            checkRange("max capture memory bytes", maxCaptureMemoryBytes, MIN_MAX_CAPTURE_MEMORY_BYTES, MAX_MAX_CAPTURE_MEMORY_BYTES);
            if (maxInvocationCaptureMemoryBytes > maxCaptureMemoryBytes) {
                throw new IllegalArgumentException(
                        "max invocation capture memory bytes must not exceed max capture memory bytes");
            }
            checkRange("max producer buffer bytes", maxProducerBufferBytes, MIN_MAX_PRODUCER_BUFFER_BYTES, MAX_MAX_PRODUCER_BUFFER_BYTES);
            ReactorModeResolver.resolve(explicitReactorContextMode, legacyReactorContextHookEnabled);
            if (transportMode == TransportMode.CLOUD
                    && (endpoint == null
                            || topicId == null
                            || secretId == null
                            || secretId.length == 0
                            || secretKey == null
                            || secretKey.length == 0)) {
                throw new IllegalArgumentException("CLS cloud configuration is incomplete");
            }
            return new ClsObservabilityConfig(this);
        }

        private static void requireNonNull(Object value, String name) {
            if (value == null) {
                throw new IllegalArgumentException(name + " is required");
            }
        }

        private static void checkDuration(String name, Duration value, long min, long max) {
            if (value == null) {
                throw new IllegalArgumentException(name + " is required");
            }
            checkRange(name, value.toMillis(), min, max);
        }

        private static void checkRange(String name, long value, long min, long max) {
            if (value < min || value > max) {
                throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            }
        }
    }
}
