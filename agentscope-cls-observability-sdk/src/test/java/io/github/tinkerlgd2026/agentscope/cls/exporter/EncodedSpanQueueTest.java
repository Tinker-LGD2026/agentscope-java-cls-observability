package io.github.tinkerlgd2026.agentscope.cls.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinkerlgd2026.agentscope.cls.internal.CaptureMemoryPool;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.schema.Utf8LogItemSizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class EncodedSpanQueueTest {

    @Test
    void offersRecordsWithExactByteReservations() {
        CaptureMemoryPool pool = new CaptureMemoryPool(1 << 20);
        EncodedSpanQueue queue = new EncodedSpanQueue(pool, 16);
        ClsSpanRecord record = record("span-1");

        assertThat(queue.offer(record)).isTrue();
        assertThat(queue.size()).isEqualTo(1);
        assertThat(queue.usedBytes()).isEqualTo(Utf8LogItemSizer.size(record.fields()));
        assertThat(pool.usedBytes()).isEqualTo(queue.usedBytes());
    }

    @Test
    void rejectsWhenRecordCountIsFull() {
        CaptureMemoryPool pool = new CaptureMemoryPool(1 << 20);
        EncodedSpanQueue queue = new EncodedSpanQueue(pool, 1);

        assertThat(queue.offer(record("a"))).isTrue();
        assertThat(queue.offer(record("b"))).isFalse();
        assertThat(queue.size()).isEqualTo(1);
        assertThat(pool.usedBytes()).isEqualTo(queue.usedBytes());
    }

    @Test
    void rejectsWhenMemoryPoolIsExhausted() {
        ClsSpanRecord record = record("big");
        CaptureMemoryPool pool =
                new CaptureMemoryPool(Utf8LogItemSizer.size(record.fields()) - 1);
        EncodedSpanQueue queue = new EncodedSpanQueue(pool, 16);

        assertThat(queue.offer(record)).isFalse();
        assertThat(pool.usedBytes()).isZero();
    }

    @Test
    void pollsBatchWithinCountAndByteLimitsWithAtLeastOneRecord() {
        CaptureMemoryPool pool = new CaptureMemoryPool(1 << 20);
        EncodedSpanQueue queue = new EncodedSpanQueue(pool, 16);
        ClsSpanRecord first = record("a");
        ClsSpanRecord second = record("b");
        queue.offer(first);
        queue.offer(second);

        EncodedSpanQueue.Batch batch = queue.pollBatch(1, Long.MAX_VALUE);
        assertThat(batch.records()).containsExactly(first);
        assertThat(queue.size()).isEqualTo(1);
        // In-flight batch keeps its reservation until closed.
        assertThat(pool.usedBytes()).isGreaterThan(0);
        batch.close();
        batch.close();
        assertThat(pool.usedBytes())
                .isEqualTo(Utf8LogItemSizer.size(second.fields()));

        EncodedSpanQueue.Batch tight = queue.pollBatch(16, 1);
        assertThat(tight.records()).containsExactly(second);
        tight.close();
        assertThat(pool.usedBytes()).isZero();
    }

    @Test
    void emptyQueueHasNoBatch() {
        EncodedSpanQueue queue = new EncodedSpanQueue(new CaptureMemoryPool(1 << 20), 4);
        assertThat(queue.pollBatch(1, Long.MAX_VALUE)).isNull();
    }

    @Test
    void closeReleasesQueuedReservations() {
        CaptureMemoryPool pool = new CaptureMemoryPool(1 << 20);
        EncodedSpanQueue queue = new EncodedSpanQueue(pool, 4);
        queue.offer(record("a"));
        queue.offer(record("b"));

        queue.close();

        assertThat(queue.size()).isZero();
        assertThat(pool.usedBytes()).isZero();
        assertThat(queue.offer(record("c"))).isFalse();
    }

    private static ClsSpanRecord record(String marker) {
        return new ClsSpanRecord(
                "0123456789abcdef0123456789abcdef",
                "0123456789abcdef",
                "",
                "chat " + marker,
                "client",
                "100",
                "200",
                "100",
                "OK",
                "",
                "{\"gen_ai.span.kind\":\"chat\"}",
                "{\"service.name\":\"svc\",\"host.name\":\"host\"}",
                "",
                "[]",
                "[]");
    }
}
