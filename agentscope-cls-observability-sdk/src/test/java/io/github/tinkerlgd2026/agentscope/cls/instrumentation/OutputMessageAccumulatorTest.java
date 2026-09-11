package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode.FULL;
import static io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode.HASH;
import static io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode.OFF;
import static io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode.TRUNCATE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.MessageCapturePolicy;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OutputMessageAccumulatorTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void preservesReasoningTextAndToolCallOrder() {
        OutputMessageAccumulator accumulator = accumulator(TRUNCATE, TRUNCATE, 4096);

        accumulator.accept(new ThinkingBlockStartEvent("reply", "think-1"), 1_000_000L);
        accumulator.accept(new ThinkingBlockDeltaEvent("reply", "think-1", "plan"), 2_000_000L);
        accumulator.accept(new ThinkingBlockEndEvent("reply", "think-1"), 3_000_000L);
        accumulator.accept(new TextBlockStartEvent("reply", "text-1"), 4_000_000L);
        accumulator.accept(new TextBlockDeltaEvent("reply", "text-1", "answer"), 5_000_000L);
        accumulator.accept(new TextBlockEndEvent("reply", "text-1"), 6_000_000L);
        accumulator.accept(new ToolCallStartEvent("reply", "call-1", "search"), 7_000_000L);
        accumulator.accept(
                new ToolCallDeltaEvent("reply", "call-1", "search", "{\"q\":\"weather\"}"),
                8_000_000L);
        accumulator.accept(new ToolCallEndEvent("reply", "call-1", "search"), 9_000_000L);

        OutputMessageAccumulator.Result result = accumulator.finish(10_000_000L);
        String output = result.messages().orElseThrow().toString();

        assertThat(output.indexOf("reasoning")).isLessThan(output.indexOf("answer"));
        assertThat(output.indexOf("answer")).isLessThan(output.indexOf("tool_call"));
        assertThat(output).contains("search", "weather");
        assertThat(result.usedTools()).isTrue();
    }

    @Test
    void toleratesMissingAndDuplicateLifecycleEvents() {
        OutputMessageAccumulator accumulator = accumulator(TRUNCATE, TRUNCATE, 4096);

        accumulator.accept(
                new ThinkingBlockDeltaEvent("reply", "missing-start", "partial"), 2_000_000L);
        accumulator.accept(new ThinkingBlockStartEvent("reply", "duplicate"), 3_000_000L);
        accumulator.accept(new ThinkingBlockStartEvent("reply", "duplicate"), 4_000_000L);
        accumulator.accept(new ThinkingBlockEndEvent("reply", "missing-block"), 5_000_000L);

        OutputMessageAccumulator.Result result = accumulator.finish(6_000_000L);

        assertThat(result.reasoning().malformedEventCount()).isEqualTo(3);
        assertThat(result.messages().orElseThrow().toString()).contains("partial");
    }

    @Test
    void calculatesReasoningAndResponseTimingsFromElapsedNanos() {
        OutputMessageAccumulator accumulator = accumulator(TRUNCATE, TRUNCATE, 4096);

        accumulator.accept(new ThinkingBlockStartEvent("reply", "think-1"), 10_000_000L);
        accumulator.accept(new ThinkingBlockDeltaEvent("reply", "think-1", "中"), 25_000_000L);
        accumulator.accept(new ThinkingBlockEndEvent("reply", "think-1"), 50_000_000L);
        accumulator.accept(new ThinkingBlockStartEvent("reply", "think-2"), 60_000_000L);
        accumulator.accept(new ThinkingBlockDeltaEvent("reply", "think-2", "plan"), 70_000_000L);
        accumulator.accept(new ThinkingBlockEndEvent("reply", "think-2"), 100_000_000L);
        accumulator.accept(new TextBlockDeltaEvent("reply", "text", "answer"), 120_000_000L);

        OutputMessageAccumulator.Result result = accumulator.finish(130_000_000L);

        assertThat(result.reasoning().present()).isTrue();
        assertThat(result.reasoning().blockCount()).isEqualTo(2);
        assertThat(result.reasoning().outputBytes())
                .isEqualTo("中plan".getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.reasoning().timeToFirstTokenMs()).hasValue(25L);
        assertThat(result.reasoning().durationMs()).isEqualTo(80L);
        assertThat(result.responseTimeToFirstTokenMs()).hasValue(120L);
    }

    @Test
    void reasoningOffKeepsMetricsButDropsReasoningContent() {
        OutputMessageAccumulator accumulator = accumulator(TRUNCATE, OFF, 4096);
        accumulator.accept(new ThinkingBlockDeltaEvent("reply", "think", "private"), 2_000_000L);
        accumulator.accept(new TextBlockDeltaEvent("reply", "text", "answer"), 3_000_000L);

        OutputMessageAccumulator.Result result = accumulator.finish(4_000_000L);
        String output = result.messages().orElseThrow().toString();

        assertThat(output).contains("answer").doesNotContain("private", "reasoning_hash");
        assertThat(result.reasoning().present()).isTrue();
        assertThat(result.reasoning().outputBytes()).isEqualTo(7);
    }

    @Test
    void reasoningHashCapturesCompleteDigestWithoutPlaintext() {
        OutputMessageAccumulator accumulator = accumulator(OFF, HASH, 4096);
        accumulator.accept(new ThinkingBlockDeltaEvent("reply", "think", "private"), 2_000_000L);
        accumulator.accept(new ThinkingBlockDeltaEvent("reply", "think", "-plan"), 3_000_000L);

        String output = accumulator.finish(4_000_000L).messages().orElseThrow().toString();

        assertThat(output)
                .contains("reasoning_hash", "sha256", "original_bytes")
                .doesNotContain("private", "plan");
    }

    @Test
    void finalBudgetKeepsShortAnswerAndDegradesLongReasoning() throws Exception {
        OutputMessageAccumulator accumulator = accumulator(FULL, FULL, 256);
        accumulator.accept(
                new ThinkingBlockDeltaEvent("reply", "think", "中".repeat(500)), 2_000_000L);
        accumulator.accept(
                new TextBlockDeltaEvent("reply", "text", "final-answer"), 3_000_000L);

        OutputMessageAccumulator.Result result = accumulator.finish(4_000_000L);
        JsonNode messages = result.messages().orElseThrow();

        assertThat(JSON.writeValueAsBytes(messages).length).isLessThanOrEqualTo(256);
        assertThat(messages.toString()).contains("final-answer");
        assertThat(result.reasoning().truncated()).isTrue();
    }

    @Test
    void hashRemainsStableWhenReasoningBudgetCausesMultipleRenderPasses() {
        OutputMessageAccumulator accumulator = accumulator(HASH, FULL, 256);
        accumulator.accept(
                new ThinkingBlockDeltaEvent("reply", "think", "中".repeat(500)), 1_000_000L);
        accumulator.accept(new TextBlockDeltaEvent("reply", "text", "answer"), 2_000_000L);

        String output = accumulator.finish(3_000_000L).messages().orElseThrow().toString();

        assertThat(output)
                .contains("0db52f4076c082518412afd3dd3576e2cb0c63703fd7fed5e23ade60efef31d9")
                .doesNotContain("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void toolArgumentsRespectOffAndCompleteHashModes() {
        OutputMessageAccumulator off = accumulator(OFF, OFF, 4096);
        off.accept(new ToolCallStartEvent("reply", "call-off", "search"), 1_000_000L);
        off.accept(new ToolCallDeltaEvent("reply", "call-off", "search", "private"), 2_000_000L);
        String offOutput = off.finish(3_000_000L).messages().orElseThrow().toString();
        assertThat(offOutput).contains("call-off", "search").doesNotContain("private");

        OutputMessageAccumulator hash = accumulator(HASH, OFF, 4096);
        hash.accept(new ToolCallStartEvent("reply", "call-hash", "search"), 1_000_000L);
        hash.accept(new ToolCallDeltaEvent("reply", "call-hash", "search", "hello"), 2_000_000L);
        hash.accept(new ToolCallDeltaEvent("reply", "call-hash", "search", " world"), 3_000_000L);
        String hashOutput = hash.finish(4_000_000L).messages().orElseThrow().toString();
        assertThat(hashOutput)
                .contains("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9")
                .contains("original_bytes", "11")
                .doesNotContain("hello", "world");
    }

    @Test
    void rejectsEventsAfterCloseAndCountsRepeatedEnds() {
        OutputMessageAccumulator accumulator = accumulator(TRUNCATE, TRUNCATE, 4096);
        accumulator.accept(new ThinkingBlockStartEvent("reply", "think"), 1_000_000L);
        accumulator.accept(new ThinkingBlockDeltaEvent("reply", "think", "first"), 2_000_000L);
        accumulator.accept(new ThinkingBlockEndEvent("reply", "think"), 3_000_000L);
        accumulator.accept(new ThinkingBlockDeltaEvent("reply", "think", "late"), 4_000_000L);
        accumulator.accept(new ThinkingBlockEndEvent("reply", "think"), 5_000_000L);
        accumulator.accept(new ToolCallEndEvent("reply", "orphan", "search"), 6_000_000L);

        OutputMessageAccumulator.Result result = accumulator.finish(7_000_000L);

        assertThat(result.messages().orElseThrow().toString()).contains("first").doesNotContain("late");
        assertThat(result.reasoning().malformedEventCount()).isEqualTo(3);
        assertThat(result.usedTools()).isFalse();
    }

    @Test
    void joinsSurrogatePairsSplitAcrossDeltasBeforeHashingAndBuffering() {
        String emoji = "😀";
        char high = emoji.charAt(0);
        char low = emoji.charAt(1);
        OutputMessageAccumulator visible = accumulator(TRUNCATE, TRUNCATE, 4096);
        visible.accept(new ThinkingBlockDeltaEvent("reply", "think", String.valueOf(high)), 1_000_000L);
        visible.accept(new ThinkingBlockDeltaEvent("reply", "think", String.valueOf(low)), 2_000_000L);
        OutputMessageAccumulator.Result visibleResult = visible.finish(3_000_000L);
        assertThat(visibleResult.messages().orElseThrow().toString()).contains(emoji);
        assertThat(visibleResult.reasoning().outputBytes()).isEqualTo(4);

        OutputMessageAccumulator hash = accumulator(OFF, HASH, 4096);
        hash.accept(new ThinkingBlockDeltaEvent("reply", "think", String.valueOf(high)), 1_000_000L);
        hash.accept(new ThinkingBlockDeltaEvent("reply", "think", String.valueOf(low)), 2_000_000L);
        assertThat(hash.finish(3_000_000L).messages().orElseThrow().toString())
                .contains("f0443a342c5ef54783a111b51ba56c938e474c32324d90c3a60c9c8e3a37e2d9");
    }

    @Test
    void appliesBudgetAfterSanitizationExpansionAndStillKeepsText() throws Exception {
        OutputMessageAccumulator accumulator = accumulator(FULL, FULL, 256);
        accumulator.accept(
                new ThinkingBlockDeltaEvent(
                        "reply", "think", "token=abcdef ".repeat(11)),
                1_000_000L);
        accumulator.accept(
                new TextBlockDeltaEvent("reply", "text", "final-answer"),
                2_000_000L);

        OutputMessageAccumulator.Result result = accumulator.finish(3_000_000L);
        JsonNode messages = result.messages().orElseThrow();

        assertThat(JSON.writeValueAsBytes(messages).length).isLessThanOrEqualTo(256);
        assertThat(messages.toString()).contains("final-answer").doesNotContain("token=abcdef");
        assertThat(result.reasoning().truncated()).isTrue();
    }

    @Test
    void redactsSecretsThatSpanReasoningDeltas() {
        OutputMessageAccumulator accumulator = accumulator(OFF, TRUNCATE, 4096);
        accumulator.accept(
                new ThinkingBlockDeltaEvent("reply", "think", "Bearer abc"),
                1_000_000L);
        accumulator.accept(
                new ThinkingBlockDeltaEvent("reply", "think", "def1234567890"),
                2_000_000L);

        String output = accumulator.finish(3_000_000L).messages().orElseThrow().toString();

        assertThat(output).contains("[REDACTED_SECRET]").doesNotContain("abcdef1234567890");
    }

    @Test
    void sameBlockIdInDifferentRepliesRemainsIndependent() {
        OutputMessageAccumulator accumulator = accumulator(TRUNCATE, TRUNCATE, 4096);
        accumulator.accept(
                new ThinkingBlockDeltaEvent("reply-1", "think", "first"),
                1_000_000L);
        accumulator.accept(
                new ThinkingBlockDeltaEvent("reply-2", "think", "second"),
                2_000_000L);

        OutputMessageAccumulator.Result result = accumulator.finish(3_000_000L);

        assertThat(result.reasoning().blockCount()).isEqualTo(2);
        assertThat(result.messages().orElseThrow().toString()).contains("first", "second");
    }

    @Test
    void handlesExactUtf8BudgetBoundariesWithoutSplittingCodePoints() {
        String at255 = "a".repeat(252) + "中";
        String at256 = "a".repeat(253) + "中";
        String at257 = "a".repeat(254) + "中";

        OutputMessageAccumulator.PayloadBuffer first =
                new OutputMessageAccumulator.PayloadBuffer(FULL, 256);
        first.append(at255);
        first.finish();
        assertThat(first.originalBytes()).isEqualTo(255);
        assertThat(first.text()).isEqualTo(at255);
        assertThat(first.truncated()).isFalse();

        OutputMessageAccumulator.PayloadBuffer second =
                new OutputMessageAccumulator.PayloadBuffer(FULL, 256);
        second.append(at256);
        second.finish();
        assertThat(second.originalBytes()).isEqualTo(256);
        assertThat(second.text()).isEqualTo(at256);
        assertThat(second.truncated()).isFalse();

        OutputMessageAccumulator.PayloadBuffer third =
                new OutputMessageAccumulator.PayloadBuffer(FULL, 256);
        third.append(at257);
        third.finish();
        assertThat(third.originalBytes()).isEqualTo(257);
        assertThat(third.text()).isEqualTo("a".repeat(254));
        assertThat(third.truncated()).isTrue();
        assertThat(third.text()).doesNotContain("�");
    }

    @Test
    void closesLoneHighSurrogateWithoutThrowingOrSplittingValidText() {
        OutputMessageAccumulator accumulator = accumulator(TRUNCATE, TRUNCATE, 4096);
        accumulator.accept(
                new ThinkingBlockDeltaEvent("reply", "think", String.valueOf('\uD83D')),
                1_000_000L);
        accumulator.accept(
                new TextBlockDeltaEvent("reply", "text", "answer"),
                2_000_000L);

        OutputMessageAccumulator.Result result = accumulator.finish(3_000_000L);

        assertThat(result.messages().orElseThrow().toString()).contains("answer");
        assertThat(result.reasoning().outputBytes()).isGreaterThan(0);
    }

    @Test
    void rejectsCrossTypeReuseOfTheSameReplyAndBlockKey() {
        OutputMessageAccumulator accumulator = accumulator(TRUNCATE, TRUNCATE, 4096);
        accumulator.accept(new ThinkingBlockStartEvent("reply", "same"), 1_000_000L);
        accumulator.accept(new TextBlockStartEvent("reply", "same"), 2_000_000L);
        accumulator.accept(new TextBlockDeltaEvent("reply", "same", "late-text"), 3_000_000L);
        accumulator.accept(new ThinkingBlockDeltaEvent("reply", "same", "plan"), 4_000_000L);

        OutputMessageAccumulator.Result result = accumulator.finish(5_000_000L);

        assertThat(result.messages().orElseThrow().toString())
                .contains("plan")
                .doesNotContain("late-text");
        assertThat(result.reasoning().malformedEventCount()).isEqualTo(2);
    }

    @Test
    void finishIsIdempotent() {
        OutputMessageAccumulator accumulator = accumulator(TRUNCATE, TRUNCATE, 4096);
        accumulator.accept(new ThinkingBlockDeltaEvent("reply", "think", "plan"), 2_000_000L);

        OutputMessageAccumulator.Result first = accumulator.finish(4_000_000L);
        OutputMessageAccumulator.Result second = accumulator.finish(9_000_000L);

        assertThat(second).isEqualTo(first);
    }

    private static OutputMessageAccumulator accumulator(
            ContentCaptureMode contentMode,
            ContentCaptureMode reasoningMode,
            int maxBytes) {
        return new OutputMessageAccumulator(
                JSON,
                new MessageCapturePolicy(JSON, contentMode, reasoningMode, maxBytes),
                contentMode,
                reasoningMode,
                maxBytes);
    }
}
