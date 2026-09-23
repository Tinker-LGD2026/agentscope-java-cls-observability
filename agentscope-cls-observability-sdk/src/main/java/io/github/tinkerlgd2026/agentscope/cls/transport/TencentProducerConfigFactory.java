package io.github.tinkerlgd2026.agentscope.cls.transport;

import com.tencentcloudapi.cls.producer.AsyncProducerConfig;
import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig;
import java.util.Objects;

/** Builds the Tencent async producer configuration from the public SDK configuration. */
public final class TencentProducerConfigFactory {

    private TencentProducerConfigFactory() {}

    public static AsyncProducerConfig create(ClsObservabilityConfig config) {
        Objects.requireNonNull(config, "config");
        java.net.URI endpointUri = Objects.requireNonNull(config.endpoint(), "endpoint");
        if (endpointUri.toString().isBlank()) {
            throw new IllegalArgumentException("CLS endpoint is required");
        }
        String endpoint = endpointUri.toString();
        char[] secretId = Objects.requireNonNull(config.secretId(), "secretId");
        char[] secretKey = Objects.requireNonNull(config.secretKey(), "secretKey");
        char[] token = config.secretToken().orElse(null);
        String source = localAddress();
        try {
            AsyncProducerConfig producer =
                    token == null
                            ? new AsyncProducerConfig(
                                    endpoint,
                                    new String(secretId),
                                    new String(secretKey),
                                    source)
                            : new AsyncProducerConfig(
                                    endpoint,
                                    new String(secretId),
                                    new String(secretKey),
                                    source,
                                    new String(token));
            applyThresholds(producer, config);
            return producer;
        } finally {
            java.util.Arrays.fill(secretId, '\0');
            java.util.Arrays.fill(secretKey, '\0');
            if (token != null) {
                java.util.Arrays.fill(token, '\0');
            }
        }
    }

    private static void applyThresholds(
            AsyncProducerConfig producer, ClsObservabilityConfig config) {
        producer.setBatchSizeThresholdInBytes(config.maxExportBatchBytes());
        producer.setBatchCountThreshold(config.maxExportBatchCount());
        producer.setLingerMs((int) config.producerLinger().toMillis());
        producer.setTotalSizeInBytes(config.maxProducerBufferBytes());
        // Fail fast instead of blocking application threads when the producer buffer is full,
        // and leave retry policy to the caller: the SDK reports failures instead of re-sending.
        producer.setMaxBlockMs(0);
        producer.setRetries(0);
    }

    private static String localAddress() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception exception) {
            return "unknown";
        }
    }
}
