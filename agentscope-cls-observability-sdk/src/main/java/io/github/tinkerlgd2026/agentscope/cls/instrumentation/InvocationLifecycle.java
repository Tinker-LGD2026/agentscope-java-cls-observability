package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.github.tinkerlgd2026.agentscope.cls.schema.ClsFields;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Owns the span tree of one invocation generation. The only component allowed to call
 * {@code Span.end()}; terminal transitions are linearized (first terminal wins) and children
 * always end before parents.
 */
final class InvocationLifecycle {
    enum State {
        OPEN,
        FINISHING,
        FINISHED
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final AtomicReference<TerminalOutcome> terminalOutcome = new AtomicReference<>();
    private final AtomicLong lateTerminalSignals = new AtomicLong();
    private final List<Span> chatAndToolSpans = new CopyOnWriteArrayList<>();
    private final List<Span> stepSpans = new CopyOnWriteArrayList<>();
    private final List<Span> agentSpans = new CopyOnWriteArrayList<>();
    private final AtomicReference<Span> entrySpan = new AtomicReference<>();

    State state() {
        return state.get();
    }

    @Nullable TerminalOutcome terminalOutcome() {
        return terminalOutcome.get();
    }

    long lateTerminalSignals() {
        return lateTerminalSignals.get();
    }

    void registerEntry(Span span) {
        entrySpan.set(Objects.requireNonNull(span, "entry span"));
    }

    void registerAgent(String agentId, Span span) {
        agentSpans.add(Objects.requireNonNull(span, "agent span"));
    }

    void registerStep(String agentId, String stepId, Span span) {
        stepSpans.add(Objects.requireNonNull(span, "step span"));
    }

    void registerChat(String stepId, Span span) {
        chatAndToolSpans.add(Objects.requireNonNull(span, "chat span"));
    }

    void registerTool(String stepId, String callId, Span span) {
        chatAndToolSpans.add(Objects.requireNonNull(span, "tool span"));
    }

    /**
     * Linearizes the terminal transition: the first caller wins, every span ends exactly once
     * in child-before-parent order. Later terminal signals are counted, not applied.
     *
     * @return true when this call performed the terminal transition
     */
    boolean terminal(
            TerminalOutcome outcome, boolean resultObserved, @Nullable Throwable error) {
        Objects.requireNonNull(outcome, "outcome");
        if (!state.compareAndSet(State.OPEN, State.FINISHING)) {
            lateTerminalSignals.incrementAndGet();
            return false;
        }
        terminalOutcome.set(outcome);
        try {
            for (Span span : chatAndToolSpans) {
                endQuietly(span, outcome, error);
            }
            for (Span span : stepSpans) {
                endQuietly(span, outcome, error);
            }
            for (Span span : agentSpans) {
                endQuietly(span, outcome, error);
            }
            Span entry = entrySpan.get();
            if (entry != null) {
                entry.setAttribute(ClsFields.TURN_COMPLETED, resultObserved);
                entry.setAttribute(ClsFields.TURN_FINISH_REASON, outcome.finishReason());
                entry.setAttribute(ClsFields.INCOMPLETE, outcome.incomplete());
                endQuietly(entry, outcome, error);
            }
        } finally {
            state.set(State.FINISHED);
        }
        return true;
    }

    private static void endQuietly(
            Span span, TerminalOutcome outcome, @Nullable Throwable error) {
        try {
            StatusCode statusCode = outcome.statusCode();
            String description = outcome.statusDescription(error);
            switch (statusCode) {
                case OK -> span.setStatus(StatusCode.OK);
                case UNSET -> span.setStatus(StatusCode.UNSET);
                case ERROR -> {
                    span.setStatus(StatusCode.ERROR, description);
                    span.setAttribute("error.type", description);
                    if (outcome == TerminalOutcome.ERROR && error != null) {
                        span.addEvent(
                                "exception",
                                Attributes.builder()
                                        .put("exception.type", error.getClass().getName())
                                        .build());
                    }
                }
            }
        } finally {
            span.end();
        }
    }
}
