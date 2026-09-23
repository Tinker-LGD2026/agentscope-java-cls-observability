package io.github.tinkerlgd2026.agentscope.cls.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Applies the fixed final-message priority order within one exact UTF-8 byte budget. */
public final class CaptureBudgetPlanner {
    private final ObjectMapper objectMapper;
    private final int maxBytes;

    public CaptureBudgetPlanner(ObjectMapper objectMapper, int maxBytes) {
        if (objectMapper == null || maxBytes < 64) {
            throw new IllegalArgumentException("objectMapper and a byte budget of at least 64 are required");
        }
        this.objectMapper = objectMapper;
        this.maxBytes = maxBytes;
    }

    public Result capture(List<Map<String, Object>> messages) {
        List<Map<String, Object>> source = messages == null ? List.of() : messages;
        long originalBytes = encodedBytes(source);
        Optional<JsonNode> complete = encodedWithinBudget(source);
        if (complete.isPresent() && !hasTruncatedToolPayload(source)) {
            return result(complete, originalBytes, countParts(source), false);
        }

        List<Map<String, Object>> degradedProvider = degradeProviderOnly(source);
        if (containsOnlyProvider(source)) {
            Optional<JsonNode> provider = encodedWithinBudget(degradedProvider);
            if (provider.isPresent()) {
                return result(provider, originalBytes, countParts(source), true);
            }
        }

        List<Map<String, Object>> priority = prioritySkeleton(source);
        Optional<JsonNode> value = fitPriority(priority);
        long retained = value.map(this::encodedBytes).orElse(0L);
        long droppedParts = Math.max(0, countParts(source) - countParts(value));
        long droppedBytes = Math.max(0L, originalBytes - retained);
        return new Result(value, originalBytes, retained, droppedParts, droppedBytes, true);
    }

    private Optional<JsonNode> fitPriority(List<Map<String, Object>> priority) {
        List<Map<String, Object>> candidate = minimalize(priority, 64, 32);
        Optional<JsonNode> value = encodedWithinBudget(candidate);
        while (value.isEmpty() && countTools(candidate) > 1) {
            candidate = removeOldestTool(candidate);
            value = encodedWithinBudget(candidate);
        }
        int textBytes = 48;
        while (value.isEmpty() && textBytes > 0) {
            textBytes /= 2;
            candidate = minimalize(candidate, textBytes, 24);
            value = encodedWithinBudget(candidate);
        }
        return value;
    }

    private List<Map<String, Object>> minimalize(
            List<Map<String, Object>> messages, int textBytes, int identityBytes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            List<Map<String, Object>> retained = new ArrayList<>();
            for (Map<String, Object> part : parts(message.get("parts"))) {
                String type = String.valueOf(part.get("type"));
                Map<String, Object> copy = new LinkedHashMap<>(part);
                if ("text".equals(type) && copy.get("content") != null) {
                    copy.put("content", utf8Prefix(String.valueOf(copy.get("content")), textBytes));
                    copy.put("truncated", true);
                } else if (type.startsWith("tool_")) {
                    for (String key : List.of("id", "name")) {
                        if (copy.get(key) != null) {
                            copy.put(key, utf8Prefix(String.valueOf(copy.get(key)), identityBytes));
                        }
                    }
                    copy.keySet().removeIf(
                            key -> !List.of("type", "id", "name", "state").contains(key));
                }
                retained.add(Map.copyOf(copy));
            }
            if (!retained.isEmpty()) {
                Map<String, Object> copy = new LinkedHashMap<>();
                copy.put("role", message.getOrDefault("role", "unknown"));
                if (message.get("name") != null) {
                    copy.put("name", utf8Prefix(String.valueOf(message.get("name")), identityBytes));
                }
                copy.put("parts", List.copyOf(retained));
                result.add(Map.copyOf(copy));
            }
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> removeOldestTool(List<Map<String, Object>> messages) {
        boolean removed = false;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            List<Map<String, Object>> retained = new ArrayList<>();
            for (Map<String, Object> part : parts(message.get("parts"))) {
                String type = String.valueOf(part.get("type"));
                if (!removed && type.startsWith("tool_")) {
                    removed = true;
                    continue;
                }
                retained.add(part);
            }
            if (!retained.isEmpty()) {
                Map<String, Object> copy = new LinkedHashMap<>(message);
                copy.put("parts", List.copyOf(retained));
                result.add(Map.copyOf(copy));
            }
        }
        return List.copyOf(result);
    }

    private static long countTools(List<Map<String, Object>> messages) {
        return messages.stream()
                .flatMap(message -> parts(message.get("parts")).stream())
                .filter(part -> String.valueOf(part.get("type")).startsWith("tool_"))
                .count();
    }

    private static String utf8Prefix(String value, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > maxBytes) {
                break;
            }
            result.appendCodePoint(codePoint);
            used += bytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private Result result(
            Optional<JsonNode> value, long originalBytes, long sourceParts, boolean truncated) {
        long retained = value.map(this::encodedBytes).orElse(0L);
        long retainedParts = countParts(value);
        return new Result(
                value,
                originalBytes,
                retained,
                Math.max(0, sourceParts - retainedParts),
                Math.max(0, originalBytes - retained),
                truncated);
    }

    private List<Map<String, Object>> prioritySkeleton(List<Map<String, Object>> messages) {
        PartRef finalText = null;
        PartRef recentUserText = null;
        List<PartRef> tools = new ArrayList<>();
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            Map<String, Object> message = messages.get(messageIndex);
            String role = String.valueOf(message.getOrDefault("role", "unknown"));
            List<Map<String, Object>> parts = parts(message.get("parts"));
            for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
                String type = String.valueOf(parts.get(partIndex).get("type"));
                PartRef ref = new PartRef(messageIndex, partIndex);
                if ("text".equals(type) || "text_hash".equals(type)) {
                    finalText = ref;
                    if ("user".equals(role)) {
                        recentUserText = ref;
                    }
                } else if ("tool_call".equals(type) || "tool_call_response".equals(type)) {
                    tools.add(ref);
                }
            }
        }
        Set<PartRef> selected = new HashSet<>();
        if (finalText != null) {
            selected.add(finalText);
        }
        if (recentUserText != null) {
            selected.add(recentUserText);
        }
        selected.addAll(tools);

        List<Map<String, Object>> result = new ArrayList<>();
        for (int messageIndex = 0; messageIndex < messages.size(); messageIndex++) {
            Map<String, Object> message = messages.get(messageIndex);
            List<Map<String, Object>> selectedParts = new ArrayList<>();
            List<Map<String, Object>> sourceParts = parts(message.get("parts"));
            for (int partIndex = 0; partIndex < sourceParts.size(); partIndex++) {
                PartRef ref = new PartRef(messageIndex, partIndex);
                if (!selected.contains(ref)) {
                    continue;
                }
                Map<String, Object> part = sourceParts.get(partIndex);
                String type = String.valueOf(part.get("type"));
                selectedParts.add(type.startsWith("tool_") ? toolIdentity(part) : part);
            }
            if (!selectedParts.isEmpty()) {
                Map<String, Object> copy = new LinkedHashMap<>();
                copy.put("role", message.getOrDefault("role", "unknown"));
                if (message.get("name") != null) {
                    copy.put("name", message.get("name"));
                }
                copy.put("parts", List.copyOf(selectedParts));
                result.add(Map.copyOf(copy));
            }
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> toolIdentity(Map<String, Object> part) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("type", part.getOrDefault("type", "unknown"));
        for (String key : List.of("id", "name", "state")) {
            if (part.get(key) != null) {
                identity.put(key, part.get(key));
            }
        }
        return Map.copyOf(identity);
    }

    private List<Map<String, Object>> degradeProviderOnly(List<Map<String, Object>> messages) {
        List<Map<String, Object>> truncated = transformProvider(messages, "truncate");
        if (encodedWithinBudget(truncated).isPresent()) {
            return truncated;
        }
        List<Map<String, Object>> hashed = transformProvider(messages, "hash");
        if (encodedWithinBudget(hashed).isPresent()) {
            return hashed;
        }
        return transformProvider(messages, "off");
    }

    private List<Map<String, Object>> transformProvider(
            List<Map<String, Object>> messages, String targetMode) {
        List<Map<String, Object>> transformed = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            List<Map<String, Object>> retained = new ArrayList<>();
            for (Map<String, Object> part : parts(message.get("parts"))) {
                if (!"provider_payload".equals(part.get("type"))) {
                    retained.add(part);
                    continue;
                }
                if ("off".equals(targetMode)) {
                    continue;
                }
                Object sourceEnvelope = part.getOrDefault("provider_payload", part.get("payload"));
                Map<String, Object> envelope = providerEnvelope(sourceEnvelope, targetMode);
                retained.add(Map.of("type", "provider_payload", "provider_payload", envelope));
            }
            if (!retained.isEmpty()) {
                transformed.add(
                        Map.of(
                                "role", message.getOrDefault("role", "unknown"),
                                "parts", List.copyOf(retained)));
            }
        }
        return List.copyOf(transformed);
    }

    private Map<String, Object> providerEnvelope(Object source, String mode) {
        Object payload = source instanceof Map<?, ?> map ? map.get("payload") : source;
        long originalBytes = source instanceof Map<?, ?> map && map.get("original_bytes") instanceof Number number
                ? number.longValue()
                : encodedBytes(payload);
        if ("hash".equals(mode)) {
            return Map.of(
                    "mode", "hash",
                    "complete", true,
                    "sha256", sha256(payload),
                    "original_bytes", originalBytes);
        }
        String preview = String.valueOf(payload);
        if (preview.length() > 48) {
            preview = preview.substring(0, 48);
        }
        return Map.of(
                "mode", "truncate",
                "complete", true,
                "payload", preview,
                "original_bytes", originalBytes,
                "retained_bytes", (long) encodedBytes(preview),
                "truncated", true);
    }

    private String sha256(Object value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(objectMapper.writeValueAsBytes(value)));
        } catch (Exception exception) {
            throw new IllegalArgumentException("provider payload cannot be hashed", exception);
        }
    }

    private static boolean hasTruncatedToolPayload(List<Map<String, Object>> messages) {
        return messages.stream()
                .flatMap(message -> parts(message.get("parts")).stream())
                .anyMatch(
                        part ->
                                String.valueOf(part.get("type")).startsWith("tool_")
                                        && Boolean.TRUE.equals(part.get("truncated")));
    }

    private boolean containsOnlyProvider(List<Map<String, Object>> messages) {
        long parts = countParts(messages);
        if (parts == 0) {
            return false;
        }
        long provider = messages.stream()
                .flatMap(message -> parts(message.get("parts")).stream())
                .filter(part -> "provider_payload".equals(part.get("type")))
                .count();
        return provider == parts;
    }

    private Optional<JsonNode> encodedWithinBudget(List<Map<String, Object>> messages) {
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(messages);
            return encoded.length <= maxBytes
                    ? Optional.of(objectMapper.readTree(encoded))
                    : Optional.empty();
        } catch (Exception exception) {
            return Optional.empty();
        }
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

    private static long countParts(Optional<JsonNode> value) {
        if (value.isEmpty() || !value.orElseThrow().isArray()) {
            return 0;
        }
        long count = 0;
        for (JsonNode message : value.orElseThrow()) {
            JsonNode parts = message.get("parts");
            if (parts != null && parts.isArray()) {
                count += parts.size();
            }
        }
        return count;
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

    private record PartRef(int messageIndex, int partIndex) {}

    public record Result(
            Optional<JsonNode> value,
            long originalBytes,
            long retainedBytes,
            long droppedParts,
            long droppedBytes,
            boolean truncated) {}
}
