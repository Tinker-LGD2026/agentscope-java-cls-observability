package io.github.tinkerlgd2026.agentscope.cls.internal;

import io.github.tinkerlgd2026.agentscope.cls.ClsTelemetrySnapshot;
import java.util.concurrent.atomic.AtomicLong;

public final class TelemetryCounters {
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong invalid = new AtomicLong();
    private final AtomicLong exportFailures = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public void accepted(long count) {
        accepted.addAndGet(count);
    }

    public void invalid(long count) {
        invalid.addAndGet(count);
    }

    public void exportFailed(long count) {
        exportFailures.addAndGet(count);
    }

    public void dropped(long count) {
        dropped.addAndGet(count);
    }

    public ClsTelemetrySnapshot snapshot() {
        return new ClsTelemetrySnapshot(
                accepted.get(), invalid.get(), exportFailures.get(), dropped.get());
    }
}
