package io.github.tinkerlgd2026.agentscope.cls.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
    private static final int MINIMAL_TEXT_BYTES = 64;
    private static final int MINIMAL_IDENTITY_BYTES = 32;

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
        List<Map<String, Object>> source = messages == null ? List.of() : messages;
        try {
            BoundedMessages bounded = boundedMessages(source);
            List<Map<String, Object>> hashBasis = hashBasis(bounded.messages());
            Optional<String> observableHash =
                    includeObservableHash && sourceComplete && bounded.complete()
                            ? Optional.of(finalBudgetSanitizer.hash(hashBasis))
                            : Optional.empty();
            List<Map<String, Object>> captured = captureMessages(bounded.messages());
            Optional<JsonNode> value = captureWithPriority(captured);
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
        boolean textExpected = containsText(captured);
        boolean toolExpected = containsTool(captured);
        Optional<JsonNode> value = finalBudgetSanitizer.captureMessages(captured);
        if (retainsExpectedPriority(value, textExpected, toolExpected)) {
            return value;
        }

        List<Map<String, Object>> withoutReasoning = transformMessages(captured, true, false, false);
        value = finalBudgetSanitizer.captureMessages(withoutReasoning);
        if (retainsExpectedPriority(value, textExpected, toolExpected)) {
            return value;
        }

        List<Map<String, Object>> withoutToolPayloads =
                transformMessages(withoutReasoning, false, true, false);
        value = finalBudgetSanitizer.captureMessages(withoutToolPayloads);
        if (retainsExpectedPriority(value, textExpected, toolExpected)) {
            return value;
        }

        List<Map<String, Object>> latestPriority =
                latestPriorityMessages(withoutToolPayloads, textExpected, toolExpected);
        value = finalBudgetSanitizer.captureMessages(latestPriority);
        if (retainsExpectedPriority(value, textExpected, toolExpected)) {
            return value;
        }

        List<Map<String, Object>> minimalPriority = minimalPriorityMessages(latestPriority);
        value = finalBudgetSanitizer.captureMessages(minimalPriority);
        if (retainsExpectedPriority(value, textExpected, toolExpected)) {
            return value;
        }

        if (textExpected) {
            List<Map<String, Object>> latestText =
                    minimalPriorityMessages(
                            latestPriorityMessages(withoutToolPayloads, true, false));
            value = finalBudgetSanitizer.captureMessages(latestText);
            if (containsText(value)) {
                return value;
            }
        }
        List<Map<String, Object>> latestTool =
                minimalPriorityMessages(
                        latestPriorityMessages(withoutToolPayloads, false, toolExpected));
        value = finalBudgetSanitizer.captureMessages(latestTool);
        return containsTool(value) ? value : Optional.empty();
    }

    private static List<Map<String, Object>> minimalPriorityMessages(
            List<Map<String, Object>> messages) {
        List<Map<String, Object>> minimal = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            List<Map<String, Object>> parts = new ArrayList<>();
            for (Map<String, Object> part : asParts(message.get("parts"))) {
                String type = String.valueOf(part.get("type"));
                if ("text".equals(type)) {
                    String text = safePreview(part.get("content"));
                    parts.add(
                            Map.of(
                                    "type", "text",
                                    "content", utf8Prefix(text, MINIMAL_TEXT_BYTES),
                                    "truncated", true));
                } else if ("text_hash".equals(type)) {
                    parts.add(copyHashPart(part, "text_hash"));
                } else if ("tool_call".equals(type) || "tool_call_response".equals(type)) {
                    Map<String, Object> identity = new LinkedHashMap<>();
                    identity.put("type", type);
                    if (part.get("id") != null) {
                        identity.put(
                                "id",
                                utf8Prefix(
                                        String.valueOf(part.get("id")),
                                        MINIMAL_IDENTITY_BYTES));
                    }
                    if (part.get("name") != null) {
                        identity.put(
                                "name",
                                utf8Prefix(
                                        String.valueOf(part.get("name")),
                                        MINIMAL_IDENTITY_BYTES));
                    }
                    parts.add(Map.copyOf(identity));
                }
            }
            if (parts.isEmpty()) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put(
                    "role",
                    utf8Prefix(
                            String.valueOf(nonNullValue(message.get("role"), "unknown")),
                            MINIMAL_IDENTITY_BYTES));
            if (message.get("name") != null) {
                copy.put(
                        "name",
                        utf8Prefix(
                                String.valueOf(message.get("name")),
                                MINIMAL_IDENTITY_BYTES));
            }
            copy.put("parts", List.copyOf(parts));
            minimal.add(Map.copyOf(copy));
        }
        return List.copyOf(minimal);
    }

    private static String safePreview(@Nullable Object content) {
        if (content instanceof JsonNode node) {
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.path("preview").isTextual()) {
                return node.path("preview").asText();
            }
            return "[TRUNCATED]";
        }
        return content == null ? "" : String.valueOf(content);
    }

    private static String utf8Prefix(String value, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maxBytes) {
                break;
            }
            result.append(character);
            bytes += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static List<Map<String, Object>> latestPriorityMessages(
            List<Map<String, Object>> messages, boolean includeText, boolean includeTool) {
        PartLocation latestText = null;
        PartLocation latestTool = null;
        for (int messageIndex = messages.size() - 1; messageIndex >= 0; messageIndex--) {
            List<Map<String, Object>> parts = asParts(messages.get(messageIndex).get("parts"));
            for (int partIndex = parts.size() - 1; partIndex >= 0; partIndex--) {
                String type = String.valueOf(parts.get(partIndex).get("type"));
                if (includeText
                        && latestText == null
                        && ("text".equals(type) || "text_hash".equals(type))) {
                    latestText = new PartLocation(messageIndex, partIndex);
                }
                if (includeTool
                        && latestTool == null
                        && ("tool_call".equals(type) || "tool_call_response".equals(type))) {
                    latestTool = new PartLocation(messageIndex, partIndex);
                }
            }
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            List<Map<String, Object>> parts = asParts(messages.get(messageIndex).get("parts"));
            List<Map<String, Object>> selectedParts = new ArrayList<>(2);
            for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                PartLocation current = new PartLocation(messageIndex, partIndex);
                if (current.equals(latestText) || current.equals(latestTool)) {
                    selectedParts.add(parts.get(partIndex));
                }
            }
            if (selectedParts.isEmpty()) {
                continue;
            }
            Map<String, Object> message = messages.get(messageIndex);
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("role", nonNullValue(message.get("role"), "unknown"));
            if (message.get("name") != null) {
                copy.put("name", message.get("name"));
            }
            copy.put("parts", List.copyOf(selectedParts));
            selected.add(Map.copyOf(copy));
        }
        return List.copyOf(selected);
    }

    private List<Map<String, Object>> transformMessages(
            List<Map<String, Object>> messages,
            boolean removeReasoning,
            boolean stripToolPayloads,
            boolean removeTools) {
        List<Map<String, Object>> transformed = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            List<Map<String, Object>> parts =
                    transformParts(
                            asParts(message.get("parts")),
                            removeReasoning,
                            stripToolPayloads,
                            removeTools);
            if (parts.isEmpty()) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("role", nonNullValue(message.get("role"), "unknown"));
            if (message.get("name") != null) {
                copy.put("name", message.get("name"));
            }
            copy.put("parts", parts);
            transformed.add(Map.copyOf(copy));
        }
        return List.copyOf(transformed);
    }

    private List<Map<String, Object>> transformParts(
            List<Map<String, Object>> parts,
            boolean removeReasoning,
            boolean stripToolPayloads,
            boolean removeTools) {
        List<Map<String, Object>> transformed = new ArrayList<>();
        for (Map<String, Object> part : parts) {
            String type = String.valueOf(part.getOrDefault("type", "unknown"));
            if (removeReasoning
                    && ("reasoning".equals(type) || "reasoning_hash".equals(type))) {
                continue;
            }
            if (removeTools
                    && ("tool_call".equals(type) || "tool_call_response".equals(type))) {
                continue;
            }
            if (stripToolPayloads && "tool_call".equals(type)) {
                transformed.add(toolIdentity(part, true));
            } else if (stripToolPayloads && "tool_call_response".equals(type)) {
                transformed.add(toolIdentity(part, true));
            } else {
                transformed.add(part);
            }
        }
        return List.copyOf(transformed);
    }

    private static boolean containsText(List<Map<String, Object>> messages) {
        for (Map<String, Object> message : messages) {
            for (Map<String, Object> part : asParts(message.get("parts"))) {
                String type = String.valueOf(part.get("type"));
                if ("text".equals(type) || "text_hash".equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsText(Optional<JsonNode> messages) {
        return containsType(messages, "text", "text_hash");
    }

    private static boolean containsTool(List<Map<String, Object>> messages) {
        for (Map<String, Object> message : messages) {
            for (Map<String, Object> part : asParts(message.get("parts"))) {
                String type = String.valueOf(part.get("type"));
                if ("tool_call".equals(type) || "tool_call_response".equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsTool(Optional<JsonNode> messages) {
        return containsType(messages, "tool_call", "tool_call_response");
    }

    private static boolean retainsExpectedPriority(
            Optional<JsonNode> messages, boolean textExpected, boolean toolExpected) {
        return (!textExpected || containsText(messages))
                && (!toolExpected || containsTool(messages));
    }

    private static boolean containsType(
            Optional<JsonNode> messages, String firstType, String secondType) {
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
                if (firstType.equals(type) || secondType.equals(type)) {
                    return true;
                }
            }
        }
        return false;
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

    private record PartLocation(int messageIndex, int partIndex) {}

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
