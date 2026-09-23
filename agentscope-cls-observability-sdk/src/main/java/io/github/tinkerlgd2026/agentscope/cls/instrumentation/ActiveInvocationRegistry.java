package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.github.tinkerlgd2026.agentscope.cls.internal.DeadlineBudget;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * Tracks active top-level subscriptions (leases, not generations). DRAINING rejects new
 * roots but lets existing leases continue and rotate; FREEZE forbids rotation everywhere.
 */
final class ActiveInvocationRegistry {
    enum Phase {
        RUNNING,
        DRAINING,
        FROZEN
    }

    private final Set<InvocationLease> leases = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);

    Phase phase() {
        return phase.get();
    }

    int activeLeases() {
        return leases.size();
    }

    boolean registerRoot(InvocationLease lease) {
        if (phase.get() != Phase.RUNNING) {
            return false;
        }
        leases.add(lease);
        // Re-check after adding to close the drain race.
        if (phase.get() != Phase.RUNNING) {
            leases.remove(lease);
            return false;
        }
        return true;
    }

    void unregister(InvocationLease lease) {
        leases.remove(lease);
        synchronized (leases) {
            leases.notifyAll();
        }
    }

    void drain() {
        phase.compareAndSet(Phase.RUNNING, Phase.DRAINING);
    }

    void freeze() {
        phase.set(Phase.FROZEN);
        for (InvocationLease lease : leases) {
            lease.freeze();
        }
    }

    /** Allows rotation only for already registered leases while DRAINING. */
    @Nullable InvocationLifecycle allowRotate(InvocationLease lease) {
        if (phase.get() == Phase.FROZEN || !leases.contains(lease)) {
            return null;
        }
        return lease.rotateFrom(lease.current());
    }

    boolean awaitQuiescence(DeadlineBudget budget) {
        while (leases.isEmpty() == false && !budget.expired()) {
            synchronized (leases) {
                if (!leases.isEmpty()) {
                    try {
                        leases.wait(10);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return leases.isEmpty();
    }
}
