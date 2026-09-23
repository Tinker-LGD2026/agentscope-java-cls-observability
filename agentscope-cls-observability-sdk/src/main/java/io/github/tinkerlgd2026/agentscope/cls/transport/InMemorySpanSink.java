package io.github.tinkerlgd2026.agentscope.cls.transport;

import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class InMemorySpanSink implements SpanSink {
    private final List<ClsSpanRecord> records = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean closed;

    @Override
    public CompletionStage<Void> export(List<ClsSpanRecord> records) {
        this.records.addAll(List.copyOf(records));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Boolean> flush(Duration timeout) {
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void close() {
        closed = true;
    }

    public boolean closed() {
        return closed;
    }

    public List<ClsSpanRecord> records() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }
}
