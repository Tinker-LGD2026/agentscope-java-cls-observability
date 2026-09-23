package io.github.tinkerlgd2026.agentscope.cls.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencentcloudapi.cls.producer.common.LogItem;
import com.tencentcloudapi.cls.producer.common.LogSizeCalculator;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsFieldLimits;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanRecord;
import io.github.tinkerlgd2026.agentscope.cls.schema.Utf8LogItemSizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TencentExportBatchPlannerTest {
    private static final long MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_COUNT = 256;

    @Test
    void conservativeSizerNeverUnderestimatesOfficialCalculator() {
        Random random = new Random(20260923L);
        String[] alphabets = {"ascii-value", "中文内容", "😀🎉", "a中😀mix"};
        for (int iteration = 0; iteration < 200; iteration++) {
            Map<String, String> fields = new LinkedHashMap<>();
            int fieldCount = random.nextInt(16);
            for (int field = 0; field < fieldCount; field++) {
                String alphabet = alphabets[random.nextInt(alphabets.length)];
                fields.put(
                        "key-" + field,
                        alphabet.repeat(random.nextInt(20) + 1));
            }
            LogItem item = new LogItem();
            fields.forEach(item::PushBack);

            assertThat(Utf8LogItemSizer.size(fields))
                    .as("iteration %d", iteration)
                    .isGreaterThanOrEqualTo(LogSizeCalculator.calculate(item));
        }
    }

    @Test
    void splitsByByteThresholdWithBothCalculatorsWithinBudget() {
        TencentExportBatchPlanner planner = new TencentExportBatchPlanner(MAX_BYTES, MAX_COUNT);
        List<LogItem> items = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            items.add(logItemOfSize(500 * 1024, index));
        }

        List<List<LogItem>> slices = planner.slice(items);

        assertThat(slices).hasSizeGreaterThan(1);
        for (List<LogItem> slice : slices) {
            assertThat(LogSizeCalculator.calculate(slice)).isLessThanOrEqualTo((int) MAX_BYTES);
            assertThat(slice.stream().mapToLong(TencentExportBatchPlannerTest::conservativeSize).sum())
                    .isLessThanOrEqualTo(MAX_BYTES);
        }
        assertThat(slices.stream().mapToInt(List::size).sum()).isEqualTo(10);
    }

    @Test
    void splitsByCountThreshold() {
        TencentExportBatchPlanner planner = new TencentExportBatchPlanner(MAX_BYTES, 3);
        List<LogItem> items = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            items.add(logItemOfSize(128, index));
        }

        List<List<LogItem>> slices = planner.slice(items);

        assertThat(slices).hasSize(3);
        assertThat(slices.get(0)).hasSize(3);
        assertThat(slices.get(1)).hasSize(3);
        assertThat(slices.get(2)).hasSize(1);
    }

    @Test
    void singletonRecordAtHardLimitAlwaysFitsOneSlice() {
        TencentExportBatchPlanner planner = new TencentExportBatchPlanner(MAX_BYTES, MAX_COUNT);
        LogItem huge = logItemOfSize(ClsFieldLimits.RECORD_MAX_BYTES - 1024, 0);

        List<List<LogItem>> slices = planner.slice(List.of(huge));

        assertThat(slices).hasSize(1);
        assertThat(slices.get(0)).containsExactly(huge);
    }

    @Test
    void emptyInputProducesNoSlices() {
        assertThat(new TencentExportBatchPlanner(MAX_BYTES, MAX_COUNT).slice(List.of()))
                .isEmpty();
    }

    @Test
    void rejectsUnsupportedThresholds() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TencentExportBatchPlanner(0, MAX_COUNT))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TencentExportBatchPlanner(MAX_BYTES, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static long conservativeSize(LogItem item) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (var content : item.mContents.getContentsList()) {
            fields.put(content.getKey(), content.getValue());
        }
        return Utf8LogItemSizer.size(fields);
    }

    private static LogItem logItemOfSize(int targetBytes, int marker) {
        StringBuilder value = new StringBuilder("v" + marker + "-");
        while (value.length() < targetBytes - 64) {
            value.append('x');
        }
        LogItem item = new LogItem();
        item.PushBack("traceID", "0123456789abcdef0123456789abcdef");
        item.PushBack("payload", value.toString());
        return item;
    }
}
