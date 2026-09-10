package io.github.tinkerlgd2026.agentscope.cls.transport;

import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;

public interface SpanSink extends AutoCloseable {
    CompletionStage<Void> export(List<ClsSpanRecord> records);

    CompletionStage<Boolean> flush(Duration timeout);

    @Override
    void close();
}
