package io.github.tinkerlgd2026.agentscope.cls.transport;

import com.tencentcloudapi.cls.producer.common.LogItem;
import com.tencentcloudapi.cls.producer.common.LogSizeCalculator;
import io.github.tinkerlgd2026.agentscope.cls.schema.Utf8LogItemSizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Slices a record batch into physical Tencent producer submissions that satisfy both the
 * official {@code LogSizeCalculator} and the more conservative UTF-8 sizer.
 */
public final class TencentExportBatchPlanner {
    private final long maxBytes;
    private final int maxCount;

    public TencentExportBatchPlanner(long maxBytes, int maxCount) {
        if (maxBytes <= 0 || maxCount < 1) {
            throw new IllegalArgumentException(
                    "batch bytes and count must be positive");
        }
        this.maxBytes = maxBytes;
        this.maxCount = maxCount;
    }

    public List<List<LogItem>> slice(List<LogItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<List<LogItem>> slices = new ArrayList<>();
        List<LogItem> current = new ArrayList<>();
        long currentConservativeBytes = 0;
        int currentOfficialBytes = 0;
        for (LogItem item : items) {
            long itemConservative = conservativeSize(item);
            int itemOfficial = LogSizeCalculator.calculate(item);
            boolean overCount = current.size() >= maxCount;
            boolean overBytes =
                    !current.isEmpty()
                            && (currentConservativeBytes + itemConservative > maxBytes
                                    || currentOfficialBytes + itemOfficial > maxBytes);
            if (overCount || overBytes) {
                slices.add(List.copyOf(current));
                current = new ArrayList<>();
                currentConservativeBytes = 0;
                currentOfficialBytes = 0;
            }
            // A single item is always admitted: records are hard-limited to 1.5 MiB, which
            // fits the minimum 2 MiB submission threshold enforced at configuration level.
            current.add(item);
            currentConservativeBytes += itemConservative;
            currentOfficialBytes += itemOfficial;
        }
        if (!current.isEmpty()) {
            slices.add(List.copyOf(current));
        }
        return List.copyOf(slices);
    }

    // Sum over the raw contents list: duplicate keys must not collapse, otherwise the
    // conservative size could fall below the official calculator.
    private static long conservativeSize(LogItem item) {
        long size = Utf8LogItemSizer.RECORD_OVERHEAD_BYTES;
        for (var content : item.mContents.getContentsList()) {
            if (content.getKey() != null) {
                size += Utf8LogItemSizer.utf8Length(content.getKey());
            }
            if (content.getValue() != null) {
                size += Utf8LogItemSizer.utf8Length(content.getValue());
            }
        }
        return size;
    }
}
