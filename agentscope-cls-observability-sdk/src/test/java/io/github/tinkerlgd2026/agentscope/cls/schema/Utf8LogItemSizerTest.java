package io.github.tinkerlgd2026.agentscope.cls.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencentcloudapi.cls.producer.common.LogItem;
import com.tencentcloudapi.cls.producer.common.LogSizeCalculator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Utf8LogItemSizerTest {

    @Test
    void emptyRecordHasOnlyFixedOverhead() {
        assertThat(Utf8LogItemSizer.size(Map.of())).isEqualTo(4);
    }

    @Test
    void asciiMatchesUtf8ByteCount() {
        Map<String, String> fields = Map.of("traceID", "0123456789abcdef0123456789abcdef");

        assertThat(Utf8LogItemSizer.size(fields)).isEqualTo(4 + 7 + 32);
    }

    @Test
    void chineseAndEmojiCountUtf8BytesNotUtf16Units() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("name", "中"); // 3 UTF-8 bytes, 1 UTF-16 unit
        fields.put("statusMessage", "😀"); // 4 UTF-8 bytes, 2 UTF-16 units

        assertThat(Utf8LogItemSizer.size(fields))
                .isEqualTo(4 + 4 + 3 + 13 + 4);
    }

    @Test
    void isNeverBelowTencentLogSizeCalculator() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("traceID", "0123456789abcdef0123456789abcdef");
        fields.put("name", "chat 模型 😀");
        fields.put("attribute", "{\"key\":\"值\"}".repeat(10));
        fields.put("statusMessage", "");

        LogItem item = new LogItem();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            item.PushBack(entry.getKey(), entry.getValue());
        }

        assertThat(Utf8LogItemSizer.size(fields))
                .isGreaterThanOrEqualTo(LogSizeCalculator.calculate(item));
    }

    @Test
    void rejectsNullKeysAndValues() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Utf8LogItemSizer.size(fields))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
