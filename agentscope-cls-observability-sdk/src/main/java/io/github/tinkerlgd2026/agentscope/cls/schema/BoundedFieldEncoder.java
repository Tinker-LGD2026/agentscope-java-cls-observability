package io.github.tinkerlgd2026.agentscope.cls.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Enforces CLS field byte limits and the single-record hard limit. Croppable fields are
 * replaced by a deterministic truncated envelope; non-croppable invalid fields cause a
 * deterministic rejection.
 */
public final class BoundedFieldEncoder {
    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern NON_NEGATIVE_LONG = Pattern.compile("[0-9]+");
    private static final String ENVELOPE_PREFIX = "{\"truncated\":true,\"original_bytes\":";
    // {"truncated":true,"original_bytes":<N>,"sha256":"<64 hex>","preview":""} with N
    // bounded by 20 digits: 35 + 20 + 11 + 64 + 13 + 2 = 145; rounded up conservatively.
    private static final int ENVELOPE_OVERHEAD_BYTES = 149;

    private final int attributeMaxBytes;
    private final long recordMaxBytes;

    public BoundedFieldEncoder(ObjectMapper objectMapper, int attributeMaxBytes) {
        this(objectMapper, attributeMaxBytes, ClsFieldLimits.RECORD_MAX_BYTES);
    }

    public BoundedFieldEncoder(ObjectMapper objectMapper, int attributeMaxBytes, long recordMaxBytes) {
        if (objectMapper == null
                || attributeMaxBytes < 256
                || attributeMaxBytes > ClsFieldLimits.ATTRIBUTE_FIELD_MAX_BYTES
                || recordMaxBytes < 1_024) {
            throw new IllegalArgumentException(
                    "objectMapper and supported byte budgets are required");
        }
        this.attributeMaxBytes = attributeMaxBytes;
        this.recordMaxBytes = recordMaxBytes;
    }

    public Result encode(ClsSpanRecord record) {
        if (record == null) {
            return Result.rejected("record is required");
        }
        String rejection = validateNonCroppable(record);
        if (rejection != null) {
            return Result.rejected(rejection);
        }

        String name = cropPlain(record.name(), ClsFieldLimits.NAME_MAX_BYTES);
        String statusMessage =
                cropPlain(
                        record.statusMessage() == null ? "" : record.statusMessage(),
                        ClsFieldLimits.STATUS_MESSAGE_MAX_BYTES);
        String traceState =
                cropPlain(
                        record.traceState() == null ? "" : record.traceState(),
                        ClsFieldLimits.TRACE_STATE_MAX_BYTES);
        if (record.resource() == null) {
            return Result.rejected("resource is required");
        }
        String resource = cropJson(record.resource(), ClsFieldLimits.RESOURCE_MAX_BYTES);
        if (resource == null) {
            return Result.rejected("resource cannot fit its field limit");
        }
        String links = cropJson(record.links() == null ? "[]" : record.links(), ClsFieldLimits.LINKS_MAX_BYTES);
        if (links == null) {
            return Result.rejected("links cannot fit its field limit");
        }
        String logs = cropJson(record.logs() == null ? "[]" : record.logs(), ClsFieldLimits.LOGS_MAX_BYTES);
        if (logs == null) {
            return Result.rejected("logs cannot fit its field limit");
        }

        if (record.attribute() == null) {
            return Result.rejected("attribute is required");
        }
        String attribute = cropJson(record.attribute(), attributeMaxBytes);
        if (attribute == null) {
            return Result.rejected("attribute cannot fit its field limit");
        }

        ClsSpanRecord cropped =
                new ClsSpanRecord(
                        record.traceID(),
                        record.spanID(),
                        record.parentSpanID(),
                        name,
                        record.kind(),
                        record.start(),
                        record.end(),
                        record.duration(),
                        record.statusCode(),
                        statusMessage,
                        attribute,
                        resource,
                        traceState,
                        links,
                        logs);
        long size = Utf8LogItemSizer.size(cropped.fields());
        if (size <= recordMaxBytes) {
            return Result.accepted(cropped.equals(record) ? record : cropped);
        }

        // The attribute field is the only field large enough to matter; shrink it to the
        // remaining record budget before giving up.
        long others = size - Utf8LogItemSizer.utf8Length(attribute);
        long attributeBudget = recordMaxBytes - others;
        if (attributeBudget >= 256) {
            String reduced = cropJson(record.attribute(), (int) attributeBudget);
            if (reduced != null) {
                ClsSpanRecord reducedRecord =
                        new ClsSpanRecord(
                                cropped.traceID(),
                                cropped.spanID(),
                                cropped.parentSpanID(),
                                cropped.name(),
                                cropped.kind(),
                                cropped.start(),
                                cropped.end(),
                                cropped.duration(),
                                cropped.statusCode(),
                                cropped.statusMessage(),
                                reduced,
                                cropped.resource(),
                                cropped.traceState(),
                                cropped.links(),
                                cropped.logs());
                if (Utf8LogItemSizer.size(reducedRecord.fields()) <= recordMaxBytes) {
                    return Result.accepted(reducedRecord);
                }
            }
        }
        return Result.rejected("record exceeds the hard size limit");
    }

    private static String validateNonCroppable(ClsSpanRecord record) {
        if (record.traceID() == null || !TRACE_ID.matcher(record.traceID()).matches()) {
            return "traceID has an invalid format";
        }
        if (record.spanID() == null || !SPAN_ID.matcher(record.spanID()).matches()) {
            return "spanID has an invalid format";
        }
        if (record.parentSpanID() == null
                || (!record.parentSpanID().isEmpty()
                        && !SPAN_ID.matcher(record.parentSpanID()).matches())) {
            return "parentSpanID must be empty or a 16 character lowercase hex value";
        }
        if (record.name() == null || record.name().isBlank()) {
            return "name is required";
        }
        if (record.kind() == null || record.kind().isBlank()) {
            return "kind is required";
        }
        if (record.statusCode() == null || record.statusCode().isBlank()) {
            return "statusCode is required";
        }
        if (!validTime(record.start()) || !validTime(record.end()) || !validTime(record.duration())) {
            return "start, end and duration must be non-negative integer strings";
        }
        return null;
    }

    private static boolean validTime(String value) {
        if (value == null || !NON_NEGATIVE_LONG.matcher(value).matches()) {
            return false;
        }
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String cropPlain(String value, int maxBytes) {
        return utf8Prefix(value, maxBytes);
    }

    private String cropJson(String json, int maxBytes) {
        if (json == null) {
            return null;
        }
        if (Utf8LogItemSizer.utf8Length(json) <= maxBytes) {
            return json;
        }
        int previewBudget = maxBytes - ENVELOPE_OVERHEAD_BYTES;
        if (previewBudget < 0) {
            return null;
        }
        return ENVELOPE_PREFIX
                + Utf8LogItemSizer.utf8Length(json)
                + ",\"sha256\":\""
                + sha256(json)
                + "\",\"preview\":\""
                + escapedUtf8Prefix(json, previewBudget)
                + "\"}";
    }

    // Escaping can inflate bytes (e.g. '"' → '\"'), so the budget applies to the escaped
    // UTF-8 output, not the raw prefix.
    private static String escapedUtf8Prefix(String value, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            String escaped = escapeCodePoint(codePoint);
            int bytes = escaped.getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > maxBytes) {
                break;
            }
            result.append(escaped);
            used += bytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static String escapeCodePoint(int codePoint) {
        return switch (codePoint) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default ->
                    codePoint < 0x20
                            ? String.format("\\u%04x", codePoint)
                            : new String(Character.toChars(codePoint));
        };
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String utf8Prefix(String value, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int bytes =
                    new String(Character.toChars(codePoint))
                            .getBytes(StandardCharsets.UTF_8)
                            .length;
            if (used + bytes > maxBytes) {
                break;
            }
            result.appendCodePoint(codePoint);
            used += bytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    public record Result(ClsSpanRecord record, String rejection) {
        public boolean accepted() {
            return rejection == null;
        }

        static Result accepted(ClsSpanRecord record) {
            return new Result(Objects.requireNonNull(record, "record"), null);
        }

        static Result rejected(String reason) {
            return new Result(null, Objects.requireNonNull(reason, "reason"));
        }
    }
}
