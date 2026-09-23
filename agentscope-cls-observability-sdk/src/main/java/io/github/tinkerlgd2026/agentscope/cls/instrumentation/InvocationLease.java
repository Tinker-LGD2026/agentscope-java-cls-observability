package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Mutable holder of the current generation for one top-level subscription. Rotation is
 * atomic and exactly-once per old generation; a frozen lease forbids rotation and only
 * counts late events.
 */
final class InvocationLease {
    private final AtomicReference<InvocationLifecycle> current;
    private final AtomicBoolean frozen = new AtomicBoolean();
    private final AtomicLong rotationCount = new AtomicLong();
    private final AtomicLong lateEvents = new AtomicLong();

    InvocationLease(InvocationLifecycle initial) {
        current = new AtomicReference<>(Objects.requireNonNull(initial, "initial generation"));
    }

    InvocationLifecycle current() {
        return current.get();
    }

    boolean frozen() {
        return frozen.get();
    }

    long rotationCount() {
        return rotationCount.get();
    }

    long lateEventCount() {
        return lateEvents.get();
    }

    void noteLateEvent() {
        lateEvents.incrementAndGet();
    }

    /**
     * Rotates away from a generation that has already been terminated (for example by an
     * await timeout): installs a fresh open generation. Rotating the same terminated
     * generation twice reuses the existing replacement; rotating an open generation is a
     * no-op. Returns null when the lease is frozen.
     */
    @Nullable InvocationLifecycle rotateFrom(InvocationLifecycle expectedOld) {
        Objects.requireNonNull(expectedOld, "expectedOld");
        if (frozen.get()) {
            lateEvents.incrementAndGet();
            return null;
        }
        if (expectedOld.state() == InvocationLifecycle.State.OPEN) {
            return null;
        }
        if (current.get() != expectedOld) {
            return current.get();
        }
        InvocationLifecycle next = new InvocationLifecycle();
        if (!current.compareAndSet(expectedOld, next)) {
            return current.get() == expectedOld ? null : current.get();
        }
        if (frozen.get()) {
            // Freeze raced with rotation: terminate the new generation immediately as a
            // frozen late artifact.
            lateEvents.incrementAndGet();
            next.terminal(TerminalOutcome.SHUTDOWN, false, null);
            return null;
        }
        rotationCount.incrementAndGet();
        return next;
    }

    void freeze() {
        frozen.set(true);
    }
}
