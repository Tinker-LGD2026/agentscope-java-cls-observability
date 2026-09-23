package io.github.tinkerlgd2026.agentscope.cls.schema;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Conservative UTF-8 byte sizer for one CLS log item. Always returns a value greater than
 * or equal to Tencent {@code LogSizeCalculator}, which counts UTF-16 code units.
 */
public final class Utf8LogItemSizer {
    /** Matches the fixed per-record overhead used by Tencent LogSizeCalculator. */
    public static final long RECORD_OVERHEAD_BYTES = 4;

    private Utf8LogItemSizer() {}

    public static long size(Map<String, String> fields) {
        if (fields == null) {
            throw new IllegalArgumentException("fields are required");
        }
        long size = RECORD_OVERHEAD_BYTES;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException("field keys and values must not be null");
            }
            size += utf8Length(entry.getKey());
            size += utf8Length(entry.getValue());
        }
        return size;
    }

    public static long utf8Length(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value is required");
        }
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
