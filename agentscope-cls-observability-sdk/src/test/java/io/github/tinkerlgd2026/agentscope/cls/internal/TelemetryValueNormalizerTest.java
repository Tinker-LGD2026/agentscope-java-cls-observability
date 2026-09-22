package io.github.tinkerlgd2026.agentscope.cls.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TelemetryValueNormalizerTest {

    @Test
    void preservesValuesWithinUtf8Budget() {
        assertThat(TelemetryValueNormalizer.bounded("  用户😀  ", 32)).isEqualTo("用户😀");
    }

    @Test
    void truncatesAtCodePointBoundaryAndAddsStableFingerprint() {
        String value = "用户😀-".repeat(20);

        String first = TelemetryValueNormalizer.bounded(value, 48);
        String second = TelemetryValueNormalizer.bounded(value, 48);

        assertThat(first).isEqualTo(second).matches(".*~[0-9a-f]{16}");
        assertThat(first.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(48);
        assertThat(first).doesNotContain("�");
    }

    @Test
    void redactsCredentialShapedIdentifiersEvenWhenTheyFit() {
        assertRedacted("AKIDabcdefghijklmnop");
        assertRedacted("sk-abcdefghijklmnop");
        assertRedacted("Bearer abc.def.ghi");
    }

    @Test
    void ordinaryIdentifiersRemainReadable() {
        assertThat(TelemetryValueNormalizer.safeIdentifier("model-gpt-4o", 128))
                .isEqualTo("model-gpt-4o");
    }

    @Test
    void rejectsBlankValuesAndUnsafeBudgets() {
        assertThatThrownBy(() -> TelemetryValueNormalizer.bounded(" ", 128))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TelemetryValueNormalizer.bounded("value", 17))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budget");
        assertThatThrownBy(() -> TelemetryValueNormalizer.safeIdentifier("AKIDabcdefgh", 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budget");
    }

    private static void assertRedacted(String value) {
        String normalized = TelemetryValueNormalizer.safeIdentifier(value, 128);
        assertThat(normalized)
                .startsWith("redacted~")
                .matches("redacted~[0-9a-f]{16}")
                .doesNotContain(value);
    }
}
