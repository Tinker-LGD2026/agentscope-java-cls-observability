package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.agentscope.core.agent.Agent;
import java.util.Objects;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/**
 * Detects duplicate instrumentation by the same middleware instance within one callback
 * subscription chain. Callback identity = callback type + Agent identity + Input identity;
 * duplicate wrapping in the onion model receives identical objects, nested agents use
 * different objects and are never flagged.
 */
final class MiddlewareInvocationGuard {
    private final Object guardKey;
    private final TelemetryCounters counters;

    MiddlewareInvocationGuard(Object guardKey, TelemetryCounters counters) {
        this.guardKey = Objects.requireNonNull(guardKey, "guardKey");
        this.counters = Objects.requireNonNull(counters, "counters");
    }

    /** Returns true when this callback is not a duplicate and instrumentation may proceed. */
    boolean enter(ContextView context, String callbackType, Agent agent, Object input) {
        CallbackIdentity current = new CallbackIdentity(callbackType, agent, input);
        Object existing = context.getOrDefault(guardKey, null);
        if (current.equals(existing)) {
            counters.duplicateMiddlewareDetected(1);
            return false;
        }
        return true;
    }

    /** Publishes the current callback identity downstream for duplicate detection. */
    Context write(Context context, String callbackType, Agent agent, Object input) {
        return context.put(guardKey, new CallbackIdentity(callbackType, agent, input));
    }

    private static final class CallbackIdentity {
        private final String callbackType;
        private final int agentIdentity;
        private final int inputIdentity;

        private CallbackIdentity(String callbackType, Agent agent, Object input) {
            this.callbackType = callbackType;
            this.agentIdentity = System.identityHashCode(agent);
            this.inputIdentity = System.identityHashCode(input);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CallbackIdentity identity)) {
                return false;
            }
            return callbackType.equals(identity.callbackType)
                    && agentIdentity == identity.agentIdentity
                    && inputIdentity == identity.inputIdentity;
        }

        @Override
        public int hashCode() {
            return Objects.hash(callbackType, agentIdentity, inputIdentity);
        }
    }
}
