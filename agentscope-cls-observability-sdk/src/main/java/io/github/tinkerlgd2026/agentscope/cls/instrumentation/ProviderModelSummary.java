package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import java.util.List;
import java.util.TreeSet;

/** Sorted distinct provider/model sets observed within one invocation. */
final class ProviderModelSummary {
    private final TreeSet<String> providers = new TreeSet<>();
    private final TreeSet<String> models = new TreeSet<>();

    synchronized void record(String provider, String model) {
        if (provider != null && !provider.isBlank()) {
            providers.add(provider);
        }
        if (model != null && !model.isBlank()) {
            models.add(model);
        }
    }

    synchronized List<String> providers() {
        return List.copyOf(providers);
    }

    synchronized List<String> models() {
        return List.copyOf(models);
    }
}
