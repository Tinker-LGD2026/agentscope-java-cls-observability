package io.github.tinkerlgd2026.agentscope.cls.transport;

import com.tencentcloudapi.cls.producer.AsyncProducerConfig;
import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig;
import java.util.Objects;

/** Builds the Tencent async producer configuration from the public SDK configuration. */
public final class TencentProducerConfigFactory {

    private TencentProducerConfigFactory() {}

    public static AsyncProducerConfig create(ClsObservabilityConfig config) {
        Objects.requireNonNull(config, "config");
        String source = localAddress();
        String endpoint = Objects.requireNonNull(config.endpoint()).toString();
        String accessKeyId = new String(Objects.requireNonNull(config.secretId()));
        String accessKeySecret = new String(Objects.requireNonNull(config.secretKey()));
        char[] token = config.secretToken().orElse(null);
        AsyncProducerConfig producer =
                token == null
                        ? new AsyncProducerConfig(
                                endpoint, accessKeyId, accessKeySecret, source)
                        : new AsyncProducerConfig(
                                endpoint,
                                accessKeyId,
                                accessKeySecret,
                                source,
                                new String(token));
        producer.setBatchSizeThresholdInBytes(config.maxExportBatchBytes());
        producer.setBatchCountThreshold(config.maxExportBatchCount());
        producer.setLingerMs((int) config.producerLinger().toMillis());
        producer.setTotalSizeInBytes(config.maxProducerBufferBytes());
        // Fail fast instead of blocking application threads when the producer buffer is full,
        // and leave retry policy to the caller: the SDK reports failures instead of re-sending.
        producer.setMaxBlockMs(0);
        producer.setRetries(0);
        return producer;
    }

    private static String localAddress() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception exception) {
            return "unknown";
        }
    }
}
