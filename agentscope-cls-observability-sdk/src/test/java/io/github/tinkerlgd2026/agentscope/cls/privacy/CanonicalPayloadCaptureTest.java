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
                .containsEntry("retained_bytes", 66L)
                .containsKey("sha256")
                .doesNotContainKey("payload");
        assertThat(one.get("sha256")).isEqualTo(two.get("sha256"));
        assertThat(one.get("original_bytes")).isNotEqualTo(two.get("original_bytes"));
        assertThat(one.toString()).doesNotContain("private-token-value");
    }

    @Test
    void fullAndTruncatePreserveListOrderAndBoundUtf8Payload() throws Exception {
        CanonicalPayloadCapture capture = new CanonicalPayloadCapture(JSON);
        List<Object> value = List.of("first", "用户😀", "third");

        Map<String, Object> full = capture.capture(value, ContentCaptureMode.FULL, 64, budget(4096));
        Map<String, Object> truncated = capture.capture(value, ContentCaptureMode.TRUNCATE, 12, budget(256));

        assertThat(full).containsEntry("mode", "full").containsEntry("complete", true);
        assertThat(payloadNode(full).toString()).containsSubsequence("first", "用户😀", "third");
        assertThat(truncated).containsEntry("mode", "truncate").containsKey("payload");
        assertThat(((Number) truncated.get("retained_bytes")).longValue()).isLessThanOrEqualTo(12);
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
                .doesNotContainKeys("payload", "sha256");
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

        for (Object value :
                List.of(
                        circular,
                        tooMany,
                        nested(17),
                        throwing,
                        Double.NaN,
                        Double.POSITIVE_INFINITY)) {
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
    void stripsUrlSecretsAndCompletePemBlocksBeforeFullCapture() throws Exception {
        Map<String, Object> payload =
                Map.of(
                        "url",
                        "https://user:pass@example.test/path?token=private#secret-fragment",
                        "pem",
                        "-----BEGIN RSA PRIVATE KEY-----\nPRIVATE-BODY\n"
                                + "-----END RSA PRIVATE KEY-----");

        Map<String, Object> envelope =
                new CanonicalPayloadCapture(JSON)
                        .capture(payload, ContentCaptureMode.FULL, 4096, budget(4096));

        assertThat(payloadNode(envelope).toString())
                .contains("example.test/path", "[REDACTED]")
                .doesNotContain("user", "pass", "token=private", "secret-fragment", "PRIVATE-BODY");
    }

    @Test
    void oversizedTextThatNeedsRedactionFailsClosedWithoutDigestOrPayload() {
        String oversized = "x".repeat(800_000) + " Bearer private-token-value";

        Map<String, Object> envelope =
                new CanonicalPayloadCapture(JSON)
                        .capture(oversized, ContentCaptureMode.HASH, 64, budget(66));

        assertThat(envelope)
                .containsEntry("complete", false)
                .containsEntry("capture_error", true)
                .containsEntry("retained_bytes", 0L)
                .doesNotContainKeys("sha256", "payload");
    }

    @Test
    void hashBudgetFailureNeverPublishesDigestOrExceedsBudget() {
        Map<String, Object> envelope =
                new CanonicalPayloadCapture(JSON)
                        .capture(Map.of("value", "secret"), ContentCaptureMode.HASH, 64, budget(32));

        assertThat(envelope)
                .containsEntry("mode", "hash")
                .containsEntry("complete", false)
                .containsEntry("capture_error", true)
                .containsEntry("retained_bytes", 0L)
                .containsKey("original_bytes_at_least")
                .doesNotContainKeys("sha256", "payload", "original_bytes");
    }

    @Test
    void truncateUsesValidUtf8AndMeasuresSerializedPayloadBytes() throws Exception {
        Map<String, Object> envelope =
                new CanonicalPayloadCapture(JSON)
                        .capture(Map.of("text", "😀😀\\\"中文"), ContentCaptureMode.TRUNCATE, 13, budget(64));

        Object payload = envelope.get("payload");
        assertThat(payload.toString()).doesNotContain("�");
        assertThat(((Number) envelope.get("retained_bytes")).longValue())
                .isEqualTo(JSON.writeValueAsBytes(payload).length)
                .isLessThanOrEqualTo(13);
    }

    @Test
    void rootNullIsCanonicalPayloadRatherThanOff() throws Exception {
        Map<String, Object> envelope =
                new CanonicalPayloadCapture(JSON)
                        .capture(null, ContentCaptureMode.FULL, 64, budget(64));

        assertThat(envelope)
                .containsEntry("complete", true)
                .containsEntry("original_bytes", 4L)
                .containsEntry("retained_bytes", 4L)
                .containsKey("payload");
        assertThat(JSON.readTree(JSON.writeValueAsBytes(envelope)).get("payload").isNull())
                .isTrue();
    }

    @Test
    void recognizesCompositeCredentialKeysWithoutNearMissFalsePositives() throws Exception {
        Map<String, Object> payload =
                Map.ofEntries(
                        Map.entry("client_secret", "private-1"),
                        Map.entry("refresh_token", "private-2"),
                        Map.entry("accessToken", "private-3"),
                        Map.entry("x-api-key", "private-4"),
                        Map.entry("session_cookie", "private-5"),
                        Map.entry("authorization_header", "private-6"),
                        Map.entry("aws_secret_access_key", "private-7"),
                        Map.entry("tokenizer", "visible-one"),
                        Map.entry("secretary", "visible-two"),
                        Map.entry("cookiecutter", "visible-three"));

        Map<String, Object> envelope =
                new CanonicalPayloadCapture(JSON)
                        .capture(payload, ContentCaptureMode.FULL, 4096, budget(4096));
        String captured = payloadNode(envelope).toString();

        assertThat(captured)
                .contains("visible-one", "visible-two", "visible-three", "[REDACTED]")
                .doesNotContain(
                        "private-1",
                        "private-2",
                        "private-3",
                        "private-4",
                        "private-5",
                        "private-6",
                        "private-7");
    }

    @Test
    void supportsNullElementsAndExactSuccessBoundaries() throws Exception {
        CanonicalPayloadCapture capture = new CanonicalPayloadCapture(JSON);
        List<Object> nullable = new ArrayList<>();
        nullable.add(null);
        nullable.add("value");

        Map<String, Object> nullableEnvelope =
                capture.capture(nullable, ContentCaptureMode.FULL, 4096, budget(4096));
        assertThat(nullableEnvelope).containsEntry("complete", true);
        assertThat(payloadNode(nullableEnvelope).toString()).contains("null", "value");

        List<Object> collectionBoundary = new ArrayList<>();
        for (int index = 0; index < 256; index++) {
            collectionBoundary.add(index);
        }
        assertThat(capture.capture(collectionBoundary, ContentCaptureMode.HASH, 64, budget(66)))
                .containsEntry("complete", true)
                .containsKey("sha256");
        assertThat(capture.capture(nested(16), ContentCaptureMode.HASH, 64, budget(66)))
                .containsEntry("complete", true);
    }

    @Test
    void enforcesNodeLimitIndependentlyOfCollectionLimit() {
        CanonicalPayloadCapture capture = new CanonicalPayloadCapture(JSON);

        assertThat(capture.capture(nodeTree(2), ContentCaptureMode.HASH, 64, budget(66)))
                .containsEntry("complete", true)
                .containsKey("sha256");
        assertThat(capture.capture(nodeTree(3), ContentCaptureMode.HASH, 64, budget(66)))
                .containsEntry("complete", false)
                .containsEntry("capture_error", true)
                .containsKey("original_bytes_at_least")
                .doesNotContainKey("sha256");
        assertThat(
                        capture.capture(
                                JSON.valueToTree(nodeTree(2)),
                                ContentCaptureMode.HASH,
                                64,
                                budget(66)))
                .containsEntry("complete", true)
                .containsKey("sha256");
    }

    private static com.fasterxml.jackson.databind.JsonNode payloadNode(
            Map<String, Object> envelope) throws Exception {
        return JSON.readTree(JSON.writeValueAsBytes(envelope)).get("payload");
    }

    private static List<Object> nodeTree(int finalLeafCount) {
        List<Object> root = new ArrayList<>();
        for (int index = 0; index < 255; index++) {
            root.add(List.of(index, index, index));
        }
        List<Object> finalBranch = new ArrayList<>();
        for (int index = 0; index < finalLeafCount; index++) {
            finalBranch.add(index);
        }
        root.add(finalBranch);
        return root;
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
