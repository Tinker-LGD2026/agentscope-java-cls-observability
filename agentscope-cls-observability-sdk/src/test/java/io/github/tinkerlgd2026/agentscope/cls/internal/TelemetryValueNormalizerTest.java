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
        assertThat(hasUnpairedSurrogate(first)).isFalse();
        String prefix = first.substring(0, first.indexOf('~'));
        assertThat(value).startsWith(prefix);
    }

    @Test
    void redactsCredentialShapedIdentifiersEvenWhenEmbeddedOrWhitespaceVaries() {
        assertRedacted("AKIDabcdefghijklmnop");
        assertRedacted("tenant/AKIDabcdefghijklmnop");
        assertRedacted("tenant-AKIDabcdefghijklmnop");
        assertRedacted("label:AKIDabcdefghijklmnop");
        assertRedacted("prefix AKIDabcdefghijklmnop");
        assertRedacted("\"AKIDabcdefghijklmnop");
        assertRedacted("sk-abcdefghijklmnop");
        assertRedacted("credential=sk-abcdefghijklmnop");
        assertRedacted("credential:sk-abcdefghijklmnop");
        assertRedacted("credential_sk-abcdefghijklmnop");
        assertRedacted("Bearer abc.def.ghi");
        assertRedacted("Authorization:Bearer abc.def.ghi");
        assertRedacted("Authorization:  Bearer abc.def.ghi");
        assertRedacted("Authorization:\tBearer abc.def.ghi");
    }

    @Test
    void ordinaryAndNearMissIdentifiersRemainReadable() {
        assertThat(TelemetryValueNormalizer.safeIdentifier("model-gpt-4o", 128))
                .isEqualTo("model-gpt-4o");
        assertThat(TelemetryValueNormalizer.safeIdentifier("akidabcdefghijklmnop", 128))
                .isEqualTo("akidabcdefghijklmnop");
        assertThat(TelemetryValueNormalizer.safeIdentifier("task-sk-short", 128))
                .isEqualTo("task-sk-short");
        assertThat(TelemetryValueNormalizer.safeIdentifier("Bearer", 128))
                .isEqualTo("Bearer");
        assertThat(TelemetryValueNormalizer.safeIdentifier("notbearer token", 128))
                .isEqualTo("notbearer token");
        assertThat(TelemetryValueNormalizer.safeIdentifier("mybearer value", 128))
                .isEqualTo("mybearer value");
    }

    @Test
    void preservesLegacySmallBudgetBehaviorForValuesThatAlreadyFit() {
        assertThat(TelemetryValueNormalizer.bounded("x", 1)).isEqualTo("x");
        assertThat(TelemetryValueNormalizer.safeIdentifier("x", 1)).isEqualTo("x");
        assertThatThrownBy(() -> TelemetryValueNormalizer.bounded("value-too-long", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budget");
    }

    @Test
    void rejectsBlankMalformedUtf16AndUnsafeCredentialBudgets() {
        assertThatThrownBy(() -> TelemetryValueNormalizer.bounded(" ", 128))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TelemetryValueNormalizer.bounded("bad\ud83d", 128))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UTF-16");
        assertThatThrownBy(() -> TelemetryValueNormalizer.safeIdentifier("AKIDabcdefgh", 24))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("budget");
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static void assertRedacted(String value) {
        String normalized = TelemetryValueNormalizer.safeIdentifier(value, 128);
        assertThat(normalized)
                .startsWith("redacted~")
                .matches("redacted~[0-9a-f]{16}")
                .doesNotContain(value);
    }
}
