package io.github.tinkerlgd2026.agentscope.cls.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class CaptureMemoryPoolTest {

    @Test
    void reservesExactlyAndReleasesIdempotently() {
        CaptureMemoryPool pool = new CaptureMemoryPool(100);
        CaptureMemoryPool.Reservation first = pool.reserve(60).orElseThrow();

        assertThat(pool.usedBytes()).isEqualTo(60);
        assertThat(pool.availableBytes()).isEqualTo(40);
        assertThat(pool.reserve(41)).isEmpty();

        first.close();
        first.close();
        assertThat(pool.usedBytes()).isZero();
        assertThat(pool.availableBytes()).isEqualTo(100);
    }

    @Test
    void concurrentReservationsNeverExceedCapacity() throws Exception {
        CaptureMemoryPool pool = new CaptureMemoryPool(1_000);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CaptureMemoryPool.Reservation>> futures = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                futures.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    return pool.reserve(25).orElse(null);
                                }));
            }
            start.countDown();
            List<CaptureMemoryPool.Reservation> acquired = new ArrayList<>();
            for (Future<CaptureMemoryPool.Reservation> future : futures) {
                CaptureMemoryPool.Reservation reservation = future.get();
                if (reservation != null) {
                    acquired.add(reservation);
                }
            }

            assertThat(acquired).hasSize(40);
            assertThat(pool.usedBytes()).isEqualTo(1_000);
            acquired.forEach(CaptureMemoryPool.Reservation::close);
            assertThat(pool.usedBytes()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidCapacityAndReservationSize() {
        assertThatThrownBy(() -> new CaptureMemoryPool(0))
                .isInstanceOf(IllegalArgumentException.class);
        CaptureMemoryPool pool = new CaptureMemoryPool(10);
        assertThatThrownBy(() -> pool.reserve(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(pool.reserve(0)).isPresent();
    }
}
