package io.github.tinkerlgd2026.agentscope.cls.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeadlineBudgetTest {

    @Test
    void computesRemainingFromOneMonotonicAbsoluteDeadline() {
        AtomicLong ticker = new AtomicLong(1_000_000L);
        DeadlineBudget budget = DeadlineBudget.start(Duration.ofMillis(10), ticker::get);

        assertThat(budget.deadlineNanos()).isEqualTo(11_000_000L);
        assertThat(budget.remaining()).isEqualTo(Duration.ofMillis(10));
        ticker.addAndGet(4_500_000L);
        assertThat(budget.remaining()).isEqualTo(Duration.ofNanos(5_500_000L));
        ticker.set(20_000_000L);
        assertThat(budget.remaining()).isZero();
        assertThat(budget.expired()).isTrue();
    }

    @Test
    void remainsCorrectWhenNanoTimeWraps() {
        AtomicLong ticker = new AtomicLong(Long.MAX_VALUE - 5L);
        DeadlineBudget budget = DeadlineBudget.start(Duration.ofNanos(10L), ticker::get);

        assertThat(budget.deadlineNanos()).isEqualTo(Long.MIN_VALUE + 4L);
        assertThat(budget.remaining()).isEqualTo(Duration.ofNanos(10L));
        ticker.set(Long.MIN_VALUE + 1L);
        assertThat(budget.remaining()).isEqualTo(Duration.ofNanos(3L));
        ticker.set(Long.MIN_VALUE + 4L);
        assertThat(budget.expired()).isTrue();
    }

    @Test
    void rejectsNullZeroAndNegativeDurations() {
        assertThatThrownBy(() -> DeadlineBudget.start(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeadlineBudget.start(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeadlineBudget.start(Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
