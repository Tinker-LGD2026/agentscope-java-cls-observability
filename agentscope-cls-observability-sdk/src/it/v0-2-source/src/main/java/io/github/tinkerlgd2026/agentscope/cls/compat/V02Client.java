package io.github.tinkerlgd2026.agentscope.cls.compat;

import io.github.tinkerlgd2026.agentscope.cls.ClsObservabilityConfig;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import java.time.Duration;

/** Auditable source fixture using only the public 0.2 API. */
public final class V02Client {
    private V02Client() {}

    public static int run() {
        ClsObservabilityConfig config =
                ClsObservabilityConfig.builder()
                        .contentCaptureMode(ContentCaptureMode.OFF)
                        .reasoningCaptureMode(ContentCaptureMode.OFF)
                        .maxContentBytes(4096)
                        .reactorContextHookEnabled(false)
                        .exportScheduleDelay(Duration.ofSeconds(2))
                        .maxQueueSize(4096)
                        .build();
        return config.maxQueueSize();
    }
}
