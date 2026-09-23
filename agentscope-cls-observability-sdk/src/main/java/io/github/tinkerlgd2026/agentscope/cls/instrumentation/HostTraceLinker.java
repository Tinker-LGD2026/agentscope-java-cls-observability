package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import reactor.util.context.ContextView;

/**
 * CLS entry spans stay roots of their own trace; when enabled, the original host span context
 * (captured before any CLS context write) is attached as an OTel link.
 */
final class HostTraceLinker {
    /** Shared snapshot key: written only when absent, never holds a CLS span. */
    static final Object HOST_CONTEXT_SNAPSHOT_KEY = new Object();

    private final boolean enabled;

    HostTraceLinker(boolean enabled) {
        this.enabled = enabled;
    }

    SpanBuilder apply(SpanBuilder builder, @Nullable SpanContext hostSpanContext) {
        Objects.requireNonNull(builder, "builder").setNoParent();
        if (enabled && hostSpanContext != null && hostSpanContext.isValid()) {
            builder.addLink(hostSpanContext);
        }
        return builder;
    }

    /** Captures the host span context into the Reactor context once, immutably. */
    static reactor.util.context.Context writeSnapshot(
            reactor.util.context.Context context, ContextView source) {
        if (context.hasKey(HOST_CONTEXT_SNAPSHOT_KEY)) {
            return context;
        }
        // Reactor context rejects null values; an absent host is stored as the invalid
        // sentinel so the write-once guarantee still holds.
        SpanContext host = hostSpanContext(source);
        return context.put(
                HOST_CONTEXT_SNAPSHOT_KEY, host == null ? SpanContext.getInvalid() : host);
    }

    static @Nullable SpanContext readSnapshot(ContextView context) {
        Object value = context.getOrDefault(HOST_CONTEXT_SNAPSHOT_KEY, null);
        return value instanceof SpanContext spanContext ? spanContext : null;
    }

    static @Nullable SpanContext hostSpanContext(ContextView context) {
        try {
            Context otel =
                    io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator
                            .getOpenTelemetryContextFromContextView(context, Context.current());
            Span span = Span.fromContext(otel);
            return span.getSpanContext().isValid() ? span.getSpanContext() : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
