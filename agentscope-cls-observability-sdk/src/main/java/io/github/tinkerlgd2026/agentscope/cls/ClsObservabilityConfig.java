package io.github.tinkerlgd2026.agentscope.cls;

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

public final class ClsObservabilityConfig {
    private static final int DEFAULT_MAX_CONTENT_BYTES = 1_100_000;
    private static final int MAX_CONTENT_BYTES = 1_100_000;
    private static final int DEFAULT_EXPORT_SCHEDULE_DELAY_MS = 2000;
    private static final int MIN_EXPORT_SCHEDULE_DELAY_MS = 50;
    private static final int MAX_EXPORT_SCHEDULE_DELAY_MS = 60_000;
    private static final int DEFAULT_MAX_QUEUE_SIZE = 4096;
    private static final int MIN_MAX_QUEUE_SIZE = 256;
    private static final int MAX_MAX_QUEUE_SIZE = 65_536;
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
    private final int maxContentBytes;
    private final boolean reactorContextHookEnabled;
    private final Duration exportScheduleDelay;
    private final int maxQueueSize;
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
        maxContentBytes = builder.maxContentBytes;
        reactorContextHookEnabled = builder.reactorContextHookEnabled;
        exportScheduleDelay = builder.exportScheduleDelay;
        maxQueueSize = builder.maxQueueSize;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ClsObservabilityConfig fromEnvironment(Map<String, String> environment) {
        if (environment == null) {
            throw new IllegalArgumentException("environment must not be null");
        }
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

        Builder builder =
                builder()
                        .transportMode(cloud ? TransportMode.CLOUD : TransportMode.CONSOLE)
                        .serviceName(
                                defaultIfBlank(
                                        environment.get("CLS_SERVICE_NAME"),
                                        "agentscope-java-app"))
                        .deploymentEnvironment(
                                clean(environment.get("CLS_DEPLOYMENT_ENVIRONMENT")))
                        .contentCaptureMode(
                                ContentCaptureMode.parse(environment.get("CLS_CONTENT_CAPTURE")))
                        .reasoningCaptureMode(
                                ContentCaptureMode.parse(
                                        environment.get("CLS_REASONING_CAPTURE"),
                                        "CLS_REASONING_CAPTURE"))
                        .maxContentBytes(
                                parseContentBudget(environment.get("CLS_MAX_CONTENT_BYTES")))
                        .reactorContextHookEnabled(
                                parseBoolean(
                                        environment.get("CLS_REACTOR_CONTEXT_HOOK"),
                                        "CLS_REACTOR_CONTEXT_HOOK"))
                        .exportScheduleDelay(
                                Duration.ofMillis(
                                        parseBoundedInt(
                                                environment.get("CLS_EXPORT_SCHEDULE_DELAY_MS"),
                                                "CLS_EXPORT_SCHEDULE_DELAY_MS",
                                                DEFAULT_EXPORT_SCHEDULE_DELAY_MS,
                                                MIN_EXPORT_SCHEDULE_DELAY_MS,
                                                MAX_EXPORT_SCHEDULE_DELAY_MS)))
                        .maxQueueSize(
                                parseBoundedInt(
                                        environment.get("CLS_MAX_QUEUE_SIZE"),
                                        "CLS_MAX_QUEUE_SIZE",
                                        DEFAULT_MAX_QUEUE_SIZE,
                                        MIN_MAX_QUEUE_SIZE,
                                        MAX_MAX_QUEUE_SIZE));
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

    /**
     * Overwrites the retained credential characters and makes further reads return nothing.
     *
     * <p>Call this once the SDK has been created so long-lived processes do not keep CLS
     * credentials reachable in the heap. The same configuration object cannot create another
     * cloud transport afterwards.
     */
    public void destroyCredentials() {
        credentialsDestroyed = true;
        clear(secretId);
        clear(secretKey);
        clear(secretToken);
    }

    public boolean reactorContextHookEnabled() {
        return reactorContextHookEnabled;
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

    public int maxContentBytes() {
        return maxContentBytes;
    }

    @Override
    public String toString() {
        return "ClsObservabilityConfig{"
                + "transportMode="
                + transportMode
                + ", endpoint="
                + endpoint
                + ", topicConfigured="
                + (topicId != null)
                + ", credentialsConfigured="
                + (!credentialsDestroyed && secretId != null && secretKey != null)
                + ", serviceName='"
                + serviceName
                + '\''
                + ", deploymentEnvironment='"
                + deploymentEnvironment
                + '\''
                + ", contentCaptureMode="
                + contentCaptureMode
                + ", reasoningCaptureMode="
                + reasoningCaptureMode
                + ", maxContentBytes="
                + maxContentBytes
                + ", reactorContextHookEnabled="
                + reactorContextHookEnabled
                + ", exportScheduleDelayMs="
                + exportScheduleDelay.toMillis()
                + ", maxQueueSize="
                + maxQueueSize
                + '}';
    }

    private static boolean parseBoolean(String raw, String name) {
        String value = clean(raw);
        if (value == null) {
            return false;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static int parseBoundedInt(
            String raw, String name, int fallback, int minimum, int maximum) {
        String value = clean(raw);
        if (value == null) {
            return fallback;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static int parseContentBudget(String raw) {
        String value = clean(raw);
        if (value == null) {
            return DEFAULT_MAX_CONTENT_BYTES;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("CLS_MAX_CONTENT_BYTES must be an integer", exception);
        }
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
        private int maxContentBytes = DEFAULT_MAX_CONTENT_BYTES;
        private boolean reactorContextHookEnabled;
        private Duration exportScheduleDelay = Duration.ofMillis(DEFAULT_EXPORT_SCHEDULE_DELAY_MS);
        private int maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;

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

        public Builder maxContentBytes(int value) {
            maxContentBytes = value;
            return this;
        }

        public Builder reactorContextHookEnabled(boolean value) {
            reactorContextHookEnabled = value;
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

        public ClsObservabilityConfig build() {
            if (transportMode == null) {
                throw new IllegalArgumentException("transport mode is required");
            }
            if (serviceName == null || serviceName.length() > 128) {
                throw new IllegalArgumentException("service name must contain 1 to 128 characters");
            }
            if (contentCaptureMode == null) {
                throw new IllegalArgumentException("content capture mode is required");
            }
            if (reasoningCaptureMode == null) {
                throw new IllegalArgumentException("reasoning capture mode is required");
            }
            if (maxContentBytes < 256 || maxContentBytes > MAX_CONTENT_BYTES) {
                throw new IllegalArgumentException(
                        "content byte budget must be between 256 and 1100000");
            }
            if (exportScheduleDelay == null
                    || exportScheduleDelay.toMillis() < MIN_EXPORT_SCHEDULE_DELAY_MS
                    || exportScheduleDelay.toMillis() > MAX_EXPORT_SCHEDULE_DELAY_MS) {
                throw new IllegalArgumentException(
                        "export schedule delay must be between "
                                + MIN_EXPORT_SCHEDULE_DELAY_MS
                                + " and "
                                + MAX_EXPORT_SCHEDULE_DELAY_MS
                                + " milliseconds");
            }
            if (maxQueueSize < MIN_MAX_QUEUE_SIZE || maxQueueSize > MAX_MAX_QUEUE_SIZE) {
                throw new IllegalArgumentException(
                        "max queue size must be between "
                                + MIN_MAX_QUEUE_SIZE
                                + " and "
                                + MAX_MAX_QUEUE_SIZE);
            }
            if (transportMode == TransportMode.CLOUD) {
                if (endpoint == null
                        || topicId == null
                        || secretId == null
                        || secretId.length == 0
                        || secretKey == null
                        || secretKey.length == 0) {
                    throw new IllegalArgumentException("CLS cloud configuration is incomplete");
                }
            }
            return new ClsObservabilityConfig(this);
        }
    }
}
