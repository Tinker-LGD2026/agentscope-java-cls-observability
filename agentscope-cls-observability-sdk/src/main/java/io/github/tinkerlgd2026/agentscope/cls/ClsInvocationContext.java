package io.github.tinkerlgd2026.agentscope.cls;

import org.jspecify.annotations.Nullable;

/**
 * Optional request identity metadata carried inside AgentScope {@code RuntimeContext}.
 *
 * <p>Values are normalized before Span creation. User names are bounded to 256 UTF-8 bytes, turn
 * IDs to 512 bytes, and agent/entry types to 128 bytes. Oversized values retain a bounded prefix
 * and stable SHA-256 fingerprint.
 *
 * @param userName optional display name; prefer a non-sensitive alias
 * @param turnId optional unique business request ID; a new ID is generated when absent
 * @param agentType business classification for the Agent
 * @param entryType application entry classification such as {@code web-api}
 */
public record ClsInvocationContext(
        @Nullable String userName,
        @Nullable String turnId,
        String agentType,
        String entryType) {

    public ClsInvocationContext {
        userName = normalizeOptional(userName);
        turnId = normalizeOptional(turnId);
        agentType = defaultIfBlank(agentType, "agentscope-java");
        entryType = defaultIfBlank(entryType, "java-sdk");
    }

    public static ClsInvocationContext defaults() {
        return new ClsInvocationContext(null, null, "agentscope-java", "java-sdk");
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        String normalized = normalizeOptional(value);
        return normalized == null ? fallback : normalized;
    }
}
