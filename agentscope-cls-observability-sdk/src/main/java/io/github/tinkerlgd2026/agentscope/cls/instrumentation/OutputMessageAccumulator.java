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
import io.github.tinkerlgd2026.agentscope.cls.internal.IdentityNormalizer;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.MessageCapturePolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
    private static final int MAX_PARTS = 256;
    private static final int MAX_REASONING_PARTS = 128;
    private static final int MAX_TEXT_PARTS = 96;
    private static final int MAX_TOOL_PARTS = 32;
    private static final int METADATA_MAX_BYTES = 256;
    private static final int ENCODING_CHUNK_CHARS = 4096;

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
    private final PayloadBudget reasoningPayloadBudget;
    private final PayloadBudget textPayloadBudget;
    private final PayloadBudget toolPayloadBudget;
    private long malformedEvents;
    private long capacityDroppedParts;
    private long capacityDroppedBytes;
    private boolean reasoningCapacityTruncated;
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
        int reasoningBytes = maxBytes / 4;
        int toolBytes = maxBytes / 4;
        int textBytes = maxBytes - reasoningBytes - toolBytes;
        this.reasoningPayloadBudget = new PayloadBudget(reasoningBytes);
        this.textPayloadBudget = new PayloadBudget(textBytes);
        this.toolPayloadBudget = new PayloadBudget(toolBytes);
    }

    synchronized void accept(AgentEvent event, long elapsedNanos) {
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

    synchronized Result finish(long elapsedNanos) {
        if (completed != null) {
            return completed;
        }
        reasoningBlocks.values().forEach(part -> part.close(elapsedNanos));
        textBlocks.values().forEach(part -> part.close(elapsedNanos));
        toolCalls.values().forEach(ToolPart::close);
        List<Map<String, Object>> parts = outputParts(false);
        boolean reasoningIncluded = hasReasoning(parts);
        MessageCapturePolicy.CapturedMessages capture =
                capturePolicy.capture(messages(parts), false);
        boolean reasoningRemovedByFinalBudget =
                reasoningIncluded && !capturedHasReasoning(capture.value());
        if (reasoningRemovedByFinalBudget) {
            reasoningBlocks.values().forEach(StreamPart::markBudgetTruncated);
        }
        long reasoningBytes =
                reasoningBlocks.values().stream()
                        .mapToLong(StreamPart::originalBytes)
                        .reduce(0L, OutputMessageAccumulator::saturatingAdd);
        long reasoningDurationNanos =
                reasoningBlocks.values().stream()
                        .mapToLong(StreamPart::durationNanos)
                        .reduce(0L, OutputMessageAccumulator::saturatingAdd);
        boolean truncated =
                reasoningCapacityTruncated
                        || reasoningBlocks.values().stream().anyMatch(StreamPart::truncated);
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
                        optionalMillis(firstTextDeltaNanos),
                        capacityDroppedParts,
                        capacityDroppedBytes);
        return completed;
    }

    private void startReasoning(BlockKey key, long elapsedNanos) {
        ClaimResult claim = claim(key, PartKind.REASONING);
        if (claim == ClaimResult.REJECTED) {
            return;
        }
        if (claim != ClaimResult.NEW || reasoningBlocks.containsKey(key)) {
            incrementMalformedEvents();
            return;
        }
        StreamPart part =
                new StreamPart(
                        "reasoning", reasoningMode, elapsedNanos, reasoningPayloadBudget);
        reasoningBlocks.put(key, part);
        ordered.add(part);
    }

    private void reasoningDelta(BlockKey key, String delta, long elapsedNanos) {
        ClaimResult claim = claim(key, PartKind.REASONING);
        if (claim == ClaimResult.REJECTED) {
            recordDroppedPayload(PartKind.REASONING, delta);
            return;
        }
        if (claim == ClaimResult.CONFLICT) {
            incrementMalformedEvents();
            return;
        }
        StreamPart part = reasoningBlocks.get(key);
        if (part == null) {
            incrementMalformedEvents();
            part =
                    new StreamPart(
                            "reasoning", reasoningMode, elapsedNanos, reasoningPayloadBudget);
            reasoningBlocks.put(key, part);
            ordered.add(part);
        }
        if (part.closed()) {
            incrementMalformedEvents();
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
            incrementMalformedEvents();
            return;
        }
        part.close(elapsedNanos);
    }

    private void startText(BlockKey key, long elapsedNanos) {
        ClaimResult claim = claim(key, PartKind.TEXT);
        if (claim == ClaimResult.REJECTED) {
            return;
        }
        if (claim != ClaimResult.NEW || textBlocks.containsKey(key)) {
            incrementMalformedEvents();
            return;
        }
        StreamPart part =
                new StreamPart("text", contentMode, elapsedNanos, textPayloadBudget);
        textBlocks.put(key, part);
        ordered.add(part);
    }

    private void textDelta(BlockKey key, String delta, long elapsedNanos) {
        ClaimResult claim = claim(key, PartKind.TEXT);
        if (claim == ClaimResult.REJECTED) {
            recordDroppedPayload(PartKind.TEXT, delta);
            return;
        }
        if (claim == ClaimResult.CONFLICT) {
            incrementMalformedEvents();
            return;
        }
        StreamPart part = textBlocks.get(key);
        if (part == null) {
            incrementMalformedEvents();
            part = new StreamPart("text", contentMode, elapsedNanos, textPayloadBudget);
            textBlocks.put(key, part);
            ordered.add(part);
        }
        if (part.closed()) {
            incrementMalformedEvents();
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
            incrementMalformedEvents();
            return;
        }
        part.close(elapsedNanos);
    }

    private void startTool(BlockKey key, String name) {
        ClaimResult claim = claim(key, PartKind.TOOL);
        if (claim == ClaimResult.REJECTED) {
            return;
        }
        if (claim != ClaimResult.NEW || toolCalls.containsKey(key)) {
            incrementMalformedEvents();
            return;
        }
        ToolPart part = new ToolPart(key.blockId(), name, toolPayloadBudget);
        toolCalls.put(key, part);
        ordered.add(part);
    }

    private void toolDelta(BlockKey key, String name, String delta) {
        ClaimResult claim = claim(key, PartKind.TOOL);
        if (claim == ClaimResult.REJECTED) {
            recordDroppedPayload(PartKind.TOOL, delta);
            return;
        }
        if (claim == ClaimResult.CONFLICT) {
            incrementMalformedEvents();
            return;
        }
        ToolPart part = toolCalls.get(key);
        if (part == null) {
            incrementMalformedEvents();
            part = new ToolPart(key.blockId(), name, toolPayloadBudget);
            toolCalls.put(key, part);
            ordered.add(part);
        }
        if (part.closed) {
            incrementMalformedEvents();
            return;
        }
        part.append(delta);
    }

    private void endTool(BlockKey key, String name) {
        ToolPart part = toolCalls.get(key);
        if (part == null || lifecycleTypes.get(key) != PartKind.TOOL || part.closed) {
            incrementMalformedEvents();
            return;
        }
        part.close();
    }

    private ClaimResult claim(BlockKey key, PartKind kind) {
        PartKind existing = lifecycleTypes.get(key);
        if (existing != null) {
            return existing == kind ? ClaimResult.EXISTING : ClaimResult.CONFLICT;
        }
        if (lifecycleTypes.size() >= MAX_PARTS || partCount(kind) >= partLimit(kind)) {
            capacityDroppedParts = saturatingAdd(capacityDroppedParts, 1L);
            capacityDroppedBytes =
                    saturatingAdd(
                            capacityDroppedBytes,
                            key.replyId().getBytes(StandardCharsets.UTF_8).length
                                    + key.blockId().getBytes(StandardCharsets.UTF_8).length);
            if (kind == PartKind.REASONING) {
                reasoningCapacityTruncated = true;
            }
            return ClaimResult.REJECTED;
        }
        lifecycleTypes.put(key, kind);
        return ClaimResult.NEW;
    }

    private void recordDroppedPayload(PartKind kind, String delta) {
        capacityDroppedBytes =
                saturatingAdd(
                        capacityDroppedBytes,
                        delta == null ? 0 : delta.getBytes(StandardCharsets.UTF_8).length);
        if (kind == PartKind.REASONING) {
            reasoningCapacityTruncated = true;
        }
    }

    private int partCount(PartKind kind) {
        return switch (kind) {
            case REASONING -> reasoningBlocks.size();
            case TEXT -> textBlocks.size();
            case TOOL -> toolCalls.size();
        };
    }

    private static int partLimit(PartKind kind) {
        return switch (kind) {
            case REASONING -> MAX_REASONING_PARTS;
            case TEXT -> MAX_TEXT_PARTS;
            case TOOL -> MAX_TOOL_PARTS;
        };
    }

    private void incrementMalformedEvents() {
        malformedEvents = saturatingAdd(malformedEvents, 1L);
    }

    synchronized long retainedPayloadBytes() {
        return saturatingAdd(
                reasoningPayloadBudget.used(),
                saturatingAdd(textPayloadBudget.used(), toolPayloadBudget.used()));
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
        CappedOutputStream output = new CappedOutputStream(maxBytes);
        try {
            objectMapper.writeValue(
                    output, List.of(Map.of("role", "assistant", "parts", parts)));
            return output.count();
        } catch (IOException exception) {
            return maxBytes + 1;
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

    private static boolean capturedHasReasoning(Optional<JsonNode> messages) {
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
                if ("reasoning".equals(type) || "reasoning_hash".equals(type)) {
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

    private static long saturatingAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    private static BlockKey key(String replyId, String blockId) {
        return new BlockKey(boundedMetadata(replyId), boundedMetadata(blockId));
    }

    private static String boundedMetadata(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return IdentityNormalizer.bounded(value, METADATA_MAX_BYTES);
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

        private StreamPart(
                String type,
                ContentCaptureMode mode,
                long startedNanos,
                PayloadBudget payloadBudget) {
            this.type = type;
            this.mode = mode;
            this.payload = new PayloadBuffer(mode, maxBytes, payloadBudget);
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

        private void markBudgetTruncated() {
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
        private final PayloadBuffer arguments;
        private boolean closed;

        private ToolPart(String id, String name, PayloadBudget payloadBudget) {
            this.id = boundedMetadata(id);
            this.name = boundedMetadata(name);
            this.arguments = new PayloadBuffer(contentMode, maxBytes, payloadBudget);
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
        private final PayloadBudget payloadBudget;
        private final MessageDigest digest;
        private final ByteArrayOutputStream buffered;
        private long originalBytes;
        private boolean truncated;
        private char pendingHighSurrogate;
        private String digestHex;

        PayloadBuffer(ContentCaptureMode mode, int maxBytes) {
            this(mode, maxBytes, new PayloadBudget(maxBytes));
        }

        PayloadBuffer(ContentCaptureMode mode, int maxBytes, PayloadBudget payloadBudget) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.maxBytes = maxBytes;
            this.payloadBudget = Objects.requireNonNull(payloadBudget, "payloadBudget");
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
            int start = 0;
            if (pendingHighSurrogate != 0) {
                if (Character.isLowSurrogate(delta.charAt(0))) {
                    appendComplete(new String(new char[] {pendingHighSurrogate, delta.charAt(0)}));
                    start = 1;
                } else {
                    appendComplete(String.valueOf(pendingHighSurrogate));
                }
                pendingHighSurrogate = 0;
            }
            int end = delta.length();
            if (start < end && Character.isHighSurrogate(delta.charAt(end - 1))) {
                pendingHighSurrogate = delta.charAt(end - 1);
                end--;
            }
            appendComplete(delta, start, end);
        }

        void finish() {
            if (pendingHighSurrogate != 0) {
                appendComplete(String.valueOf(pendingHighSurrogate));
                pendingHighSurrogate = 0;
            }
        }

        private void appendComplete(String value) {
            appendComplete(value, 0, value.length());
        }

        private void appendComplete(String value, int initialStart, int finalEnd) {
            for (int start = initialStart; start < finalEnd; ) {
                int end = Math.min(finalEnd, start + ENCODING_CHUNK_CHARS);
                if (end < finalEnd && Character.isHighSurrogate(value.charAt(end - 1))) {
                    end--;
                }
                String chunk = value.substring(start, end);
                byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
                originalBytes = saturatingAdd(originalBytes, bytes.length);
                if (digest != null) {
                    digest.update(bytes);
                }
                if (buffered != null) {
                    appendUtf8Prefix(chunk);
                }
                start = end;
            }
        }

        private void appendUtf8Prefix(String value) {
            if (truncated) {
                return;
            }
            int remaining = Math.min(maxBytes - buffered.size(), payloadBudget.remaining());
            for (int offset = 0; offset < value.length(); ) {
                int codePoint = value.codePointAt(offset);
                byte[] bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                if (bytes.length > remaining || !payloadBudget.tryAcquire(bytes.length)) {
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

    private static final class PayloadBudget {
        private final int limit;
        private int used;

        private PayloadBudget(int limit) {
            this.limit = Math.max(0, limit);
        }

        private boolean tryAcquire(int bytes) {
            if (bytes < 0 || bytes > remaining()) {
                return false;
            }
            used += bytes;
            return true;
        }

        private int remaining() {
            return limit - used;
        }

        private int used() {
            return used;
        }
    }

    private static final class CappedOutputStream extends OutputStream {
        private final int limit;
        private int count;

        private CappedOutputStream(int limit) {
            this.limit = Math.max(0, limit);
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            requireCapacity(length);
            count += length;
        }

        private void requireCapacity(int length) throws IOException {
            if (length > limit - count) {
                throw new IOException("encoded output exceeds byte budget");
            }
        }

        private int count() {
            return count;
        }
    }

    private enum PartKind {
        REASONING,
        TEXT,
        TOOL
    }

    private enum ClaimResult {
        NEW,
        EXISTING,
        CONFLICT,
        REJECTED
    }

    private record BlockKey(String replyId, String blockId) {}

    record Result(
            Optional<JsonNode> messages,
            boolean usedTools,
            ReasoningMetrics reasoning,
            OptionalLong responseTimeToFirstTokenMs,
            long capacityDroppedParts,
            long capacityDroppedBytes) {
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
