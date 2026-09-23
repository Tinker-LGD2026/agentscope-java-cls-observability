package io.github.tinkerlgd2026.agentscope.cls.exporter;

import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanEncoder;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanValidator;
import io.github.tinkerlgd2026.agentscope.cls.schema.Utf8LogItemSizer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Byte-aware replacement for the generic OTel BatchSpanProcessor: spans are synchronously
 * encoded to bounded records on end, queued with exact memory reservations, and exported in
 * the background on count/byte/time triggers.
 */
public final class ClsBatchSpanProcessor implements SpanProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClsBatchSpanProcessor.class);
    public static final int MAX_CONCURRENT_ENCODERS = 2;
    public static final int MAX_SINK_BATCH_COUNT = 256;

    private final ClsSpanEncoder encoder;
    private final ClsSpanValidator validator;
    private final EncodedSpanQueue queue;
    private final SpanRecordExporter exporter;
    private final TelemetryCounters counters;
    private final int maxExportBatchCount;
    private final long maxExportBatchBytes;
    private final Semaphore encoderPermits;
    private final ScheduledExecutorService scheduler;
    private final Object exportLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Duration flushTimeout = Duration.ofSeconds(30);

    public ClsBatchSpanProcessor(
            ClsSpanEncoder encoder,
            ClsSpanValidator validator,
            EncodedSpanQueue queue,
            SpanRecordExporter exporter,
            TelemetryCounters counters,
            Duration scheduleDelay,
            int maxExportBatchCount,
            long maxExportBatchBytes,
            ScheduledExecutorService scheduler) {
        this(
                encoder,
                validator,
                queue,
                exporter,
                counters,
                scheduleDelay,
                maxExportBatchCount,
                maxExportBatchBytes,
                scheduler,
                MAX_CONCURRENT_ENCODERS);
    }

    ClsBatchSpanProcessor(
            ClsSpanEncoder encoder,
            ClsSpanValidator validator,
            EncodedSpanQueue queue,
            SpanRecordExporter exporter,
            TelemetryCounters counters,
            Duration scheduleDelay,
            int maxExportBatchCount,
            long maxExportBatchBytes,
            ScheduledExecutorService scheduler,
            int maxConcurrentEncoders) {
        if (encoder == null
                || validator == null
                || queue == null
                || exporter == null
                || counters == null
                || scheduler == null
                || scheduleDelay == null
                || scheduleDelay.isNegative()
                || scheduleDelay.isZero()) {
            throw new IllegalArgumentException("all processor components are required");
        }
        this.encoder = encoder;
        this.validator = validator;
        this.queue = queue;
        this.exporter = exporter;
        this.counters = counters;
        this.maxExportBatchCount =
                Math.max(1, Math.min(MAX_SINK_BATCH_COUNT, maxExportBatchCount));
        this.maxExportBatchBytes = Math.max(1, maxExportBatchBytes);
        this.encoderPermits = new Semaphore(Math.max(0, maxConcurrentEncoders));
        this.scheduler = scheduler;
        this.scheduler.scheduleWithFixedDelay(
                this::exportIfNonEmpty,
                scheduleDelay.toNanos(),
                scheduleDelay.toNanos(),
                TimeUnit.NANOSECONDS);
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // Records are only created when spans end.
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (span == null || closed.get()) {
            counters.dropped(1);
            return;
        }
        if (!encoderPermits.tryAcquire()) {
            counters.dropped(1);
            counters.capacityDropped(1, 0);
            return;
        }
        try {
            ClsSpanRecord record;
            try {
                record = encoder.encode(span.toSpanData());
                if (!validator.validate(record).isEmpty()) {
                    counters.invalid(1);
                    return;
                }
            } catch (RuntimeException exception) {
                counters.invalid(1);
                return;
            }
            if (!queue.offer(record)) {
                counters.dropped(1);
                counters.capacityDropped(1, Utf8LogItemSizer.size(record.fields()));
                return;
            }
        } finally {
            encoderPermits.release();
        }
        if (queue.size() >= maxExportBatchCount || queue.usedBytes() >= maxExportBatchBytes) {
            try {
                scheduler.execute(this::exportIfNonEmpty);
            } catch (RuntimeException ignored) {
                // Scheduler was shut down concurrently; the span stays queued or the queue
                // has already been closed.
            }
        }
    }

    public void prepareFlush(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("flush timeout must be positive");
        }
        flushTimeout = timeout;
    }

    @Override
    public CompletableResultCode forceFlush() {
        CompletableResultCode result = new CompletableResultCode();
        long deadline = System.nanoTime() + flushTimeout.toNanos();
        try {
            drainQueue(deadline);
            if (awaitFlush(deadline)) {
                result.succeed();
            } else {
                counters.exportFailed(1);
                result.fail();
            }
        } catch (RuntimeException exception) {
            counters.exportFailed(1);
            LOGGER.warn("CLS span flush failed: {}", exception.getClass().getSimpleName());
            result.fail();
        }
        return result;
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return CompletableResultCode.ofSuccess();
        }
        CompletableResultCode result = new CompletableResultCode();
        try {
            drainQueue(System.nanoTime() + flushTimeout.toNanos());
            result.succeed();
        } catch (RuntimeException exception) {
            counters.exportFailed(1);
            LOGGER.warn("CLS span shutdown drain failed: {}", exception.getClass().getSimpleName());
            result.fail();
        }
        // Never call sink.flush here: a blocking custom sink would deadlock against its own
        // close-triggered release. sink.close() performs the terminal flush for real sinks.
        scheduler.shutdownNow();
        queue.close();
        exporter.close();
        return result;
    }

    private void exportIfNonEmpty() {
        if (closed.get() || queue.size() == 0) {
            return;
        }
        drainQueue(System.nanoTime() + flushTimeout.toNanos());
    }

    private void drainQueue(long deadlineNanos) {
        synchronized (exportLock) {
            EncodedSpanQueue.Batch batch;
            while ((batch = queue.pollBatch(maxExportBatchCount, maxExportBatchBytes)) != null) {
                long remainingMillis = remainingMillis(deadlineNanos);
                if (remainingMillis <= 0) {
                    batch.close();
                    return;
                }
                try {
                    exporter
                            .export(batch)
                            .toCompletableFuture()
                            .get(remainingMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    batch.close();
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception exception) {
                    LOGGER.warn(
                            "CLS span batch export failed: {}",
                            exception.getClass().getSimpleName());
                    if (exception instanceof java.util.concurrent.TimeoutException) {
                        // The completion callback releases the batch as well; close is
                        // idempotent, so an abandoned stage cannot leak its reservation.
                        batch.close();
                        return;
                    }
                }
            }
        }
    }

    private boolean awaitFlush(long deadlineNanos) {
        long remainingMillis = remainingMillis(deadlineNanos);
        if (remainingMillis <= 0) {
            return false;
        }
        try {
            return exporter
                    .flush(Duration.ofMillis(remainingMillis))
                    .toCompletableFuture()
                    .get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception exception) {
            LOGGER.warn("CLS span sink flush failed: {}", exception.getClass().getSimpleName());
            return false;
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, deadlineNanos - System.nanoTime()));
    }
}
