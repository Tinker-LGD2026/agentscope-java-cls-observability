package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

/** Per-instance Reactor context keys so two CLS observability instances never share state. */
final class SdkContextKeys {
    private final Object invocationKey;
    private final Object agentKey;
    private final Object stepKey;
    private final Object otelContextKey;
    private final Object guardKey;

    private SdkContextKeys(
            Object invocationKey,
            Object agentKey,
            Object stepKey,
            Object otelContextKey,
            Object guardKey) {
        this.invocationKey = invocationKey;
        this.agentKey = agentKey;
        this.stepKey = stepKey;
        this.otelContextKey = otelContextKey;
        this.guardKey = guardKey;
    }

    /** 0.3 default: keys unique to one middleware instance. */
    static SdkContextKeys instance() {
        return new SdkContextKeys(
                new Object(), new Object(), new Object(), new Object(), new Object());
    }

    /**
     * 0.2-compatible shared class keys used only by the legacy hook mode. The guard key stays
     * per-instance: duplicate detection is a 0.3 addition with no 0.2 counterpart, and a shared
     * guard key would make two distinct legacy instances flag each other.
     */
    static SdkContextKeys legacy() {
        return new SdkContextKeys(
                InvocationState.class,
                InvocationState.AgentFrame.class,
                InvocationState.StepFrame.class,
                io.opentelemetry.context.Context.class,
                new Object());
    }

    Object invocationKey() {
        return invocationKey;
    }

    Object agentKey() {
        return agentKey;
    }

    Object stepKey() {
        return stepKey;
    }

    Object otelContextKey() {
        return otelContextKey;
    }

    Object guardKey() {
        return guardKey;
    }
}
