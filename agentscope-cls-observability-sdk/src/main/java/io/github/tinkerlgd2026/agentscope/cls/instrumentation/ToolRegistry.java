package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks tool contexts and per-step tool counts for one invocation. Capacity failures create
 * no-op tokens that consume later end/result events without creating spans; tool failures
 * aggregate into the parent partial-failure signal.
 */
final class ToolRegistry {
    static final int DEFAULT_MAX_TOOL_CONTEXTS = 1024;
    static final int DEFAULT_MAX_TOOLS_PER_STEP = 128;

    private final int maxToolContexts;
    private final int maxToolsPerStep;
    private final Map<String, ToolToken> contexts = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> stepTools = new ConcurrentHashMap<>();
    private final AtomicLong failedTools = new AtomicLong();
    private final AtomicLong capacityRejected = new AtomicLong();
    private final AtomicLong duplicateRejected = new AtomicLong();

    ToolRegistry() {
        this(DEFAULT_MAX_TOOL_CONTEXTS, DEFAULT_MAX_TOOLS_PER_STEP);
    }

    ToolRegistry(int maxToolContexts, int maxToolsPerStep) {
        if (maxToolContexts <= 0 || maxToolsPerStep <= 0) {
            throw new IllegalArgumentException("tool limits must be positive");
        }
        this.maxToolContexts = maxToolContexts;
        this.maxToolsPerStep = maxToolsPerStep;
    }

    /** Capacity checks and registration are atomic: exact bounds, never approximate. */
    synchronized ToolToken startTool(String agentId, String stepId, String callId, String name) {
        if (callId == null || callId.isBlank()) {
            capacityRejected.incrementAndGet();
            return ToolToken.noop();
        }
        Set<String> stepSet =
                stepTools.computeIfAbsent(stepId, ignored -> ConcurrentHashMap.newKeySet());
        if (contexts.containsKey(callId)) {
            duplicateRejected.incrementAndGet();
            return ToolToken.noop();
        }
        if (contexts.size() >= maxToolContexts || stepSet.size() >= maxToolsPerStep) {
            capacityRejected.incrementAndGet();
            return ToolToken.noop();
        }
        ToolToken token = new ToolToken(this, callId, true);
        contexts.put(callId, token);
        stepSet.add(callId);
        return token;
    }

    long failedToolCount() {
        return failedTools.get();
    }

    long capacityRejectedTools() {
        return capacityRejected.get();
    }

    long duplicateRejectedTools() {
        return duplicateRejected.get();
    }

    /** Partial failure: some tool failed while the invocation itself may still succeed. */
    boolean partialFailure() {
        return failedTools.get() > 0;
    }

    static final class ToolToken implements java.io.Closeable {
        private static final ToolToken NOOP = new ToolToken(null, "", false);

        private final ToolRegistry owner;
        private final String callId;
        private final boolean active;
        private final java.util.concurrent.atomic.AtomicBoolean ended =
                new java.util.concurrent.atomic.AtomicBoolean();

        private ToolToken(ToolRegistry owner, String callId, boolean active) {
            this.owner = owner;
            this.callId = callId;
            this.active = active;
        }

        private static ToolToken noop() {
            return NOOP;
        }

        public boolean active() {
            return active;
        }

        /**
         * Records the tool end; no-op tokens consume the event without side effects. Note that
         * a no-op token's failure is intentionally invisible to partialFailure — capacity
         * rejections are reported through capacityRejectedTools instead.
         */
        public void end(boolean success) {
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            if (active && owner != null) {
                owner.contexts.remove(callId);
                owner.stepTools.values().forEach(set -> set.remove(callId));
                if (!success) {
                    owner.failedTools.incrementAndGet();
                }
            }
        }

        /** Consumes a result event; retained for the no-op lifecycle contract. */
        public void result() {
            // No-op by design: results never create spans.
        }

        @Override
        public void close() {
            end(true);
        }
    }
}
