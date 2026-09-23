package io.github.tinkerlgd2026.agentscope.cls.privacy;

import java.util.Locale;

public enum ContentCaptureMode {
    OFF,
    HASH,
    TRUNCATE,
    FULL;

    public static ContentCaptureMode parse(String value) {
        return parse(value, "CLS_CONTENT_CAPTURE");
    }

    public static ContentCaptureMode parse(String value, String settingName) {
        if (value == null || value.isBlank()) {
            return OFF;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    settingName + " must be one of: off, hash, truncate, full", exception);
        }
    }
}
