package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tinkerlgd2026.agentscope.cls.ReactorContextMode;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

class ReactorContextPropagationTest {

    @Test
    void privateModeWritesOnlyTheInstanceKeyWithoutOperator() {
        Object key = new Object();
        AtomicReference<ContextView> seen = new AtomicReference<>();

        // Reactor context flows bottom-up: readers sit upstream of the writer, exactly like
        // nested middleware reading the context the outer middleware wrote.
        ReactorContextPropagation.of(ReactorContextMode.PRIVATE)
                .apply(
                        Flux.just(1)
                                .transformDeferredContextual(
                                        (flux, contextView) -> {
                                            seen.set(contextView);
                                            return flux;
                                        }),
                        Context.root(),
                        key)
                .blockLast();

        ContextView context = seen.get();
        Object stored = context.getOrDefault(key, (Object) null);
        assertThat(stored).isNotNull();
        // PRIVATE never consults the operator: reading through it yields the fallback.
        Context fallback = Context.root();
        assertThat(
                        ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                                context, fallback))
                .isSameAs(fallback);
    }

    @Test
    void bridgeModeUsesRunWithContextWithoutGlobalRegistration() {
        Object key = new Object();
        boolean registeredBefore = ReactorContextPropagation.legacyHookRegistered();
        AtomicReference<Context> current = new AtomicReference<>();

        ReactorContextPropagation.of(ReactorContextMode.BRIDGE)
                .apply(Flux.just(1), Context.root(), key)
                .transformDeferredContextual(
                        (flux, contextView) -> {
                            current.set(
                                    ContextPropagationOperator
                                            .getOpenTelemetryContextFromContextView(
                                                    contextView, Context.root()));
                            return flux;
                        })
                .blockLast();

        assertThat(current.get()).isNotNull();
        // BRIDGE must not register the process-wide legacy operator.
        assertThat(ReactorContextPropagation.legacyHookRegistered()).isEqualTo(registeredBefore);
    }

    @Test
    void legacyHookRegistersOnceAndNeverResets() {
        ReactorContextPropagation.registerLegacyHookOnce();
        boolean first = ReactorContextPropagation.legacyHookRegistered();
        ReactorContextPropagation.registerLegacyHookOnce();
        assertThat(first).isTrue();
        assertThat(ReactorContextPropagation.legacyHookRegistered()).isTrue();
    }
}
