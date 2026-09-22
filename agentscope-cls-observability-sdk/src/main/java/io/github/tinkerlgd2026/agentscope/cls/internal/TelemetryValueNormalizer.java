package io.github.tinkerlgd2026.agentscope.cls.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** Bounded normalization for identifiers and other low-cardinality telemetry values. */
public final class TelemetryValueNormalizer {
    private static final int FINGERPRINT_HEX_LENGTH = 16;
    private static final String REDACTED_PREFIX = "redacted~";
    private static final int TRUNCATION_SUFFIX_BYTES = 1 + FINGERPRINT_HEX_LENGTH;
    private static final int REDACTED_VALUE_BYTES =
            REDACTED_PREFIX.length() + FINGERPRINT_HEX_LENGTH;
    private static final Pattern TENCENT_SECRET_ID =
            Pattern.compile("(?<![A-Za-z0-9])AKID[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])");
    private static final Pattern API_SECRET =
            Pattern.compile("(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])");
    private static final Pattern BEARER =
            Pattern.compile(
                    "(?i)(?<![A-Za-z0-9])(?:authorization\\s*:\\s*)?bearer\\s+\\S+");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TelemetryValueNormalizer() {}

    public static String bounded(String value, int maxBytes) {
        requireBudget(maxBytes, 1);
        String normalized = normalize(value);
        byte[] original = normalized.getBytes(StandardCharsets.UTF_8);
        if (original.length <= maxBytes) {
            return normalized;
        }
        requireBudget(maxBytes, TRUNCATION_SUFFIX_BYTES + 1);
        return utf8Prefix(normalized, maxBytes - TRUNCATION_SUFFIX_BYTES)
                + "~"
                + fingerprint(original);
    }

    public static String safeIdentifier(String value, int maxBytes) {
        String normalized = normalize(value);
        if (!credentialShaped(normalized)) {
            return bounded(normalized, maxBytes);
        }
        requireBudget(maxBytes, REDACTED_VALUE_BYTES);
        return REDACTED_PREFIX + fingerprint(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean credentialShaped(String value) {
        return TENCENT_SECRET_ID.matcher(value).find()
                || API_SECRET.matcher(value).find()
                || BEARER.matcher(value).find();
    }

    private static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("telemetry value is required");
        }
        validateUtf16(value);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("telemetry value must not be blank");
        }
        return normalized;
    }

    private static void validateUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException("telemetry value contains malformed UTF-16");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("telemetry value contains malformed UTF-16");
            }
        }
    }

    private static void requireBudget(int maxBytes, int minimum) {
        if (maxBytes < minimum) {
            throw new IllegalArgumentException(
                    "telemetry value byte budget must be at least " + minimum);
        }
    }

    private static String utf8Prefix(String value, int byteBudget) {
        StringBuilder prefix = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            int encodedBytes = utf8Bytes(codePoint);
            if (bytes + encodedBytes > byteBudget) {
                break;
            }
            prefix.appendCodePoint(codePoint);
            bytes += encodedBytes;
            offset += Character.charCount(codePoint);
        }
        return prefix.toString();
    }

    private static int utf8Bytes(int codePoint) {
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
