package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderModelSummaryTest {

    @Test
    void collectsSortedDistinctProvidersAndModels() {
        ProviderModelSummary summary = new ProviderModelSummary();

        summary.record("deepseek", "deepseek-v3");
        summary.record("anthropic", "claude-sonnet");
        summary.record("deepseek", "deepseek-r1");
        summary.record("deepseek", "deepseek-v3");

        assertThat(summary.providers()).containsExactly("anthropic", "deepseek");
        assertThat(summary.models())
                .containsExactly("claude-sonnet", "deepseek-r1", "deepseek-v3");
    }

    @Test
    void emptySummaryProducesEmptyLists() {
        ProviderModelSummary summary = new ProviderModelSummary();

        assertThat(summary.providers()).isEmpty();
        assertThat(summary.models()).isEmpty();
    }

    @Test
    void blankValuesAreIgnored() {
        ProviderModelSummary summary = new ProviderModelSummary();

        summary.record(null, null);
        summary.record(" ", "model-x");

        assertThat(summary.providers()).isEmpty();
        assertThat(summary.models()).containsExactly("model-x");
    }
}
