package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import com.fasterxml.jackson.databind.ObjectWriter;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentSanitizer;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Bounded, fail-safe accumulation of streamed tool results into one tool span. All content
 * passes through the shared {@link ContentSanitizer}; hash-only mode keeps a streaming digest
 * instead of payload bytes.
 */
final class ToolSpan {
    private static final int MAX_TOOL_RESULT_PARTS = 256;

    private final Span span;
    private final long startedNanos;
    private final ContentSanitizer contentSanitizer;
    private final Consumer<Throwable> onTelemetryFailure;
    private final List<Object> results = new ArrayList<>();
    private final StringBuilder textResult = new StringBuilder();
    private final StreamingDigest digest;
    private final AtomicBoolean ended = new AtomicBoolean();
    private int accumulatedBytes;
    private boolean hasText;
    private boolean truncated;

    ToolSpan(
            Span span,
            long startedNanos,
            ContentSanitizer contentSanitizer,
            ObjectWriter canonicalWriter,
            Consumer<Throwable> onTelemetryFailure) {
        this.span = Objects.requireNonNull(span, "span");
        this.startedNanos = startedNanos;
        this.contentSanitizer = Objects.requireNonNull(contentSanitizer, "contentSanitizer");
        this.onTelemetryFailure =
                Objects.requireNonNull(onTelemetryFailure, "onTelemetryFailure");
        this.digest = new StreamingDigest(true, canonicalWriter, onTelemetryFailure);
    }

    void appendText(@Nullable String value) {
        if (value == null || ended.get() || !contentSanitizer.isCapturing()) {
            return;
        }
        synchronized (results) {
            if (contentSanitizer.isHashOnly()) {
                digest.updateJson(value);
                return;
            }
            hasText = true;
            int budget = contentSanitizer.maxBytes();
            if (textResult.length() >= budget) {
                truncated = true;
                return;
            }
            int allowed = Math.min(value.length(), budget - textResult.length());
            textResult.append(value, 0, allowed);
            if (allowed < value.length()) {
                truncated = true;
            }
        }
    }

    void appendData(@Nullable Object value) {
        if (value == null || ended.get() || !contentSanitizer.isCapturing()) {
            return;
        }
        synchronized (results) {
            if (contentSanitizer.isHashOnly()) {
                digest.updateJson(value);
                return;
            }
            if (results.size() >= MAX_TOOL_RESULT_PARTS
                    || accumulatedBytes >= contentSanitizer.maxBytes()) {
                truncated = true;
                return;
            }
            contentSanitizer
                    .capture(value)
                    .ifPresent(
                            captured -> {
                                int bytes =
                                        captured.toString()
                                                .getBytes(StandardCharsets.UTF_8)
                                                .length;
                                if (accumulatedBytes + bytes > contentSanitizer.maxBytes()) {
                                    truncated = true;
                                } else {
                                    results.add(captured);
                                    accumulatedBytes += bytes;
                                }
                            });
        }
    }

    void success() {
        if (ended.compareAndSet(false, true)) {
            finishSpan(
                    () -> {
                        try {
                            finishResult();
                        } finally {
                            setDuration(span, "gen_ai.tool.call.duration_ms", startedNanos);
                            span.setStatus(StatusCode.OK);
                        }
                    });
        }
    }

    void error(String type, @Nullable Throwable error) {
        if (ended.compareAndSet(false, true)) {
            finishSpan(
                    () -> {
                        try {
                            finishResult();
                        } finally {
                            setDuration(span, "gen_ai.tool.call.duration_ms", startedNanos);
                            span.setAttribute("gen_ai.tool.error.type", type);
                            span.setAttribute("error.type", type);
                            span.setStatus(StatusCode.ERROR, "tool execution failed");
                            String exceptionType =
                                    error == null ? type : error.getClass().getName();
                            span.addEvent(
                                    "exception",
                                    Objects.requireNonNull(
                                            Attributes.builder()
                                                    .put(
                                                            "exception.type",
                                                            Objects.requireNonNull(
                                                                    exceptionType))
                                                    .build()));
                        }
                    });
        }
    }

    void cancel() {
        error("cancelled", null);
    }

    private void finishResult() {
        synchronized (results) {
            if (contentSanitizer.isHashOnly()) {
                if (digest.originalBytes() > 0) {
                    span.setAttribute(
                            "gen_ai.tool.call.result",
                            contentSanitizer
                                    .streamedContentHash(
                                            digest.hexDigest(), digest.originalBytes())
                                    .toString());
                }
                return;
            }
            List<Object> parts = new ArrayList<>();
            if (hasText) {
                contentSanitizer.capture(textResult.toString()).ifPresent(parts::add);
            }
            parts.addAll(results);
            if (parts.isEmpty()) {
                return;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("parts", List.copyOf(parts));
            if (truncated) {
                result.put("truncated", true);
            }
            capture(span, "gen_ai.tool.call.result", result);
        }
    }

    private void capture(Span target, String key, Object value) {
        try {
            contentSanitizer
                    .capture(value)
                    .ifPresent(
                            node ->
                                    target.setAttribute(
                                            Objects.requireNonNull(key),
                                            Objects.requireNonNull(node.toString())));
        } catch (RuntimeException exception) {
            target.setAttribute(key + ".capture_error", exception.getClass().getSimpleName());
        }
    }

    private void finishSpan(Runnable updater) {
        try {
            updater.run();
        } catch (RuntimeException | StackOverflowError failure) {
            onTelemetryFailure.accept(failure);
        } finally {
            try {
                span.end();
            } catch (RuntimeException | StackOverflowError failure) {
                onTelemetryFailure.accept(failure);
            }
        }
    }

    private static void setDuration(Span span, String key, long startedNanos) {
        span.setAttribute(
                Objects.requireNonNull(key),
                Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
    }

    /** Streaming SHA-256 over canonical JSON parts with optional tool-result framing. */
    private static final class StreamingDigest {
        private static final byte[] TOOL_PREFIX = "{\"parts\":[".getBytes(StandardCharsets.UTF_8);
        private static final byte[] TOOL_SUFFIX = "]}".getBytes(StandardCharsets.UTF_8);

        private final MessageDigest digest = sha256Digest();
        private final boolean toolResult;
        private final ObjectWriter canonicalWriter;
        private final Consumer<Throwable> onTelemetryFailure;
        private long originalBytes;
        private int parts;
        private boolean finished;

        private StreamingDigest(
                boolean toolResult,
                ObjectWriter canonicalWriter,
                Consumer<Throwable> onTelemetryFailure) {
            this.toolResult = toolResult;
            this.canonicalWriter = canonicalWriter;
            this.onTelemetryFailure = onTelemetryFailure;
            if (toolResult) {
                updateRaw(TOOL_PREFIX);
            }
        }

        private void updateJson(Object value) {
            if (!toolResult) {
                throw new IllegalStateException("JSON parts require a tool-result digest");
            }
            if (parts++ > 0) {
                updateRaw(new byte[] {','});
            }
            CountingDigestOutput output = new CountingDigestOutput(digest);
            try {
                canonicalWriter.writeValue(output, value);
                originalBytes += output.count();
            } catch (Exception exception) {
                onTelemetryFailure.accept(exception);
            }
        }

        private void updateRaw(byte[] bytes) {
            digest.update(bytes);
            originalBytes += bytes.length;
        }

        private long originalBytes() {
            finishFraming();
            return originalBytes;
        }

        private String hexDigest() {
            finishFraming();
            return HexFormat.of().formatHex(digest.digest());
        }

        private void finishFraming() {
            if (finished) {
                return;
            }
            if (toolResult) {
                updateRaw(TOOL_SUFFIX);
            }
            finished = true;
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static final class CountingDigestOutput extends OutputStream {
        private final MessageDigest digest;
        private long count;

        private CountingDigestOutput(MessageDigest digest) {
            this.digest = digest;
        }

        @Override
        public void write(int value) {
            digest.update((byte) value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            digest.update(bytes, offset, length);
            count += length;
        }

        @Override
        public void close() throws IOException {
            // ObjectMapper owns this view, not the digest lifecycle.
        }

        private long count() {
            return count;
        }
    }
}
