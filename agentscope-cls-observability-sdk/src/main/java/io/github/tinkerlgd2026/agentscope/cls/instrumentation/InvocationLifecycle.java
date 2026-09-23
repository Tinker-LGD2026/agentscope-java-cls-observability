package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.github.tinkerlgd2026.agentscope.cls.schema.ClsFields;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Owns the span tree of one invocation generation. The only component allowed to call
 * {@code Span.end()}; terminal transitions are linearized (first terminal wins) and children
 * always end before parents. Spans registered after terminal begins are ended immediately so
 * nothing leaks.
 */
final class InvocationLifecycle {
    enum State {
        OPEN,
        FINISHING,
        FINISHED
    }

    private final String generationId =
            java.util.UUID.randomUUID().toString().replace("-", "");
    private final Object registrationLock = new Object();
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final AtomicReference<TerminalOutcome> terminalOutcome = new AtomicReference<>();
    private final AtomicLong lateTerminalSignals = new AtomicLong();
    private final List<Span> chatAndToolSpans = new CopyOnWriteArrayList<>();
    private final List<Span> stepSpans = new CopyOnWriteArrayList<>();
    private final List<Span> agentSpans = new CopyOnWriteArrayList<>();
    private final AtomicReference<Span> entrySpan = new AtomicReference<>();
    private final List<SpanFinalizer> finalizers = new CopyOnWriteArrayList<>();

    String generationId() {
        return generationId;
    }

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
        synchronized (registrationLock) {
            if (acceptsRegistrations()) {
                entrySpan.set(Objects.requireNonNull(span, "entry span"));
                return;
            }
        }
        endImmediately(span);
    }

    void registerAgent(String agentId, Span span) {
        synchronized (registrationLock) {
            if (acceptsRegistrations()) {
                agentSpans.add(Objects.requireNonNull(span, "agent span"));
                return;
            }
        }
        endImmediately(span);
    }

    void registerStep(String agentId, String stepId, Span span) {
        synchronized (registrationLock) {
            if (acceptsRegistrations()) {
                stepSpans.add(Objects.requireNonNull(span, "step span"));
                return;
            }
        }
        endImmediately(span);
    }

    void registerChat(String stepId, Span span) {
        synchronized (registrationLock) {
            if (acceptsRegistrations()) {
                chatAndToolSpans.add(Objects.requireNonNull(span, "chat span"));
                return;
            }
        }
        endImmediately(span);
    }

    void registerTool(String stepId, String callId, Span span) {
        synchronized (registrationLock) {
            if (acceptsRegistrations()) {
                chatAndToolSpans.add(Objects.requireNonNull(span, "tool span"));
                return;
            }
        }
        endImmediately(span);
    }

    /**
     * Registers a metrics/attributes finalizer for a span (e.g. streamed chat output or tool
     * results). Finalizers run exactly once: at terminal before the span ends, or immediately
     * when registered after the terminal began. Cancellation travels upstream, so the
     * generation terminal may win over a child span's own cancel handler; finalizers make
     * that race lossless.
     */
    void registerFinalizer(Runnable finalizer) {
        SpanFinalizer wrapper = new SpanFinalizer(finalizer);
        synchronized (registrationLock) {
            if (acceptsRegistrations()) {
                finalizers.add(wrapper);
                return;
            }
        }
        wrapper.run();
    }

    private boolean acceptsRegistrations() {
        return state.get() == State.OPEN;
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
            // Holding the registration lock makes registrations race-free with termination:
            // a span is either in the lists before the scan or ended immediately afterwards.
            synchronized (registrationLock) {
                for (SpanFinalizer finalizer : finalizers) {
                    finalizer.run();
                }
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
                    endAttributeQuietly(
                            entry,
                            () -> {
                                entry.setAttribute(ClsFields.TURN_COMPLETED, resultObserved);
                                entry.setAttribute(
                                        ClsFields.TURN_FINISH_REASON, outcome.finishReason());
                                entry.setAttribute(ClsFields.INCOMPLETE, outcome.incomplete());
                            });
                    endQuietly(entry, outcome, error);
                }
            }
        } finally {
            state.set(State.FINISHED);
        }
        return true;
    }

    private static final class SpanFinalizer {
        private final Runnable delegate;
        private final AtomicBoolean ran = new AtomicBoolean();

        private SpanFinalizer(Runnable delegate) {
            this.delegate = delegate;
        }

        private void run() {
            if (!ran.compareAndSet(false, true)) {
                return;
            }
            try {
                delegate.run();
            } catch (RuntimeException | StackOverflowError ignored) {
                // Metrics finalizers are best-effort; the span still ends.
            }
        }
    }

    private static void endImmediately(Span span) {
        try {
            span.end();
        } catch (RuntimeException | StackOverflowError ignored) {
            // Telemetry must never fail the host application.
        }
    }

    private static void endAttributeQuietly(Span span, Runnable attributes) {
        try {
            attributes.run();
        } catch (RuntimeException | StackOverflowError ignored) {
            // Terminal attributes are best-effort; the span still ends below.
        }
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
        } catch (RuntimeException | StackOverflowError ignored) {
            // Status marking is best-effort; the span must still end.
        } finally {
            endImmediately(span);
        }
    }
}
