package io.github.tinkerlgd2026.agentscope.cls.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Two-pass bounded canonical capture for opaque provider payloads. */
public final class CanonicalPayloadCapture {
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 1_024;
    private static final int MAX_COLLECTION_ITEMS = 256;

    private final ObjectMapper objectMapper;
    private final ObjectWriter canonicalWriter;
    private final CredentialRedactor redactor = new CredentialRedactor();

    public CanonicalPayloadCapture(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.canonicalWriter =
                objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public Map<String, Object> capture(
            Object payload,
            ContentCaptureMode mode,
            int previewBytes,
            CaptureBudget budget) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(budget, "budget");
        if (previewBytes <= 0) {
            throw new IllegalArgumentException("previewBytes must be positive");
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
        if (mode == ContentCaptureMode.OFF || payload == null) {
            envelope.put("complete", true);
            envelope.put("retained_bytes", 0L);
            envelope.put("truncated", false);
            return Map.copyOf(envelope);
        }

        Traversal traversal = new Traversal();
        try {
            Object bounded = traversal.copy(payload, 0);
            Object redacted = redactor.redact(bounded);
            byte[] canonical = canonicalWriter.writeValueAsBytes(redacted);
            envelope.put("complete", true);
            envelope.put("original_bytes", (long) canonical.length);
            if (mode == ContentCaptureMode.HASH) {
                String digest = sha256(canonical);
                envelope.put("sha256", digest);
                envelope.put("retained_bytes", (long) digest.length());
                envelope.put("truncated", false);
                return Map.copyOf(envelope);
            }

            long allowed = Math.min(budget.maxBytes(), previewBytes);
            if (mode == ContentCaptureMode.FULL && canonical.length <= budget.maxBytes()) {
                envelope.put("value", redacted);
                envelope.put("retained_bytes", (long) canonical.length);
                envelope.put("truncated", false);
            } else if (mode == ContentCaptureMode.TRUNCATE && canonical.length <= allowed) {
                envelope.put("value", redacted);
                envelope.put("retained_bytes", (long) canonical.length);
                envelope.put("truncated", false);
            } else {
                String preview = utf8Prefix(canonical, Math.toIntExact(allowed));
                envelope.put("value", preview);
                envelope.put("retained_bytes", (long) preview.getBytes(StandardCharsets.UTF_8).length);
                envelope.put("truncated", true);
            }
            return Map.copyOf(envelope);
        } catch (TraversalFailure failure) {
            return incomplete(envelope, traversal.observedBytes(), failure.code());
        } catch (StackOverflowError error) {
            return incomplete(envelope, traversal.observedBytes(), "cycle");
        } catch (Exception exception) {
            return incomplete(envelope, traversal.observedBytes(), "serialization");
        }
    }

    private static Map<String, Object> incomplete(
            Map<String, Object> envelope, long observedBytes, String error) {
        envelope.put("complete", false);
        envelope.put("original_bytes_at_least", Math.max(0L, observedBytes));
        envelope.put("retained_bytes", 0L);
        envelope.put("truncated", true);
        envelope.put("capture_error", error);
        envelope.remove("sha256");
        envelope.remove("value");
        return Map.copyOf(envelope);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String utf8Prefix(byte[] value, int maxBytes) {
        int length = Math.min(value.length, Math.max(0, maxBytes));
        while (length > 0 && (value[length - 1] & 0xc0) == 0x80) {
            length--;
        }
        if (length < value.length && length > 0) {
            int lead = value[length - 1] & 0xff;
            int expected = lead < 0x80 ? 1 : lead < 0xe0 ? 2 : lead < 0xf0 ? 3 : 4;
            if (length - 1 + expected > maxBytes) {
                length--;
            }
        }
        return new String(value, 0, length, StandardCharsets.UTF_8);
    }

    public record CaptureBudget(long maxBytes) {
        public CaptureBudget {
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("capture budget must be positive");
            }
        }

        public static CaptureBudget fixed(long maxBytes) {
            return new CaptureBudget(maxBytes);
        }
    }

    private final class Traversal {
        private final IdentityHashMap<Object, Boolean> ancestors = new IdentityHashMap<>();
        private int nodes;
        private long observedBytes;

        private Object copy(Object value, int depth) {
            if (depth > MAX_DEPTH) {
                throw new TraversalFailure("depth");
            }
            if (++nodes > MAX_NODES) {
                throw new TraversalFailure("nodes");
            }
            if (value == null || value instanceof Boolean || value instanceof String) {
                observe(value);
                return value;
            }
            if (value instanceof Number number) {
                if ((number instanceof Double d && !Double.isFinite(d))
                        || (number instanceof Float f && !Float.isFinite(f))) {
                    throw new TraversalFailure("non_finite_number");
                }
                observe(value);
                return value;
            }
            if (value instanceof Character || value instanceof Enum<?>) {
                String text = String.valueOf(value);
                observe(text);
                return text;
            }
            if (value instanceof JsonNode node) {
                try {
                    return copy(objectMapper.convertValue(node, Object.class), depth);
                } catch (IllegalArgumentException exception) {
                    throw new TraversalFailure("serialization");
                }
            }
            enter(value);
            try {
                if (value instanceof Map<?, ?> map) {
                    if (map.size() > MAX_COLLECTION_ITEMS) {
                        throw new TraversalFailure("collection");
                    }
                    Map<String, Object> result = new TreeMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        result.put(String.valueOf(entry.getKey()), copy(entry.getValue(), depth + 1));
                    }
                    return new LinkedHashMap<>(result);
                }
                if (value instanceof Iterable<?> iterable) {
                    List<Object> result = new ArrayList<>();
                    for (Object item : iterable) {
                        if (result.size() >= MAX_COLLECTION_ITEMS) {
                            throw new TraversalFailure("collection");
                        }
                        result.add(copy(item, depth + 1));
                    }
                    return List.copyOf(result);
                }
                if (value.getClass().isArray()) {
                    int length = java.lang.reflect.Array.getLength(value);
                    if (length > MAX_COLLECTION_ITEMS) {
                        throw new TraversalFailure("collection");
                    }
                    List<Object> result = new ArrayList<>(length);
                    for (int index = 0; index < length; index++) {
                        result.add(copy(java.lang.reflect.Array.get(value, index), depth + 1));
                    }
                    return List.copyOf(result);
                }
                JsonNode tree = objectMapper.valueToTree(value);
                return copy(tree, depth + 1);
            } catch (IllegalArgumentException exception) {
                throw new TraversalFailure("getter");
            } finally {
                ancestors.remove(value);
            }
        }

        private void enter(Object value) {
            if (ancestors.put(value, Boolean.TRUE) != null) {
                throw new TraversalFailure("cycle");
            }
        }

        private void observe(Object value) {
            observedBytes = saturatingAdd(observedBytes, String.valueOf(value).getBytes(StandardCharsets.UTF_8).length);
        }

        private long observedBytes() {
            return observedBytes;
        }
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static final class TraversalFailure extends RuntimeException {
        private final String code;

        private TraversalFailure(String code) {
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
