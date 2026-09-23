package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tinkerlgd2026.agentscope.cls.privacy.CaptureBudgetPlanner;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.MessageCapturePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Unified bounded capture of semantic messages, reasoning, and provider payload envelopes. */
final class BoundedMessageCapture {
    private final ObjectMapper objectMapper;
    private final ContentCaptureMode contentMode;
    private final ContentCaptureMode reasoningMode;
    private final ContentCaptureMode providerMode;
    private final int finalBytes;
    private final MessageCapturePolicy semanticPolicy;
    private final CaptureBudgetPlanner planner;

    BoundedMessageCapture(
            ObjectMapper objectMapper,
            ContentCaptureMode contentMode,
            ContentCaptureMode reasoningMode,
            ContentCaptureMode providerMode,
            int truncatePreviewBytes,
            int finalBytes) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.contentMode = Objects.requireNonNull(contentMode, "contentMode");
        this.reasoningMode = Objects.requireNonNull(reasoningMode, "reasoningMode");
        this.providerMode = Objects.requireNonNull(providerMode, "providerMode");
        this.finalBytes = finalBytes;
        this.semanticPolicy =
                new MessageCapturePolicy(objectMapper, contentMode, reasoningMode, finalBytes);
        this.planner = new CaptureBudgetPlanner(objectMapper, finalBytes);
    }

    Result captureMessages(
            List<Map<String, Object>> messages,
            boolean sourceComplete,
            InvocationCaptureBudget invocationBudget) {
        Objects.requireNonNull(invocationBudget, "invocationBudget");
        List<Map<String, Object>> source = messages == null ? List.of() : messages;
        long originalBytes = encodedBytes(source);
        try {
            List<Map<String, Object>> semanticSource = removeProvider(source);
            MessageCapturePolicy.CapturedMessages semantic =
                    semanticPolicy.capture(semanticSource, true, sourceComplete);
            List<Map<String, Object>> combined =
                    semantic.value().map(this::asMessages).orElseGet(ArrayList::new);
            if (providerMode != ContentCaptureMode.OFF) {
                combined.addAll(providerMessages(source));
            }
            CaptureBudgetPlanner.Result planned = planner.capture(combined);
            long retained = planned.retainedBytes();
            if (retained == 0 || invocationBudget.reserve(retained).isEmpty()) {
                return new Result(
                        Optional.empty(),
                        originalBytes,
                        0,
                        Math.max(1, planned.droppedParts() + countParts(source)),
                        Math.max(1, originalBytes),
                        false,
                        ContentCaptureMode.OFF);
            }
            ContentCaptureMode effectiveProvider = effectiveProviderMode(planned.value());
            return new Result(
                    planned.value(),
                    originalBytes,
                    retained,
                    planned.droppedParts(),
                    planned.droppedBytes(),
                    sourceComplete && semantic.observableHash().isPresent(),
                    effectiveProvider);
        } catch (RuntimeException failure) {
            return new Result(
                    Optional.empty(),
                    originalBytes,
                    0,
                    Math.max(1, countParts(source)),
                    Math.max(1, originalBytes),
                    false,
                    ContentCaptureMode.OFF);
        }
    }

    private List<Map<String, Object>> removeProvider(List<Map<String, Object>> messages) {
        return transform(messages, false);
    }

    private List<Map<String, Object>> providerMessages(List<Map<String, Object>> messages) {
        return transform(messages, true);
    }

    private List<Map<String, Object>> transform(
            List<Map<String, Object>> messages, boolean providerOnly) {
        List<Map<String, Object>> transformed = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            List<Map<String, Object>> selected = new ArrayList<>();
            for (Map<String, Object> part : parts(message.get("parts"))) {
                boolean provider = "provider_payload".equals(part.get("type"));
                if (provider == providerOnly) {
                    selected.add(part);
                }
            }
            if (!selected.isEmpty()) {
                Map<String, Object> copy = new LinkedHashMap<>();
                copy.put("role", message.getOrDefault("role", "unknown"));
                if (message.get("name") != null) {
                    copy.put("name", message.get("name"));
                }
                copy.put("parts", List.copyOf(selected));
                transformed.add(Map.copyOf(copy));
            }
        }
        return transformed;
    }

    private List<Map<String, Object>> asMessages(JsonNode value) {
        return objectMapper.convertValue(value, new TypeReference<List<Map<String, Object>>>() {});
    }

    private ContentCaptureMode effectiveProviderMode(Optional<JsonNode> value) {
        if (value.isEmpty()) {
            return ContentCaptureMode.OFF;
        }
        String encoded = value.orElseThrow().toString();
        if (!encoded.contains("provider_payload")) {
            return ContentCaptureMode.OFF;
        }
        for (ContentCaptureMode candidate :
                List.of(ContentCaptureMode.FULL, ContentCaptureMode.TRUNCATE, ContentCaptureMode.HASH)) {
            if (encoded.contains("\"mode\":\"" + candidate.name().toLowerCase(java.util.Locale.ROOT) + "\"")) {
                return candidate;
            }
        }
        return providerMode;
    }

    private long encodedBytes(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (Exception exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long countParts(List<Map<String, Object>> messages) {
        return messages.stream().mapToLong(message -> parts(message.get("parts")).size()).sum();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parts(Object value) {
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

    record Result(
            Optional<JsonNode> value,
            long originalBytes,
            long retainedBytes,
            long capacityDroppedParts,
            long capacityDroppedBytes,
            boolean hashComplete,
            ContentCaptureMode providerMode) {}
}
