package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvocationLeaseTest {

    @Test
    void rotateFinishesOldGenerationExactlyOnceAndInstallsNew() {
        InvocationLifecycle first = new InvocationLifecycle();
        InvocationLease lease = new InvocationLease(first);
        // The old generation is already terminated (await timeout) before the late result.
        first.terminal(TerminalOutcome.AWAIT_TIMEOUT, false, null);

        InvocationLifecycle rotated = lease.rotateFrom(first);

        assertThat(rotated).isNotNull().isNotSameAs(first);
        assertThat(first.state()).isEqualTo(InvocationLifecycle.State.FINISHED);
        assertThat(first.terminalOutcome()).isEqualTo(TerminalOutcome.AWAIT_TIMEOUT);
        assertThat(lease.current()).isSameAs(rotated);
    }

    @Test
    void rotateOnOpenGenerationIsNoop() {
        InvocationLifecycle first = new InvocationLifecycle();
        InvocationLease lease = new InvocationLease(first);

        assertThat(lease.rotateFrom(first)).isNull();
        assertThat(lease.current()).isSameAs(first);
        assertThat(lease.rotationCount()).isZero();
    }

    @Test
    void lateResultsForSameOldGenerationReuseOneRotation() {
        InvocationLifecycle first = new InvocationLifecycle();
        InvocationLease lease = new InvocationLease(first);
        first.terminal(TerminalOutcome.AWAIT_TIMEOUT, false, null);

        InvocationLifecycle second = lease.rotateFrom(first);
        InvocationLifecycle again = lease.rotateFrom(first);

        assertThat(again).isSameAs(second);
        assertThat(lease.rotationCount()).isEqualTo(1);
    }

    @Test
    void rotateWithoutCurrentChangeIsNoop() {
        InvocationLifecycle first = new InvocationLifecycle();
        InvocationLease lease = new InvocationLease(first);
        first.terminal(TerminalOutcome.AWAIT_TIMEOUT, false, null);
        InvocationLifecycle second = lease.rotateFrom(first);
        // The replacement generation is still open: it may not rotate until terminated.
        assertThat(lease.rotateFrom(second)).isNull();
        assertThat(lease.rotationCount()).isEqualTo(1);
    }

    @Test
    void frozenLeaseForbidsRotationAndCountsLateEvents() {
        InvocationLifecycle first = new InvocationLifecycle();
        InvocationLease lease = new InvocationLease(first);

        lease.freeze();

        assertThat(lease.frozen()).isTrue();
        assertThat(lease.rotateFrom(first)).isNull();
        assertThat(lease.lateEventCount()).isEqualTo(1);
        assertThat(first.state()).isEqualTo(InvocationLifecycle.State.OPEN);
    }
}
