package io.github.tinkerlgd2026.agentscope.cls.privacy;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Best-effort credential redaction for canonical provider payloads. */
final class CredentialRedactor {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern SENSITIVE_KEY =
            Pattern.compile(
                    "(?i).*(secret|token|password|passwd|authorization|cookie|credential|api.?key|access.?key|private.?key).*");
    private static final Pattern SECRET_VALUE =
            Pattern.compile(
                    "AKID[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9_-]{8,}|(?i:bearer)\\s+\\S+|"
                            + "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}|"
                            + "-----BEGIN [^-]{1,64}PRIVATE KEY-----");

    Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.entrySet().stream()
                    .sorted((left, right) -> String.valueOf(left.getKey()).compareTo(String.valueOf(right.getKey())))
                    .forEach(
                            entry -> {
                                String key = String.valueOf(entry.getKey());
                                result.put(
                                        key,
                                        SENSITIVE_KEY.matcher(normalizeKey(key)).matches()
                                                ? REDACTED
                                                : redact(entry.getValue()));
                            });
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(item -> result.add(redact(item)));
            return result;
        }
        if (value instanceof Object[] array) {
            List<Object> result = new ArrayList<>(array.length);
            for (Object item : array) {
                result.add(redact(item));
            }
            return result;
        }
        if (value instanceof CharSequence text) {
            return redactText(text.toString());
        }
        return value;
    }

    private static String redactText(String value) {
        String redacted = SECRET_VALUE.matcher(value).replaceAll(REDACTED);
        try {
            URI uri = URI.create(redacted);
            if (uri.getUserInfo() != null) {
                redacted =
                        new URI(
                                        uri.getScheme(),
                                        REDACTED,
                                        uri.getHost(),
                                        uri.getPort(),
                                        uri.getPath(),
                                        uri.getQuery(),
                                        uri.getFragment())
                                .toString();
            }
        } catch (Exception ignored) {
            // Not a URI; shape-based redaction above still applies.
        }
        return redacted;
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
