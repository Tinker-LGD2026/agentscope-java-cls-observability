package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.opentelemetry.api.trace.StatusCode;

/** Terminal outcome of one invocation generation, mapped to CLS turn fields and OTel status. */
enum TerminalOutcome {
    NORMAL("normal"),
    INCOMPLETE_COMPLETION("incomplete_completion"),
    AWAIT_TIMEOUT("await_timeout"),
    CONTROL_CAPACITY("control_capacity"),
    DENIED("denied"),
    MAX_ITERS("max_iters"),
    INTERRUPTED("interrupted"),
    CANCELLED("cancelled"),
    ERROR("error"),
    SHUTDOWN("shutdown");

    private final String finishReason;

    TerminalOutcome(String finishReason) {
        this.finishReason = finishReason;
    }

    String finishReason() {
        return finishReason;
    }

    /** Anything except a fully observed normal completion is incomplete. */
    boolean incomplete() {
        return this != NORMAL;
    }

    StatusCode statusCode() {
        return switch (this) {
            case NORMAL, DENIED, MAX_ITERS, INTERRUPTED -> StatusCode.OK;
            case CANCELLED, ERROR -> StatusCode.ERROR;
            default -> StatusCode.UNSET;
        };
    }

    /** Status description: empty for UNSET, safe marker for cancelled, error type otherwise. */
    String statusDescription(Throwable error) {
        return switch (this) {
            case CANCELLED -> "cancelled";
            case ERROR -> error == null ? "unknown" : error.getClass().getName();
            default -> "";
        };
    }
}
