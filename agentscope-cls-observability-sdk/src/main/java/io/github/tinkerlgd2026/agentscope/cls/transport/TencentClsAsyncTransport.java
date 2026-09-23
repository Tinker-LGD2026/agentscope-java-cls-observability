package io.github.tinkerlgd2026.agentscope.cls.transport;

import com.tencentcloudapi.cls.producer.AsyncProducerClient;
import com.tencentcloudapi.cls.producer.Result;
import com.tencentcloudapi.cls.producer.common.LogItem;
import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class TencentClsAsyncTransport implements ClsAsyncTransport {
    private final AsyncProducerClient client;

    public TencentClsAsyncTransport(ClsObservabilityConfig config) {
        if (config == null
                || config.transportMode() != ClsObservabilityConfig.TransportMode.CLOUD) {
            throw new IllegalArgumentException("cloud configuration is required");
        }
        char[] secretId = Objects.requireNonNull(config.secretId());
        char[] secretKey = Objects.requireNonNull(config.secretKey());
        char[] secretToken = config.secretToken().orElse(null);
        try {
            client = new AsyncProducerClient(TencentProducerConfigFactory.create(config));
        } finally {
            Arrays.fill(secretId, '\0');
            Arrays.fill(secretKey, '\0');
            if (secretToken != null) {
                Arrays.fill(secretToken, '\0');
            }
        }
    }

    @Override
    public CompletionStage<Result> putLogs(String topicId, List<LogItem> items) {
        CompletableFuture<Result> completion = new CompletableFuture<>();
        try {
            client.putLogs(topicId, items, completion::complete);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            completion.completeExceptionally(exception);
        } catch (Exception exception) {
            completion.completeExceptionally(exception);
        }
        return completion;
    }

    @Override
    public void close(Duration timeout) throws Exception {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("close timeout must be positive");
        }
        client.close(timeout.toMillis());
    }

}
