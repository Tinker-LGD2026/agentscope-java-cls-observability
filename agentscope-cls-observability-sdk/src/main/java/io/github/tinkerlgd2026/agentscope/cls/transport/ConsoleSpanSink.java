package io.github.tinkerlgd2026.agentscope.cls.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConsoleSpanSink implements SpanSink {
    private final ObjectMapper objectMapper;
    private final PrintStream output;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ConsoleSpanSink(ObjectMapper objectMapper, PrintStream output) {
        if (objectMapper == null || output == null) {
            throw new IllegalArgumentException("objectMapper and output are required");
        }
        this.objectMapper = objectMapper;
        this.output = output;
    }

    @Override
    public CompletionStage<Void> export(List<ClsSpanRecord> records) {
        if (closed.get()) {
            throw new IllegalStateException("span sink is closed");
        }
        try {
            synchronized (output) {
                for (ClsSpanRecord record : records) {
                    output.println(objectMapper.writeValueAsString(record.fields()));
                }
                output.flush();
                if (output.checkError()) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("failed to write CLS span to console"));
                }
            }
            return CompletableFuture.completedFuture(null);
        } catch (JsonProcessingException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public CompletionStage<Boolean> flush(Duration timeout) {
        output.flush();
        return CompletableFuture.completedFuture(!output.checkError());
    }

    @Override
    public void close() {
        closed.set(true);
        output.flush();
    }
}
