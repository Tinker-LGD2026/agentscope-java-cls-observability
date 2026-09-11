package io.github.tinkerlgd2026.agentscope.cls.privacy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Applies the privacy rules of the SDK to agent payloads before they become span attributes.
 *
 * <p>The sanitizer is deliberately defensive: it redacts by key name and by value shape, drops
 * URL credentials, bounds recursion depth, and keeps every captured payload inside the configured
 * byte budget so a single agent turn cannot produce an unbounded span attribute.
 */
public final class ContentSanitizer {
    private static final String REDACTED = "[REDACTED]";
    private static final String REDACTED_KEY = "[REDACTED_KEY]";
    private static final String REDACTED_SECRET = "[REDACTED_SECRET]";
    private static final String TRUNCATED_DEPTH = "[TRUNCATED_DEPTH]";
    private static final String PEM_BEGIN = "-----BEGIN ";
    private static final String PEM_END = "-----END ";
    private static final String PRIVATE_KEY_SUFFIX = "PRIVATE KEY-----";
    private static final int MAX_PEM_HEADER_LENGTH = 80;
    private static final int MAX_DEPTH = 32;
    private static final int MIN_SECRET_SCAN_LENGTH = 12;
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");
    private static final Pattern URL_TOKEN_PATTERN =
            Pattern.compile("(?i)https?://[^\\s<>\\\"']+");
    private static final String[] SENSITIVE_KEYS = {
        "secret",
        "secretid",
        "secretkey",
        "token",
        "password",
        "passwd",
        "authorization",
        "cookie",
        "credential",
        "apikey",
        "accesskey",
        "privatekey"
    };
    private static final Pattern SECRET_VALUE_PATTERN =
            Pattern.compile(
                    "AKID[0-9A-Za-z]{10,}"
                            + "|sk-[A-Za-z0-9_-]{16,}"
                            + "|(?i:bearer)\\s+[A-Za-z0-9._~+/=-]{16,}"
                            + "|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"
                            + "|(?i:api[_-]?key|secret[_-]?(?:id|key)?|access[_-]?key|token"
                            + "|password|passwd)\\s*[:=]\\s*[^\\s\"',;]{6,}");

    private final ObjectMapper objectMapper;
    private final ObjectWriter canonicalWriter;
    private final ContentCaptureMode mode;
    private final int maxBytes;

    public ContentSanitizer(ObjectMapper objectMapper, ContentCaptureMode mode, int maxBytes) {
        if (objectMapper == null || mode == null) {
            throw new IllegalArgumentException("objectMapper and mode are required");
        }
        if (maxBytes < 256) {
            throw new IllegalArgumentException("maxBytes must be at least 256");
        }
        this.objectMapper = objectMapper;
        this.canonicalWriter =
                objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.mode = mode;
        this.maxBytes = maxBytes;
    }

    /** Reports whether any payload capture is enabled, so callers can skip collecting content. */
    public boolean isCapturing() {
        return mode != ContentCaptureMode.OFF;
    }

    /** Reports whether only a digest may be emitted for payload content. */
    public boolean isHashOnly() {
        return mode == ContentCaptureMode.HASH;
    }

    public ContentCaptureMode mode() {
        return mode;
    }

    /** Builds a CLS message array from a digest computed over the complete streamed text. */
    public ArrayNode streamedMessageHash(String role, String sha256, long originalBytes) {
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode message = messages.addObject();
        message.put("role", normalizedMessageRole(role));
        ArrayNode parts = message.putArray("parts");
        ObjectNode summary = parts.addObject();
        summary.put("type", "content_hash");
        summary.put("sha256", sha256);
        summary.put("original_bytes", originalBytes);
        return messages;
    }

    /** Builds a digest envelope for a complete streamed tool result. */
    public ObjectNode streamedContentHash(String sha256, long originalBytes) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("sha256", sha256);
        result.put("original_bytes", originalBytes);
        return result;
    }

    /** The configured payload budget in bytes, used by callers to bound what they accumulate. */
    public int maxBytes() {
        return maxBytes;
    }

    /**
     * Computes a stable SHA-256 of the content in a single streaming pass.
     *
     * <p>Map entries are serialized in key order so the digest identifies the same logical payload
     * across processes and JVM restarts.
     */
    public String hash(Object content) {
        return hashContent(content).sha256();
    }

    private HashResult hashContent(Object content) {
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        MessageDigest digest = digest();
        HashingOutput stream = new HashingOutput(digest);
        try {
            if (content instanceof CharSequence text) {
                byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
                stream.write(bytes);
            } else {
                canonicalWriter.writeValue(stream, content);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("content cannot be hashed", exception);
        }
        return new HashResult(HexFormat.of().formatHex(digest.digest()), stream.count());
    }

    public Optional<JsonNode> captureMessages(@Nullable Object content) {
        if (mode == ContentCaptureMode.OFF || content == null) {
            return Optional.empty();
        }
        try {
            if (mode == ContentCaptureMode.HASH) {
                return Optional.of(summarizeHashMessages(content));
            }
            BoundedTree bounded = boundedTree(content);
            if (!bounded.complete()) {
                return Optional.of(messageLimitSummary(bounded.value(), content));
            }
            ArrayNode original = asMessageArray(bounded.value());
            ArrayNode sanitized = (ArrayNode) sanitizeNode(original, 0);
            if (encode(sanitized).length <= maxBytes) {
                return Optional.of(sanitized);
            }
            return Optional.of(summarize(original, sanitized));
        } catch (StackOverflowError error) {
            return Optional.empty();
        }
    }

    public Optional<JsonNode> capture(@Nullable Object content) {
        if (mode == ContentCaptureMode.OFF || content == null) {
            return Optional.empty();
        }
        try {
            if (mode == ContentCaptureMode.HASH) {
                HashResult digest = hashContent(content);
                ObjectNode hash = objectMapper.createObjectNode();
                hash.put("sha256", digest.sha256());
                hash.put("original_bytes", digest.originalBytes());
                return Optional.of(hash);
            }
            if (content instanceof CharSequence text) {
                String value = text.toString();
                String bounded =
                        value.length() > maxBytes ? value.substring(0, maxBytes) : value;
                JsonNode sanitized =
                        objectMapper.getNodeFactory().textNode(sanitizeText(bounded));
                byte[] encoded = encode(sanitized);
                return encoded.length <= maxBytes
                        ? Optional.of(sanitized)
                        : Optional.of(truncatedEnvelope(encoded));
            }
            BoundedTree bounded = boundedTree(content);
            if (!bounded.complete()) {
                return Optional.of(bounded.value());
            }
            JsonNode sanitized = sanitizeNode(bounded.value(), 0);
            byte[] encoded = encode(sanitized);
            if (encoded.length <= maxBytes) {
                return Optional.of(sanitized);
            }
            return Optional.of(truncatedEnvelope(encoded));
        } catch (StackOverflowError error) {
            return Optional.empty();
        }
    }

    private ArrayNode summarizeHashMessages(Object content) {
        Iterable<?> messages =
                content instanceof Iterable<?> iterable ? iterable : java.util.List.of(content);
        ArrayNode summaries = objectMapper.createArrayNode();
        int used = 2;
        for (Object messageValue : messages) {
            HashResult source = hashContent(messageValue);
            ObjectNode message =
                    summaryMessage(
                            normalizedMessageRole(messageValue), source, false, "content_hash");
            int size = encode(message).length + (summaries.isEmpty() ? 0 : 1);
            if (used + size > maxBytes) {
                break;
            }
            summaries.add(message);
            used += size;
        }
        return summaries;
    }

    private ArrayNode messageLimitSummary(JsonNode detail, Object content) {
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode message = messages.addObject();
        message.put("role", firstMessageRole(content));
        ObjectNode summary = message.putArray("parts").addObject();
        summary.put("type", "content_summary");
        summary.put("truncated", true);
        if (detail.isObject()) {
            detail.properties().forEach(field -> summary.set(field.getKey(), field.getValue()));
        }
        return messages;
    }

    private static String firstMessageRole(Object content) {
        if (content instanceof Iterable<?> iterable) {
            java.util.Iterator<?> iterator = iterable.iterator();
            return iterator.hasNext() ? normalizedMessageRole(iterator.next()) : "unknown";
        }
        return normalizedMessageRole(content);
    }

    private ArrayNode summarize(ArrayNode original, ArrayNode sanitized) {
        ArrayNode summaries = objectMapper.createArrayNode();
        int used = 2;
        for (int index = 0; index < original.size(); index++) {
            HashResult source = hashContent(original.get(index));
            ObjectNode message =
                    summaryMessage(
                            normalizedMessageRole(sanitized.get(index)),
                            source,
                            true,
                            "content_summary");
            int size = encode(message).length + (summaries.isEmpty() ? 0 : 1);
            if (used + size > maxBytes) {
                break;
            }
            summaries.add(message);
            used += size;
        }
        return summaries;
    }

    private ObjectNode summaryMessage(
            String role, HashResult source, boolean truncated, String type) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("role", role);
        ObjectNode summary = message.putArray("parts").addObject();
        summary.put("type", type);
        summary.put("sha256", source.sha256());
        summary.put("original_bytes", source.originalBytes());
        if (truncated) {
            summary.put("truncated", true);
        }
        return message;
    }

    private ArrayNode asMessageArray(JsonNode value) {
        if (value.isArray()) {
            return (ArrayNode) value;
        }
        ArrayNode messages = objectMapper.createArrayNode();
        messages.add(value);
        return messages;
    }

    private static String normalizedMessageRole(@Nullable Object message) {
        if (message instanceof JsonNode node) {
            JsonNode role = node.get("role");
            return role == null || !role.isTextual()
                    ? "unknown"
                    : normalizedMessageRole(role.asText());
        }
        if (message instanceof Map<?, ?> map) {
            Object role = map.get("role");
            return role instanceof String text ? normalizedMessageRole(text) : "unknown";
        }
        return "unknown";
    }

    private static String normalizedMessageRole(@Nullable String role) {
        if (role == null) {
            return "unknown";
        }
        return switch (role.toLowerCase(Locale.ROOT)) {
            case "system", "developer", "user", "assistant", "tool" ->
                    role.toLowerCase(Locale.ROOT);
            default -> "unknown";
        };
    }

    private JsonNode sanitizeNode(JsonNode node, int depth) {
        if (depth >= MAX_DEPTH) {
            return objectMapper.getNodeFactory().textNode(TRUNCATED_DEPTH);
        }
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String outputKey = sanitizeKey(field.getKey());
                if (isSensitive(field.getKey())) {
                    result.put(outputKey, REDACTED);
                } else {
                    result.set(outputKey, sanitizeNode(field.getValue(), depth + 1));
                }
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            for (JsonNode value : node) {
                result.add(sanitizeNode(value, depth + 1));
            }
            return result;
        }
        if (node.isTextual()) {
            String text = node.textValue();
            boolean truncated = text.length() > maxBytes;
            String bounded = truncated ? text.substring(0, maxBytes) : text;
            String sanitized = sanitizeText(bounded);
            return objectMapper
                    .getNodeFactory()
                    .textNode(truncated ? sanitized + "[TRUNCATED]" : sanitized);
        }
        return node.deepCopy();
    }

    private ObjectNode truncatedEnvelope(byte[] encoded) {
        int low = 0;
        int high = Math.min(encoded.length, maxBytes);
        String best = "";
        while (low <= high) {
            int middle = (low + high) >>> 1;
            String preview = utf8Prefix(encoded, middle);
            if (encode(envelope(preview, encoded.length)).length <= maxBytes) {
                best = preview;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return envelope(best, encoded.length);
    }

    private ObjectNode envelope(String preview, int originalBytes) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("truncated", true);
        result.put("original_bytes", originalBytes);
        result.put("preview", preview);
        return result;
    }

    private static boolean isSensitive(String key) {
        String normalized = NON_ALPHANUMERIC.matcher(key.toLowerCase(Locale.ROOT)).replaceAll("");
        for (String sensitive : SENSITIVE_KEYS) {
            if (normalized.contains(sensitive)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeKey(String key) {
        String sanitized = sanitizeText(key);
        return sanitized.equals(key) ? key : REDACTED_KEY;
    }

    private static String sanitizeText(String value) {
        return redactSecrets(sanitizeEmbeddedUrls(value));
    }

    private static String redactSecrets(String value) {
        if (value.length() < MIN_SECRET_SCAN_LENGTH) {
            return value;
        }
        String withoutPem = redactPemBlocks(value);
        return SECRET_VALUE_PATTERN.matcher(withoutPem).replaceAll(REDACTED_SECRET);
    }

    private static String redactPemBlocks(String value) {
        StringBuilder result = null;
        int copiedUntil = 0;
        int searchFrom = 0;
        while (true) {
            int begin = value.indexOf(PEM_BEGIN, searchFrom);
            if (begin < 0) {
                break;
            }
            int headerEnd = value.indexOf(PRIVATE_KEY_SUFFIX, begin + PEM_BEGIN.length());
            if (headerEnd < 0 || headerEnd - begin > MAX_PEM_HEADER_LENGTH) {
                searchFrom = begin + PEM_BEGIN.length();
                continue;
            }
            int endBegin = value.indexOf(PEM_END, headerEnd + PRIVATE_KEY_SUFFIX.length());
            int end =
                    endBegin < 0
                            ? value.length()
                            : value.indexOf(PRIVATE_KEY_SUFFIX, endBegin + PEM_END.length());
            if (end >= 0 && end < value.length()) {
                end += PRIVATE_KEY_SUFFIX.length();
            } else {
                end = value.length();
            }
            if (result == null) {
                result = new StringBuilder(value.length());
            }
            result.append(value, copiedUntil, begin).append(REDACTED_SECRET);
            copiedUntil = end;
            searchFrom = end;
        }
        if (result == null) {
            return value;
        }
        return result.append(value, copiedUntil, value.length()).toString();
    }

    private static String sanitizeEmbeddedUrls(String value) {
        String trimmed = value.trim();
        Matcher whole = URL_TOKEN_PATTERN.matcher(trimmed);
        if (whole.matches()) {
            return sanitizeUrlToken(trimmed);
        }
        Matcher matcher = URL_TOKEN_PATTERN.matcher(value);
        StringBuffer result = null;
        while (matcher.find()) {
            if (result == null) {
                result = new StringBuffer(value.length());
            }
            matcher.appendReplacement(
                    result, Matcher.quoteReplacement(sanitizeUrlToken(matcher.group())));
        }
        if (result == null) {
            return value;
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String sanitizeUrlToken(String candidate) {
        try {
            URI uri = new URI(candidate);
            if (uri.getHost() == null) {
                return "[REDACTED_URL]";
            }
            return new URI(
                            uri.getScheme().toLowerCase(Locale.ROOT),
                            null,
                            uri.getHost(),
                            uri.getPort(),
                            uri.getPath(),
                            null,
                            null)
                    .toString();
        } catch (URISyntaxException exception) {
            return "[REDACTED_URL]";
        }
    }

    private BoundedTree boundedTree(Object content) {
        BoundedOutput output = new BoundedOutput(maxBytes);
        try {
            canonicalWriter.writeValue(output, content);
            return new BoundedTree(objectMapper.readTree(output.toByteArray()), true);
        } catch (IOException exception) {
            if (output.exceeded()) {
                ObjectNode summary = objectMapper.createObjectNode();
                summary.put("truncated", true);
                summary.put("original_bytes_at_least", (long) maxBytes + 1L);
                return new BoundedTree(summary, false);
            }
            if (hasCauseNamed(exception, "StreamConstraintsException")) {
                ObjectNode summary = objectMapper.createObjectNode();
                summary.put("truncated", true);
                summary.put("reason", TRUNCATED_DEPTH);
                return new BoundedTree(summary, false);
            }
            throw new IllegalArgumentException("content cannot be encoded as JSON", exception);
        }
    }

    private static boolean hasCauseNamed(Throwable error, String simpleName) {
        Throwable current = error;
        while (current != null) {
            if (simpleName.equals(current.getClass().getSimpleName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private byte[] encode(JsonNode node) {
        try {
            return objectMapper.writeValueAsBytes(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("content cannot be encoded as JSON", exception);
        }
    }

    private static String utf8Prefix(byte[] bytes, int budget) {
        int length = Math.min(bytes.length, budget);
        while (length > 0) {
            try {
                return StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, length))
                        .toString();
            } catch (CharacterCodingException exception) {
                length--;
            }
        }
        return "";
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record HashResult(String sha256, long originalBytes) {}

    private record BoundedTree(JsonNode value, boolean complete) {}

    private static final class BoundedOutput extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream delegate;
        private boolean exceeded;

        private BoundedOutput(int limit) {
            this.limit = limit;
            this.delegate = new ByteArrayOutputStream(Math.min(limit, 8192));
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(bytes, offset, length);
        }

        private void ensureCapacity(int additional) throws IOException {
            if (additional > limit - delegate.size()) {
                exceeded = true;
                throw new IOException("content byte budget exceeded");
            }
        }

        private boolean exceeded() {
            return exceeded;
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }

    private static final class HashingOutput extends OutputStream {
        private final MessageDigest digest;
        private long count;

        private HashingOutput(MessageDigest digest) {
            this.digest = digest;
        }

        @Override
        public void write(int value) {
            digest.update((byte) value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            digest.update(bytes, offset, length);
            count += length;
        }

        private long count() {
            return count;
        }
    }
}
