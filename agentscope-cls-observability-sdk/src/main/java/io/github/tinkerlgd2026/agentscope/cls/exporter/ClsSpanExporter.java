package io.github.tinkerlgd2026.agentscope.cls.exporter;

import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanDocument;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanEncoder;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanValidator;
import io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClsSpanExporter implements SpanExporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClsSpanExporter.class);
    private static final long WARNING_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final ClsSpanEncoder encoder;
    private final ClsSpanValidator validator;
    private final SpanSink sink;
    private final TelemetryCounters counters;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastWarningNanos = new AtomicLong(Long.MIN_VALUE);
    private volatile Duration flushTimeout = Duration.ofSeconds(5);

    public ClsSpanExporter(ClsSpanEncoder encoder, ClsSpanValidator validator, SpanSink sink) {
        this(encoder, validator, sink, new TelemetryCounters());
    }

    public ClsSpanExporter(
            ClsSpanEncoder encoder,
            ClsSpanValidator validator,
            SpanSink sink,
            TelemetryCounters counters) {
        if (encoder == null || validator == null || sink == null || counters == null) {
            throw new IllegalArgumentException("encoder, validator, sink and counters are required");
        }
        this.encoder = encoder;
        this.validator = validator;
        this.sink = sink;
        this.counters = counters;
    }

    @Override
    public CompletableResultCode export(@Nonnull Collection<SpanData> spans) {
        if (closed.get()) {
            counters.dropped(spans.size());
            return CompletableResultCode.ofFailure();
        }
        List<ClsSpanRecord> records = new ArrayList<>(spans.size());
        int rejected = 0;
        for (SpanData span : spans) {
            try {
                ClsSpanDocument document = encoder.encodeDocument(span);
                List<String> validationErrors = validator.validate(document);
                if (validationErrors.isEmpty()) {
                    records.add(document.record());
                } else {
                    rejected++;
                    warn("CLS span rejected by schema validation: {}", validationErrors);
                }
            } catch (RuntimeException exception) {
                rejected++;
                warn("CLS span could not be encoded: {}", exception.getClass().getSimpleName());
            }
        }
        if (rejected > 0) {
            counters.invalid(rejected);
        }
        if (records.isEmpty()) {
            return CompletableResultCode.ofFailure();
        }
        CompletableResultCode exported = send(List.copyOf(records));
        if (rejected == 0) {
            return exported;
        }
        CompletableResultCode partial = new CompletableResultCode();
        exported.whenComplete(partial::fail);
        return partial;
    }

    private CompletableResultCode send(List<ClsSpanRecord> records) {
        try {
            CompletionStage<Void> export = sink.export(records);
            export.whenComplete(
                    (ignored, error) -> {
                        if (error == null) {
                            counters.accepted(records.size());
                        } else {
                            counters.exportFailed(records.size());
                            warn("CLS span export failed: {}", error.getClass().getSimpleName());
                        }
                    });
            return adapt(export);
        } catch (RuntimeException exception) {
            counters.exportFailed(records.size());
            warn("CLS span export failed: {}", exception.getClass().getSimpleName());
            return CompletableResultCode.ofFailure();
        }
    }

    public void prepareFlush(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("flush timeout must be positive");
        }
        flushTimeout = timeout;
    }

    @Override
    public CompletableResultCode flush() {
        return adapt(sink.flush(flushTimeout));
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return CompletableResultCode.ofSuccess();
        }
        try {
            sink.close();
            return CompletableResultCode.ofSuccess();
        } catch (RuntimeException exception) {
            closed.set(false);
            counters.exportFailed(1);
            warn("CLS span sink shutdown failed: {}", exception.getClass().getSimpleName());
            return CompletableResultCode.ofFailure();
        }
    }

    private void warn(String template, Object value) {
        long now = System.nanoTime();
        long previous = lastWarningNanos.get();
        if ((previous == Long.MIN_VALUE || now - previous >= WARNING_INTERVAL_NANOS)
                && lastWarningNanos.compareAndSet(previous, now)) {
            LOGGER.warn(template, value);
        }
    }

    private static CompletableResultCode adapt(CompletionStage<?> stage) {
        CompletableResultCode result = new CompletableResultCode();
        stage.whenComplete(
                (ignored, error) -> {
                    if (error == null && !(ignored instanceof Boolean value && !value)) {
                        result.succeed();
                    } else {
                        result.fail();
                    }
                });
        return result;
    }
}
