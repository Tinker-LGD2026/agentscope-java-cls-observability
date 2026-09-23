package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

final class InvocationState {
    private final String sessionId;
    private final String userId;
    private final String userName;
    private final String turnId;
    private final String agentType;
    private final String entryType;
    private final ConcurrentHashMap<String, AtomicInteger> rounds = new ConcurrentHashMap<>();
    private volatile @Nullable Span entrySpan;
    private volatile @Nullable InvocationLease lease;
    private volatile @Nullable ControlEventTracker controlTracker;
    private volatile @Nullable InvocationCaptureBudget controlBudget;
    private volatile @Nullable String resumeFromTurnId;

    InvocationState(
            String sessionId,
            String userId,
            String userName,
            String turnId,
            String agentType,
            String entryType) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.userName = userName;
        this.turnId = turnId;
        this.agentType = agentType;
        this.entryType = entryType;
    }

    String sessionId() {
        return sessionId;
    }

    String userId() {
        return userId;
    }

    String userName() {
        return userName;
    }

    String turnId() {
        return turnId;
    }

    String agentType() {
        return agentType;
    }

    String entryType() {
        return entryType;
    }

    void bindEntrySpan(Span value) {
        entrySpan = value;
    }

    void bindLease(InvocationLease newLease) {
        lease = newLease;
    }

    void bindControlPlane(
            InvocationLease newLease,
            ControlEventTracker tracker,
            InvocationCaptureBudget budget) {
        lease = newLease;
        controlTracker = tracker;
        controlBudget = budget;
    }

    @Nullable InvocationCaptureBudget controlBudget() {
        return controlBudget;
    }

    @Nullable InvocationLease lease() {
        return lease;
    }

    @Nullable ControlEventTracker controlTracker() {
        return controlTracker;
    }

    void resumeFromTurnId(@Nullable String value) {
        resumeFromTurnId = value;
    }

    @Nullable String resumeFromTurnId() {
        return resumeFromTurnId;
    }

    void recordProvider(String provider) {
        Span current = entrySpan;
        if (current != null) {
            current.setAttribute("gen_ai.provider.name", provider);
        }
    }

    AgentFrame newAgentFrame(String configuredId, String name) {
        String agentId =
                configuredId == null || configuredId.isBlank()
                        ? UUID.randomUUID().toString().replace("-", "")
                        : configuredId;
        return new AgentFrame(agentId, name == null || name.isBlank() ? "unknown" : name);
    }

    StepFrame nextStep(AgentFrame agent) {
        int round =
                rounds.computeIfAbsent(agent.agentId(), ignored -> new AtomicInteger()).incrementAndGet();
        return new StepFrame(
                turnId + ":s:" + round + ":a:" + stableAgentPart(agent.agentId()), round);
    }

    private static String stableAgentPart(String agentId) {
        return UUID.nameUUIDFromBytes(agentId.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
    }

    static final class AgentFrame {
        private final String agentId;
        private final String agentName;
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();
        private final AtomicLong cacheReadTokens = new AtomicLong();
        private final AtomicLong toolCalls = new AtomicLong();
        private final ConcurrentHashMap<String, ToolContext> toolContexts =
                new ConcurrentHashMap<>();
        private final java.util.Set<StepFrame> steps = ConcurrentHashMap.newKeySet();
        private volatile @Nullable StepFrame currentStep;
        private volatile String modelName = "unknown";
        private volatile String providerName = "unknown";

        AgentFrame(String agentId, String agentName) {
            this.agentId = agentId;
            this.agentName = agentName;
        }

        String agentId() {
            return agentId;
        }

        String agentName() {
            return agentName;
        }

        @Nullable StepFrame currentStep() {
            return currentStep;
        }

        void currentStep(StepFrame value) {
            currentStep = value;
            steps.add(value);
        }

        java.util.Set<StepFrame> steps() {
            return java.util.Set.copyOf(steps);
        }

        void registerToolContext(
                String callId, StepFrame step, String model, String provider) {
            if (callId != null && !callId.isBlank()) {
                toolContexts.put(callId, new ToolContext(step, model, provider));
            }
        }

        void registerToolContextIfAbsent(
                String callId, StepFrame step, String model, String provider) {
            if (callId != null && !callId.isBlank()) {
                toolContexts.putIfAbsent(callId, new ToolContext(step, model, provider));
            }
        }

        @Nullable ToolContext toolContext(String callId) {
            return callId == null ? null : toolContexts.get(callId);
        }

        void clearToolContext(String callId) {
            if (callId != null) {
                toolContexts.remove(callId);
            }
        }

        void modelContext(String model, String provider) {
            modelName = model;
            providerName = provider;
        }

        String modelName() {
            return modelName;
        }

        String providerName() {
            return providerName;
        }

        void addUsage(long input, long output, long cacheRead) {
            inputTokens.addAndGet(input);
            outputTokens.addAndGet(output);
            cacheReadTokens.addAndGet(cacheRead);
        }

        void addToolCalls(long count) {
            toolCalls.addAndGet(count);
        }

        record ToolContext(StepFrame step, String model, String provider) {}

        void applyTotals(Span span) {
            span.setAttribute("gen_ai.agent.tool_call_count", toolCalls.get());
            span.setAttribute("gen_ai.usage.input_tokens", inputTokens.get());
            span.setAttribute("gen_ai.usage.output_tokens", outputTokens.get());
            span.setAttribute("gen_ai.usage.total_tokens", inputTokens.get() + outputTokens.get());
            span.setAttribute("gen_ai.usage.cache_read.input_tokens", cacheReadTokens.get());
            span.setAttribute("gen_ai.usage.cache_creation.input_tokens", 0L);
        }
    }

    static final class StepFrame {
        private final String stepId;
        private final int round;
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile @Nullable Span span;
        private volatile @Nullable Context spanContext;

        StepFrame(String stepId, int round) {
            this.stepId = stepId;
            this.round = round;
        }

        String stepId() {
            return stepId;
        }

        int round() {
            return round;
        }

        @Nullable Context spanContext() {
            return spanContext;
        }

        void bind(Span span, Context spanContext) {
            this.span = span;
            this.spanContext = spanContext;
        }

        void finishSuccess(String reason) {
            Span current = span;
            if (current != null && ended.compareAndSet(false, true)) {
                try {
                    current.setAttribute("gen_ai.react.finish_reason", reason);
                    current.setStatus(StatusCode.OK);
                } finally {
                    current.end();
                }
            }
        }

        void finishError(String reason, @Nullable Throwable error) {
            Span current = span;
            if (current != null && ended.compareAndSet(false, true)) {
                try {
                    String errorType = error == null ? reason : error.getClass().getName();
                    current.setAttribute("gen_ai.react.finish_reason", reason);
                    current.setAttribute("error.type", errorType);
                    current.setStatus(StatusCode.ERROR, Objects.requireNonNull(reason));
                    // Exception events are only recorded for a real Throwable; controlled
                    // terminations like cancellation never fabricate one.
                    if (error != null) {
                        current.addEvent(
                                "exception",
                                Objects.requireNonNull(
                                        Attributes.builder()
                                                .put(
                                                        "exception.type",
                                                        Objects.requireNonNull(errorType))
                                                .build()));
                    }
                } finally {
                    current.end();
                }
            }
        }
    }
}
