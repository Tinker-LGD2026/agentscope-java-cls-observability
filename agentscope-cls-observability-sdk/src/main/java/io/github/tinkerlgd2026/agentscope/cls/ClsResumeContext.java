package io.github.tinkerlgd2026.agentscope.cls;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Identifies the timed-out turn from which a new AgentScope invocation resumes. */
public record ClsResumeContext(String resumeFromTurnId) {
    private static final int MAX_BYTES = 512;

    public ClsResumeContext {
        Objects.requireNonNull(resumeFromTurnId, "resumeFromTurnId");
        if (resumeFromTurnId.isBlank()) {
            throw new IllegalArgumentException("resumeFromTurnId must not be blank");
        }
        if (resumeFromTurnId.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("resumeFromTurnId must not exceed 512 UTF-8 bytes");
        }
    }
}
