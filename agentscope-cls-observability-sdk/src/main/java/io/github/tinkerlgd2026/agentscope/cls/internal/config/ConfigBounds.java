package io.github.tinkerlgd2026.agentscope.cls.internal.config;

/** Central source of configuration defaults and supported ranges. */
public final class ConfigBounds {
    public static final int DEFAULT_MAX_CONTENT_BYTES = 950_000;
    public static final int LEGACY_MAX_CONTENT_BYTES = 1_100_000;
    public static final int MAX_CONTENT_BYTES = 1_000_000;
    public static final int MIN_CONTENT_BYTES = 256;

    public static final int DEFAULT_TRUNCATE_PREVIEW_BYTES = 4_096;
    public static final int MIN_TRUNCATE_PREVIEW_BYTES = 256;
    public static final int MAX_TRUNCATE_PREVIEW_BYTES = 65_536;

    public static final long DEFAULT_HITL_WAIT_TIMEOUT_MS = 600_000L;
    public static final long MIN_HITL_WAIT_TIMEOUT_MS = 1_000L;
    public static final long MAX_HITL_WAIT_TIMEOUT_MS = 86_400_000L;
    public static final long DEFAULT_SHUTDOWN_TIMEOUT_MS = 45_000L;
    public static final long DEFAULT_EXPORT_TIMEOUT_MS = 30_000L;
    public static final long MIN_LIFECYCLE_TIMEOUT_MS = 1_000L;
    public static final long MAX_LIFECYCLE_TIMEOUT_MS = 600_000L;

    public static final int DEFAULT_MAX_EXPORT_BATCH_BYTES = 4 * 1_024 * 1_024;
    public static final int MIN_MAX_EXPORT_BATCH_BYTES = 2 * 1_024 * 1_024;
    public static final int MAX_MAX_EXPORT_BATCH_BYTES = 4_718_592;
    public static final int DEFAULT_MAX_EXPORT_BATCH_COUNT = 256;
    public static final int MIN_MAX_EXPORT_BATCH_COUNT = 1;
    public static final int MAX_MAX_EXPORT_BATCH_COUNT = 10_000;
    public static final int DEFAULT_PRODUCER_LINGER_MS = 200;
    public static final int MIN_PRODUCER_LINGER_MS = 100;
    public static final int MAX_PRODUCER_LINGER_MS = 5_000;

    public static final long DEFAULT_MAX_INVOCATION_CAPTURE_MEMORY_BYTES = 8L * 1_024 * 1_024;
    public static final long MIN_MAX_INVOCATION_CAPTURE_MEMORY_BYTES = 1L * 1_024 * 1_024;
    public static final long MAX_MAX_INVOCATION_CAPTURE_MEMORY_BYTES = 256L * 1_024 * 1_024;
    public static final long DEFAULT_MAX_CAPTURE_MEMORY_BYTES = 64L * 1_024 * 1_024;
    public static final long MIN_MAX_CAPTURE_MEMORY_BYTES = 8L * 1_024 * 1_024;
    public static final long MAX_MAX_CAPTURE_MEMORY_BYTES = 1L * 1_024 * 1_024 * 1_024;
    public static final int DEFAULT_MAX_PRODUCER_BUFFER_BYTES = 64 * 1_024 * 1_024;
    public static final int MIN_MAX_PRODUCER_BUFFER_BYTES = 1 * 1_024 * 1_024;
    public static final int MAX_MAX_PRODUCER_BUFFER_BYTES = 1_073_741_824;

    public static final int DEFAULT_EXPORT_SCHEDULE_DELAY_MS = 2_000;
    public static final int MIN_EXPORT_SCHEDULE_DELAY_MS = 50;
    public static final int MAX_EXPORT_SCHEDULE_DELAY_MS = 60_000;
    public static final int DEFAULT_MAX_QUEUE_SIZE = 4_096;
    public static final int MIN_MAX_QUEUE_SIZE = 256;
    public static final int MAX_MAX_QUEUE_SIZE = 65_536;

    private ConfigBounds() {}
}
