package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.github.tinkerlgd2026.agentscope.cls.ReactorContextMode;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;

/**
 * Mode-driven OTel context propagation. PRIVATE writes only instance keys; BRIDGE only calls
 * runWithContext; LEGACY_HOOK keeps the 0.2 global registration and never resets it.
 */
final class ReactorContextPropagation {
    private static final AtomicBoolean LEGACY_HOOK_REGISTERED = new AtomicBoolean();

    private final ReactorContextMode mode;

    private ReactorContextPropagation(ReactorContextMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    static ReactorContextPropagation of(ReactorContextMode mode) {
        ReactorContextPropagation propagation = new ReactorContextPropagation(mode);
        if (mode == ReactorContextMode.LEGACY_HOOK) {
            // 0.2 registered the hook at construction time; keep that timing.
            registerLegacyHookOnce();
        }
        return propagation;
    }

    ReactorContextMode mode() {
        return mode;
    }

    <T> Flux<T> apply(Flux<T> flux, Context spanContext, Object otelContextKey) {
        Objects.requireNonNull(flux, "flux");
        Objects.requireNonNull(spanContext, "spanContext");
        Flux<T> published =
                switch (mode) {
                    case PRIVATE -> flux;
                    case BRIDGE -> ContextPropagationOperator.runWithContext(flux, spanContext);
                    case LEGACY_HOOK -> {
                        registerLegacyHookOnce();
                        yield ContextPropagationOperator.runWithContext(flux, spanContext);
                    }
                };
        return published.contextWrite(context -> context.put(otelContextKey, spanContext));
    }

    /** 0.2-compatible global registration; never resets the process-wide operator. */
    static void registerLegacyHookOnce() {
        if (LEGACY_HOOK_REGISTERED.compareAndSet(false, true)) {
            ContextPropagationOperator.builder().build().registerOnEachOperator();
        }
    }

    static boolean legacyHookRegistered() {
        return LEGACY_HOOK_REGISTERED.get();
    }
}
