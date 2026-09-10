package io.github.tinkerlgd2026.agentscope.cls.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContentSanitizerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void offModeDoesNotCaptureContent() {
        ContentSanitizer sanitizer = new ContentSanitizer(JSON, ContentCaptureMode.OFF, 256);

        assertThat(sanitizer.capture(Map.of("message", "private"))).isEmpty();
    }

    @Test
    void hashModeReturnsDigestWithoutOriginalContent() {
        ContentSanitizer sanitizer = new ContentSanitizer(JSON, ContentCaptureMode.HASH, 256);

        JsonNode captured = sanitizer.capture("hello").orElseThrow();

        assertThat(captured.get("sha256").asText())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(captured.get("original_bytes").asInt()).isEqualTo(5);
        assertThat(captured.toString()).doesNotContain("hello");
    }

    @Test
    void messageCaptureAlwaysReturnsAnArray() {
        ContentSanitizer hash = new ContentSanitizer(JSON, ContentCaptureMode.HASH, 256);
        ContentSanitizer truncate =
                new ContentSanitizer(JSON, ContentCaptureMode.TRUNCATE, 256);

        assertThat(hash.captureMessages(Map.of("text", "private")).orElseThrow().isArray())
                .isTrue();
        JsonNode truncated =
                truncate
                        .captureMessages(
                                List.of(
                                        Map.of(
                                                "role",
                                                "user",
                                                "parts",
                                                List.of(
                                                        Map.of(
                                                                "type",
                                                                "text",
                                                                "content",
                                                                "中".repeat(500))))))
                        .orElseThrow();
        assertThat(truncated.isArray()).isTrue();
        assertThat(truncated.get(0).get("role").asText()).isEqualTo("user");
        assertThat(truncated.at("/0/parts").isArray()).isTrue();
    }

    @Test
    void hashModeNeverReturnsMessagePlaintext() {
        ContentSanitizer sanitizer = new ContentSanitizer(JSON, ContentCaptureMode.HASH, 256);

        JsonNode captured =
                sanitizer
                        .captureMessages(
                                List.of(
                                        Map.of(
                                                "role",
                                                "user",
                                                "parts",
                                                List.of(
                                                        Map.of(
                                                                "type",
                                                                "text",
                                                                "content",
                                                                "private message")))))
                        .orElseThrow();

        assertThat(captured.toString()).doesNotContain("private message");
        assertThat(captured.at("/0/parts/0/type").asText()).isEqualTo("content_hash");
        assertThat(captured.at("/0/parts/0/sha256").asText()).hasSize(64);
    }

    @Test
    void fullModeStillHonoursTheHardSafetyBudget() throws Exception {
        ContentSanitizer sanitizer = new ContentSanitizer(JSON, ContentCaptureMode.FULL, 256);

        JsonNode captured = sanitizer.capture(Map.of("text", "sensitive ".repeat(1000))).orElseThrow();
        JsonNode messages =
                sanitizer
                        .captureMessages(
                                List.of(
                                        Map.of(
                                                "role",
                                                "user",
                                                "parts",
                                                List.of(
                                                        Map.of(
                                                                "type",
                                                                "text",
                                                                "content",
                                                                "sensitive ".repeat(1000))))))
                        .orElseThrow();

        assertThat(JSON.writeValueAsBytes(captured).length).isLessThanOrEqualTo(256);
        assertThat(JSON.writeValueAsBytes(messages).length).isLessThanOrEqualTo(256);
    }

    @Test
    void messageSummaryBoundsFirstMessageAndNormalizesRole() throws Exception {
        ContentSanitizer sanitizer = new ContentSanitizer(JSON, ContentCaptureMode.HASH, 256);

        JsonNode captured =
                sanitizer
                        .captureMessages(
                                List.of(
                                        Map.of(
                                                "role",
                                                "private-role-".repeat(200),
                                                "parts",
                                                List.of(Map.of("content", "message")))))
                        .orElseThrow();

        assertThat(JSON.writeValueAsBytes(captured).length).isLessThanOrEqualTo(256);
        assertThat(captured.at("/0/role").asText()).isEqualTo("unknown");
        assertThat(captured.toString()).doesNotContain("private-role");
    }

    @Test
    void sanitizesEmbeddedUrlsSecretKeysAndUnterminatedPem() {
        ContentSanitizer sanitizer = new ContentSanitizer(JSON, ContentCaptureMode.FULL, 2048);
        Map<String, Object> input =
                Map.of(
                        "note",
                        "open https://user:pass@example.com/path?token=secret#part now",
                        "token=actual-secret-value",
                        "visible-value",
                        "pem",
                        "before -----BEGIN PRIVATE KEY----- no end marker");

        JsonNode captured = sanitizer.capture(input).orElseThrow();
        String encoded = captured.toString();

        assertThat(encoded).doesNotContain("user:pass", "token=secret", "actual-secret-value");
        assertThat(encoded).contains("https://example.com/path", "[REDACTED_KEY]");
        assertThat(captured.get("pem").asText()).contains("[REDACTED_SECRET]");
    }

    @Test
    void hashModeUsesOriginalContentBeforeRedaction() {
        ContentSanitizer sanitizer = new ContentSanitizer(JSON, ContentCaptureMode.HASH, 256);

        JsonNode first = sanitizer.capture(Map.of("password", "one")).orElseThrow();
        JsonNode second = sanitizer.capture(Map.of("password", "two")).orElseThrow();

        assertThat(first.get("sha256").asText()).isNotEqualTo(second.get("sha256").asText());
        assertThat(first.get("original_bytes").asInt()).isGreaterThan(0);
    }

    @Test
    void recursivelyRedactsSensitiveKeysAndRemovesUrlSecrets() {
        ContentSanitizer sanitizer =
                new ContentSanitizer(JSON, ContentCaptureMode.FULL, 1024);
        Map<String, Object> input =
                Map.of(
                        "authorization", "Bearer value",
                        "nested", Map.of("api_key", "value", "safe", "kept"),
                        "url", "https://user:pass@example.com/path?token=value#fragment");

        JsonNode captured = sanitizer.capture(input).orElseThrow();

        assertThat(captured.get("authorization").asText()).isEqualTo("[REDACTED]");
        assertThat(captured.at("/nested/api_key").asText()).isEqualTo("[REDACTED]");
        assertThat(captured.at("/nested/safe").asText()).isEqualTo("kept");
        assertThat(captured.get("url").asText()).isEqualTo("https://example.com/path");
        assertThat(
                        sanitizer
                                .capture("  HTTPS://user:pass@example.com/path?token=value#fragment")
                                .orElseThrow()
                                .asText())
                .isEqualTo("https://example.com/path");
        assertThat(
                        sanitizer
                                .capture("https://user:pass@例子.测试/path?token=value")
                                .orElseThrow()
                                .asText())
                .isEqualTo("[REDACTED_URL]");
    }

    @Test
    void removesCompleteEmbeddedUrlQueryIncludingLegalPunctuation() {
        ContentSanitizer sanitizer = new ContentSanitizer(JSON, ContentCaptureMode.FULL, 2048);

        JsonNode captured =
                sanitizer
                        .capture(
                                "open https://user:pass@example.com/path?sig=AAAA,BBBB;CCCC now")
                        .orElseThrow();

        assertThat(captured.asText()).isEqualTo("open https://example.com/path now");
    }

    @Test
    void redactsSecretLikeValuesInsideFreeText() {
        ContentSanitizer sanitizer = new ContentSanitizer(JSON, ContentCaptureMode.FULL, 8192);

        JsonNode captured =
                sanitizer
                        .capture(
                                Map.of(
                                        "note",
                                        "id AKIDabcdefghijklmnopqrstuvwxyz01 key"
                                                + " sk-abcdefghijklmnopqrstuvwxyz012345 header"
                                                + " Authorization: Bearer aaaaaaaaaaaaaaaaaaaa"))
                        .orElseThrow();

        String note = captured.get("note").asText();
        assertThat(note).doesNotContain("AKIDabcdefghijklmnopqrstuvwxyz01");
        assertThat(note).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz012345");
        assertThat(note).doesNotContain("aaaaaaaaaaaaaaaaaaaa");
        assertThat(note).contains("[REDACTED_SECRET]");
    }

    @Test
    void stopsTraversingStructuredPayloadAfterSafetyBudget() throws Exception {
        java.util.concurrent.atomic.AtomicInteger visited =
                new java.util.concurrent.atomic.AtomicInteger();
        Iterable<String> huge =
                () ->
                        java.util.stream.IntStream.range(0, 100_000)
                                .mapToObj(
                                        index -> {
                                            visited.incrementAndGet();
                                            return "x".repeat(128);
                                        })
                                .iterator();
        ContentSanitizer sanitizer =
                new ContentSanitizer(JSON, ContentCaptureMode.FULL, 256);

        JsonNode captured = sanitizer.capture(huge).orElseThrow();

        assertThat(JSON.writeValueAsBytes(captured).length).isLessThanOrEqualTo(256);
        assertThat(captured.get("truncated").asBoolean()).isTrue();
        assertThat(visited.get()).isLessThan(100);
    }

    @Test
    void limitsRecursionDepthInsteadOfOverflowing() {
        ContentSanitizer sanitizer = new ContentSanitizer(JSON, ContentCaptureMode.FULL, 64 * 1024);
        Object nested = Map.of("leaf", "value");
        for (int index = 0; index < 200; index++) {
            nested = Map.of("child", nested);
        }

        JsonNode captured = sanitizer.capture(nested).orElseThrow();

        assertThat(captured.toString()).contains("[TRUNCATED_DEPTH]");
    }

    @Test
    void hashIsStableAcrossCaptureModes() {
        Object content =
                List.of(
                        Map.of(
                                "role",
                                "user",
                                "parts",
                                List.of(Map.of("type", "text", "content", "hello"))));

        String off = new ContentSanitizer(JSON, ContentCaptureMode.OFF, 256).hash(content);
        String full = new ContentSanitizer(JSON, ContentCaptureMode.FULL, 256).hash(content);

        assertThat(off).isEqualTo(full).hasSize(64);
        assertThat(new ContentSanitizer(JSON, ContentCaptureMode.OFF, 256).hash(List.of()))
                .isNotEqualTo(off);
    }

    @Test
    void reportsWhetherContentCaptureIsEnabled() {
        assertThat(new ContentSanitizer(JSON, ContentCaptureMode.OFF, 256).isCapturing()).isFalse();
        assertThat(new ContentSanitizer(JSON, ContentCaptureMode.HASH, 256).isCapturing()).isTrue();
    }

    @Test
    void truncateHonoursBudgetWithEscapeHeavyContent() throws Exception {
        ContentSanitizer sanitizer = new ContentSanitizer(JSON, ContentCaptureMode.TRUNCATE, 512);

        JsonNode captured =
                sanitizer.capture(Map.of("text", "\"\n\t".repeat(2000))).orElseThrow();

        assertThat(JSON.writeValueAsBytes(captured).length).isLessThanOrEqualTo(512);
        assertThat(captured.get("truncated").asBoolean()).isTrue();
    }

    @Test
    void truncateModeKeepsValidJsonWithinUtf8Budget() throws Exception {
        ContentSanitizer sanitizer =
                new ContentSanitizer(JSON, ContentCaptureMode.TRUNCATE, 256);

        JsonNode captured = sanitizer.capture(Map.of("text", "中".repeat(500))).orElseThrow();
        byte[] encoded = JSON.writeValueAsBytes(captured);

        assertThat(encoded.length).isLessThanOrEqualTo(256);
        assertThat(JSON.readTree(encoded).toString()).isEqualTo(captured.toString());
        assertThat(captured.toString()).contains("truncated");
        assertThat(new String(encoded, StandardCharsets.UTF_8)).doesNotContain("�");
    }
}
