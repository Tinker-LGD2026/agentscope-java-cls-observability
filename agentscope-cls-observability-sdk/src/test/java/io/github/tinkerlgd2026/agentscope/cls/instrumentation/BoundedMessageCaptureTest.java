package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.tinkerlgd2026.agentscope.cls.internal.CaptureMemoryPool;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BoundedMessageCaptureTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void combinesSemanticAndProviderCaptureUnderOneFinalBudget() throws Exception {
        CaptureMemoryPool pool = new CaptureMemoryPool(4096);
        try (InvocationCaptureBudget invocation = new InvocationCaptureBudget(pool, 1024)) {
            BoundedMessageCapture capture =
                    new BoundedMessageCapture(
                            JSON,
                            ContentCaptureMode.FULL,
                            ContentCaptureMode.FULL,
                            ContentCaptureMode.FULL,
                            256,
                            320);
            Map<String, Object> provider =
                    Map.of(
                            "mode", "full",
                            "complete", true,
                            "payload", "provider-private-" + "p".repeat(400),
                            "original_bytes", 417L,
                            "retained_bytes", 417L,
                            "truncated", false);

            BoundedMessageCapture.Result result =
                    capture.captureMessages(
                            List.of(
                                    Map.of(
                                            "role", "assistant",
                                            "parts", List.of(
                                                    Map.of("type", "reasoning", "content", "plan-" + "r".repeat(300)),
                                                    Map.of("type", "text", "content", "final-answer"),
                                                    Map.of("type", "provider_payload", "provider_payload", provider)))),
                            true,
                            invocation);

            assertThat(JSON.writeValueAsBytes(result.value().orElseThrow()).length)
                    .isLessThanOrEqualTo(320);
            assertThat(result.value().orElseThrow().toString())
                    .contains("final-answer")
                    .doesNotContain("provider-private", "plan-");
            assertThat(result.originalBytes()).isGreaterThanOrEqualTo(result.retainedBytes());
            assertThat(result.capacityDroppedParts()).isGreaterThan(0);
            assertThat(result.capacityDroppedBytes()).isGreaterThan(0);
            assertThat(invocation.usedBytes()).isEqualTo(result.retainedBytes());
        }
        assertThat(pool.usedBytes()).isZero();
    }

    @Test
    void fallsBackProviderFullToTruncateHashAndOff() {
        BoundedMessageCapture capture =
                new BoundedMessageCapture(
                        JSON,
                        ContentCaptureMode.OFF,
                        ContentCaptureMode.OFF,
                        ContentCaptureMode.FULL,
                        64,
                        256);
        Map<String, Object> provider =
                Map.of(
                        "mode", "full",
                        "complete", true,
                        "payload", "opaque-provider-payload-" + "x".repeat(500),
                        "original_bytes", 524L,
                        "retained_bytes", 524L,
                        "truncated", false,
                        "sha256", "a".repeat(64));

        BoundedMessageCapture.Result result =
                capture.captureMessages(
                        List.of(
                                Map.of(
                                        "role", "assistant",
                                        "parts", List.of(
                                                Map.of("type", "provider_payload", "provider_payload", provider)))),
                        true,
                        InvocationCaptureBudget.unbounded());

        String encoded = result.value().map(Object::toString).orElse("");
        assertThat(encoded)
                .doesNotContain("x".repeat(100))
                .doesNotContain("\"mode\":\"full\"");
        assertThat(result.providerMode())
                .isIn(
                        ContentCaptureMode.TRUNCATE,
                        ContentCaptureMode.HASH,
                        ContentCaptureMode.OFF);
    }

    @Test
    void reservationFailureReturnsSafeEmptyResultAndCapacityMetrics() {
        CaptureMemoryPool pool = new CaptureMemoryPool(64);
        try (InvocationCaptureBudget invocation = new InvocationCaptureBudget(pool, 64)) {
            BoundedMessageCapture.Result result =
                    new BoundedMessageCapture(
                                    JSON,
                                    ContentCaptureMode.FULL,
                                    ContentCaptureMode.OFF,
                                    ContentCaptureMode.OFF,
                                    64,
                                    256)
                            .captureMessages(
                                    List.of(
                                            Map.of(
                                                    "role", "assistant",
                                                    "parts", List.of(
                                                            Map.of("type", "text", "content", "answer-" + "x".repeat(200))))),
                                    true,
                                    invocation);

            assertThat(result.value()).isEmpty();
            assertThat(result.capacityDroppedParts()).isGreaterThan(0);
            assertThat(result.capacityDroppedBytes()).isGreaterThan(0);
            assertThat(pool.usedBytes()).isZero();
        }
    }
}
