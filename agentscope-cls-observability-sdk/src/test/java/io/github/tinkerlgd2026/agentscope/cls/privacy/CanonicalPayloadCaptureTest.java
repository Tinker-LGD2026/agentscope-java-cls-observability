package io.github.tinkerlgd2026.agentscope.cls.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalPayloadCaptureTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void canonicalHashIgnoresMapInsertionOrderAndHashesRedactedValue() {
        CanonicalPayloadCapture capture = new CanonicalPayloadCapture(JSON);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", "value");
        first.put("authorization", "Bearer private-token-value");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("authorization", "Bearer different-private-token");
        second.put("z", "value");

        Map<String, Object> one = capture.capture(first, ContentCaptureMode.HASH, 64, budget(4096));
        Map<String, Object> two = capture.capture(second, ContentCaptureMode.HASH, 64, budget(4096));

        assertThat(one)
                .containsEntry("mode", "hash")
                .containsEntry("complete", true)
                .containsEntry("truncated", false)
                .containsKey("original_bytes")
                .containsKey("retained_bytes")
                .containsKey("sha256")
                .doesNotContainKey("value");
        assertThat(one.get("sha256")).isEqualTo(two.get("sha256"));
        assertThat(one.toString()).doesNotContain("private-token-value");
    }

    @Test
    void fullAndTruncatePreserveListOrderAndBoundUtf8Payload() {
        CanonicalPayloadCapture capture = new CanonicalPayloadCapture(JSON);
        List<Object> value = List.of("first", "用户😀", "third");

        Map<String, Object> full = capture.capture(value, ContentCaptureMode.FULL, 64, budget(4096));
        Map<String, Object> truncated = capture.capture(value, ContentCaptureMode.TRUNCATE, 12, budget(256));

        assertThat(full).containsEntry("mode", "full").containsEntry("complete", true);
        assertThat(full.get("value").toString()).containsSubsequence("first", "用户😀", "third");
        assertThat(truncated).containsEntry("mode", "truncate").containsKey("value");
        assertThat(((Number) truncated.get("retained_bytes")).longValue()).isLessThanOrEqualTo(256);
    }

    @Test
    void offNeverRetainsPayloadOrStableHash() {
        Map<String, Object> envelope =
                new CanonicalPayloadCapture(JSON)
                        .capture("private", ContentCaptureMode.OFF, 64, budget(256));

        assertThat(envelope)
                .containsEntry("mode", "off")
                .containsEntry("complete", true)
                .containsEntry("retained_bytes", 0L)
                .doesNotContainKeys("value", "sha256");
        assertThat(envelope.toString()).doesNotContain("private");
    }

    @Test
    void reportsIncompleteTraversalWithoutPublishingPartialDigest() {
        CanonicalPayloadCapture capture = new CanonicalPayloadCapture(JSON);
        Map<String, Object> circular = new LinkedHashMap<>();
        circular.put("self", circular);
        List<Object> tooMany = new ArrayList<>();
        for (int index = 0; index < 257; index++) {
            tooMany.add(index);
        }
        Object throwing = new Object() {
            public String getSecret() {
                throw new IllegalStateException("boom");
            }
        };

        for (Object value : List.of(circular, tooMany, nested(17), throwing, Double.NaN)) {
            Map<String, Object> envelope =
                    capture.capture(value, ContentCaptureMode.HASH, 64, budget(4096));
            assertThat(envelope)
                    .containsEntry("mode", "hash")
                    .containsEntry("complete", false)
                    .containsKey("original_bytes_at_least")
                    .containsKey("capture_error")
                    .doesNotContainKey("sha256");
        }
    }

    @Test
    void nodeLimitProducesIncompleteEnvelope() {
        List<Object> root = new ArrayList<>();
        for (int index = 0; index < 256; index++) {
            root.add(List.of(index, index, index, index));
        }
        root.add(List.of("overflow"));

        Map<String, Object> envelope =
                new CanonicalPayloadCapture(JSON)
                        .capture(root, ContentCaptureMode.FULL, 64, budget(4096));

        assertThat(envelope)
                .containsEntry("complete", false)
                .containsKey("original_bytes_at_least")
                .containsKey("capture_error");
    }

    private static CanonicalPayloadCapture.CaptureBudget budget(long bytes) {
        return CanonicalPayloadCapture.CaptureBudget.fixed(bytes);
    }

    private static Object nested(int depth) {
        Object value = "leaf";
        for (int index = 0; index < depth; index++) {
            value = List.of(value);
        }
        return value;
    }
}
