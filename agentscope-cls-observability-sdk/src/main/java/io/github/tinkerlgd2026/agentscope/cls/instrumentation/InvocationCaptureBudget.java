package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.github.tinkerlgd2026.agentscope.cls.internal.CaptureMemoryPool;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Per-invocation view over the SDK-wide capture memory pool. */
final class InvocationCaptureBudget implements AutoCloseable {
    private final CaptureMemoryPool pool;
    private final long limitBytes;
    private final boolean unbounded;
    private final List<Reservation> reservations = new ArrayList<>();
    private long usedBytes;
    private boolean closed;

    InvocationCaptureBudget(CaptureMemoryPool pool, long limitBytes) {
        if (pool == null || limitBytes <= 0 || limitBytes > pool.capacityBytes()) {
            throw new IllegalArgumentException("valid pool and invocation capture limit are required");
        }
        this.pool = pool;
        this.limitBytes = limitBytes;
        this.unbounded = false;
    }

    private InvocationCaptureBudget() {
        this.pool = null;
        this.limitBytes = Long.MAX_VALUE;
        this.unbounded = true;
    }

    static InvocationCaptureBudget unbounded() {
        return new InvocationCaptureBudget();
    }

    synchronized Optional<Reservation> reserve(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("reservation bytes must be non-negative");
        }
        if (closed || bytes > limitBytes - usedBytes) {
            return Optional.empty();
        }
        CaptureMemoryPool.Reservation pooled = null;
        if (!unbounded) {
            Optional<CaptureMemoryPool.Reservation> candidate = pool.reserve(bytes);
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            pooled = candidate.orElseThrow();
        }
        Reservation reservation = new Reservation(this, pooled, bytes);
        reservations.add(reservation);
        usedBytes += bytes;
        return Optional.of(reservation);
    }

    synchronized long usedBytes() {
        return usedBytes;
    }

    private synchronized void release(Reservation reservation) {
        if (!reservations.remove(reservation)) {
            return;
        }
        usedBytes -= reservation.bytes;
        if (reservation.pooled != null) {
            reservation.pooled.close();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Reservation reservation : List.copyOf(reservations)) {
            reservation.close();
        }
    }

    static final class Reservation implements AutoCloseable {
        private final InvocationCaptureBudget owner;
        private final CaptureMemoryPool.Reservation pooled;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Reservation(
                InvocationCaptureBudget owner,
                CaptureMemoryPool.Reservation pooled,
                long bytes) {
            this.owner = owner;
            this.pooled = pooled;
            this.bytes = bytes;
        }

        long bytes() {
            return bytes;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                owner.release(this);
            }
        }
    }
}
