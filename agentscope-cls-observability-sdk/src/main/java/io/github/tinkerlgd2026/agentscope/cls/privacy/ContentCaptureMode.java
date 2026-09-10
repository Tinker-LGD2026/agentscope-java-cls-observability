package io.github.tinkerlgd2026.agentscope.cls.privacy;

import java.util.Locale;

public enum ContentCaptureMode {
    OFF,
    HASH,
    TRUNCATE,
    FULL;

    public static ContentCaptureMode parse(String value) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "CLS_CONTENT_CAPTURE must be one of: off, hash, truncate, full", exception);
        }
    }
}
