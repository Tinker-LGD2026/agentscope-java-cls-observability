package io.github.tinkerlgd2026.agentscope.cls.exporter;

import io.github.tinkerlgd2026.agentscope.cls.internal.CaptureMemoryPool;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.schema.Utf8LogItemSizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded queue of encoded span records. Records are stored with exact byte reservations
 * from the shared capture memory pool, never as full SpanData objects.
 */
public final class EncodedSpanQueue implements AutoCloseable {
    private final CaptureMemoryPool pool;
    private final int maxRecords;
    private final ArrayDeque<Entry> pending = new ArrayDeque<>();
    private long usedBytes;
    private boolean closed;

    public EncodedSpanQueue(CaptureMemoryPool pool, int maxRecords) {
        if (pool == null || maxRecords <= 0) {
            throw new IllegalArgumentException("pool and a positive record limit are required");
        }
        this.pool = pool;
        this.maxRecords = maxRecords;
    }

    public synchronized boolean offer(ClsSpanRecord record) {
        Objects.requireNonNull(record, "record");
        if (closed || pending.size() >= maxRecords) {
            return false;
        }
        long bytes = Utf8LogItemSizer.size(record.fields());
        Optional<CaptureMemoryPool.Reservation> reservation = pool.reserve(bytes);
        if (reservation.isEmpty()) {
            return false;
        }
        pending.addLast(new Entry(record, bytes, reservation.orElseThrow()));
        usedBytes += bytes;
        return true;
    }

    /**
     * Pops up to {@code maxCount} records within {@code maxBytes}, always returning at least
     * one record when the queue is non-empty. Reservations stay held until the returned batch
     * is closed. Returns null when the queue is empty.
     */
    public synchronized Batch pollBatch(int maxCount, long maxBytes) {
        if (pending.isEmpty()) {
            return null;
        }
        List<Entry> entries = new ArrayList<>();
        long bytes = 0;
        while (!pending.isEmpty() && entries.size() < maxCount) {
            Entry next = pending.peekFirst();
            if (!entries.isEmpty() && bytes + next.bytes > maxBytes) {
                break;
            }
            entries.add(pending.pollFirst());
            bytes += next.bytes;
            usedBytes -= next.bytes;
        }
        if (entries.isEmpty()) {
            return null;
        }
        return new Batch(entries, bytes);
    }

    public synchronized int size() {
        return pending.size();
    }

    public synchronized long usedBytes() {
        return usedBytes;
    }

    @Override
    public synchronized void close() {
        closed = true;
        Entry entry;
        while ((entry = pending.pollFirst()) != null) {
            usedBytes -= entry.bytes;
            entry.reservation.close();
        }
    }

    private static final class Entry {
        private final ClsSpanRecord record;
        private final long bytes;
        private final CaptureMemoryPool.Reservation reservation;

        private Entry(ClsSpanRecord record, long bytes, CaptureMemoryPool.Reservation reservation) {
            this.record = record;
            this.bytes = bytes;
            this.reservation = reservation;
        }
    }

    /** A drained batch whose memory reservations are released exactly once on close. */
    public static final class Batch implements AutoCloseable {
        private final List<Entry> entries;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();
        private volatile boolean abandoned;

        private Batch(List<Entry> entries, long bytes) {
            this.entries = entries;
            this.bytes = bytes;
        }

        public List<ClsSpanRecord> records() {
            return entries.stream().map(entry -> entry.record).toList();
        }

        public long bytes() {
            return bytes;
        }

        /** Marks the batch as abandoned by the waiter; a late completion must not count it. */
        public void abandon() {
            abandoned = true;
            close();
        }

        public boolean wasAbandoned() {
            return abandoned;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                for (Entry entry : entries) {
                    entry.reservation.close();
                }
            }
        }
    }
}
