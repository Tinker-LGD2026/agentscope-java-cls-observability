package io.github.tinkerlgd2026.agentscope.cls.transport;

import com.tencentcloudapi.cls.producer.Result;
import com.tencentcloudapi.cls.producer.common.LogItem;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TencentClsSpanSink implements SpanSink {
    private static final Duration DEFAULT_EXPORT_TIMEOUT = Duration.ofSeconds(30);

    private final String topicId;
    private final ClsAsyncTransport transport;
    private final Duration exportTimeout;
    private final Object lifecycleLock = new Object();
    private final Set<CompletableFuture<Void>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public TencentClsSpanSink(String topicId, ClsAsyncTransport transport) {
        this(topicId, transport, DEFAULT_EXPORT_TIMEOUT);
    }

    TencentClsSpanSink(String topicId, ClsAsyncTransport transport, Duration exportTimeout) {
        if (topicId == null
                || topicId.isBlank()
                || transport == null
                || exportTimeout == null
                || exportTimeout.isNegative()
                || exportTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "topicId, transport and positive exportTimeout are required");
        }
        this.topicId = topicId;
        this.transport = transport;
        this.exportTimeout = exportTimeout;
    }

    @Override
    public CompletionStage<Void> export(List<ClsSpanRecord> records) {
        if (records == null || records.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        List<LogItem> items = records.stream().map(TencentClsSpanSink::toLogItem).toList();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("span sink is closed");
            }
            pending.add(completion);
        }
        completion.orTimeout(exportTimeout.toMillis(), TimeUnit.MILLISECONDS);
        completion.whenComplete((ignored, error) -> pending.remove(completion));
        try {
            transport.putLogs(topicId, items)
                    .whenComplete(
                            (result, error) -> {
                                if (error != null) {
                                    completion.completeExceptionally(error);
                                } else if (result != null && result.isSuccessful()) {
                                    completion.complete(null);
                                } else {
                                    completion.completeExceptionally(exportFailure(result));
                                }
                            });
        } catch (RuntimeException exception) {
            completion.completeExceptionally(exception);
        }
        return completion;
    }

    @Override
    public CompletionStage<Boolean> flush(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("flush timeout must be positive");
        }
        CompletableFuture<?>[] barrier;
        synchronized (lifecycleLock) {
            barrier = pending.toArray(CompletableFuture[]::new);
        }
        if (barrier.length == 0) {
            return CompletableFuture.completedFuture(true);
        }
        return CompletableFuture.allOf(barrier)
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((ignored, error) -> error == null);
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        try {
            transport.close(Duration.ofSeconds(5));
        } catch (InterruptedException exception) {
            synchronized (lifecycleLock) {
                closed.set(false);
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while closing CLS transport", exception);
        } catch (Exception exception) {
            synchronized (lifecycleLock) {
                closed.set(false);
            }
            throw new IllegalStateException("failed to close CLS transport", exception);
        }
    }

    private static ClsExportException exportFailure(Result result) {
        String code = result == null ? "NoResult" : safeErrorCode(result);
        int attempts = result == null ? 0 : result.getAttemptCount();
        return new ClsExportException(
                "CLS export failed: code=" + code + ", attempts=" + attempts);
    }

    private static String safeErrorCode(Result result) {
        if (result.getReservedAttempts() == null || result.getReservedAttempts().isEmpty()) {
            return "Unknown";
        }
        String code = result.getErrorCode();
        return code == null || code.isBlank() ? "Unknown" : code;
    }

    private static LogItem toLogItem(ClsSpanRecord record) {
        long timestampSeconds = Long.parseLong(record.start()) / 1_000_000_000L;
        LogItem item = new LogItem(timestampSeconds);
        record.fields().forEach(item::PushBack);
        return item;
    }

    private static final class ClsExportException extends RuntimeException {
        private ClsExportException(String message) {
            super(message);
        }
    }
}
