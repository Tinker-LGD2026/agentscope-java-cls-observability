package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryValueNormalizer;
import java.util.List;
import java.util.Objects;

/**
 * Bounded correlation record for one control wait or tombstone. Carries identifiers only —
 * never payload.
 */
final class ControlRecord {
    static final int ID_MAX_BYTES = 128;
    static final int MAX_TOOL_CALL_IDS = 16;
    /** Fixed estimate used for control-record memory reservations. */
    static final long ESTIMATED_BYTES = 768;

    enum Kind {
        USER_CONFIRM,
        EXTERNAL_EXECUTION
    }

    private final String generationId;
    private final String agentId;
    private final Kind kind;
    private final String replyId;
    private final List<String> toolCallIds;
    private final long createdNanos;
    private boolean tombstone;
    private String rotatedGenerationId;

    ControlRecord(
            String generationId,
            String agentId,
            Kind kind,
            String replyId,
            List<String> toolCallIds,
            long createdNanos) {
        this.generationId = Objects.requireNonNull(generationId, "generationId");
        this.agentId = TelemetryValueNormalizer.bounded(agentId, ID_MAX_BYTES);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.replyId = TelemetryValueNormalizer.bounded(replyId, ID_MAX_BYTES);
        this.toolCallIds =
                toolCallIds == null
                        ? List.of()
                        : toolCallIds.stream()
                                .filter(Objects::nonNull)
                                .limit(MAX_TOOL_CALL_IDS)
                                .map(id -> TelemetryValueNormalizer.bounded(id, ID_MAX_BYTES))
                                .toList();
        this.createdNanos = createdNanos;
    }

    String generationId() {
        return generationId;
    }

    String agentId() {
        return agentId;
    }

    Kind kind() {
        return kind;
    }

    String replyId() {
        return replyId;
    }

    List<String> toolCallIds() {
        return toolCallIds;
    }

    long createdNanos() {
        return createdNanos;
    }

    boolean tombstone() {
        return tombstone;
    }

    void markTombstone(String rotatedGenerationId) {
        this.tombstone = true;
        this.rotatedGenerationId = rotatedGenerationId;
    }

    String rotatedGenerationId() {
        return rotatedGenerationId;
    }

    String key() {
        return generationId + "|" + agentId + "|" + kind + "|" + replyId;
    }

    String secondaryKey() {
        return kind + "|" + replyId;
    }
}
