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
