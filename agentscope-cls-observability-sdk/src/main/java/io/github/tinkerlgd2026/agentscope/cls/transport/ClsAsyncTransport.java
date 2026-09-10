package io.github.tinkerlgd2026.agentscope.cls.transport;

import com.tencentcloudapi.cls.producer.Result;
import com.tencentcloudapi.cls.producer.common.LogItem;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface ClsAsyncTransport extends AutoCloseable {
    CompletionStage<Result> putLogs(String topicId, List<LogItem> items);

    void close(Duration timeout) throws Exception;

    @Override
    default void close() throws Exception {
        close(Duration.ofSeconds(5));
    }
}
