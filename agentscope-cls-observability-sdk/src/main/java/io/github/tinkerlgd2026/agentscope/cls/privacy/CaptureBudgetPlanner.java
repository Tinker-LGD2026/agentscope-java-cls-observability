package io.github.tinkerlgd2026.agentscope.cls.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Applies the fixed final-message priority order within one exact UTF-8 byte budget. */
public final class CaptureBudgetPlanner {
    private final ObjectMapper objectMapper;
    private final int maxBytes;
    private final int previewBytes;

    public CaptureBudgetPlanner(ObjectMapper objectMapper, int maxBytes) {
        this(objectMapper, maxBytes, Math.min(4_096, maxBytes));
    }

    public CaptureBudgetPlanner(ObjectMapper objectMapper, int maxBytes, int previewBytes) {
        if (objectMapper == null || maxBytes < 64 || previewBytes <= 0) {
            throw new IllegalArgumentException("objectMapper and positive byte budgets are required");
        }
        this.objectMapper = objectMapper;
        this.maxBytes = maxBytes;
        this.previewBytes = previewBytes;
    }

    public Result capture(List<Map<String, Object>> messages) {
        List<Map<String, Object>> source = messages == null ? List.of() : List.copyOf(messages);
        if (source.isEmpty()) {
            return new Result(Optional.empty(), 0, 0, 0, 0, false);
        }
        long originalBytes = encodedBytes(source);
        Optional<JsonNode> complete = encodedWithinBudget(source);
        if (complete.isPresent() && !hasTruncatedToolPayload(source)) {
            return result(complete, originalBytes, countParts(source), false);
        }

        Selection selection = new Selection(source);
        PartRef finalText = latest(selection.refsOf("text", "text_hash"));
        PartRef recentUser = latest(selection.refsByRole("user", "text", "text_hash"));
        List<PartRef> tools = newestFirst(selection.refsOf("tool_call", "tool_call_response"));
        List<PartRef> reasoning = newestFirst(selection.refsOf("reasoning", "reasoning_hash"));
        List<PartRef> provider = newestFirst(selection.refsOf("provider_payload"));

        if (finalText != null) {
            selection.putMaximal(finalText, sourcePart(source, finalText));
        }
        for (PartRef tool : tools) {
            selection.putIdentity(tool, sourcePart(source, tool));
        }
        if (recentUser != null && !recentUser.equals(finalText)) {
            selection.putMaximal(recentUser, sourcePart(source, recentUser));
        }
        for (PartRef tool : tools) {
            Map<String, Object> payload = sourcePart(source, tool);
            if (selection.contains(tool) && !Boolean.TRUE.equals(payload.get("truncated"))) {
                selection.tryPut(tool, payload);
            }
        }
        for (PartRef ref : reasoning) {
            for (Map<String, Object> candidate : reasoningCandidates(sourcePart(source, ref))) {
                if (selection.tryPut(ref, candidate)) {
                    break;
                }
            }
        }
        for (PartRef ref : provider) {
            for (Map<String, Object> candidate : providerCandidates(sourcePart(source, ref))) {
                if (selection.tryPut(ref, candidate)) {
                    break;
                }
            }
        }

        Set<PartRef> reserved = new LinkedHashSet<>();
        if (finalText != null) reserved.add(finalText);
        if (recentUser != null) reserved.add(recentUser);
        reserved.addAll(tools);
        reserved.addAll(reasoning);
        reserved.addAll(provider);
        List<PartRef> early = new ArrayList<>(selection.allRefs());
        early.removeAll(reserved);
        for (PartRef ref : newestFirst(early)) {
            selection.tryPut(ref, sourcePart(source, ref));
        }

        Optional<JsonNode> value = selection.value();
        long retained = value.map(this::encodedBytes).orElse(0L);
        return new Result(
                value,
                originalBytes,
                retained,
                Math.max(0L, countParts(source) - countParts(value)),
                Math.max(0L, originalBytes - retained),
                retained < originalBytes);
    }

    private List<Map<String, Object>> reasoningCandidates(Map<String, Object> part) {
        if ("reasoning_hash".equals(part.get("type"))) {
            return List.of(part);
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        candidates.add(part);
        Object content = part.get("content");
        if (content != null) {
            Map<String, Object> truncated = new LinkedHashMap<>(part);
            String text = String.valueOf(content);
            truncated.put("content", utf8Prefix(text, previewBytes));
            truncated.put("truncated", true);
            candidates.add(Map.copyOf(truncated));
            candidates.add(
                    Map.of(
                            "type", "reasoning_hash",
                            "sha256", sha256(content),
                            "original_bytes", encodedBytes(content),
                            "truncated", true));
        }
        return List.copyOf(candidates);
    }

    private List<Map<String, Object>> providerCandidates(Map<String, Object> part) {
        Object raw = part.getOrDefault("provider_payload", part.get("payload"));
        if (!(raw instanceof Map<?, ?> envelope)) {
            return List.of(part);
        }
        String mode = String.valueOf(envelope.containsKey("mode") ? envelope.get("mode") : "off");
        boolean complete = !Boolean.FALSE.equals(envelope.get("complete"));
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (!"off".equals(mode)) {
            candidates.add(part);
        }
        if ("full".equals(mode) && envelope.get("payload") != null) {
            candidates.add(providerPart(truncatedEnvelope(envelope, complete)));
        }
        if (("full".equals(mode) || "truncate".equals(mode)) && complete) {
            Map<String, Object> hash = hashEnvelope(envelope);
            if (hash != null) {
                candidates.add(providerPart(hash));
            }
        }
        return List.copyOf(candidates);
    }

    private Map<String, Object> truncatedEnvelope(Map<?, ?> source, boolean complete) {
        Object payload = source.get("payload");
        String preview = utf8Prefix(String.valueOf(payload), previewBytes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "truncate");
        result.put("complete", complete);
        result.put("payload", preview);
        copyOriginalBytes(source, result);
        result.put("retained_bytes", encodedBytes(preview));
        result.put("truncated", true);
        if (!complete) {
            result.put("capture_error", true);
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> hashEnvelope(Map<?, ?> source) {
        Object payload = source.get("payload");
        if (payload == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "hash");
        result.put("complete", true);
        result.put("sha256", sha256(payload));
        Object original = source.get("original_bytes");
        result.put(
                "original_bytes",
                original instanceof Number ? ((Number) original).longValue() : encodedBytes(payload));
        return Map.copyOf(result);
    }

    private static void copyOriginalBytes(Map<?, ?> source, Map<String, Object> target) {
        if (source.get("original_bytes") instanceof Number value) {
            target.put("original_bytes", value.longValue());
        } else if (source.get("original_bytes_at_least") instanceof Number value) {
            target.put("original_bytes_at_least", value.longValue());
        }
    }

    private static Map<String, Object> providerPart(Map<String, Object> envelope) {
        return Map.of("type", "provider_payload", "provider_payload", envelope);
    }

    private Map<String, Object> fitIdentity(Map<String, Object> part, int maxIdentityBytes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", part.getOrDefault("type", "unknown"));
        for (String key : List.of("id", "name", "state")) {
            if (part.get(key) != null) {
                result.put(
                        key,
                        "state".equals(key)
                                ? part.get(key)
                                : utf8Prefix(String.valueOf(part.get(key)), maxIdentityBytes));
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> fitTextPart(Map<String, Object> part, Selection selection, PartRef ref) {
        Object content = part.get("content");
        String text;
        if (content instanceof CharSequence sequence) {
            text = sequence.toString();
        } else if (content instanceof JsonNode node && node.isTextual()) {
            text = node.asText();
        } else if (content instanceof JsonNode node && node.path("preview").isTextual()) {
            text = node.path("preview").asText();
        } else if (content instanceof Map<?, ?> map
                && map.get("preview") instanceof CharSequence preview) {
            text = preview.toString();
        } else {
            return selection.fits(ref, part) ? part : minimalTextPart(part, "");
        }
        int low = 0;
        int high = text.codePointCount(0, text.length());
        Map<String, Object> best = minimalTextPart(part, "");
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int end = text.offsetByCodePoints(0, middle);
            Map<String, Object> candidate = minimalTextPart(part, text.substring(0, end));
            if (selection.fits(ref, candidate)) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private static Map<String, Object> minimalTextPart(Map<String, Object> part, String content) {
        Map<String, Object> result = new LinkedHashMap<>(part);
        result.put("content", content);
        if (!content.equals(String.valueOf(part.get("content")))) {
            result.put("truncated", true);
        }
        return Map.copyOf(result);
    }

    private String sha256(Object value) {
        try {
            ObjectMapper canonical = objectMapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.writeValueAsBytes(value)));
        } catch (Exception exception) {
            throw new IllegalArgumentException("payload cannot be hashed", exception);
        }
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

    private static List<PartRef> newestFirst(List<PartRef> refs) {
        List<PartRef> result = new ArrayList<>(refs);
        result.sort(Comparator.comparingInt(PartRef::messageIndex)
                .thenComparingInt(PartRef::partIndex)
                .reversed());
        return result;
    }

    private static PartRef latest(List<PartRef> refs) {
        return refs.isEmpty() ? null : newestFirst(refs).get(0);
    }

    private static Map<String, Object> sourcePart(
            List<Map<String, Object>> messages, PartRef ref) {
        return parts(messages.get(ref.messageIndex()).get("parts")).get(ref.partIndex());
    }

    private Result result(
            Optional<JsonNode> value, long originalBytes, long sourceParts, boolean truncated) {
        long retained = value.map(this::encodedBytes).orElse(0L);
        return new Result(
                value,
                originalBytes,
                retained,
                Math.max(0L, sourceParts - countParts(value)),
                Math.max(0L, originalBytes - retained),
                truncated);
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

    private static boolean hasTruncatedToolPayload(List<Map<String, Object>> messages) {
        return messages.stream()
                .flatMap(message -> parts(message.get("parts")).stream())
                .anyMatch(
                        part ->
                                String.valueOf(part.get("type")).startsWith("tool_")
                                        && Boolean.TRUE.equals(part.get("truncated")));
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

    private final class Selection {
        private final List<Map<String, Object>> source;
        private final Map<PartRef, Map<String, Object>> selected = new LinkedHashMap<>();

        private Selection(List<Map<String, Object>> source) {
            this.source = source;
        }

        private List<PartRef> allRefs() {
            List<PartRef> result = new ArrayList<>();
            for (int messageIndex = 0; messageIndex < source.size(); messageIndex++) {
                List<Map<String, Object>> values = parts(source.get(messageIndex).get("parts"));
                for (int partIndex = 0; partIndex < values.size(); partIndex++) {
                    result.add(new PartRef(messageIndex, partIndex));
                }
            }
            return result;
        }

        private List<PartRef> refsOf(String... types) {
            Set<String> accepted = Set.of(types);
            return allRefs().stream()
                    .filter(ref -> accepted.contains(String.valueOf(sourcePart(source, ref).get("type"))))
                    .toList();
        }

        private List<PartRef> refsByRole(String role, String... types) {
            Set<String> accepted = Set.of(types);
            return allRefs().stream()
                    .filter(ref -> role.equals(String.valueOf(source.get(ref.messageIndex()).get("role"))))
                    .filter(ref -> accepted.contains(String.valueOf(sourcePart(source, ref).get("type"))))
                    .toList();
        }

        private boolean contains(PartRef ref) {
            return selected.containsKey(ref);
        }

        private void putMaximal(PartRef ref, Map<String, Object> part) {
            if (tryPut(ref, part)) {
                return;
            }
            Map<String, Object> fitted = fitTextPart(part, this, ref);
            tryPut(ref, fitted);
        }

        private void putIdentity(PartRef ref, Map<String, Object> part) {
            for (int bytes : List.of(128, 96, 64, 48, 32, 24, 16, 8, 0)) {
                if (tryPut(ref, fitIdentity(part, bytes))) {
                    return;
                }
            }
        }

        private boolean tryPut(PartRef ref, Map<String, Object> value) {
            Map<String, Object> previous = selected.put(ref, value);
            if (encodedWithinBudget(render()).isPresent()) {
                return true;
            }
            if (previous == null) {
                selected.remove(ref);
            } else {
                selected.put(ref, previous);
            }
            return false;
        }

        private boolean fits(PartRef ref, Map<String, Object> value) {
            Map<String, Object> previous = selected.put(ref, value);
            boolean fits = encodedWithinBudget(render()).isPresent();
            if (previous == null) {
                selected.remove(ref);
            } else {
                selected.put(ref, previous);
            }
            return fits;
        }

        private Optional<JsonNode> value() {
            return encodedWithinBudget(render());
        }

        private List<Map<String, Object>> render() {
            List<Map<String, Object>> result = new ArrayList<>();
            for (int messageIndex = 0; messageIndex < source.size(); messageIndex++) {
                List<Map<String, Object>> retained = new ArrayList<>();
                List<Map<String, Object>> sourceValues = parts(source.get(messageIndex).get("parts"));
                for (int partIndex = 0; partIndex < sourceValues.size(); partIndex++) {
                    Map<String, Object> value = selected.get(new PartRef(messageIndex, partIndex));
                    if (value != null) {
                        retained.add(value);
                    }
                }
                if (!retained.isEmpty()) {
                    Map<String, Object> message = source.get(messageIndex);
                    Map<String, Object> copy = new LinkedHashMap<>();
                    copy.put("role", message.getOrDefault("role", "unknown"));
                    if (message.get("name") != null) {
                        copy.put("name", message.get("name"));
                    }
                    copy.put("parts", List.copyOf(retained));
                    result.add(Map.copyOf(copy));
                }
            }
            return List.copyOf(result);
        }
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
