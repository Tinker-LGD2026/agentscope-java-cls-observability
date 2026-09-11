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
        List<Map<String, Object>> source = messages == null ? List.of() : messages;
        try {
            List<Map<String, Object>> hashBasis = hashBasis(source);
            Optional<String> observableHash =
                    includeObservableHash
                            ? Optional.of(finalBudgetSanitizer.hash(hashBasis))
                            : Optional.empty();
            List<Map<String, Object>> captured = captureMessages(source);
            Optional<JsonNode> value =
                    captured.isEmpty()
                            ? Optional.empty()
                            : finalBudgetSanitizer.captureMessages(captured);
            return new CapturedMessages(value, observableHash);
        } catch (RuntimeException | StackOverflowError failure) {
            return new CapturedMessages(Optional.empty(), Optional.empty());
        }
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
        if (contentMode == ContentCaptureMode.HASH && isHashEnvelope(arguments)) {
            result.put("arguments", copyHashEnvelope((Map<?, ?>) arguments));
        } else {
            contentSanitizer
                    .capture(arguments)
                    .ifPresent(value -> result.put("arguments", value));
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

    private static boolean isHashEnvelope(@Nullable Object value) {
        return value instanceof Map<?, ?> map
                && map.get("sha256") instanceof String
                && map.get("original_bytes") instanceof Number;
    }

    private static Map<String, Object> copyHashEnvelope(Map<?, ?> source) {
        return Map.of(
                "sha256", nonNullValue(source.get("sha256"), ""),
                "original_bytes", nonNullValue(source.get("original_bytes"), 0L));
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

    public record CapturedMessages(
            Optional<JsonNode> value, Optional<String> observableHash) {
        public CapturedMessages {
            value = Objects.requireNonNull(value, "value");
            observableHash = Objects.requireNonNull(observableHash, "observableHash");
        }
    }
}
