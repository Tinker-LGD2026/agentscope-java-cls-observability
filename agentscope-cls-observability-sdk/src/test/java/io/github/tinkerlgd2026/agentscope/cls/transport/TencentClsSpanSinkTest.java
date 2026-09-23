package io.github.tinkerlgd2026.agentscope.cls.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentcloudapi.cls.producer.Result;
import com.tencentcloudapi.cls.producer.common.Attempt;
import com.tencentcloudapi.cls.producer.common.LogItem;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TencentClsSpanSinkTest {

    @Test
    void consoleSinkWritesOneJsonObjectPerSpan() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ConsoleSpanSink sink =
                new ConsoleSpanSink(new ObjectMapper(), new PrintStream(output, true, StandardCharsets.UTF_8));

        sink.export(List.of(record("0123456789abcdef"))).toCompletableFuture().get(1, TimeUnit.SECONDS);

        String line = output.toString(StandardCharsets.UTF_8).trim();
        assertThat(new ObjectMapper().readTree(line).get("spanID").asText())
                .isEqualTo("0123456789abcdef");
    }

    @Test
    void cloudSinkConvertsEveryFieldToLogContent() throws Exception {
        FakeTransport transport = new FakeTransport();
        TencentClsSpanSink sink = new TencentClsSpanSink("topic-test", transport);

        CompletionStage<Void> export = sink.export(List.of(record("0123456789abcdef")));
        transport.completeSuccess();
        export.toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertThat(transport.submissions).hasSize(1);
        LogItem item = transport.submissions.get(0).get(0);
        assertThat(item.GetTime()).isEqualTo(1L);
        assertThat(item.mContents.getContentsList())
                .anySatisfy(
                        content -> {
                            assertThat(content.getKey()).isEqualTo("traceID");
                            assertThat(content.getValue())
                                    .isEqualTo("0123456789abcdef0123456789abcdef");
                        });
    }

    @Test
    void cloudSinkTreatsUnsuccessfulCallbackAsFailure() {
        FakeTransport transport = new FakeTransport();
        TencentClsSpanSink sink = new TencentClsSpanSink("topic-test", transport);
        CompletionStage<Void> export = sink.export(List.of(record("0123456789abcdef")));

        transport.completeFailure("AuthFailure", "details that must not include payload");

        assertThatThrownBy(() -> export.toCompletableFuture().join())
                .hasRootCauseMessage("CLS export failed: code=AuthFailure, attempts=1");
    }

    @Test
    void flushWaitsOnlyForSubmissionsAcceptedBeforeBarrier() throws Exception {
        FakeTransport transport = new FakeTransport();
        TencentClsSpanSink sink = new TencentClsSpanSink("topic-test", transport);
        sink.export(List.of(record("0123456789abcdef")));
        CompletionStage<Boolean> flush = sink.flush(Duration.ofSeconds(1));
        sink.export(List.of(record("fedcba9876543210")));

        transport.complete(0, success());

        assertThat(flush.toCompletableFuture().get(1, TimeUnit.SECONDS)).isTrue();
        assertThat(transport.pending.get(1)).isNotDone();
    }

    @Test
    void flushIncludesExportAlreadyAcceptedWhileTransportSubmissionIsBlocking() throws Exception {
        BlockingTransport transport = new BlockingTransport();
        TencentClsSpanSink sink =
                new TencentClsSpanSink("topic-test", transport, Duration.ofSeconds(1));
        AtomicReference<CompletionStage<Void>> export = new AtomicReference<>();
        CompletableFuture<Void> caller =
                CompletableFuture.runAsync(
                        () -> export.set(sink.export(List.of(record("0123456789abcdef")))));

        assertThat(transport.entered.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(sink.flush(Duration.ofMillis(50)).toCompletableFuture().get(1, TimeUnit.SECONDS))
                .isFalse();
        transport.release.countDown();
        caller.get(1, TimeUnit.SECONDS);
        transport.completion.complete(success());
        export.get().toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test
    void stalledExportsExpireAndDoNotRemainInFlushBarrier() throws Exception {
        FakeTransport transport = new FakeTransport();
        TencentClsSpanSink sink =
                new TencentClsSpanSink("topic-test", transport, Duration.ofMillis(30));

        CompletionStage<Void> export = sink.export(List.of(record("0123456789abcdef")));

        assertThatThrownBy(() -> export.toCompletableFuture().get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(sink.flush(Duration.ofSeconds(1)).toCompletableFuture().get(1, TimeUnit.SECONDS))
                .isTrue();
    }

    @Test
    void failedCloseCanBeRetried() {
        FakeTransport transport = new FakeTransport();
        transport.closeFailures = 1;
        TencentClsSpanSink sink = new TencentClsSpanSink("topic-test", transport);

        assertThatThrownBy(sink::close).isInstanceOf(IllegalStateException.class);
        sink.close();

        assertThat(transport.closeCalls).isEqualTo(2);
    }

    @Test
    void closeIsIdempotentAndRejectsNewExports() {
        FakeTransport transport = new FakeTransport();
        TencentClsSpanSink sink = new TencentClsSpanSink("topic-test", transport);

        sink.close();
        sink.close();

        assertThat(transport.closeCalls).isEqualTo(1);
        assertThatThrownBy(() -> sink.export(List.of(record("0123456789abcdef"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void splitsOversizedExportIntoPhysicalSlicesWithinThresholds() throws Exception {
        FakeTransport transport = new FakeTransport();
        TencentClsSpanSink sink =
                new TencentClsSpanSink(
                        "topic-test",
                        transport,
                        Duration.ofSeconds(5),
                        new TencentExportBatchPlanner(4 * 1024, 256));
        List<ClsSpanRecord> records = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            records.add(bigRecord("0123456789abcde" + index, 2 * 1024));
        }

        CompletionStage<Void> export = sink.export(records);
        assertThat(transport.submissions).hasSizeGreaterThan(1);
        transport.completeAllSuccessfully();
        export.toCompletableFuture().get(1, TimeUnit.SECONDS);

        int total = transport.submissions.stream().mapToInt(List::size).sum();
        assertThat(total).isEqualTo(5);
    }

    @Test
    void oneSliceFailureFailsExportButSiblingSlicesAreStillSubmitted() {
        FakeTransport transport = new FakeTransport();
        TencentClsSpanSink sink =
                new TencentClsSpanSink(
                        "topic-test",
                        transport,
                        Duration.ofSeconds(5),
                        new TencentExportBatchPlanner(4 * 1024, 1));
        List<ClsSpanRecord> records =
                List.of(record("0123456789abcdef"), record("fedcba9876543210"));

        CompletionStage<Void> export = sink.export(records);
        assertThat(transport.submissions).hasSize(2);
        transport.complete(0, failure("InternalError", "boom"));
        transport.complete(1, success());

        assertThatThrownBy(() -> export.toCompletableFuture().join())
                .hasStackTraceContaining("CLS export failed");
    }

    @Test
    void allSlicesShareOneExportDeadline() {
        FakeTransport transport = new FakeTransport();
        TencentClsSpanSink sink =
                new TencentClsSpanSink(
                        "topic-test",
                        transport,
                        Duration.ofMillis(30),
                        new TencentExportBatchPlanner(4 * 1024, 1));
        CompletionStage<Void> export =
                sink.export(List.of(record("0123456789abcdef"), record("fedcba9876543210")));

        assertThatThrownBy(() -> export.toCompletableFuture().get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
        // Both slices were submitted before the shared deadline expired.
        assertThat(transport.submissions).hasSize(2);
    }

    private static ClsSpanRecord bigRecord(String spanId, int payloadBytes) {
        return new ClsSpanRecord(
                "0123456789abcdef0123456789abcdef",
                spanId,
                "",
                "chat model-x",
                "client",
                "1000000000",
                "2000000000",
                "1000000000",
                "OK",
                "",
                "{\"gen_ai.span.kind\":\"chat\",\"gen_ai.operation.name\":\"chat\",\"gen_ai.agent.type\":\"agentscope-java\",\"gen_ai.session.id\":\"s\",\"gen_ai.turn.id\":\"t\",\"gen_ai.user.id\":\"u\",\"gen_ai.user.name\":\"U\",\"big\":\""
                        + "x".repeat(payloadBytes)
                        + "\"}",
                "{\"service.name\":\"svc\",\"host.name\":\"host\"}",
                "",
                "[]",
                "[]");
    }

    private static Result failure(String code, String message) {
        return new Result(
                false,
                List.of(new Attempt(false, "request", code, message, System.currentTimeMillis())),
                1);
    }

    private static ClsSpanRecord record(String spanId) {
        return new ClsSpanRecord(
                "0123456789abcdef0123456789abcdef",
                spanId,
                "",
                "chat model-x",
                "client",
                "1000000000",
                "2000000000",
                "1000000000",
                "OK",
                "",
                "{\"gen_ai.span.kind\":\"chat\",\"gen_ai.operation.name\":\"chat\",\"gen_ai.agent.type\":\"agentscope-java\",\"gen_ai.session.id\":\"s\",\"gen_ai.turn.id\":\"t\",\"gen_ai.user.id\":\"u\",\"gen_ai.user.name\":\"U\"}",
                "{\"service.name\":\"svc\",\"host.name\":\"host\"}",
                "",
                "[]",
                "[]");
    }

    private static Result success() {
        return new Result(
                true,
                List.of(new Attempt(true, "request", "", "", System.currentTimeMillis())),
                1);
    }

    private static final class BlockingTransport implements ClsAsyncTransport {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CompletableFuture<Result> completion = new CompletableFuture<>();

        @Override
        public CompletionStage<Result> putLogs(String topicId, List<LogItem> items) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(exception);
            }
            return completion;
        }

        @Override
        public void close(Duration timeout) {}
    }

    private static final class FakeTransport implements ClsAsyncTransport {
        private final List<List<LogItem>> submissions = new ArrayList<>();
        private final List<CompletableFuture<Result>> pending = new ArrayList<>();
        private int closeCalls;
        private int closeFailures;

        @Override
        public CompletionStage<Result> putLogs(String topicId, List<LogItem> items) {
            submissions.add(List.copyOf(items));
            CompletableFuture<Result> future = new CompletableFuture<>();
            pending.add(future);
            return future;
        }

        @Override
        public void close(Duration timeout) {
            closeCalls++;
            if (closeFailures > 0) {
                closeFailures--;
                throw new IllegalStateException("scripted close failure");
            }
        }

        void completeSuccess() {
            complete(0, success());
        }

        void completeFailure(String code, String message) {
            complete(
                    0,
                    new Result(
                            false,
                            List.of(
                                    new Attempt(
                                            false,
                                            "request",
                                            code,
                                            message,
                                            System.currentTimeMillis())),
                            1));
        }

        void complete(int index, Result result) {
            pending.get(index).complete(result);
        }

        void completeAllSuccessfully() {
            for (CompletableFuture<Result> future : pending) {
                if (!future.isDone()) {
                    future.complete(success());
                }
            }
        }
    }
}
