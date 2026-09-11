package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.MessageCapturePolicy;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Collects provider-neutral model output blocks while preserving first-seen order. */
final class OutputMessageAccumulator {
    private final ObjectMapper objectMapper;
    private final MessageCapturePolicy capturePolicy;
    private final ContentCaptureMode contentMode;
    private final ContentCaptureMode reasoningMode;
    private final int maxBytes;
    private final List<OutputPart> ordered = new ArrayList<>();
    private final Map<BlockKey, StreamPart> textBlocks = new LinkedHashMap<>();
    private final Map<BlockKey, StreamPart> reasoningBlocks = new LinkedHashMap<>();
    private final Map<BlockKey, ToolPart> toolCalls = new LinkedHashMap<>();
    private final Map<BlockKey, PartKind> lifecycleTypes = new LinkedHashMap<>();
    private long malformedEvents;
    private long firstReasoningDeltaNanos = -1;
    private long firstTextDeltaNanos = -1;
    private Result completed;

    OutputMessageAccumulator(
            ObjectMapper objectMapper,
            MessageCapturePolicy capturePolicy,
            ContentCaptureMode contentMode,
            ContentCaptureMode reasoningMode,
            int maxBytes) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.capturePolicy = Objects.requireNonNull(capturePolicy, "capturePolicy");
        this.contentMode = Objects.requireNonNull(contentMode, "contentMode");
        this.reasoningMode = Objects.requireNonNull(reasoningMode, "reasoningMode");
        this.maxBytes = maxBytes;
    }

    void accept(AgentEvent event, long elapsedNanos) {
        if (event == null || completed != null) {
            return;
        }
        if (event instanceof ThinkingBlockStartEvent start) {
            startReasoning(key(start.getReplyId(), start.getBlockId()), elapsedNanos);
        } else if (event instanceof ThinkingBlockDeltaEvent delta) {
            reasoningDelta(key(delta.getReplyId(), delta.getBlockId()), delta.getDelta(), elapsedNanos);
        } else if (event instanceof ThinkingBlockEndEvent end) {
            endReasoning(key(end.getReplyId(), end.getBlockId()), elapsedNanos);
        } else if (event instanceof TextBlockStartEvent start) {
            startText(key(start.getReplyId(), start.getBlockId()), elapsedNanos);
        } else if (event instanceof TextBlockDeltaEvent delta) {
            textDelta(key(delta.getReplyId(), delta.getBlockId()), delta.getDelta(), elapsedNanos);
        } else if (event instanceof TextBlockEndEvent end) {
            endText(key(end.getReplyId(), end.getBlockId()), elapsedNanos);
        } else if (event instanceof ToolCallStartEvent start) {
            startTool(key(start.getReplyId(), start.getToolCallId()), start.getToolCallName());
        } else if (event instanceof ToolCallDeltaEvent delta) {
            toolDelta(
                    key(delta.getReplyId(), delta.getToolCallId()),
                    delta.getToolCallName(),
                    delta.getDelta());
        } else if (event instanceof ToolCallEndEvent end) {
            endTool(key(end.getReplyId(), end.getToolCallId()), end.getToolCallName());
        }
    }

    Result finish(long elapsedNanos) {
        if (completed != null) {
            return completed;
        }
        reasoningBlocks.values().forEach(part -> part.close(elapsedNanos));
        textBlocks.values().forEach(part -> part.close(elapsedNanos));
        toolCalls.values().forEach(ToolPart::close);
        List<Map<String, Object>> parts = outputParts(false);
        if (encodedLength(parts) > maxBytes && hasVisibleReasoning(parts)) {
            reasoningBlocks.values().forEach(StreamPart::forceHash);
            parts = outputParts(true);
        }
        if (encodedLength(parts) > maxBytes && hasReasoning(parts)) {
            reasoningBlocks.values().forEach(StreamPart::forceHash);
            parts = withoutReasoning(parts);
        }
        boolean textExpected = hasText(parts);
        boolean reasoningIncluded = hasReasoning(parts);
        List<Map<String, Object>> messages = messages(parts);
        MessageCapturePolicy.CapturedMessages capture = capturePolicy.capture(messages, false);
        if (textExpected && reasoningIncluded && !capturedHasText(capture.value())) {
            reasoningBlocks.values().forEach(StreamPart::forceHash);
            parts = withoutReasoning(parts);
            capture = capturePolicy.capture(messages(parts), false);
        }
        long reasoningBytes = reasoningBlocks.values().stream().mapToLong(StreamPart::originalBytes).sum();
        long reasoningDurationNanos =
                reasoningBlocks.values().stream().mapToLong(StreamPart::durationNanos).sum();
        boolean truncated = reasoningBlocks.values().stream().anyMatch(StreamPart::truncated);
        ReasoningMetrics reasoning =
                new ReasoningMetrics(
                        !reasoningBlocks.isEmpty() &&
                                (reasoningBytes > 0 || reasoningBlocks.values().stream().anyMatch(StreamPart::started)),
                        reasoningBlocks.size(),
                        reasoningBytes,
                        reasoningDurationNanos / 1_000_000L,
                        optionalMillis(firstReasoningDeltaNanos),
                        truncated,
                        malformedEvents);
        completed =
                new Result(
                        capture.value(),
                        !toolCalls.isEmpty(),
                        reasoning,
                        optionalMillis(firstTextDeltaNanos));
        return completed;
    }

    private void startReasoning(BlockKey key, long elapsedNanos) {
        if (!claim(key, PartKind.REASONING) || reasoningBlocks.containsKey(key)) {
            malformedEvents++;
            return;
        }
        StreamPart part = new StreamPart("reasoning", reasoningMode, elapsedNanos);
        reasoningBlocks.put(key, part);
        ordered.add(part);
    }

    private void reasoningDelta(BlockKey key, String delta, long elapsedNanos) {
        if (!claim(key, PartKind.REASONING)) {
            malformedEvents++;
            return;
        }
        StreamPart part = reasoningBlocks.get(key);
        if (part == null) {
            malformedEvents++;
            part = new StreamPart("reasoning", reasoningMode, elapsedNanos);
            reasoningBlocks.put(key, part);
            ordered.add(part);
        }
        if (part.closed()) {
            malformedEvents++;
            return;
        }
        if (delta != null && !delta.isEmpty() && firstReasoningDeltaNanos < 0) {
            firstReasoningDeltaNanos = elapsedNanos;
        }
        part.append(delta);
    }

    private void endReasoning(BlockKey key, long elapsedNanos) {
        StreamPart part = reasoningBlocks.get(key);
        if (part == null || lifecycleTypes.get(key) != PartKind.REASONING || part.closed()) {
            malformedEvents++;
            return;
        }
        part.close(elapsedNanos);
    }

    private void startText(BlockKey key, long elapsedNanos) {
        if (!claim(key, PartKind.TEXT) || textBlocks.containsKey(key)) {
            malformedEvents++;
            return;
        }
        StreamPart part = new StreamPart("text", contentMode, elapsedNanos);
        textBlocks.put(key, part);
        ordered.add(part);
    }

    private void textDelta(BlockKey key, String delta, long elapsedNanos) {
        if (!claim(key, PartKind.TEXT)) {
            malformedEvents++;
            return;
        }
        StreamPart part = textBlocks.get(key);
        if (part == null) {
            malformedEvents++;
            part = new StreamPart("text", contentMode, elapsedNanos);
            textBlocks.put(key, part);
            ordered.add(part);
        }
        if (part.closed()) {
            malformedEvents++;
            return;
        }
        if (delta != null && !delta.isEmpty() && firstTextDeltaNanos < 0) {
            firstTextDeltaNanos = elapsedNanos;
        }
        part.append(delta);
    }

    private void endText(BlockKey key, long elapsedNanos) {
        StreamPart part = textBlocks.get(key);
        if (part == null || lifecycleTypes.get(key) != PartKind.TEXT || part.closed()) {
            malformedEvents++;
            return;
        }
        part.close(elapsedNanos);
    }

    private void startTool(BlockKey key, String name) {
        if (!claim(key, PartKind.TOOL) || toolCalls.containsKey(key)) {
            malformedEvents++;
            return;
        }
        ToolPart part = new ToolPart(key.blockId(), name);
        toolCalls.put(key, part);
        ordered.add(part);
    }

    private void toolDelta(BlockKey key, String name, String delta) {
        if (!claim(key, PartKind.TOOL)) {
            malformedEvents++;
            return;
        }
        ToolPart part = toolCalls.get(key);
        if (part == null) {
            malformedEvents++;
            part = new ToolPart(key.blockId(), name);
            toolCalls.put(key, part);
            ordered.add(part);
        }
        if (part.closed) {
            malformedEvents++;
            return;
        }
        part.append(delta);
    }

    private void endTool(BlockKey key, String name) {
        ToolPart part = toolCalls.get(key);
        if (part == null || lifecycleTypes.get(key) != PartKind.TOOL || part.closed) {
            malformedEvents++;
            return;
        }
        part.close();
    }

    private boolean claim(BlockKey key, PartKind kind) {
        PartKind existing = lifecycleTypes.putIfAbsent(key, kind);
        return existing == null || existing == kind;
    }

    private List<Map<String, Object>> outputParts(boolean degradedReasoning) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (OutputPart part : new LinkedHashSet<>(ordered)) {
            Map<String, Object> value = part.output(degradedReasoning);
            if (value != null) {
                parts.add(value);
            }
        }
        return List.copyOf(parts);
    }

    private int encodedLength(List<Map<String, Object>> parts) {
        try {
            return objectMapper.writeValueAsBytes(
                            List.of(Map.of("role", "assistant", "parts", parts)))
                    .length;
        } catch (JsonProcessingException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private static List<Map<String, Object>> messages(List<Map<String, Object>> parts) {
        return parts.isEmpty()
                ? List.of()
                : List.of(Map.of("role", "assistant", "parts", parts));
    }

    private static boolean hasText(List<Map<String, Object>> parts) {
        return parts.stream()
                .anyMatch(
                        part -> {
                            Object type = part.get("type");
                            return "text".equals(type) || "text_hash".equals(type);
                        });
    }

    private static boolean capturedHasText(Optional<JsonNode> messages) {
        if (messages.isEmpty() || !messages.orElseThrow().isArray()) {
            return false;
        }
        for (JsonNode message : messages.orElseThrow()) {
            JsonNode parts = message.get("parts");
            if (parts == null || !parts.isArray()) {
                continue;
            }
            for (JsonNode part : parts) {
                String type = part.path("type").asText();
                if ("text".equals(type) || "text_hash".equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasVisibleReasoning(List<Map<String, Object>> parts) {
        return parts.stream().anyMatch(part -> "reasoning".equals(part.get("type")));
    }

    private static boolean hasReasoning(List<Map<String, Object>> parts) {
        return parts.stream()
                .anyMatch(
                        part -> {
                            Object type = part.get("type");
                            return "reasoning".equals(type) || "reasoning_hash".equals(type);
                        });
    }

    private static List<Map<String, Object>> withoutReasoning(
            List<Map<String, Object>> parts) {
        return parts.stream()
                .filter(
                        part -> {
                            Object type = part.get("type");
                            return !"reasoning".equals(type) && !"reasoning_hash".equals(type);
                        })
                .toList();
    }

    private static OptionalLong optionalMillis(long nanos) {
        return nanos < 0 ? OptionalLong.empty() : OptionalLong.of(nanos / 1_000_000L);
    }

    private static BlockKey key(String replyId, String blockId) {
        return new BlockKey(replyId == null ? "" : replyId, blockId == null ? "" : blockId);
    }

    private interface OutputPart {
        Map<String, Object> output(boolean degradedReasoning);
    }

    private final class StreamPart implements OutputPart {
        private final String type;
        private final ContentCaptureMode mode;
        private final PayloadBuffer payload;
        private final long startedNanos;
        private long endedNanos = -1;
        private boolean forcedHash;

        private StreamPart(String type, ContentCaptureMode mode, long startedNanos) {
            this.type = type;
            this.mode = mode;
            this.payload = new PayloadBuffer(mode, maxBytes);
            this.startedNanos = startedNanos;
        }

        private boolean started() {
            return true;
        }

        private boolean closed() {
            return endedNanos >= 0;
        }

        private void append(String delta) {
            payload.append(delta);
        }

        private void close(long elapsedNanos) {
            if (!closed()) {
                payload.finish();
                endedNanos = Math.max(startedNanos, elapsedNanos);
            }
        }

        private long durationNanos() {
            return endedNanos < 0 ? 0 : Math.max(0, endedNanos - startedNanos);
        }

        private long originalBytes() {
            return payload.originalBytes;
        }

        private boolean truncated() {
            return payload.truncated || forcedHash;
        }

        private void forceHash() {
            if ("reasoning".equals(type) && mode != ContentCaptureMode.OFF) {
                forcedHash = true;
            }
        }

        @Override
        public Map<String, Object> output(boolean degradedReasoning) {
            if (mode == ContentCaptureMode.OFF || payload.originalBytes == 0) {
                return null;
            }
            boolean hashOnly =
                    mode == ContentCaptureMode.HASH
                            || forcedHash
                            || (degradedReasoning && "reasoning".equals(type));
            if (hashOnly) {
                return hashPart(type + "_hash", payload, truncated());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", type);
            result.put("content", payload.text());
            if (payload.truncated) {
                result.put("truncated", true);
            }
            return Map.copyOf(result);
        }
    }

    private final class ToolPart implements OutputPart {
        private final String id;
        private final String name;
        private final PayloadBuffer arguments = new PayloadBuffer(contentMode, maxBytes);
        private boolean closed;

        private ToolPart(String id, String name) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }

        private void append(String delta) {
            arguments.append(delta);
        }

        private void close() {
            if (!closed) {
                arguments.finish();
                closed = true;
            }
        }

        @Override
        public Map<String, Object> output(boolean degradedReasoning) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "tool_call");
            result.put("id", id);
            result.put("name", name);
            if (arguments.originalBytes > 0 && contentMode == ContentCaptureMode.HASH) {
                result.put("arguments", hashEnvelope(arguments));
            } else if (arguments.originalBytes > 0 && contentMode != ContentCaptureMode.OFF) {
                result.put("arguments", parseArguments(arguments.text()));
            }
            if (arguments.truncated) {
                result.put("truncated", true);
            }
            return Map.copyOf(result);
        }

        private Object parseArguments(String value) {
            try {
                return objectMapper.readTree(value);
            } catch (JsonProcessingException exception) {
                return value;
            }
        }
    }

    static final class PayloadBuffer {
        private final ContentCaptureMode mode;
        private final int maxBytes;
        private final MessageDigest digest;
        private final ByteArrayOutputStream buffered;
        private long originalBytes;
        private boolean truncated;
        private char pendingHighSurrogate;
        private String digestHex;

        PayloadBuffer(ContentCaptureMode mode, int maxBytes) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.maxBytes = maxBytes;
            this.digest = mode == ContentCaptureMode.OFF ? null : digest();
            this.buffered =
                    mode == ContentCaptureMode.TRUNCATE || mode == ContentCaptureMode.FULL
                            ? new ByteArrayOutputStream()
                            : null;
        }

        void append(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            String value =
                    pendingHighSurrogate == 0 ? delta : pendingHighSurrogate + delta;
            pendingHighSurrogate = 0;
            if (!value.isEmpty() && Character.isHighSurrogate(value.charAt(value.length() - 1))) {
                pendingHighSurrogate = value.charAt(value.length() - 1);
                value = value.substring(0, value.length() - 1);
            }
            appendComplete(value);
        }

        void finish() {
            if (pendingHighSurrogate != 0) {
                appendComplete(String.valueOf(pendingHighSurrogate));
                pendingHighSurrogate = 0;
            }
        }

        private void appendComplete(String value) {
            if (value.isEmpty()) {
                return;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            originalBytes += bytes.length;
            if (digest != null) {
                digest.update(bytes);
            }
            if (buffered != null) {
                appendUtf8Prefix(value, maxBytes - buffered.size());
            }
        }

        private void appendUtf8Prefix(String value, int remaining) {
            for (int offset = 0; offset < value.length(); ) {
                int codePoint = value.codePointAt(offset);
                byte[] bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > remaining) {
                    truncated = true;
                    return;
                }
                buffered.writeBytes(bytes);
                remaining -= bytes.length;
                offset += Character.charCount(codePoint);
            }
        }

        String text() {
            return buffered == null ? "" : buffered.toString(StandardCharsets.UTF_8);
        }

        long originalBytes() {
            return originalBytes;
        }

        boolean truncated() {
            return truncated;
        }

        private String digestHex() {
            if (digestHex == null) {
                digestHex = HexFormat.of().formatHex(Objects.requireNonNull(digest).digest());
            }
            return digestHex;
        }
    }

    private static Map<String, Object> hashPart(
            String type, PayloadBuffer payload, boolean truncated) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("sha256", payload.digestHex());
        result.put("original_bytes", payload.originalBytes);
        if (truncated) {
            result.put("truncated", true);
        }
        return Map.copyOf(result);
    }

    private static MessageCapturePolicy.PrecomputedHash hashEnvelope(PayloadBuffer payload) {
        return new MessageCapturePolicy.PrecomputedHash(
                payload.digestHex(), payload.originalBytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private enum PartKind {
        REASONING,
        TEXT,
        TOOL
    }

    private record BlockKey(String replyId, String blockId) {}

    record Result(
            Optional<JsonNode> messages,
            boolean usedTools,
            ReasoningMetrics reasoning,
            OptionalLong responseTimeToFirstTokenMs) {
        Result {
            messages = Objects.requireNonNull(messages, "messages");
            reasoning = Objects.requireNonNull(reasoning, "reasoning");
            responseTimeToFirstTokenMs =
                    Objects.requireNonNull(responseTimeToFirstTokenMs, "responseTimeToFirstTokenMs");
        }
    }

    record ReasoningMetrics(
            boolean present,
            long blockCount,
            long outputBytes,
            long durationMs,
            OptionalLong timeToFirstTokenMs,
            boolean truncated,
            long malformedEventCount) {
        ReasoningMetrics {
            timeToFirstTokenMs = Objects.requireNonNull(timeToFirstTokenMs, "timeToFirstTokenMs");
        }
    }
}
