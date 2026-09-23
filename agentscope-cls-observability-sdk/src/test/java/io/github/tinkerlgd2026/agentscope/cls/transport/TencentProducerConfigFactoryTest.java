package io.github.tinkerlgd2026.agentscope.cls.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencentcloudapi.cls.producer.AsyncProducerConfig;
import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TencentProducerConfigFactoryTest {

    @Test
    void appliesBatchBufferLingerAndRetryPolicyFromConfig() {
        ClsObservabilityConfig config =
                ClsObservabilityConfig.builder()
                        .transportMode(ClsObservabilityConfig.TransportMode.CLOUD)
                        .endpoint("https://ap-guangzhou.cls.tencentcs.com")
                        .topicId("topic-1")
                        .secretId("secret-id".toCharArray())
                        .secretKey("secret-key".toCharArray())
                        .maxExportBatchBytes(3 * 1024 * 1024)
                        .maxExportBatchCount(128)
                        .producerLinger(Duration.ofMillis(500))
                        .maxProducerBufferBytes(16 * 1024 * 1024)
                        .build();

        AsyncProducerConfig producer = TencentProducerConfigFactory.create(config);

        assertThat(producer.getBatchSizeThresholdInBytes()).isEqualTo(3 * 1024 * 1024);
        assertThat(producer.getBatchCountThreshold()).isEqualTo(128);
        assertThat(producer.getLingerMs()).isEqualTo(500);
        assertThat(producer.getTotalSizeInBytes()).isEqualTo(16 * 1024 * 1024);
        assertThat(producer.getMaxBlockMs()).isZero();
        assertThat(producer.getRetries()).isZero();
    }

    @Test
    void usesZeroThreeDefaults() {
        ClsObservabilityConfig config =
                ClsObservabilityConfig.builder()
                        .transportMode(ClsObservabilityConfig.TransportMode.CLOUD)
                        .endpoint("https://ap-guangzhou.cls.tencentcs.com")
                        .topicId("topic-1")
                        .secretId("secret-id".toCharArray())
                        .secretKey("secret-key".toCharArray())
                        .build();

        AsyncProducerConfig producer = TencentProducerConfigFactory.create(config);

        assertThat(producer.getBatchSizeThresholdInBytes()).isEqualTo(4 * 1024 * 1024);
        assertThat(producer.getBatchCountThreshold()).isEqualTo(256);
        assertThat(producer.getLingerMs()).isEqualTo(200);
        assertThat(producer.getTotalSizeInBytes()).isEqualTo(64 * 1024 * 1024);
    }
}
