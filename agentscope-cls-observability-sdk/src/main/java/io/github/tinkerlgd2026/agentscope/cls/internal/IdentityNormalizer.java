package io.github.tinkerlgd2026.agentscope.cls.internal;

/** Compatibility facade for request identity attribute normalization. */
public final class IdentityNormalizer {
    public static final int SESSION_ID_MAX_BYTES = 512;
    public static final int USER_ID_MAX_BYTES = 512;
    public static final int TURN_ID_MAX_BYTES = 512;
    public static final int USER_NAME_MAX_BYTES = 256;
    public static final int TYPE_MAX_BYTES = 128;

    private IdentityNormalizer() {}

    public static String bounded(String value, int maxBytes) {
        return TelemetryValueNormalizer.bounded(value, maxBytes);
    }
}
