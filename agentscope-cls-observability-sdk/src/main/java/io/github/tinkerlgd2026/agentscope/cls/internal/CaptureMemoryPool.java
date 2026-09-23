package io.github.tinkerlgd2026.agentscope.cls.internal;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** SDK-wide exact byte reservation pool for bounded capture and export queues. */
public final class CaptureMemoryPool {
    private final long capacityBytes;
    private final AtomicLong usedBytes = new AtomicLong();

    public CaptureMemoryPool(long capacityBytes) {
        if (capacityBytes <= 0) {
            throw new IllegalArgumentException("capture memory capacity must be positive");
        }
        this.capacityBytes = capacityBytes;
    }

    public Optional<Reservation> reserve(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("reservation bytes must be non-negative");
        }
        if (bytes > capacityBytes) {
            return Optional.empty();
        }
        while (true) {
            long current = usedBytes.get();
            if (bytes > capacityBytes - current) {
                return Optional.empty();
            }
            if (usedBytes.compareAndSet(current, current + bytes)) {
                return Optional.of(new Reservation(this, bytes));
            }
        }
    }

    public long capacityBytes() {
        return capacityBytes;
    }

    public long usedBytes() {
        return usedBytes.get();
    }

    public long availableBytes() {
        return capacityBytes - usedBytes.get();
    }

    private void release(long bytes) {
        long remaining = usedBytes.addAndGet(-bytes);
        if (remaining < 0) {
            usedBytes.addAndGet(bytes);
            throw new IllegalStateException("capture memory reservation accounting underflow");
        }
    }

    public static final class Reservation implements AutoCloseable {
        private final CaptureMemoryPool owner;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Reservation(CaptureMemoryPool owner, long bytes) {
            this.owner = owner;
            this.bytes = bytes;
        }

        public long bytes() {
            return bytes;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                owner.release(bytes);
            }
        }
    }
}
