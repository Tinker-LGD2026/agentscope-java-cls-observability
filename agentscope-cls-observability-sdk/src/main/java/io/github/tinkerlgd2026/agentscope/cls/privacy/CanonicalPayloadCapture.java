package io.github.tinkerlgd2026.agentscope.cls.privacy;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.RawValue;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final int MAX_RETAINED_BYTES = 1_572_864;
    private static final int HASH_HEX_BYTES = 64;
    private static final int HASH_JSON_BYTES = HASH_HEX_BYTES + 2;

    private final ObjectMapper objectMapper;
    private final CredentialRedactor redactor = new CredentialRedactor();

    public CanonicalPayloadCapture(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
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
        Map<String, Object> envelope = envelope(mode);
        if (mode == ContentCaptureMode.OFF) {
            envelope.put("complete", true);
            envelope.put("retained_bytes", 0L);
            envelope.put("truncated", false);
            return immutable(envelope);
        }

        CountingOutput rawOutput = new CountingOutput();
        CaptureRun raw = write(payload, false, rawOutput);
        if (!raw.complete()) {
            return incomplete(envelope, rawOutput.count(), null);
        }
        long originalBytes = rawOutput.count();

        if (mode == ContentCaptureMode.HASH) {
            if (budget.maxBytes() < HASH_JSON_BYTES) {
                return incomplete(envelope, originalBytes, null);
            }
            MessageDigest digest = digest();
            DigestOutput digestOutput = new DigestOutput(digest);
            CaptureRun redacted = write(payload, true, digestOutput);
            if (!redacted.complete()) {
                return incomplete(envelope, originalBytes, null);
            }
            envelope.put("complete", true);
            envelope.put("sha256", HexFormat.of().formatHex(digest.digest()));
            envelope.put("original_bytes", originalBytes);
            envelope.put("retained_bytes", (long) HASH_JSON_BYTES);
            envelope.put("truncated", false);
            return immutable(envelope);
        }

        int retainedLimit =
                Math.toIntExact(
                        Math.min(
                                budget.maxBytes(),
                                mode == ContentCaptureMode.TRUNCATE
                                        ? previewBytes
                                        : MAX_RETAINED_BYTES));
        if (retainedLimit < 2) {
            return incomplete(envelope, originalBytes, null);
        }
        PrefixOutput retained = new PrefixOutput(retainedLimit);
        CaptureRun redacted = write(payload, true, retained);
        if (!redacted.complete()) {
            return incomplete(envelope, originalBytes, retained);
        }

        envelope.put("complete", true);
        envelope.put("original_bytes", originalBytes);
        if (retained.count() <= retainedLimit) {
            String canonicalPayload = retained.utf8Value();
            if (canonicalPayload == null) {
                return incomplete(envelope, originalBytes, null);
            }
            envelope.put("payload", new RawValue(canonicalPayload));
            envelope.put("retained_bytes", retained.count());
            envelope.put("truncated", false);
            return immutable(envelope);
        }
        addPreview(envelope, retained, retainedLimit);
        envelope.put("truncated", true);
        return immutable(envelope);
    }

    private CaptureRun write(Object payload, boolean redact, OutputStream output) {
        Walker walker = new Walker(redact);
        try (JsonGenerator generator = objectMapper.getFactory().createGenerator(output)) {
            walker.write(generator, payload, 0, null);
            generator.flush();
            return new CaptureRun(true);
        } catch (IOException | RuntimeException failure) {
            return new CaptureRun(false);
        } catch (StackOverflowError failure) {
            return new CaptureRun(false);
        }
    }

    private Map<String, Object> incomplete(
            Map<String, Object> envelope, long originalBytesAtLeast, PrefixOutput safePrefix) {
        envelope.put("complete", false);
        envelope.put("original_bytes_at_least", Math.max(0L, originalBytesAtLeast));
        envelope.put("capture_error", true);
        envelope.put("truncated", true);
        if (safePrefix != null && safePrefix.size() > 0) {
            addPreview(envelope, safePrefix, safePrefix.limit());
        } else {
            envelope.put("retained_bytes", 0L);
        }
        envelope.remove("original_bytes");
        envelope.remove("sha256");
        return immutable(envelope);
    }

    private void addPreview(
            Map<String, Object> envelope, PrefixOutput output, int serializedBudget) {
        String preview = fitSerializedString(validUtf8Prefix(output.bytes()), serializedBudget);
        long retainedBytes = encodedStringBytes(preview);
        if (retainedBytes <= serializedBudget) {
            envelope.put("payload", preview);
            envelope.put("retained_bytes", retainedBytes);
        } else {
            envelope.put("retained_bytes", 0L);
        }
    }

    private static String fitSerializedString(String value, int serializedBudget) {
        if (serializedBudget < 2) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        int encodedBytes = 2;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int nextBytes = jsonStringBytes(codePoint);
            if (encodedBytes + nextBytes > serializedBudget) {
                break;
            }
            result.appendCodePoint(codePoint);
            encodedBytes += nextBytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static int jsonStringBytes(int codePoint) {
        if (codePoint == '"' || codePoint == '\\') {
            return 2;
        }
        if (codePoint >= 0 && codePoint <= 0x1f) {
            return switch (codePoint) {
                case '\b', '\t', '\n', '\f', '\r' -> 2;
                default -> 6;
            };
        }
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        if (codePoint <= 0xffff) {
            return 3;
        }
        return 4;
    }

    private int encodedStringBytes(String value) {
        try {
            return objectMapper.writeValueAsBytes(value).length;
        } catch (IOException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private static String validUtf8Prefix(byte[] value) {
        for (int length = value.length; length >= 0; length--) {
            try {
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(value, 0, length))
                        .toString();
            } catch (CharacterCodingException ignored) {
                // Remove one byte until the retained canonical prefix is valid UTF-8.
            }
        }
        return "";
    }

    private static Map<String, Object> envelope(ContentCaptureMode mode) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("mode", mode.name().toLowerCase(java.util.Locale.ROOT));
        return envelope;
    }

    private static Map<String, Object> immutable(Map<String, Object> value) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record CaptureBudget(long maxBytes) {
        public CaptureBudget {
            if (maxBytes <= 0 || maxBytes > MAX_RETAINED_BYTES) {
                throw new IllegalArgumentException(
                        "capture budget must be between 1 and " + MAX_RETAINED_BYTES);
            }
        }

        public static CaptureBudget fixed(long maxBytes) {
            return new CaptureBudget(maxBytes);
        }
    }

    private final class Walker {
        private final boolean redact;
        private final IdentityHashMap<Object, Boolean> ancestors = new IdentityHashMap<>();
        private int nodes;

        private Walker(boolean redact) {
            this.redact = redact;
        }

        private void write(JsonGenerator generator, Object value, int depth, String key)
                throws IOException {
            if (depth > MAX_DEPTH) {
                throw new TraversalFailure();
            }
            if (++nodes > MAX_NODES) {
                throw new TraversalFailure();
            }
            if (redact && key != null && redactor.sensitiveKey(key)) {
                generator.writeString(CredentialRedactor.REDACTED);
                return;
            }
            if (value == null) {
                generator.writeNull();
                return;
            }
            if (value instanceof CharSequence text) {
                generator.writeString(redact ? redactor.redactText(text.toString()) : text.toString());
                return;
            }
            if (value instanceof Boolean bool) {
                generator.writeBoolean(bool);
                return;
            }
            if (value instanceof Number number) {
                writeNumber(generator, number);
                return;
            }
            if (value instanceof Character || value instanceof Enum<?>) {
                generator.writeString(String.valueOf(value));
                return;
            }
            enter(value);
            try {
                if (value instanceof JsonNode node) {
                    writeNode(generator, node, depth);
                } else if (value instanceof Map<?, ?> map) {
                    writeMap(generator, map, depth);
                } else if (value instanceof Iterable<?> iterable) {
                    writeIterable(generator, iterable, depth);
                } else if (value.getClass().isArray()) {
                    writeArray(generator, value, depth);
                } else {
                    writeBean(generator, value, depth);
                }
            } finally {
                ancestors.remove(value);
            }
        }

        private void writeNode(JsonGenerator generator, JsonNode node, int depth)
                throws IOException {
            if (node.isNull()) {
                generator.writeNull();
            } else if (node.isTextual()) {
                String value = node.textValue();
                generator.writeString(redact ? redactor.redactText(value) : value);
            } else if (node.isBoolean()) {
                generator.writeBoolean(node.booleanValue());
            } else if (node.isNumber()) {
                writeNumber(generator, node.numberValue());
            } else if (node.isObject()) {
                TreeMap<String, JsonNode> fields = new TreeMap<>();
                java.util.Iterator<Map.Entry<String, JsonNode>> iterator =
                        node.properties().iterator();
                while (iterator.hasNext()) {
                    if (fields.size() >= MAX_COLLECTION_ITEMS) {
                        throw new TraversalFailure();
                    }
                    Map.Entry<String, JsonNode> entry = iterator.next();
                    fields.put(entry.getKey(), entry.getValue());
                }
                generator.writeStartObject();
                for (Map.Entry<String, JsonNode> field : fields.entrySet()) {
                    generator.writeFieldName(field.getKey());
                    write(generator, field.getValue(), depth + 1, field.getKey());
                }
                generator.writeEndObject();
            } else if (node.isArray()) {
                if (node.size() > MAX_COLLECTION_ITEMS) {
                    throw new TraversalFailure();
                }
                generator.writeStartArray();
                for (JsonNode item : node) {
                    write(generator, item, depth + 1, null);
                }
                generator.writeEndArray();
            } else {
                throw new TraversalFailure();
            }
        }

        private void writeMap(JsonGenerator generator, Map<?, ?> map, int depth)
                throws IOException {
            if (map.size() > MAX_COLLECTION_ITEMS) {
                throw new TraversalFailure();
            }
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            generator.writeStartObject();
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                generator.writeFieldName(entry.getKey());
                write(generator, entry.getValue(), depth + 1, entry.getKey());
            }
            generator.writeEndObject();
        }

        private void writeIterable(JsonGenerator generator, Iterable<?> iterable, int depth)
                throws IOException {
            generator.writeStartArray();
            int count = 0;
            for (Object item : iterable) {
                if (count++ >= MAX_COLLECTION_ITEMS) {
                    throw new TraversalFailure();
                }
                write(generator, item, depth + 1, null);
            }
            generator.writeEndArray();
        }

        private void writeArray(JsonGenerator generator, Object array, int depth)
                throws IOException {
            int length = java.lang.reflect.Array.getLength(array);
            if (length > MAX_COLLECTION_ITEMS) {
                throw new TraversalFailure();
            }
            generator.writeStartArray();
            for (int index = 0; index < length; index++) {
                write(generator, java.lang.reflect.Array.get(array, index), depth + 1, null);
            }
            generator.writeEndArray();
        }

        private void writeBean(JsonGenerator generator, Object bean, int depth)
                throws IOException {
            PropertyDescriptor[] descriptors;
            try {
                descriptors = Introspector.getBeanInfo(bean.getClass(), Object.class).getPropertyDescriptors();
            } catch (Exception exception) {
                throw new TraversalFailure();
            }
            List<PropertyDescriptor> properties =
                    java.util.Arrays.stream(descriptors)
                            .filter(property -> property.getReadMethod() != null)
                            .filter(property -> Modifier.isPublic(property.getReadMethod().getModifiers()))
                            .sorted(Comparator.comparing(PropertyDescriptor::getName))
                            .toList();
            if (properties.isEmpty() || properties.size() > MAX_COLLECTION_ITEMS) {
                throw new TraversalFailure();
            }
            generator.writeStartObject();
            for (PropertyDescriptor property : properties) {
                Object propertyValue;
                Method getter = property.getReadMethod();
                try {
                    propertyValue = getter.invoke(bean);
                } catch (IllegalAccessException | InvocationTargetException exception) {
                    throw new TraversalFailure();
                }
                generator.writeFieldName(property.getName());
                write(generator, propertyValue, depth + 1, property.getName());
            }
            generator.writeEndObject();
        }

        private void enter(Object value) {
            if (ancestors.put(value, Boolean.TRUE) != null) {
                throw new TraversalFailure();
            }
        }

        private void writeNumber(JsonGenerator generator, Number number) throws IOException {
            if ((number instanceof Double doubleValue && !Double.isFinite(doubleValue))
                    || (number instanceof Float floatValue && !Float.isFinite(floatValue))) {
                throw new TraversalFailure();
            }
            if (number instanceof Integer || number instanceof Short || number instanceof Byte) {
                generator.writeNumber(number.intValue());
            } else if (number instanceof Long) {
                generator.writeNumber(number.longValue());
            } else if (number instanceof java.math.BigInteger value) {
                generator.writeNumber(value);
            } else if (number instanceof java.math.BigDecimal value) {
                generator.writeNumber(value);
            } else if (number instanceof Float) {
                generator.writeNumber(number.floatValue());
            } else {
                generator.writeNumber(number.doubleValue());
            }
        }
    }

    private record CaptureRun(boolean complete) {}

    private static class CountingOutput extends OutputStream {
        private long count;

        @Override
        public void write(int value) {
            count = saturatingAdd(count, 1);
        }

        @Override
        public void write(byte[] value, int offset, int length) {
            count = saturatingAdd(count, length);
        }

        long count() {
            return count;
        }
    }

    private static final class DigestOutput extends CountingOutput {
        private final MessageDigest digest;

        private DigestOutput(MessageDigest digest) {
            this.digest = digest;
        }

        @Override
        public void write(int value) {
            digest.update((byte) value);
            super.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) {
            digest.update(value, offset, length);
            super.write(value, offset, length);
        }
    }

    private static final class PrefixOutput extends CountingOutput {
        private final int limit;
        private final BoundedByteArrayOutput retained;

        private PrefixOutput(int limit) {
            this.limit = limit;
            this.retained = new BoundedByteArrayOutput(Math.min(limit, 8_192));
        }

        @Override
        public void write(int value) {
            if (retained.size() < limit) {
                retained.write(value);
            }
            super.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) {
            int available = limit - retained.size();
            if (available > 0) {
                retained.write(value, offset, Math.min(available, length));
            }
            super.write(value, offset, length);
        }

        byte[] bytes() {
            return retained.toByteArray();
        }

        String utf8Value() {
            try {
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(retained.buffer(), 0, retained.size()))
                        .toString();
            } catch (CharacterCodingException exception) {
                return null;
            }
        }

        int size() {
            return retained.size();
        }

        int limit() {
            return limit;
        }
    }

    private static final class BoundedByteArrayOutput extends ByteArrayOutputStream {
        private BoundedByteArrayOutput(int initialSize) {
            super(initialSize);
        }

        private byte[] buffer() {
            return buf;
        }
    }

    private static long saturatingAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static final class TraversalFailure extends RuntimeException {}
}
