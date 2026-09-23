package io.github.tinkerlgd2026.agentscope.cls.privacy;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort credential redaction for canonical provider payloads. */
final class CredentialRedactor {
    static final String REDACTED = "[REDACTED]";
    private static final int MAX_REDACTION_COPY_CHARACTERS = 786_432;
    private static final Set<String> SENSITIVE_WORDS =
            Set.of(
                    "secret",
                    "token",
                    "password",
                    "passwd",
                    "authorization",
                    "cookie",
                    "credential",
                    "signature");
    private static final Set<String> SENSITIVE_NORMALIZED_KEYS =
            Set.of(
                    "secretid",
                    "secretkey",
                    "apikey",
                    "accesskey",
                    "privatekey",
                    "thoughtsignature",
                    "reasoningdetails");
    private static final Pattern PEM_PRIVATE_KEY =
            Pattern.compile(
                    "-----BEGIN [^-\\r\\n]{0,64}PRIVATE KEY-----.*?"
                            + "-----END [^-\\r\\n]{0,64}PRIVATE KEY-----",
                    Pattern.DOTALL);
    private static final Pattern URL =
            Pattern.compile("(?i)https?://[^\\s<>\"']+");
    private static final Pattern SECRET_VALUE =
            Pattern.compile(
                    "AKID[A-Za-z0-9_-]{8,}|sk-[A-Za-z0-9_-]{8,}|(?i:bearer)\\s+\\S+|"
                            + "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

    boolean sensitiveKey(String key) {
        String normalized = normalizeKey(key);
        if (SENSITIVE_NORMALIZED_KEYS.contains(normalized)) {
            return true;
        }
        List<String> words = splitKeyWords(key);
        for (String word : words) {
            if (SENSITIVE_WORDS.contains(word)) {
                return true;
            }
        }
        return words.contains("key")
                && (words.contains("api")
                        || words.contains("access")
                        || words.contains("private"));
    }

    String redactText(String value) {
        String redacted = replaceIfPresent(PEM_PRIVATE_KEY, value, REDACTED);
        Matcher urls = URL.matcher(redacted);
        if (urls.find()) {
            requireBoundedCopy(redacted);
            StringBuffer sanitized = new StringBuffer(Math.min(redacted.length(), 8_192));
            do {
                urls.appendReplacement(
                        sanitized, Matcher.quoteReplacement(sanitizeUrl(urls.group())));
            } while (urls.find());
            urls.appendTail(sanitized);
            redacted = sanitized.toString();
        }
        return replaceIfPresent(SECRET_VALUE, redacted, REDACTED);
    }

    private static String replaceIfPresent(Pattern pattern, String value, String replacement) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return value;
        }
        requireBoundedCopy(value);
        return matcher.replaceAll(replacement);
    }

    private static void requireBoundedCopy(String value) {
        if (value.length() > MAX_REDACTION_COPY_CHARACTERS) {
            throw new RedactionLimitException();
        }
    }

    private static String sanitizeUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null) {
                return value;
            }
            return new URI(
                            uri.getScheme(),
                            null,
                            uri.getHost(),
                            uri.getPort(),
                            uri.getPath(),
                            null,
                            null)
                    .toString();
        } catch (Exception exception) {
            return REDACTED;
        }
    }

    private static List<String> splitKeyWords(String key) {
        String separated =
                key.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                        .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
        return java.util.Arrays.stream(separated.split("[^A-Za-z0-9]+"))
                .filter(word -> !word.isEmpty())
                .map(word -> word.toLowerCase(Locale.ROOT))
                .toList();
    }

    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static final class RedactionLimitException extends RuntimeException {}
}
