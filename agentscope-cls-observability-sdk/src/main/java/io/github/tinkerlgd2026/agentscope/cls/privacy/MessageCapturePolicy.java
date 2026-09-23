package io.github.tinkerlgd2026.agentscope.cls.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Applies independent privacy policies to ordinary and reasoning message parts. */
public final class MessageCapturePolicy {
    private static final int MAX_REASONING_PARTS = 8;
    private static final int MAX_CONTENT_PARTS = 16;
    private static final int MAX_TOOL_PARTS = 8;

    private final ObjectMapper objectMapper;
    private final ContentCaptureMode contentMode;
    private final ContentCaptureMode reasoningMode;
    private final ContentSanitizer contentSanitizer;
    private final ContentSanitizer reasoningSanitizer;
    private final ContentSanitizer finalBudgetSanitizer;

    public MessageCapturePolicy(
            ObjectMapper objectMapper,
            ContentCaptureMode contentMode,
            ContentCaptureMode reasoningMode,
            int maxBytes) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.contentMode = Objects.requireNonNull(contentMode, "contentMode");
        this.reasoningMode = Objects.requireNonNull(reasoningMode, "reasoningMode");
        contentSanitizer = new ContentSanitizer(objectMapper, contentMode, maxBytes);
        reasoningSanitizer = new ContentSanitizer(objectMapper, reasoningMode, maxBytes);
        finalBudgetSanitizer =
                new ContentSanitizer(objectMapper, ContentCaptureMode.FULL, maxBytes);
    }

    public CapturedMessages capture(
            @Nullable List<Map<String, Object>> messages, boolean includeObservableHash) {
        return capture(messages, includeObservableHash, true);
    }

    public CapturedMessages capture(
            @Nullable List<Map<String, Object>> messages,
            boolean includeObservableHash,
            boolean sourceComplete) {
        CapturedMessages unplanned =
                captureUnplanned(messages, includeObservableHash, sourceComplete);
        if (unplanned.value().isEmpty()) {
            return unplanned;
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> captured =
                    objectMapper.convertValue(unplanned.value().orElseThrow(), List.class);
            return new CapturedMessages(captureWithPriority(captured), unplanned.observableHash());
        } catch (RuntimeException failure) {
            return new CapturedMessages(Optional.empty(), unplanned.observableHash());
        }
    }

    public CapturedMessages captureUnplanned(
            @Nullable List<Map<String, Object>> messages,
            boolean includeObservableHash,
            boolean sourceComplete) {
        List<Map<String, Object>> source = messages == null ? List.of() : messages;
        try {
            BoundedMessages bounded = boundedMessages(source);
            List<Map<String, Object>> hashBasis = hashBasis(bounded.messages());
            Optional<String> observableHash =
                    includeObservableHash && sourceComplete && bounded.complete()
                            ? Optional.of(finalBudgetSanitizer.hash(hashBasis))
                            : Optional.empty();
            List<Map<String, Object>> captured = captureMessages(bounded.messages());
            Optional<JsonNode> value =
                    captured.isEmpty()
                            ? Optional.empty()
                            : Optional.of(objectMapper.valueToTree(captured));
            return new CapturedMessages(value, observableHash);
        } catch (RuntimeException | StackOverflowError failure) {
            return new CapturedMessages(Optional.empty(), Optional.empty());
        }
    }

    private BoundedMessages boundedMessages(List<Map<String, Object>> messages) {
        SelectionLimits limits = new SelectionLimits();
        List<Map<String, Object>> bounded = new ArrayList<>();
        int firstMessage = Math.max(0, messages.size() - SelectionLimits.MAX_MESSAGES);
        if (firstMessage > 0) {
            limits.incomplete = true;
        }
        for (int index = messages.size() - 1; index >= firstMessage; index--) {
            Map<String, Object> message = messages.get(index);
            if (message == null) {
                continue;
            }
            List<Map<String, Object>> parts = boundedParts(message.get("parts"), limits, 0);
            if (parts.isEmpty()) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("role", nonNullValue(message.get("role"), "unknown"));
            if (message.get("name") != null) {
                copy.put("name", message.get("name"));
            }
            copy.put("parts", parts);
            bounded.add(0, Map.copyOf(copy));
            if (limits.scanExhausted()) {
                break;
            }
        }
        return new BoundedMessages(List.copyOf(bounded), !limits.incomplete);
    }

    private List<Map<String, Object>> boundedParts(
            @Nullable Object value, SelectionLimits limits, int depth) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        if (depth >= SelectionLimits.MAX_NESTING_DEPTH) {
            limits.incomplete = true;
            return List.of();
        }
        List<Map<String, Object>> bounded = new ArrayList<>();
        for (int index = list.size() - 1; index >= 0; index--) {
            if (!limits.tryScan()) {
                break;
            }
            Object item = list.get(index);
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> part = (Map<String, Object>) raw;
            String type = String.valueOf(part.getOrDefault("type", "unknown"));
            if (!limits.tryAcquire(type)) {
                limits.incomplete = true;
                continue;
            }
            if ("tool_call_response".equals(type)) {
                Map<String, Object> nested = new LinkedHashMap<>(part);
                nested.put("result", boundedParts(part.get("result"), limits, depth + 1));
                bounded.add(0, Map.copyOf(nested));
            } else {
                bounded.add(0, part);
            }
        }
        return List.copyOf(bounded);
    }

    private Optional<JsonNode> captureWithPriority(List<Map<String, Object>> captured) {
        if (captured.isEmpty()) {
            return Optional.empty();
        }
        return new CaptureBudgetPlanner(objectMapper, finalBudgetSanitizer.maxBytes())
                .capture(captured)
                .value();
    }

    private List<Map<String, Object>> captureMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> captured = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            if (message == null) {
                continue;
            }
            List<Map<String, Object>> parts = captureParts(asParts(message.get("parts")));
            if (parts.isEmpty()) {
                continue;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("role", nonNullValue(message.get("role"), "unknown"));
            if (message.get("name") != null) {
                result.put("name", message.get("name"));
            }
            result.put("parts", parts);
            captured.add(Map.copyOf(result));
        }
        return List.copyOf(captured);
    }

    private List<Map<String, Object>> captureParts(List<Map<String, Object>> parts) {
        List<Map<String, Object>> captured = new ArrayList<>();
        for (Map<String, Object> part : parts) {
            Map<String, Object> transformed = capturePart(part);
            if (transformed != null) {
                captured.add(transformed);
            }
        }
        return List.copyOf(captured);
    }

    private @Nullable Map<String, Object> capturePart(Map<String, Object> part) {
        String type = String.valueOf(part.getOrDefault("type", "unknown"));
        return switch (type) {
            case "reasoning" ->
                    captureContentPart(part.get("content"), reasoningMode, reasoningSanitizer,
                            "reasoning", "reasoning_hash");
            case "text" ->
                    captureContentPart(part.get("content"), contentMode, contentSanitizer,
                            "text", "text_hash");
            case "reasoning_hash" ->
                    reasoningMode == ContentCaptureMode.OFF ? null : copyHashPart(part, "reasoning_hash");
            case "text_hash" ->
                    contentMode == ContentCaptureMode.OFF ? null : copyHashPart(part, "text_hash");
            case "tool_call" -> captureToolCall(part);
            case "tool_call_response" -> captureToolResponse(part);
            default -> captureUnknown(part);
        };
    }

    private @Nullable Map<String, Object> captureContentPart(
            Object value,
            ContentCaptureMode mode,
            ContentSanitizer sanitizer,
            String visibleType,
            String hashType) {
        Optional<JsonNode> captured = sanitizer.capture(value);
        if (captured.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (mode == ContentCaptureMode.HASH) {
            result.put("type", hashType);
            result.put("sha256", captured.orElseThrow().path("sha256").asText());
            result.put("original_bytes", captured.orElseThrow().path("original_bytes").asLong());
        } else {
            result.put("type", visibleType);
            result.put("content", captured.orElseThrow());
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> captureToolCall(Map<String, Object> part) {
        Map<String, Object> result = toolIdentity(part, true);
        Object arguments = part.get("arguments");
        if (contentMode == ContentCaptureMode.HASH && arguments instanceof PrecomputedHash hash) {
            result.put("arguments", hash.asMap());
        } else {
            contentSanitizer
                    .capture(arguments)
                    .ifPresent(value -> result.put("arguments", value));
        }
        if (Boolean.TRUE.equals(part.get("truncated"))) {
            result.put("truncated", true);
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> captureToolResponse(Map<String, Object> part) {
        Map<String, Object> result = toolIdentity(part, true);
        List<Map<String, Object>> nested = captureParts(asParts(part.get("result")));
        if (!nested.isEmpty()) {
            result.put("result", nested);
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> toolIdentity(Map<String, Object> part, boolean includeName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", part.getOrDefault("type", "unknown"));
        if (part.get("id") != null) {
            result.put("id", part.get("id"));
        }
        if (includeName && part.get("name") != null) {
            result.put("name", part.get("name"));
        }
        if (part.get("state") != null) {
            result.put("state", part.get("state"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private @Nullable Map<String, Object> captureUnknown(Map<String, Object> part) {
        Optional<JsonNode> captured = contentSanitizer.capture(part);
        if (captured.isEmpty()) {
            return null;
        }
        if (contentMode == ContentCaptureMode.HASH) {
            return Map.of(
                    "type", "content_hash",
                    "sha256", captured.orElseThrow().path("sha256").asText(),
                    "original_bytes", captured.orElseThrow().path("original_bytes").asLong());
        }
        return objectMapper.convertValue(captured.orElseThrow(), Map.class);
    }

    private List<Map<String, Object>> hashBasis(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            if (message == null) {
                continue;
            }
            List<Map<String, Object>> parts = hashBasisParts(asParts(message.get("parts")));
            if (parts.isEmpty()) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("role", nonNullValue(message.get("role"), "unknown"));
            if (message.get("name") != null) {
                copy.put("name", message.get("name"));
            }
            copy.put("parts", parts);
            result.add(Map.copyOf(copy));
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> hashBasisParts(List<Map<String, Object>> parts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> part : parts) {
            String type = String.valueOf(part.getOrDefault("type", "unknown"));
            if (("reasoning".equals(type) || "reasoning_hash".equals(type))
                    && reasoningMode == ContentCaptureMode.OFF) {
                continue;
            }
            if ("tool_call_response".equals(type)) {
                Map<String, Object> nested = new LinkedHashMap<>(part);
                nested.put("result", hashBasisParts(asParts(part.get("result"))));
                result.add(Map.copyOf(nested));
            } else {
                result.add(copy(part));
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> copyHashPart(
            Map<String, Object> source, String expectedType) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", expectedType);
        result.put("sha256", nonNullValue(source.get("sha256"), ""));
        Object originalBytes = source.get("original_bytes");
        result.put("original_bytes", originalBytes instanceof Number ? originalBytes : 0L);
        if (Boolean.TRUE.equals(source.get("truncated"))) {
            result.put("truncated", true);
        }
        return Map.copyOf(result);
    }

    private static Object nonNullValue(@Nullable Object value, Object fallback) {
        return value == null ? fallback : value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asParts(@Nullable Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return Map.copyOf(new LinkedHashMap<>(source));
    }

    private record BoundedMessages(List<Map<String, Object>> messages, boolean complete) {}

    private static final class SelectionLimits {
        private static final int MAX_MESSAGES = 32;
        private static final int MAX_SCANNED_PARTS = 256;
        private static final int MAX_NESTING_DEPTH = 16;

        private int scannedParts;
        private int reasoningParts;
        private int contentParts;
        private int toolParts;
        private boolean incomplete;

        private boolean tryScan() {
            if (scannedParts >= MAX_SCANNED_PARTS) {
                incomplete = true;
                return false;
            }
            scannedParts++;
            return true;
        }

        private boolean scanExhausted() {
            return scannedParts >= MAX_SCANNED_PARTS;
        }

        private boolean tryAcquire(String type) {
            if ("reasoning".equals(type) || "reasoning_hash".equals(type)) {
                if (reasoningParts >= MAX_REASONING_PARTS) {
                    return false;
                }
                reasoningParts++;
                return true;
            }
            if ("tool_call".equals(type) || "tool_call_response".equals(type)) {
                if (toolParts >= MAX_TOOL_PARTS) {
                    return false;
                }
                toolParts++;
                return true;
            }
            if (contentParts >= MAX_CONTENT_PARTS) {
                return false;
            }
            contentParts++;
            return true;
        }
    }

    public record PrecomputedHash(String sha256, long originalBytes) {
        public PrecomputedHash {
            sha256 = Objects.requireNonNull(sha256, "sha256");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
            }
            if (originalBytes < 0) {
                throw new IllegalArgumentException("originalBytes must be non-negative");
            }
        }

        Map<String, Object> asMap() {
            return Map.of("sha256", sha256, "original_bytes", originalBytes);
        }
    }

    public record CapturedMessages(
            Optional<JsonNode> value, Optional<String> observableHash) {
        public CapturedMessages {
            value = Objects.requireNonNull(value, "value");
            observableHash = Objects.requireNonNull(observableHash, "observableHash");
        }
    }
}
