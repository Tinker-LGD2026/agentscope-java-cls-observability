package io.github.tinkerlgd2026.agentscope.cls;

/** Controls how CLS OpenTelemetry context is propagated through Reactor. */
public enum ReactorContextMode {
    /** Uses only SDK-instance-private Reactor context keys. */
    PRIVATE,
    /** Publishes context for a host-managed OpenTelemetry Reactor integration. */
    BRIDGE,
    /** Preserves the deprecated 0.2 process-wide Reactor hook behavior. */
    LEGACY_HOOK
}
