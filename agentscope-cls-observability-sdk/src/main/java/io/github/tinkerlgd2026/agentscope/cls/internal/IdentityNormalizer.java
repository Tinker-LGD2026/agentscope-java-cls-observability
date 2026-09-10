package io.github.tinkerlgd2026.agentscope.cls.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Internal bounded normalization for request identity attributes. */
public final class IdentityNormalizer {
    public static final int SESSION_ID_MAX_BYTES = 512;
    public static final int USER_ID_MAX_BYTES = 512;
    public static final int TURN_ID_MAX_BYTES = 512;
    public static final int USER_NAME_MAX_BYTES = 256;
    public static final int TYPE_MAX_BYTES = 128;

    private static final int FINGERPRINT_HEX_LENGTH = 16;
    private static final int SUFFIX_BYTES = 1 + FINGERPRINT_HEX_LENGTH;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private IdentityNormalizer() {}

    public static String bounded(String value, int maxBytes) {
        if (value == null) {
            throw new IllegalArgumentException("identity value is required");
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("identity value must not be blank");
        }
        byte[] original = normalized.getBytes(StandardCharsets.UTF_8);
        if (original.length <= maxBytes) {
            return normalized;
        }
        if (maxBytes <= SUFFIX_BYTES) {
            throw new IllegalArgumentException("identity byte budget is too small");
        }

        int prefixBudget = maxBytes - SUFFIX_BYTES;
        StringBuilder prefix = new StringBuilder();
        int prefixBytes = 0;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (prefixBytes + characterBytes > prefixBudget) {
                break;
            }
            prefix.append(character);
            prefixBytes += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return prefix + "~" + fingerprint(original);
    }

    private static String fingerprint(byte[] value) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        char[] encoded = new char[FINGERPRINT_HEX_LENGTH];
        for (int index = 0; index < FINGERPRINT_HEX_LENGTH / 2; index++) {
            int current = digest[index] & 0xff;
            encoded[index * 2] = HEX[current >>> 4];
            encoded[index * 2 + 1] = HEX[current & 0x0f];
        }
        return new String(encoded);
    }
}
