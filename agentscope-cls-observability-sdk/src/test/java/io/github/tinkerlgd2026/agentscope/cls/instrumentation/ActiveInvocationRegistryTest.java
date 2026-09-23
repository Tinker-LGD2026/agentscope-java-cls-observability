package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinkerlgd2026.agentscope.cls.internal.DeadlineBudget;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActiveInvocationRegistryTest {

    @Test
    void countsLeasesNotGenerations() {
        ActiveInvocationRegistry registry = new ActiveInvocationRegistry();
        InvocationLease lease = new InvocationLease(new InvocationLifecycle());

        assertThat(registry.registerRoot(lease)).isTrue();
        assertThat(registry.activeLeases()).isEqualTo(1);

        lease.rotateFrom(lease.current());
        assertThat(registry.activeLeases()).isEqualTo(1);

        registry.unregister(lease);
        assertThat(registry.activeLeases()).isZero();
        registry.unregister(lease);
        assertThat(registry.activeLeases()).isZero();
    }

    @Test
    void drainingRejectsNewRootsButAllowsExistingLeaseRotation() {
        ActiveInvocationRegistry registry = new ActiveInvocationRegistry();
        InvocationLease existing = new InvocationLease(new InvocationLifecycle());
        registry.registerRoot(existing);

        registry.drain();

        assertThat(registry.registerRoot(new InvocationLease(new InvocationLifecycle())))
                .isFalse();
        // Rotation happens after the current generation terminated (await timeout).
        existing.current().terminal(TerminalOutcome.AWAIT_TIMEOUT, false, null);
        InvocationLifecycle rotated = registry.allowRotate(existing);
        assertThat(rotated).isNotNull();
        assertThat(registry.activeLeases()).isEqualTo(1);
    }

    @Test
    void freezeForbidsRotationAtomicallyAcrossLeases() {
        ActiveInvocationRegistry registry = new ActiveInvocationRegistry();
        InvocationLease first = new InvocationLease(new InvocationLifecycle());
        InvocationLease second = new InvocationLease(new InvocationLifecycle());
        registry.registerRoot(first);
        registry.registerRoot(second);

        registry.freeze();

        assertThat(registry.allowRotate(first)).isNull();
        assertThat(registry.allowRotate(second)).isNull();
        assertThat(first.frozen()).isTrue();
        assertThat(second.frozen()).isTrue();
    }

    @Test
    void awaitQuiescenceCompletesWhenActiveReachesZero() {
        ActiveInvocationRegistry registry = new ActiveInvocationRegistry();
        InvocationLease lease = new InvocationLease(new InvocationLifecycle());
        registry.registerRoot(lease);
        DeadlineBudget budget = DeadlineBudget.start(Duration.ofMillis(100));

        assertThat(registry.awaitQuiescence(budget)).isFalse();
        registry.unregister(lease);
        assertThat(registry.awaitQuiescence(DeadlineBudget.start(Duration.ofSeconds(5))))
                .isTrue();
    }

    @Test
    void expiredBudgetWithActiveLeaseReturnsFalse() {
        ActiveInvocationRegistry registry = new ActiveInvocationRegistry();
        registry.registerRoot(new InvocationLease(new InvocationLifecycle()));
        java.util.concurrent.atomic.AtomicLong ticker = new java.util.concurrent.atomic.AtomicLong();
        DeadlineBudget expired =
                DeadlineBudget.start(Duration.ofNanos(10), ticker::get);
        ticker.set(1_000L);

        assertThat(registry.awaitQuiescence(expired)).isFalse();
    }
}
