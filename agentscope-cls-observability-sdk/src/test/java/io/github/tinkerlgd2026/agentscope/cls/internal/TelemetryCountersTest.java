package io.github.tinkerlgd2026.agentscope.cls.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tinkerlgd2026.agentscope.cls.ClsDetailedTelemetrySnapshot;
import io.github.tinkerlgd2026.agentscope.cls.ClsTelemetrySnapshot;
import org.junit.jupiter.api.Test;

class TelemetryCountersTest {

    @Test
    void mapsDetailedCountersToLegacySnapshotUnits() {
        TelemetryCounters counters = new TelemetryCounters();
        counters.accepted(3);
        counters.invalid(2);
        counters.dropped(5);
        counters.exportFailed(7);
        counters.exportBatchFailed(11);
        counters.captureFailed(13);
        counters.capacityDropped(17, 19);
        counters.duplicateMiddlewareDetected(23);
        counters.flushFailed(29);
        counters.shutdownFailed(31);

        assertThat(counters.detailedSnapshot(37, 41))
                .isEqualTo(
                        new ClsDetailedTelemetrySnapshot(
                                3, 2, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41));
        assertThat(counters.snapshot())
                .isEqualTo(new ClsTelemetrySnapshot(3, 2, 67, 5));
    }

    @Test
    void countersSaturateInsteadOfOverflowing() {
        TelemetryCounters counters = new TelemetryCounters();
        counters.accepted(Long.MAX_VALUE - 1);
        counters.accepted(10);
        counters.capacityDropped(Long.MAX_VALUE, Long.MAX_VALUE);
        counters.capacityDropped(1, 1);

        ClsDetailedTelemetrySnapshot snapshot = counters.detailedSnapshot(0, 0);
        assertThat(snapshot.acceptedSpans()).isEqualTo(Long.MAX_VALUE);
        assertThat(snapshot.capacityDroppedParts()).isEqualTo(Long.MAX_VALUE);
        assertThat(snapshot.capacityDroppedBytes()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsNegativeDeltasAndGaugeValues() {
        TelemetryCounters counters = new TelemetryCounters();
        assertThatThrownBy(() -> counters.accepted(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counters.capacityDropped(1, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(counters.detailedSnapshot(0, 0).capacityDroppedParts()).isZero();
        assertThatThrownBy(() -> counters.detailedSnapshot(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
