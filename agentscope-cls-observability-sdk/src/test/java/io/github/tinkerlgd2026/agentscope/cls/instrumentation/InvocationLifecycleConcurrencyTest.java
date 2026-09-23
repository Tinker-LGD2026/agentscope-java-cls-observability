package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvocationLifecycleConcurrencyTest {
    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider =
                SdkTracerProvider.builder()
                        .setResource(
                                Resource.builder()
                                        .put("service.name", "svc")
                                        .put("host.name", "host")
                                        .build())
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void spansRegisteredDuringOrAfterTerminalAreStillEnded() {
        InvocationLifecycle lifecycle = new InvocationLifecycle();
        lifecycle.registerEntry(span("entry"));
        lifecycle.terminal(TerminalOutcome.CANCELLED, false, null);

        // A span registered after terminal must not leak: it is ended immediately.
        Span late = span("late-chat");
        lifecycle.registerChat("step-late", late);

        assertThat(exporter.getFinishedSpanItems().stream().map(s -> s.getName()).toList())
                .contains("late-chat");
    }

    @Test
    void concurrentTerminalsEndEverySpanExactlyOnce() throws Exception {
        InvocationLifecycle lifecycle = new InvocationLifecycle();
        lifecycle.registerEntry(span("entry"));
        for (int index = 0; index < 20; index++) {
            lifecycle.registerChat("step", span("chat-" + index));
        }
        AtomicInteger terminalWinners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[8];
        for (int index = 0; index < threads.length; index++) {
            threads[index] =
                    new Thread(
                            () -> {
                                await(start);
                                if (lifecycle.terminal(TerminalOutcome.ERROR, false, null)) {
                                    terminalWinners.incrementAndGet();
                                }
                            });
            threads[index].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(terminalWinners.get()).isEqualTo(1);
        assertThat(lifecycle.lateTerminalSignals()).isEqualTo(7);
        assertThat(exporter.getFinishedSpanItems()).hasSize(21);
    }

    @Test
    void attributeFailureDuringTerminalDoesNotSkipRemainingSpans() {
        InvocationLifecycle lifecycle = new InvocationLifecycle();
        lifecycle.registerEntry(span("entry"));
        lifecycle.registerChat("step", span("chat-a"));
        lifecycle.registerChat("step", span("chat-b"));

        lifecycle.terminal(TerminalOutcome.CANCELLED, false, null);

        assertThat(exporter.getFinishedSpanItems()).hasSize(3);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private Span span(String name) {
        return provider.get("test").spanBuilder(name).startSpan();
    }
}
