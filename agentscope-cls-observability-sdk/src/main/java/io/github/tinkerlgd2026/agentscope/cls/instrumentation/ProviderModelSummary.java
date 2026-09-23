package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import java.util.List;
import java.util.TreeSet;

/** Sorted distinct provider/model sets observed within one invocation, capped at 32 each. */
final class ProviderModelSummary {
    static final int MAX_ITEMS = 32;

    private final TreeSet<String> providers = new TreeSet<>();
    private final TreeSet<String> models = new TreeSet<>();
    private boolean overflowed;

    synchronized void record(String provider, String model) {
        overflowed |= addCapped(providers, provider);
        overflowed |= addCapped(models, model);
    }

    private static boolean addCapped(TreeSet<String> set, String value) {
        if (value == null || value.isBlank() || set.contains(value)) {
            return false;
        }
        if (set.size() >= MAX_ITEMS) {
            return true;
        }
        set.add(value);
        return false;
    }

    synchronized boolean overflowed() {
        return overflowed;
    }

    synchronized List<String> providers() {
        return List.copyOf(providers);
    }

    synchronized List<String> models() {
        return List.copyOf(models);
    }
}
