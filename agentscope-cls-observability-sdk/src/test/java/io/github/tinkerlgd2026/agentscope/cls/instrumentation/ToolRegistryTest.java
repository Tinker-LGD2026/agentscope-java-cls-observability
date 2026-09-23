package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void activeTokenEndsSpanNormally() {
        ToolRegistry registry = new ToolRegistry();
        ToolRegistry.ToolToken token = registry.startTool("agent-1", "step-1", "call-1", "search");

        assertThat(token.active()).isTrue();
        token.end(true);
        assertThat(registry.failedToolCount()).isZero();
        assertThat(registry.partialFailure()).isFalse();
    }

    @Test
    void capacityFailureCreatesNoopTokenThatConsumesLifecycleEvents() {
        ToolRegistry registry = new ToolRegistry(1, 128);
        ToolRegistry.ToolToken first = registry.startTool("agent-1", "step-1", "call-1", "search");
        ToolRegistry.ToolToken overflow = registry.startTool("agent-1", "step-1", "call-2", "calc");

        assertThat(first.active()).isTrue();
        assertThat(overflow.active()).isFalse();
        // No-op token consumes end/result without failing the invocation.
        overflow.end(true);
        overflow.result();
        assertThat(registry.capacityRejectedTools()).isEqualTo(1);
    }

    @Test
    void perStepToolCapIsIndependentOfGlobalContextCap() {
        ToolRegistry registry = new ToolRegistry(1024, 1);
        ToolRegistry.ToolToken first = registry.startTool("agent-1", "step-1", "call-1", "a");
        ToolRegistry.ToolToken secondStep = registry.startTool("agent-1", "step-2", "call-2", "b");
        ToolRegistry.ToolToken overflow = registry.startTool("agent-1", "step-1", "call-3", "c");

        assertThat(first.active()).isTrue();
        assertThat(secondStep.active()).isTrue();
        assertThat(overflow.active()).isFalse();
    }

    @Test
    void toolFailureAggregatesPartialFailureAndRetryDoesNotClearHistory() {
        ToolRegistry registry = new ToolRegistry();
        ToolRegistry.ToolToken token = registry.startTool("agent-1", "step-1", "call-1", "search");
        token.end(false);
        ToolRegistry.ToolToken retry = registry.startTool("agent-1", "step-1", "call-2", "search");
        retry.end(true);

        assertThat(registry.failedToolCount()).isEqualTo(1);
        assertThat(registry.partialFailure()).isTrue();
    }

    @Test
    void duplicateStartWithSameCallIdKeepsOriginalToken() {
        ToolRegistry registry = new ToolRegistry();
        ToolRegistry.ToolToken first = registry.startTool("agent-1", "step-1", "call-1", "search");
        ToolRegistry.ToolToken duplicate =
                registry.startTool("agent-1", "step-1", "call-1", "search");

        assertThat(first.active()).isTrue();
        assertThat(duplicate.active()).isFalse();
        first.end(true);
        assertThat(registry.failedToolCount()).isZero();
    }
}
