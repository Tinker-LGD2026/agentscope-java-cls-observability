package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.github.tinkerlgd2026.agentscope.cls.ClsInvocationContext;
import io.github.tinkerlgd2026.agentscope.cls.ClsResumeContext;
import io.github.tinkerlgd2026.agentscope.cls.ReactorContextMode;
import io.github.tinkerlgd2026.agentscope.cls.internal.CaptureMemoryPool;
import io.github.tinkerlgd2026.agentscope.cls.internal.config.ConfigBounds;
import io.github.tinkerlgd2026.agentscope.cls.internal.JsonSupport;
import io.github.tinkerlgd2026.agentscope.cls.internal.IdentityNormalizer;
import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.privacy.CanonicalPayloadCapture;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentSanitizer;
import io.github.tinkerlgd2026.agentscope.cls.privacy.MessageCapturePolicy;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsFields;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.util.context.ContextView;

/**
 * Produces the CLS Agent Trace span tree from the public AgentScope middleware hooks.
 *
 * <p>The middleware is built so telemetry can never change the behaviour of the host agent: every
 * span setup step and every event callback is guarded, and any telemetry failure downgrades to
 * running the untouched business flux while the failure is counted and logged at a bounded rate.
 */
public final class ClsTracingMiddleware implements MiddlewareBase, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClsTracingMiddleware.class);
    private static final long WARNING_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final Tracer tracer;
    private final ContentSanitizer contentSanitizer;
    private final MessageCapturePolicy messageCapturePolicy;
    private final ContentCaptureMode contentCaptureMode;
    private final ContentCaptureMode reasoningCaptureMode;
    private final ContentCaptureMode providerPayloadCaptureMode;
    private final int maxContentBytes;
    private final int truncatePreviewBytes;
    private final long maxInvocationCaptureMemoryBytes;
    private final CaptureMemoryPool captureMemoryPool;
    private final BoundedMessageCapture boundedMessageCapture;
    private final ProviderPayloadSummary providerPayloadSummary;
    private final ObjectMapper objectMapper;
    private final ObjectWriter canonicalWriter;
    private final AgentScopeMessageConverter messageConverter;
    private final BooleanSupplier active;
    private final TelemetryCounters counters;
    private final SdkContextKeys contextKeys;
    private final ReactorContextPropagation contextPropagation;
    private final MiddlewareInvocationGuard invocationGuard;
    private final HostTraceLinker hostTraceLinker;
    private final java.time.Duration hitlWaitTimeout;
    private volatile java.util.concurrent.ScheduledExecutorService controlScheduler;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong lastWarningNanos = new AtomicLong(Long.MIN_VALUE);

    public ClsTracingMiddleware(
            Tracer tracer, ContentSanitizer contentSanitizer, BooleanSupplier active) {
        this(tracer, contentSanitizer, active, new TelemetryCounters());
    }

    public ClsTracingMiddleware(
            Tracer tracer,
            ContentSanitizer contentSanitizer,
            BooleanSupplier active,
            TelemetryCounters counters) {
        this(
                tracer,
                contentSanitizer,
                new MessageCapturePolicy(
                        JsonSupport.newObjectMapper(),
                        contentSanitizer.mode(),
                        ContentCaptureMode.OFF,
                        contentSanitizer.maxBytes()),
                contentSanitizer.mode(),
                ContentCaptureMode.OFF,
                contentSanitizer.maxBytes(),
                active,
                counters,
                JsonSupport.newObjectMapper(),
                false);
    }

    public ClsTracingMiddleware(
            Tracer tracer,
            ContentSanitizer contentSanitizer,
            BooleanSupplier active,
            TelemetryCounters counters,
            ObjectMapper objectMapper,
            boolean reactorContextHookEnabled) {
        this(
                tracer,
                contentSanitizer,
                new MessageCapturePolicy(
                        objectMapper,
                        contentSanitizer.mode(),
                        ContentCaptureMode.OFF,
                        contentSanitizer.maxBytes()),
                contentSanitizer.mode(),
                ContentCaptureMode.OFF,
                contentSanitizer.maxBytes(),
                active,
                counters,
                objectMapper,
                reactorContextHookEnabled);
    }

    public ClsTracingMiddleware(
            Tracer tracer,
            ContentSanitizer contentSanitizer,
            MessageCapturePolicy messageCapturePolicy,
            ContentCaptureMode contentCaptureMode,
            ContentCaptureMode reasoningCaptureMode,
            int maxContentBytes,
            BooleanSupplier active,
            TelemetryCounters counters,
            ObjectMapper objectMapper,
            boolean reactorContextHookEnabled) {
        this(
                tracer,
                contentSanitizer,
                messageCapturePolicy,
                contentCaptureMode,
                reasoningCaptureMode,
                ContentCaptureMode.OFF,
                Math.min(4096, maxContentBytes),
                maxContentBytes,
                new CaptureMemoryPool(Math.max(maxContentBytes, 8L * 1024 * 1024)),
                maxContentBytes,
                active,
                counters,
                objectMapper,
                reactorContextHookEnabled);
    }

    public ClsTracingMiddleware(
            Tracer tracer,
            ContentSanitizer contentSanitizer,
            BooleanSupplier active,
            TelemetryCounters counters,
            ObjectMapper objectMapper,
            boolean reactorContextHookEnabled,
            java.time.Duration hitlWaitTimeout) {
        this(
                tracer,
                contentSanitizer,
                new MessageCapturePolicy(
                        objectMapper,
                        contentSanitizer.mode(),
                        ContentCaptureMode.OFF,
                        contentSanitizer.maxBytes()),
                contentSanitizer.mode(),
                ContentCaptureMode.OFF,
                ContentCaptureMode.OFF,
                Math.min(4096, contentSanitizer.maxBytes()),
                contentSanitizer.maxBytes(),
                new CaptureMemoryPool(
                        Math.max(contentSanitizer.maxBytes(), 8L * 1024 * 1024)),
                contentSanitizer.maxBytes(),
                active,
                counters,
                objectMapper,
                reactorContextHookEnabled,
                hitlWaitTimeout);
    }

    public ClsTracingMiddleware(
            Tracer tracer,
            ContentSanitizer contentSanitizer,
            MessageCapturePolicy messageCapturePolicy,
            ContentCaptureMode contentCaptureMode,
            ContentCaptureMode reasoningCaptureMode,
            ContentCaptureMode providerPayloadCaptureMode,
            int truncatePreviewBytes,
            int maxContentBytes,
            CaptureMemoryPool captureMemoryPool,
            long maxInvocationCaptureMemoryBytes,
            BooleanSupplier active,
            TelemetryCounters counters,
            ObjectMapper objectMapper,
            boolean reactorContextHookEnabled) {
        this(
                tracer,
                contentSanitizer,
                messageCapturePolicy,
                contentCaptureMode,
                reasoningCaptureMode,
                providerPayloadCaptureMode,
                truncatePreviewBytes,
                maxContentBytes,
                captureMemoryPool,
                maxInvocationCaptureMemoryBytes,
                active,
                counters,
                objectMapper,
                reactorContextHookEnabled,
                Duration.ofMinutes(10));
    }

    public ClsTracingMiddleware(
            Tracer tracer,
            ContentSanitizer contentSanitizer,
            MessageCapturePolicy messageCapturePolicy,
            ContentCaptureMode contentCaptureMode,
            ContentCaptureMode reasoningCaptureMode,
            ContentCaptureMode providerPayloadCaptureMode,
            int truncatePreviewBytes,
            int maxContentBytes,
            CaptureMemoryPool captureMemoryPool,
            long maxInvocationCaptureMemoryBytes,
            BooleanSupplier active,
            TelemetryCounters counters,
            ObjectMapper objectMapper,
            boolean reactorContextHookEnabled,
            java.time.Duration hitlWaitTimeout) {
        this(
                tracer,
                contentSanitizer,
                messageCapturePolicy,
                contentCaptureMode,
                reasoningCaptureMode,
                providerPayloadCaptureMode,
                truncatePreviewBytes,
                maxContentBytes,
                captureMemoryPool,
                maxInvocationCaptureMemoryBytes,
                active,
                counters,
                objectMapper,
                reactorContextHookEnabled
                        ? ReactorContextMode.LEGACY_HOOK
                        : ReactorContextMode.PRIVATE,
                ConfigBounds.DEFAULT_HOST_TRACE_LINK_ENABLED,
                hitlWaitTimeout);
    }

    public ClsTracingMiddleware(
            Tracer tracer,
            ContentSanitizer contentSanitizer,
            MessageCapturePolicy messageCapturePolicy,
            ContentCaptureMode contentCaptureMode,
            ContentCaptureMode reasoningCaptureMode,
            ContentCaptureMode providerPayloadCaptureMode,
            int truncatePreviewBytes,
            int maxContentBytes,
            CaptureMemoryPool captureMemoryPool,
            long maxInvocationCaptureMemoryBytes,
            BooleanSupplier active,
            TelemetryCounters counters,
            ObjectMapper objectMapper,
            ReactorContextMode reactorContextMode,
            boolean hostTraceLinkEnabled,
            java.time.Duration hitlWaitTimeout) {
        if (tracer == null
                || contentSanitizer == null
                || messageCapturePolicy == null
                || contentCaptureMode == null
                || reasoningCaptureMode == null
                || providerPayloadCaptureMode == null
                || captureMemoryPool == null
                || active == null
                || counters == null
                || objectMapper == null
                || reactorContextMode == null) {
            throw new IllegalArgumentException("CLS tracing dependencies are required");
        }
        this.tracer = tracer;
        this.contentSanitizer = contentSanitizer;
        this.messageCapturePolicy = messageCapturePolicy;
        this.contentCaptureMode = contentCaptureMode;
        this.reasoningCaptureMode = reasoningCaptureMode;
        this.providerPayloadCaptureMode = providerPayloadCaptureMode;
        this.maxContentBytes = maxContentBytes;
        this.truncatePreviewBytes = truncatePreviewBytes;
        this.captureMemoryPool = captureMemoryPool;
        this.maxInvocationCaptureMemoryBytes = maxInvocationCaptureMemoryBytes;
        this.boundedMessageCapture =
                new BoundedMessageCapture(
                        objectMapper,
                        contentCaptureMode,
                        reasoningCaptureMode,
                        providerPayloadCaptureMode,
                        truncatePreviewBytes,
                        maxContentBytes);
        this.objectMapper = objectMapper;
        this.active = active;
        this.counters = counters;
        this.canonicalWriter =
                objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.messageConverter = new AgentScopeMessageConverter();
        this.providerPayloadSummary =
                new ProviderPayloadSummary(
                        this.messageConverter,
                        new CanonicalPayloadCapture(objectMapper),
                        providerPayloadCaptureMode,
                        truncatePreviewBytes,
                        maxContentBytes);
        this.contextKeys =
                reactorContextMode == ReactorContextMode.LEGACY_HOOK
                        ? SdkContextKeys.legacy()
                        : SdkContextKeys.instance();
        this.contextPropagation = ReactorContextPropagation.of(reactorContextMode);
        this.invocationGuard = new MiddlewareInvocationGuard(contextKeys.guardKey(), counters);
        this.hostTraceLinker = new HostTraceLinker(hostTraceLinkEnabled);
        this.hitlWaitTimeout =
                Objects.requireNonNull(hitlWaitTimeout, "hitlWaitTimeout");
    }

    @Override
    public void close() {
        closed.set(true);
        java.util.concurrent.ScheduledExecutorService scheduler = controlScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

    private boolean isActive() {
        return !closed.get() && active.getAsBoolean();
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext runtimeContext,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                reactorContext -> {
                    if (!isActive()) {
                        counters.dropped(1);
                        return next.apply(input);
                    }
                    if (!invocationGuard.enter(reactorContext, "onAgent", agent, input)) {
                        return next.apply(input);
                    }
                    InvocationState inherited =
                            get(
                                    reactorContext,
                                    contextKeys.invocationKey(),
                                    InvocationState.class);
                    if (inherited == null && !hasIdentity(runtimeContext)) {
                        counters.dropped(1);
                        return next.apply(input);
                    }
                    TrackedNext<AgentInput> tracked = new TrackedNext<>(next);
                    return guarded(
                                    tracked,
                                    input,
                                    next,
                                    () ->
                                            instrumentAgent(
                                                    agent,
                                                    runtimeContext,
                                                    input,
                                                    tracked,
                                                    reactorContext))
                            .contextWrite(
                                    context ->
                                            invocationGuard.write(
                                                    context, "onAgent", agent, input));
                });
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext runtimeContext,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                reactorContext -> {
                    if (!isActive()) {
                        return next.apply(input);
                    }
                    if (!invocationGuard.enter(reactorContext, "onReasoning", agent, input)) {
                        return next.apply(input);
                    }
                    InvocationState state =
                            get(
                                    reactorContext,
                                    contextKeys.invocationKey(),
                                    InvocationState.class);
                    InvocationState.AgentFrame agentFrame =
                            get(
                                    reactorContext,
                                    contextKeys.agentKey(),
                                    InvocationState.AgentFrame.class);
                    if (state == null || agentFrame == null) {
                        return next.apply(input);
                    }
                    TrackedNext<ReasoningInput> tracked = new TrackedNext<>(next);
                    return guarded(
                                    tracked,
                                    input,
                                    next,
                                    () ->
                                            instrumentReasoning(
                                                    state,
                                                    agentFrame,
                                                    input,
                                                    tracked,
                                                    reactorContext))
                            .contextWrite(
                                    context ->
                                            invocationGuard.write(
                                                    context, "onReasoning", agent, input));
                });
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext runtimeContext,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                reactorContext -> {
                    if (!isActive()) {
                        return next.apply(input);
                    }
                    if (!invocationGuard.enter(reactorContext, "onModelCall", agent, input)) {
                        return next.apply(input);
                    }
                    InvocationState state =
                            get(
                                    reactorContext,
                                    contextKeys.invocationKey(),
                                    InvocationState.class);
                    InvocationState.AgentFrame agentFrame =
                            get(
                                    reactorContext,
                                    contextKeys.agentKey(),
                                    InvocationState.AgentFrame.class);
                    if (state == null || agentFrame == null) {
                        return next.apply(input);
                    }
                    TrackedNext<ModelCallInput> tracked = new TrackedNext<>(next);
                    return guarded(
                                    tracked,
                                    input,
                                    next,
                                    () ->
                                            instrumentModelCall(
                                                    state,
                                                    agentFrame,
                                                    input,
                                                    tracked,
                                                    reactorContext))
                            .contextWrite(
                                    context ->
                                            invocationGuard.write(
                                                    context, "onModelCall", agent, input));
                });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext runtimeContext,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.deferContextual(
                reactorContext -> {
                    if (!isActive()) {
                        return next.apply(input);
                    }
                    if (!invocationGuard.enter(reactorContext, "onActing", agent, input)) {
                        return next.apply(input);
                    }
                    InvocationState state =
                            get(
                                    reactorContext,
                                    contextKeys.invocationKey(),
                                    InvocationState.class);
                    InvocationState.AgentFrame agentFrame =
                            get(
                                    reactorContext,
                                    contextKeys.agentKey(),
                                    InvocationState.AgentFrame.class);
                    if (state == null || agentFrame == null) {
                        return next.apply(input);
                    }
                    TrackedNext<ActingInput> tracked = new TrackedNext<>(next);
                    return guarded(
                                    tracked,
                                    input,
                                    next,
                                    () ->
                                            instrumentActing(
                                                    state,
                                                    agentFrame,
                                                    input,
                                                    tracked,
                                                    reactorContext))
                            .contextWrite(
                                    context ->
                                            invocationGuard.write(
                                                    context, "onActing", agent, input));
                });
    }

    private Flux<AgentEvent> instrumentReasoning(
            InvocationState state,
            InvocationState.AgentFrame agentFrame,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next,
            ContextView reactorContext) {
        InvocationState.StepFrame pendingStep = state.nextStep(agentFrame);
        Context parent = resolveOtelContext(Objects.requireNonNull(reactorContext));
        Span span =
                common(
                                tracer.spanBuilder("react round_" + pendingStep.round())
                                        .setSpanKind(SpanKind.INTERNAL)
                                        .setParent(Objects.requireNonNull(parent)),
                                state,
                                "step",
                                "react")
                        .setAttribute(ClsFields.AGENT_ID, agentFrame.agentId())
                        .setAttribute(ClsFields.STEP_ID, pendingStep.stepId())
                        .setAttribute("gen_ai.react.round", (long) pendingStep.round())
                        .startSpan();
        Context spanContext = span.storeInContext(Objects.requireNonNull(parent));
        pendingStep.bind(span, spanContext);
        agentFrame.currentStep(pendingStep);
        Flux<AgentEvent> downstream;
        try {
            downstream = next.apply(input);
        } catch (RuntimeException | Error exception) {
            quietly(() -> pendingStep.finishError("error", exception));
            return Flux.error(exception);
        }
        AtomicBoolean usedTools = new AtomicBoolean();
        Flux<AgentEvent> observed =
                downstream
                        .doOnNext(
                                event ->
                                        quietly(
                                                () -> {
                                                    if (event instanceof ToolCallStartEvent toolCall) {
                                                        usedTools.set(true);
                                                        agentFrame.registerToolContextIfAbsent(
                                                                toolCall.getToolCallId(),
                                                                pendingStep,
                                                                agentFrame.modelName(),
                                                                agentFrame.providerName());
                                                    }
                                                }))
                        .doOnComplete(
                                () ->
                                        quietly(
                                                () -> {
                                                    if (usedTools.get()) {
                                                        span.setAttribute(
                                                                "gen_ai.react.finish_reason",
                                                                "tool_calls");
                                                    } else {
                                                        pendingStep.finishSuccess("stop");
                                                    }
                                                }))
                        .doOnError(
                                error -> quietly(() -> pendingStep.finishError("error", error)))
                        .doOnCancel(
                                () -> quietly(() -> pendingStep.finishError("cancelled", null)));
        return propagate(observed, spanContext)
                .contextWrite(context -> context.put(contextKeys.stepKey(), pendingStep));
    }

    private Flux<AgentEvent> instrumentModelCall(
            InvocationState state,
            InvocationState.AgentFrame agentFrame,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next,
            ContextView reactorContext) {
        InvocationState.StepFrame step =
                get(reactorContext, contextKeys.stepKey(), InvocationState.StepFrame.class);
        String modelName =
                input.model() == null || input.model().getModelName() == null
                        ? "unknown"
                        : input.model().getModelName();
        String providerName = inferProvider(modelName);
        agentFrame.modelContext(modelName, providerName);
        state.recordProvider(providerName);
        Context parent = resolveOtelContext(Objects.requireNonNull(reactorContext));
        Span span =
                common(
                                tracer.spanBuilder("chat " + modelName)
                                        .setSpanKind(SpanKind.CLIENT)
                                        .setParent(Objects.requireNonNull(parent)),
                                state,
                                "chat",
                                "chat")
                        .setAttribute(ClsFields.AGENT_ID, agentFrame.agentId())
                        .setAttribute("gen_ai.provider.name", providerName)
                        .setAttribute("gen_ai.request.model", modelName)
                        .setAttribute("gen_ai.response.model", modelName)
                        .startSpan();
        if (step != null) {
            span.setAttribute(ClsFields.STEP_ID, step.stepId());
            span.setAttribute("gen_ai.react.round", (long) step.round());
        }
        try {
            captureMessages(
                    input.messages(), true, "gen_ai.input.messages", span);
        } catch (RuntimeException | StackOverflowError failure) {
            telemetryFailed(failure);
            span.setAttribute(
                    "gen_ai.input.messages.capture_error",
                    failure.getClass().getSimpleName());
        }
        long started = System.nanoTime();
        OutputMessageAccumulator output =
                new OutputMessageAccumulator(
                        objectMapper,
                        messageCapturePolicy,
                        contentCaptureMode,
                        reasoningCaptureMode,
                        maxContentBytes);
        Context spanContext = span.storeInContext(Objects.requireNonNull(parent));
        Flux<AgentEvent> downstream;
        try {
            downstream = next.apply(input);
        } catch (RuntimeException | Error exception) {
            quietly(
                    () -> {
                        setDuration(span, "gen_ai.chat.duration_ms", started);
                        endError(span, "model call failed", exception);
                    });
            return Flux.error(exception);
        }
        AtomicBoolean completedNormally = new AtomicBoolean();
        Flux<AgentEvent> observed =
                terminate(
                        downstream
                                .doOnNext(
                                        event ->
                                                quietly(
                                                        () ->
                                                                applyModelEvent(
                                                                        span,
                                                                        event,
                                                                        output,
                                                                        started,
                                                                        agentFrame,
                                                                        step,
                                                                        modelName,
                                                                        providerName)))
                                .doOnComplete(() -> completedNormally.set(true)),
                        span,
                        "model call failed",
                        () -> {
                            setDuration(span, "gen_ai.chat.duration_ms", started);
                            OutputMessageAccumulator.Result result =
                                    output.finish(System.nanoTime() - started);
                            if (completedNormally.get()) {
                                String finishReason = result.usedTools() ? "tool_calls" : "stop";
                                span.setAttribute(
                                        Objects.requireNonNull(
                                                AttributeKey.stringArrayKey(
                                                        "gen_ai.response.finish_reasons")),
                                        List.of(finishReason));
                                span.setAttribute("gen_ai.react.finish_reason", finishReason);
                            }
                            result.messages()
                                    .ifPresent(
                                            node ->
                                                    span.setAttribute(
                                                            "gen_ai.output.messages",
                                                            node.toString()));
                            writeReasoningMetrics(span, result);
                        });
        return propagate(observed, spanContext);
    }

    private Flux<AgentEvent> instrumentActing(
            InvocationState state,
            InvocationState.AgentFrame agentFrame,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next,
            ContextView reactorContext) {
        InvocationState.StepFrame contextualStep =
                get(reactorContext, contextKeys.stepKey(), InvocationState.StepFrame.class);
        InvocationState.StepFrame fallbackStep =
                contextualStep == null ? agentFrame.currentStep() : contextualStep;
        Context fallbackParent = resolveOtelContext(Objects.requireNonNull(reactorContext));
        Map<String, List<ToolSpan>> tools = new LinkedHashMap<>();
        Set<InvocationState.StepFrame> actingSteps = new LinkedHashSet<>();
        List<ToolUseBlock> toolCalls = input.toolCalls() == null ? List.of() : input.toolCalls();
        agentFrame.addToolCalls(toolCalls.size());
        try {
            for (ToolUseBlock call : toolCalls) {
                String callId =
                        call.getId() == null || call.getId().isBlank()
                                ? UUID.randomUUID().toString()
                                : call.getId();
                String name =
                        call.getName() == null || call.getName().isBlank()
                                ? "unknown"
                                : call.getName();
                InvocationState.AgentFrame.ToolContext toolContext =
                        agentFrame.toolContext(callId);
                InvocationState.StepFrame callStep =
                        toolContext == null ? fallbackStep : toolContext.step();
                String callModel =
                        toolContext == null ? agentFrame.modelName() : toolContext.model();
                String callProvider =
                        toolContext == null ? agentFrame.providerName() : toolContext.provider();
                if (callStep != null) {
                    actingSteps.add(callStep);
                }
                Context parent =
                        callStep != null && callStep.spanContext() != null
                                ? callStep.spanContext()
                                : fallbackParent;
                Span span =
                        common(
                                        tracer.spanBuilder("execute_tool " + name)
                                                .setSpanKind(SpanKind.CLIENT)
                                                .setParent(Objects.requireNonNull(parent)),
                                        state,
                                        "tool",
                                        "execute_tool")
                                .setAttribute(ClsFields.AGENT_ID, agentFrame.agentId())
                                .setAttribute("gen_ai.provider.name", callProvider)
                                .setAttribute("gen_ai.request.model", callModel)
                                .setAttribute(
                                        "gen_ai.react.round",
                                        callStep == null ? 0L : (long) callStep.round())
                                .setAttribute("gen_ai.tool.call.id", callId)
                                .setAttribute("gen_ai.tool.name", name)
                                .setAttribute("gen_ai.tool.type", "function")
                                .startSpan();
                if (callStep != null) {
                    span.setAttribute(ClsFields.STEP_ID, callStep.stepId());
                }
                capture(span, "gen_ai.tool.call.arguments", call.getInput());
                tools.computeIfAbsent(callId, ignored -> new ArrayList<>())
                        .add(
                                new ToolSpan(
                                        span,
                                        System.nanoTime(),
                                        contentSanitizer,
                                        canonicalWriter,
                                        this::telemetryFailed));
            }
        } catch (RuntimeException | StackOverflowError failure) {
            failTools(tools, failure);
            finishStepsError(actingSteps, "error", failure);
            clearToolSteps(agentFrame, tools);
            throw failure;
        }
        Flux<AgentEvent> downstream;
        try {
            downstream = next.apply(input);
        } catch (RuntimeException | Error exception) {
            quietly(
                    () -> {
                        failTools(tools, exception);
                        finishStepsError(actingSteps, "error", exception);
                        clearToolSteps(agentFrame, tools);
                    });
            return Flux.error(exception);
        }
        return downstream
                .doOnNext(event -> quietly(() -> applyToolEvent(tools, event)))
                .doOnComplete(
                        () ->
                                quietly(
                                        () -> {
                                            forEachTool(tools, tool -> tool.success());
                                            actingSteps.forEach(
                                                    step -> step.finishSuccess("tool_calls"));
                                            clearToolSteps(agentFrame, tools);
                                        }))
                .doOnError(
                        error ->
                                quietly(
                                        () -> {
                                            failTools(tools, error);
                                            finishStepsError(actingSteps, "error", error);
                                            clearToolSteps(agentFrame, tools);
                                        }))
                .doOnCancel(
                        () ->
                                quietly(
                                        () -> {
                                            forEachTool(tools, tool -> tool.cancel());
                                            finishStepsError(actingSteps, "cancelled", null);
                                            clearToolSteps(agentFrame, tools);
                                        }));
    }

    private Flux<AgentEvent> instrumentAgent(
            Agent agent,
            RuntimeContext runtimeContext,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next,
            ContextView reactorContext) {
        InvocationState existing =
                get(reactorContext, contextKeys.invocationKey(), InvocationState.class);
        InvocationState state = existing == null ? createState(runtimeContext) : existing;
        InvocationState.AgentFrame agentFrame =
                state.newAgentFrame(agent.getAgentId(), agent.getName());
        List<io.agentscope.core.message.Msg> agentMessages =
                input.msgs() == null ? List.of() : input.msgs();
        Context parent = resolveOtelContext(Objects.requireNonNull(reactorContext));
        Span entry = null;
        Context agentParent = parent;
        // Captured before any CLS context write; readSnapshot returns the immutable write-once
        // value when an outer middleware (or another CLS instance) already stored it.
        SpanContext hostSnapshot =
                existing == null ? HostTraceLinker.readSnapshot(reactorContext) : null;
        if (existing == null && hostSnapshot == null) {
            hostSnapshot = HostTraceLinker.hostSpanContext(reactorContext);
        }
        if (existing == null) {
            entry =
                    common(
                                    hostTraceLinker.apply(
                                            tracer.spanBuilder("enter_application")
                                                    .setSpanKind(SpanKind.INTERNAL),
                                            hostSnapshot),
                                    state,
                                    "entry",
                                    "enter_application")
                            .setAttribute("gen_ai.entry.type", state.entryType())
                            .setAttribute("observed_time_unix_nano", Long.toString(epochNanos()))
                            .startSpan();
            state.bindEntrySpan(entry);
            agentParent = entry.storeInContext(Objects.requireNonNull(Context.root()));
        }
        Span agentSpan =
                common(
                                tracer.spanBuilder("invoke_agent " + agentFrame.agentName())
                                        .setSpanKind(SpanKind.INTERNAL)
                                        .setParent(Objects.requireNonNull(agentParent)),
                                state,
                                "agent",
                                "invoke_agent")
                        .setAttribute(ClsFields.AGENT_ID, agentFrame.agentId())
                        .setAttribute(ClsFields.AGENT_NAME, agentFrame.agentName())
                        .setAttribute("gen_ai.agent.message_count", (long) agentMessages.size())
                        .startSpan();
        Span inputEntry = entry;
        try {
            if (inputEntry == null) {
                captureMessages(
                        agentMessages, false, "gen_ai.input.messages", agentSpan);
            } else {
                captureMessages(
                        agentMessages,
                        false,
                        "gen_ai.input.messages",
                        agentSpan,
                        inputEntry);
            }
        } catch (RuntimeException | StackOverflowError failure) {
            telemetryFailed(failure);
            agentSpan.setAttribute(
                    "gen_ai.input.messages.capture_error",
                    failure.getClass().getSimpleName());
            if (entry != null) {
                entry.setAttribute(
                        "gen_ai.input.messages.capture_error",
                        failure.getClass().getSimpleName());
            }
        }
        Context spanContext = agentSpan.storeInContext(Objects.requireNonNull(agentParent));
        Span rootEntry = entry;
        if (existing == null) {
            ClsResumeContext resume = runtimeContext.get(ClsResumeContext.class);
            if (resume != null) {
                state.resumeFromTurnId(resume.resumeFromTurnId());
                entry.setAttribute(
                        ClsFields.TURN_RESUME_FROM_TURN_ID, resume.resumeFromTurnId());
            }
        }
        Flux<AgentEvent> downstream;
        try {
            downstream = next.apply(input);
        } catch (RuntimeException | Error exception) {
            quietly(
                    () -> {
                        endError(agentSpan, "agent invocation failed", exception);
                        if (rootEntry != null) {
                            endError(rootEntry, "application entry failed", exception);
                        }
                    });
            return Flux.error(exception);
        }
        AtomicBoolean ended = new AtomicBoolean();
        ControlEventTracker controlTracker =
                controlTracker(state, agentFrame, agentSpan, rootEntry, ended);
        Flux<AgentEvent> observed =
                downstream
                        .doOnNext(
                                event ->
                                        quietly(
                                                () ->
                                                        captureAgentResult(
                                                                agentSpan, rootEntry, event)))
                        .doOnNext(
                                event ->
                                        quietly(
                                                () ->
                                                        routeControlEvent(
                                                                controlTracker,
                                                                agentFrame,
                                                                event)))
                        .doOnComplete(
                                () ->
                                        quietly(
                                                () -> {
                                                    finishOpenStep(agentFrame, "stop", null);
                                                    if (rootEntry != null && controlTracker != null) {
                                                        ControlEventTracker.TerminalResolution
                                                                resolution =
                                                                        controlTracker
                                                                                .resolveOnComplete();
                                                        controlTracker.cancelAll();
                                                        releaseControlPlane(state);
                                                        endAgentTreeOutcome(
                                                                ended,
                                                                agentSpan,
                                                                rootEntry,
                                                                agentFrame,
                                                                resolution.outcome(),
                                                                resolution.completed(),
                                                                null,
                                                                controlTracker);
                                                    } else {
                                                        endAgentTree(
                                                                ended,
                                                                agentSpan,
                                                                rootEntry,
                                                                agentFrame,
                                                                null,
                                                                false);
                                                    }
                                                }))
                        .doOnError(
                                error ->
                                        quietly(
                                                () -> {
                                                    finishOpenStep(agentFrame, "error", error);
                                                    if (rootEntry != null && controlTracker != null) {
                                                        controlTracker.cancelAll();
                                                        releaseControlPlane(state);
                                                        endAgentTreeOutcome(
                                                                ended,
                                                                agentSpan,
                                                                rootEntry,
                                                                agentFrame,
                                                                TerminalOutcome.ERROR,
                                                                controlTracker
                                                                        .resolveOnComplete()
                                                                        .completed(),
                                                                error,
                                                                controlTracker);
                                                    } else {
                                                        endAgentTree(
                                                                ended,
                                                                agentSpan,
                                                                rootEntry,
                                                                agentFrame,
                                                                error,
                                                                false);
                                                    }
                                                }))
                        .doOnCancel(
                                () ->
                                        quietly(
                                                () -> {
                                                    finishOpenStep(agentFrame, "cancelled", null);
                                                    if (rootEntry != null && controlTracker != null) {
                                                        controlTracker.cancelAll();
                                                        releaseControlPlane(state);
                                                        endAgentTreeOutcome(
                                                                ended,
                                                                agentSpan,
                                                                rootEntry,
                                                                agentFrame,
                                                                TerminalOutcome.CANCELLED,
                                                                controlTracker
                                                                        .resolveOnComplete()
                                                                        .completed(),
                                                                null,
                                                                controlTracker);
                                                    } else {
                                                        endAgentTree(
                                                                ended,
                                                                agentSpan,
                                                                rootEntry,
                                                                agentFrame,
                                                                null,
                                                                true);
                                                    }
                                                }));
        boolean publishSnapshot = existing == null;
        return propagate(observed, spanContext)
                .contextWrite(
                        context -> {
                            reactor.util.context.Context updated =
                                    context.put(contextKeys.invocationKey(), state)
                                            .put(contextKeys.agentKey(), agentFrame);
                            // writeSnapshot is write-once and stores the invalid sentinel when
                            // no host is present, so it never holds a CLS span.
                            return publishSnapshot
                                    ? HostTraceLinker.writeSnapshot(updated, reactorContext)
                                    : updated;
                        });
    }

    private void captureMessages(
            @Nullable List<io.agentscope.core.message.Msg> source,
            boolean includeObservableHash,
            String attribute,
            Span... spans) {
        List<io.agentscope.core.message.Msg> messages = source == null ? List.of() : source;
        AgentScopeMessageConverter.ConversionResult converted =
                messageConverter.convertBounded(messages);
        List<Map<String, Object>> combined = new ArrayList<>(converted.messages());
        combined.addAll(providerPayloadSummary.summarize(messages));
        try (InvocationCaptureBudget budget =
                        new InvocationCaptureBudget(
                                captureMemoryPool, maxInvocationCaptureMemoryBytes);
                BoundedMessageCapture.Result captured =
                        boundedMessageCapture.captureMessages(
                                combined, converted.complete(), budget)) {
            String encoded = captured.value().map(JsonNode::toString).orElse(null);
            for (Span target : spans) {
                if (encoded != null) {
                    target.setAttribute(attribute, encoded);
                }
                if (includeObservableHash) {
                    captured.observableHash()
                            .ifPresent(hash -> target.setAttribute(attribute + ".hash", hash));
                    target.setAttribute(
                            "agentscope.capture.hash_complete", captured.hashComplete());
                }
                target.setAttribute(
                        "agentscope.capture.original_bytes", captured.originalBytes());
                target.setAttribute(
                        "agentscope.capture.retained_bytes", captured.retainedBytes());
                target.setAttribute(
                        "agentscope.capture.truncated",
                        captured.capacityDroppedParts() > 0
                                || captured.retainedBytes() < captured.originalBytes());
                target.setAttribute(
                        "agentscope.provider_payload.capture_mode",
                        captured.providerMode().name().toLowerCase(Locale.ROOT));
                if (captured.capacityDroppedParts() > 0
                        || captured.capacityDroppedBytes() > 0) {
                    target.setAttribute(
                            "agentscope.capture.capacity_dropped_parts",
                            captured.capacityDroppedParts());
                    target.setAttribute(
                            "agentscope.capture.capacity_dropped_bytes",
                            captured.capacityDroppedBytes());
                }
            }
            if (captured.capacityDroppedParts() > 0 || captured.capacityDroppedBytes() > 0) {
                counters.capacityDropped(
                        captured.capacityDroppedParts(), captured.capacityDroppedBytes());
            }
            if (!combined.isEmpty() && captured.value().isEmpty()) {
                counters.captureFailed(1);
            }
        }
    }

    private void captureAgentResult(Span agentSpan, @Nullable Span entry, AgentEvent event) {
        if (!(event instanceof AgentResultEvent result)) {
            return;
        }
        if (entry == null) {
            captureMessages(
                    List.of(result.getResult()),
                    false,
                    "gen_ai.output.messages",
                    agentSpan);
        } else {
            captureMessages(
                    List.of(result.getResult()),
                    false,
                    "gen_ai.output.messages",
                    agentSpan,
                    entry);
        }
    }

    private <I> Flux<AgentEvent> guarded(
            TrackedNext<I> tracked,
            I input,
            Function<I, Flux<AgentEvent>> next,
            Supplier<Flux<AgentEvent>> instrumented) {
        try {
            return instrumented.get();
        } catch (RuntimeException | StackOverflowError failure) {
            telemetryFailed(failure);
            Flux<AgentEvent> downstream = tracked.downstream();
            if (downstream != null) {
                return downstream;
            }
            if (tracked.invoked()) {
                return Flux.error(
                        failure instanceof RuntimeException runtime
                                ? runtime
                                : new IllegalStateException("business invocation failed", failure));
            }
            return next.apply(input);
        }
    }

    private void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | StackOverflowError failure) {
            telemetryFailed(failure);
        }
    }

    private void telemetryFailed(Throwable failure) {
        counters.dropped(1);
        long now = System.nanoTime();
        long previous = lastWarningNanos.get();
        if ((previous == Long.MIN_VALUE || now - previous >= WARNING_INTERVAL_NANOS)
                && lastWarningNanos.compareAndSet(previous, now)) {
            LOGGER.warn(
                    "CLS tracing skipped after telemetry failure: {}",
                    failure.getClass().getSimpleName());
        }
    }

    private InvocationState createState(RuntimeContext context) {
        ClsInvocationContext invocation = context.get(ClsInvocationContext.class);
        if (invocation == null) {
            invocation = ClsInvocationContext.defaults();
        }
        String sessionId =
                IdentityNormalizer.bounded(
                        Objects.requireNonNull(context.getSessionId()),
                        IdentityNormalizer.SESSION_ID_MAX_BYTES);
        String userId =
                IdentityNormalizer.bounded(
                        Objects.requireNonNull(context.getUserId()),
                        IdentityNormalizer.USER_ID_MAX_BYTES);
        String turnId =
                IdentityNormalizer.bounded(
                        invocation.turnId() == null
                                ? sessionId + ":t:" + UUID.randomUUID()
                                : invocation.turnId(),
                        IdentityNormalizer.TURN_ID_MAX_BYTES);
        String userName =
                IdentityNormalizer.bounded(
                        invocation.userName() == null ? userId : invocation.userName(),
                        IdentityNormalizer.USER_NAME_MAX_BYTES);
        return new InvocationState(
                sessionId,
                userId,
                userName,
                turnId,
                IdentityNormalizer.bounded(
                        invocation.agentType(), IdentityNormalizer.TYPE_MAX_BYTES),
                IdentityNormalizer.bounded(
                        invocation.entryType(), IdentityNormalizer.TYPE_MAX_BYTES));
    }

    private static boolean hasIdentity(RuntimeContext context) {
        return context != null
                && context.getSessionId() != null
                && !context.getSessionId().isBlank()
                && context.getUserId() != null
                && !context.getUserId().isBlank();
    }

    private static SpanBuilder common(
            SpanBuilder builder, InvocationState state, String spanKind, String operationName) {
        return Objects.requireNonNull(builder)
                .setAttribute(ClsFields.SPAN_KIND, Objects.requireNonNull(spanKind))
                .setAttribute(ClsFields.OPERATION_NAME, Objects.requireNonNull(operationName))
                .setAttribute(ClsFields.AGENT_TYPE, Objects.requireNonNull(state.agentType()))
                .setAttribute(ClsFields.SESSION_ID, Objects.requireNonNull(state.sessionId()))
                .setAttribute(ClsFields.TURN_ID, Objects.requireNonNull(state.turnId()))
                .setAttribute(ClsFields.USER_ID, Objects.requireNonNull(state.userId()))
                .setAttribute(ClsFields.USER_NAME, Objects.requireNonNull(state.userName()));
    }

    private void capture(Span span, String key, Object value) {
        try {
            contentSanitizer
                    .capture(value)
                    .ifPresent(
                            node ->
                                    span.setAttribute(
                                            Objects.requireNonNull(key),
                                            Objects.requireNonNull(node.toString())));
        } catch (RuntimeException exception) {
            span.setAttribute(key + ".capture_error", exception.getClass().getSimpleName());
        }
    }

    private void captureMessages(Span span, String key, Object value) {
        try {
            contentSanitizer
                    .captureMessages(value)
                    .ifPresent(
                            node ->
                                    span.setAttribute(
                                            Objects.requireNonNull(key),
                                            Objects.requireNonNull(node.toString())));
        } catch (RuntimeException exception) {
            span.setAttribute(key + ".capture_error", exception.getClass().getSimpleName());
        }
    }

    private void applyModelEvent(
            Span span,
            AgentEvent event,
            OutputMessageAccumulator output,
            long startedNanos,
            InvocationState.AgentFrame agentFrame,
            InvocationState.@Nullable StepFrame step,
            String modelName,
            String providerName) {
        output.accept(event, System.nanoTime() - startedNanos);
        if (event instanceof ToolCallStartEvent toolCall) {
            if (step != null) {
                agentFrame.registerToolContext(
                        toolCall.getToolCallId(), step, modelName, providerName);
            }
            return;
        }
        if (!(event instanceof ModelCallEndEvent modelEnd) || modelEnd.getUsage() == null) {
            return;
        }
        var usage = modelEnd.getUsage();
        long inputTokens = usage.getInputTokens();
        long outputTokens = usage.getOutputTokens();
        long cacheReadTokens = usage.getCachedTokens();
        span.setAttribute("gen_ai.usage.input_tokens", inputTokens);
        span.setAttribute("gen_ai.usage.output_tokens", outputTokens);
        span.setAttribute("gen_ai.usage.total_tokens", (long) usage.getTotalTokens());
        span.setAttribute("gen_ai.usage.cache_read.input_tokens", cacheReadTokens);
        span.setAttribute("gen_ai.usage.cache_creation.input_tokens", 0L);
        span.setAttribute(
                "gen_ai.usage.cache_miss.input_tokens", Math.max(0L, inputTokens - cacheReadTokens));
        agentFrame.addUsage(inputTokens, outputTokens, cacheReadTokens);
    }

    private void writeReasoningMetrics(
            Span span, OutputMessageAccumulator.Result result) {
        OutputMessageAccumulator.ReasoningMetrics reasoning = result.reasoning();
        span.setAttribute(ClsFields.REASONING_PRESENT, reasoning.present());
        span.setAttribute(ClsFields.REASONING_BLOCK_COUNT, reasoning.blockCount());
        span.setAttribute(ClsFields.REASONING_OUTPUT_BYTES, reasoning.outputBytes());
        span.setAttribute(ClsFields.REASONING_DURATION_MS, reasoning.durationMs());
        span.setAttribute(
                ClsFields.REASONING_CAPTURE_MODE,
                reasoningCaptureMode.name().toLowerCase(Locale.ROOT));
        span.setAttribute(ClsFields.REASONING_TRUNCATED, reasoning.truncated());
        span.setAttribute(
                ClsFields.REASONING_MALFORMED_EVENTS,
                reasoning.malformedEventCount());
        if (result.capacityDroppedParts() > 0 || result.capacityDroppedBytes() > 0) {
            counters.capacityDropped(
                    result.capacityDroppedParts(), result.capacityDroppedBytes());
            span.setAttribute(
                    "agentscope.capture.capacity_dropped_parts",
                    result.capacityDroppedParts());
            span.setAttribute(
                    "agentscope.capture.capacity_dropped_bytes",
                    result.capacityDroppedBytes());
        }
        reasoning.timeToFirstTokenMs()
                .ifPresent(value -> span.setAttribute(ClsFields.REASONING_TTFT_MS, value));
        result.responseTimeToFirstTokenMs()
                .ifPresent(value -> span.setAttribute(ClsFields.RESPONSE_TTFT_MS, value));
    }

    private static void applyToolEvent(Map<String, List<ToolSpan>> tools, AgentEvent event) {
        if (event instanceof ToolResultTextDeltaEvent text) {
            tools.getOrDefault(text.getToolCallId(), List.of())
                    .forEach(tool -> tool.appendText(text.getDelta()));
            return;
        }
        if (event instanceof ToolResultDataDeltaEvent data) {
            tools.getOrDefault(data.getToolCallId(), List.of())
                    .forEach(tool -> tool.appendData(data.getData()));
            return;
        }
        if (!(event instanceof ToolResultEndEvent result)) {
            return;
        }
        List<ToolSpan> matching = tools.getOrDefault(result.getToolCallId(), List.of());
        if (result.getState() == ToolResultState.SUCCESS) {
            matching.forEach(tool -> tool.success());
        } else {
            String type = result.getState() == null ? "tool_error" : result.getState().getValue();
            matching.forEach(tool -> tool.error(type, null));
        }
    }

    private static void forEachTool(
            Map<String, List<ToolSpan>> tools, java.util.function.Consumer<ToolSpan> action) {
        tools.values().forEach(spans -> spans.forEach(action));
    }

    private static void failTools(Map<String, List<ToolSpan>> tools, Throwable error) {
        forEachTool(tools, tool -> tool.error("tool execution failed", error));
    }

    private static void finishStepsError(
            Set<InvocationState.StepFrame> steps, String reason, @Nullable Throwable error) {
        steps.forEach(step -> step.finishError(reason, error));
    }

    private static void clearToolSteps(
            InvocationState.AgentFrame agentFrame, Map<String, List<ToolSpan>> tools) {
        tools.keySet().forEach(agentFrame::clearToolContext);
    }

    private Flux<AgentEvent> terminate(
            Flux<AgentEvent> downstream, Span span, String errorDescription, Runnable beforeEnd) {
        AtomicBoolean ended = new AtomicBoolean();
        return downstream
                .doOnComplete(
                        () -> {
                            if (ended.compareAndSet(false, true)) {
                                finishSpan(
                                        span,
                                        () -> {
                                            beforeEnd.run();
                                            span.setStatus(StatusCode.OK);
                                        });
                            }
                        })
                .doOnError(
                        error -> {
                            if (ended.compareAndSet(false, true)) {
                                finishSpan(
                                        span,
                                        () -> {
                                            try {
                                                beforeEnd.run();
                                            } finally {
                                                markError(span, errorDescription, error);
                                            }
                                        });
                            }
                        })
                .doOnCancel(
                        () -> {
                            if (ended.compareAndSet(false, true)) {
                                finishSpan(
                                        span,
                                        () -> {
                                            try {
                                                beforeEnd.run();
                                            } finally {
                                                span.setStatus(StatusCode.ERROR, "cancelled");
                                                span.setAttribute("error.type", "cancelled");
                                            }
                                        });
                            }
                        });
    }

    private static void finishOpenStep(
            InvocationState.AgentFrame agentFrame, String reason, @Nullable Throwable error) {
        for (InvocationState.StepFrame step : agentFrame.steps()) {
            if (error == null && !"cancelled".equals(reason) && !"error".equals(reason)) {
                step.finishSuccess(reason);
            } else {
                step.finishError(reason, error);
            }
        }
    }

    private ControlEventTracker controlTracker(
            InvocationState state,
            InvocationState.AgentFrame agentFrame,
            Span agentSpan,
            @Nullable Span rootEntry,
            AtomicBoolean ended) {
        ControlEventTracker existing = state.controlTracker();
        if (existing != null) {
            return existing;
        }
        InvocationLifecycle lifecycle = new InvocationLifecycle();
        InvocationLease lease = new InvocationLease(lifecycle);
        InvocationCaptureBudget controlBudget =
                new InvocationCaptureBudget(captureMemoryPool, maxInvocationCaptureMemoryBytes);
        ControlEventTracker tracker =
                new ControlEventTracker(
                        new ControlScheduler(controlScheduler()),
                        hitlWaitTimeout,
                        lease,
                        lifecycle,
                        (outcome, resultObserved) ->
                                quietly(
                                        () -> {
                                            finishOpenStep(
                                                    agentFrame, outcome.finishReason(), null);
                                            // Advance the generation state machine first so
                                            // late results can rotate; spans follow.
                                            lifecycle.terminal(outcome, resultObserved, null);
                                            endAgentTreeOutcome(
                                                    ended,
                                                    agentSpan,
                                                    rootEntry,
                                                    agentFrame,
                                                    outcome,
                                                    resultObserved,
                                                    null,
                                                    state.controlTracker());
                                        }),
                        1024,
                        System::nanoTime,
                        controlBudget);
        state.bindControlPlane(lease, tracker, controlBudget);
        return tracker;
    }

    private java.util.concurrent.ScheduledExecutorService controlScheduler() {
        java.util.concurrent.ScheduledExecutorService current = controlScheduler;
        if (current == null) {
            synchronized (this) {
                current = controlScheduler;
                if (current == null) {
                    current =
                            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                                    runnable -> {
                                        Thread thread =
                                                new Thread(
                                                        runnable,
                                                        "agentscope-cls-control");
                                        thread.setDaemon(true);
                                        return thread;
                                    });
                    controlScheduler = current;
                }
            }
        }
        return current;
    }

    private static void routeControlEvent(
            ControlEventTracker tracker,
            InvocationState.AgentFrame agentFrame,
            AgentEvent event) {
        if (event instanceof AgentResultEvent) {
            tracker.onAgentResult();
            return;
        }
        if (event instanceof io.agentscope.core.event.RequireUserConfirmEvent require) {
            tracker.onWait(
                    ControlRecord.Kind.USER_CONFIRM,
                    agentFrame.agentId(),
                    require.getReplyId(),
                    toolCallIds(require.getToolCalls()));
        } else if (event instanceof io.agentscope.core.event.UserConfirmResultEvent result) {
            tracker.onResult(ControlRecord.Kind.USER_CONFIRM, result.getReplyId());
        } else if (event
                instanceof io.agentscope.core.event.RequireExternalExecutionEvent external) {
            tracker.onWait(
                    ControlRecord.Kind.EXTERNAL_EXECUTION,
                    agentFrame.agentId(),
                    external.getReplyId(),
                    toolCallIds(external.getToolCalls()));
        } else if (event
                instanceof io.agentscope.core.event.ExternalExecutionResultEvent externalResult) {
            tracker.onResult(
                    ControlRecord.Kind.EXTERNAL_EXECUTION, externalResult.getReplyId());
        } else if (event instanceof io.agentscope.core.event.AllToolsDeniedEvent) {
            tracker.onControlOutcome(TerminalOutcome.DENIED);
        } else if (event instanceof io.agentscope.core.event.ExceedMaxItersEvent) {
            tracker.onControlOutcome(TerminalOutcome.MAX_ITERS);
        } else if (event instanceof io.agentscope.core.event.RequestStopEvent) {
            tracker.onControlOutcome(TerminalOutcome.INTERRUPTED);
        }
    }

    private static List<String> toolCallIds(
            List<io.agentscope.core.message.ToolUseBlock> toolCalls) {
        if (toolCalls == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(toolCalls.size());
        for (io.agentscope.core.message.ToolUseBlock call : toolCalls) {
            if (call != null && call.getId() != null) {
                ids.add(call.getId());
            }
        }
        return List.copyOf(ids);
    }

    private void endAgentTreeOutcome(
            AtomicBoolean ended,
            Span agent,
            @Nullable Span entry,
            InvocationState.AgentFrame agentFrame,
            TerminalOutcome outcome,
            boolean resultObserved,
            @Nullable Throwable error,
            @Nullable ControlEventTracker tracker) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        applyTurnTerminal(agent, entry, outcome, resultObserved, tracker);
        finishSpan(
                agent,
                () -> {
                    try {
                        agentFrame.applyTotals(agent);
                    } finally {
                        applyOutcomeStatus(agent, outcome, error);
                    }
                });
        if (entry != null) {
            finishSpan(entry, () -> applyOutcomeStatus(entry, outcome, error));
        }
    }

    private void applyTurnTerminal(
            Span agent,
            @Nullable Span entry,
            TerminalOutcome outcome,
            boolean resultObserved,
            @Nullable ControlEventTracker tracker) {
        for (Span span : entry == null ? new Span[] {agent} : new Span[] {agent, entry}) {
            span.setAttribute(ClsFields.TURN_COMPLETED, resultObserved);
            span.setAttribute(ClsFields.TURN_FINISH_REASON, outcome.finishReason());
            span.setAttribute(ClsFields.INCOMPLETE, outcome.incomplete());
            if (tracker != null) {
                span.setAttribute(ClsFields.HITL_WAIT_COUNT, tracker.waitCount());
                span.setAttribute(
                        ClsFields.HITL_TOTAL_WAIT_MS, tracker.totalWaitNanos() / 1_000_000L);
            }
        }
    }

    private static void releaseControlPlane(InvocationState state) {
        InvocationLease lease = state.lease();
        if (lease != null) {
            lease.freeze();
        }
        InvocationCaptureBudget budget = state.controlBudget();
        if (budget != null) {
            budget.close();
        }
    }

    private static void applyOutcomeStatus(
            Span span, TerminalOutcome outcome, @Nullable Throwable error) {
        switch (outcome.statusCode()) {
            case OK -> span.setStatus(StatusCode.OK);
            case UNSET -> span.setStatus(StatusCode.UNSET);
            case ERROR -> {
                String description = outcome.statusDescription(error);
                span.setStatus(StatusCode.ERROR, description);
                span.setAttribute("error.type", description);
                if (outcome == TerminalOutcome.ERROR && error != null) {
                    span.addEvent(
                            "exception",
                            Attributes.builder()
                                    .put("exception.type", error.getClass().getName())
                                    .build());
                }
            }
        }
    }

    private void endAgentTree(
            AtomicBoolean ended,
            Span agent,
            @Nullable Span entry,
            InvocationState.AgentFrame agentFrame,
            @Nullable Throwable error,
            boolean cancelled) {
        if (!ended.compareAndSet(false, true)) {
            return;
        }
        finishSpan(
                agent,
                () -> {
                    try {
                        agentFrame.applyTotals(agent);
                    } finally {
                        markTreeStatus(agent, "agent invocation failed", error, cancelled);
                    }
                });
        if (entry != null) {
            finishSpan(
                    entry,
                    () -> markTreeStatus(entry, "application entry failed", error, cancelled));
        }
    }

    private static void markTreeStatus(
            Span span, String description, @Nullable Throwable error, boolean cancelled) {
        if (cancelled) {
            span.setStatus(StatusCode.ERROR, "cancelled");
            span.setAttribute("error.type", "cancelled");
        } else if (error != null) {
            markError(span, description, error);
        } else {
            span.setStatus(StatusCode.OK);
        }
    }

    private void endError(Span span, String description, @Nullable Throwable error) {
        finishSpan(span, () -> markError(span, description, error));
    }

    private static void markError(Span span, String description, @Nullable Throwable error) {
        String errorType = error == null ? "unknown" : error.getClass().getName();
        span.setStatus(StatusCode.ERROR, Objects.requireNonNull(description));
        span.setAttribute("error.type", Objects.requireNonNull(errorType));
        span.addEvent(
                "exception",
                Objects.requireNonNull(
                        Attributes.builder()
                                .put("exception.type", Objects.requireNonNull(errorType))
                                .build()));
    }

    private void finishSpan(Span span, Runnable updater) {
        try {
            updater.run();
        } catch (RuntimeException | StackOverflowError failure) {
            telemetryFailed(failure);
        } finally {
            try {
                span.end();
            } catch (RuntimeException | StackOverflowError failure) {
                telemetryFailed(failure);
            }
        }
    }

    private static void setDuration(Span span, String key, long startedNanos) {
        span.setAttribute(
                Objects.requireNonNull(key),
                Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
    }

    private static long epochNanos() {
        java.time.Instant now = java.time.Instant.now();
        return Math.addExact(
                Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
    }

    private static String inferProvider(String modelName) {
        String normalized = modelName.toLowerCase(Locale.ROOT);
        if (normalized.contains("deepseek")) {
            return "deepseek";
        }
        if (normalized.contains("gpt") || normalized.contains("openai")) {
            return "openai";
        }
        if (normalized.contains("claude")) {
            return "anthropic";
        }
        if (normalized.contains("qwen")) {
            return "dashscope";
        }
        return "unknown";
    }

    /**
     * Publishes the span context to nested middleware through the Reactor context.
     *
     * <p>The SDK carries its own per-instance key so parent and child spans stay connected
     * without installing a process wide Reactor hook. BRIDGE/LEGACY_HOOK additionally side load
     * the context into the OpenTelemetry operator so nested auto instrumentation can attach.
     */
    private Flux<AgentEvent> propagate(Flux<AgentEvent> flux, Context spanContext) {
        return contextPropagation.apply(flux, spanContext, contextKeys.otelContextKey());
    }

    private Context resolveOtelContext(ContextView contextView) {
        Context stored = get(contextView, contextKeys.otelContextKey(), Context.class);
        if (stored != null) {
            return stored;
        }
        return Objects.requireNonNull(
                ContextPropagationOperator.getOpenTelemetryContextFromContextView(
                        Objects.requireNonNull(contextView),
                        Objects.requireNonNull(Context.current())));
    }

    private static <T> T get(ContextView context, Object key, Class<T> type) {
        Object value = context.getOrDefault(key, null);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    private static final class TrackedNext<I> implements Function<I, Flux<AgentEvent>> {
        private final Function<I, Flux<AgentEvent>> delegate;
        private volatile boolean invoked;
        private volatile @Nullable Flux<AgentEvent> downstream;

        private TrackedNext(Function<I, Flux<AgentEvent>> delegate) {
            this.delegate = delegate;
        }

        private boolean invoked() {
            return invoked;
        }

        private @Nullable Flux<AgentEvent> downstream() {
            return downstream;
        }

        @Override
        public Flux<AgentEvent> apply(I input) {
            invoked = true;
            Flux<AgentEvent> result = delegate.apply(input);
            downstream = result;
            return result;
        }
    }
}
