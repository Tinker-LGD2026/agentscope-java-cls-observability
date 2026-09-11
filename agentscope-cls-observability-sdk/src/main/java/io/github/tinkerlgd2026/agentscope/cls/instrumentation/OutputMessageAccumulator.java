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
        List<Map<String, Object>> parts = outputParts(false);
        if (encodedLength(parts) > maxBytes && hasVisibleReasoning(parts)) {
            reasoningBlocks.values().forEach(StreamPart::forceHash);
            parts = outputParts(true);
        }
        if (encodedLength(parts) > maxBytes && hasReasoning(parts)) {
            reasoningBlocks.values().forEach(StreamPart::forceHash);
            parts = withoutReasoning(parts);
        }
        List<Map<String, Object>> messages =
                parts.isEmpty()
                        ? List.of()
                        : List.of(Map.of("role", "assistant", "parts", parts));
        MessageCapturePolicy.CapturedMessages capture = capturePolicy.capture(messages, false);
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
        StreamPart existing = reasoningBlocks.get(key);
        if (existing != null) {
            malformedEvents++;
            return;
        }
        StreamPart part = new StreamPart("reasoning", reasoningMode, elapsedNanos);
        reasoningBlocks.put(key, part);
        ordered.add(part);
    }

    private void reasoningDelta(BlockKey key, String delta, long elapsedNanos) {
        StreamPart part = reasoningBlocks.get(key);
        if (part == null) {
            malformedEvents++;
            part = new StreamPart("reasoning", reasoningMode, elapsedNanos);
            reasoningBlocks.put(key, part);
            ordered.add(part);
        }
        if (delta != null && !delta.isEmpty() && firstReasoningDeltaNanos < 0) {
            firstReasoningDeltaNanos = elapsedNanos;
        }
        part.append(delta);
    }

    private void endReasoning(BlockKey key, long elapsedNanos) {
        StreamPart part = reasoningBlocks.get(key);
        if (part == null) {
            malformedEvents++;
            return;
        }
        part.close(elapsedNanos);
    }

    private void startText(BlockKey key, long elapsedNanos) {
        if (textBlocks.containsKey(key)) {
            malformedEvents++;
            return;
        }
        StreamPart part = new StreamPart("text", contentMode, elapsedNanos);
        textBlocks.put(key, part);
        ordered.add(part);
    }

    private void textDelta(BlockKey key, String delta, long elapsedNanos) {
        StreamPart part = textBlocks.get(key);
        if (part == null) {
            malformedEvents++;
            part = new StreamPart("text", contentMode, elapsedNanos);
            textBlocks.put(key, part);
            ordered.add(part);
        }
        if (delta != null && !delta.isEmpty() && firstTextDeltaNanos < 0) {
            firstTextDeltaNanos = elapsedNanos;
        }
        part.append(delta);
    }

    private void endText(BlockKey key, long elapsedNanos) {
        StreamPart part = textBlocks.get(key);
        if (part == null) {
            malformedEvents++;
            return;
        }
        part.close(elapsedNanos);
    }

    private void startTool(BlockKey key, String name) {
        ToolPart existing = toolCalls.get(key);
        if (existing != null) {
            malformedEvents++;
            return;
        }
        ToolPart part = new ToolPart(key.blockId(), name);
        toolCalls.put(key, part);
        ordered.add(part);
    }

    private void toolDelta(BlockKey key, String name, String delta) {
        ToolPart part = toolCalls.get(key);
        if (part == null) {
            malformedEvents++;
            part = new ToolPart(key.blockId(), name);
            toolCalls.put(key, part);
            ordered.add(part);
        }
        part.append(delta);
    }

    private void endTool(BlockKey key, String name) {
        ToolPart part = toolCalls.get(key);
        if (part == null) {
            malformedEvents++;
            part = new ToolPart(key.blockId(), name);
            toolCalls.put(key, part);
            ordered.add(part);
        }
        part.closed = true;
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
        private final MessageDigest digest = digest();
        private final ByteArrayOutputStream buffered = new ByteArrayOutputStream();
        private final long startedNanos;
        private long endedNanos = -1;
        private long originalBytes;
        private boolean truncated;
        private boolean forcedHash;

        private StreamPart(String type, ContentCaptureMode mode, long startedNanos) {
            this.type = type;
            this.mode = mode;
            this.startedNanos = startedNanos;
        }

        private boolean started() {
            return true;
        }

        private void append(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            byte[] bytes = delta.getBytes(StandardCharsets.UTF_8);
            originalBytes += bytes.length;
            if (mode != ContentCaptureMode.OFF) {
                digest.update(bytes);
            }
            if (mode == ContentCaptureMode.TRUNCATE || mode == ContentCaptureMode.FULL) {
                appendUtf8Prefix(delta, maxBytes - buffered.size());
            }
        }

        private void appendUtf8Prefix(String value, int remaining) {
            if (remaining <= 0) {
                truncated = true;
                return;
            }
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

        private void close(long elapsedNanos) {
            if (endedNanos < 0) {
                endedNanos = Math.max(startedNanos, elapsedNanos);
            }
        }

        private long durationNanos() {
            return endedNanos < 0 ? 0 : Math.max(0, endedNanos - startedNanos);
        }

        private long originalBytes() {
            return originalBytes;
        }

        private boolean truncated() {
            return truncated || forcedHash;
        }

        private void forceHash() {
            if ("reasoning".equals(type) && mode != ContentCaptureMode.OFF) {
                forcedHash = true;
            }
        }

        @Override
        public Map<String, Object> output(boolean degradedReasoning) {
            if (mode == ContentCaptureMode.OFF || originalBytes == 0) {
                return null;
            }
            boolean hashOnly =
                    mode == ContentCaptureMode.HASH
                            || forcedHash
                            || (degradedReasoning && "reasoning".equals(type));
            if (hashOnly) {
                return Map.of(
                        "type", type + "_hash",
                        "sha256", HexFormat.of().formatHex(digest.digest()),
                        "original_bytes", originalBytes,
                        "truncated", truncated());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", type);
            result.put("content", buffered.toString(StandardCharsets.UTF_8));
            if (truncated) {
                result.put("truncated", true);
            }
            return Map.copyOf(result);
        }
    }

    private final class ToolPart implements OutputPart {
        private final String id;
        private final String name;
        private final StringBuilder arguments = new StringBuilder();
        private boolean closed;
        private boolean truncated;

        private ToolPart(String id, String name) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }

        private void append(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            int remaining = maxBytes - arguments.toString().getBytes(StandardCharsets.UTF_8).length;
            for (int offset = 0; offset < delta.length(); ) {
                int codePoint = delta.codePointAt(offset);
                String character = new String(Character.toChars(codePoint));
                int size = character.getBytes(StandardCharsets.UTF_8).length;
                if (size > remaining) {
                    truncated = true;
                    return;
                }
                arguments.append(character);
                remaining -= size;
                offset += Character.charCount(codePoint);
            }
        }

        @Override
        public Map<String, Object> output(boolean degradedReasoning) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "tool_call");
            result.put("id", id);
            result.put("name", name);
            if (!arguments.isEmpty()) {
                result.put("arguments", parseArguments(arguments.toString()));
            }
            if (truncated) {
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

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
