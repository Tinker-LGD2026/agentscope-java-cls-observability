package io.github.tinkerlgd2026.agentscope.cls.instrumentation;

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
import io.github.tinkerlgd2026.agentscope.cls.internal.JsonSupport;
import io.github.tinkerlgd2026.agentscope.cls.internal.IdentityNormalizer;
import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentCaptureMode;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentSanitizer;
import io.github.tinkerlgd2026.agentscope.cls.privacy.MessageCapturePolicy;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsFields;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.reactor.v3_1.ContextPropagationOperator;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
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
    private static final int MAX_TOOL_RESULT_PARTS = 256;
    private static final Object INVOCATION_KEY = InvocationState.class;
    private static final Object AGENT_KEY = InvocationState.AgentFrame.class;
    private static final Object STEP_KEY = InvocationState.StepFrame.class;
    private static final Object OTEL_CONTEXT_KEY = Context.class;
    private static final AtomicBoolean REACTOR_HOOK_REGISTERED = new AtomicBoolean();

    private final Tracer tracer;
    private final ContentSanitizer contentSanitizer;
    private final MessageCapturePolicy messageCapturePolicy;
    private final ContentCaptureMode contentCaptureMode;
    private final ContentCaptureMode reasoningCaptureMode;
    private final int maxContentBytes;
    private final ObjectMapper objectMapper;
    private final ObjectWriter canonicalWriter;
    private final AgentScopeMessageConverter messageConverter;
    private final BooleanSupplier active;
    private final TelemetryCounters counters;
    private final boolean reactorContextHookEnabled;
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
        if (tracer == null
                || contentSanitizer == null
                || messageCapturePolicy == null
                || contentCaptureMode == null
                || reasoningCaptureMode == null
                || active == null
                || counters == null
                || objectMapper == null) {
            throw new IllegalArgumentException("CLS tracing dependencies are required");
        }
        this.tracer = tracer;
        this.contentSanitizer = contentSanitizer;
        this.messageCapturePolicy = messageCapturePolicy;
        this.contentCaptureMode = contentCaptureMode;
        this.reasoningCaptureMode = reasoningCaptureMode;
        this.maxContentBytes = maxContentBytes;
        this.objectMapper = objectMapper;
        this.active = active;
        this.counters = counters;
        this.canonicalWriter =
                objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.messageConverter = new AgentScopeMessageConverter();
        this.reactorContextHookEnabled = reactorContextHookEnabled;
        if (reactorContextHookEnabled) {
            acquireReactorHook();
        }
    }

    @Override
    public void close() {
        closed.set(true);
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
                    InvocationState inherited =
                            get(reactorContext, INVOCATION_KEY, InvocationState.class);
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
                                            agent, runtimeContext, input, tracked, reactorContext));
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
                    InvocationState state =
                            get(reactorContext, INVOCATION_KEY, InvocationState.class);
                    InvocationState.AgentFrame agentFrame =
                            get(reactorContext, AGENT_KEY, InvocationState.AgentFrame.class);
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
                                            state, agentFrame, input, tracked, reactorContext));
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
                    InvocationState state =
                            get(reactorContext, INVOCATION_KEY, InvocationState.class);
                    InvocationState.AgentFrame agentFrame =
                            get(reactorContext, AGENT_KEY, InvocationState.AgentFrame.class);
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
                                            state, agentFrame, input, tracked, reactorContext));
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
                    InvocationState state =
                            get(reactorContext, INVOCATION_KEY, InvocationState.class);
                    InvocationState.AgentFrame agentFrame =
                            get(reactorContext, AGENT_KEY, InvocationState.AgentFrame.class);
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
                                            state, agentFrame, input, tracked, reactorContext));
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
                .contextWrite(context -> context.put(STEP_KEY, pendingStep));
    }

    private Flux<AgentEvent> instrumentModelCall(
            InvocationState state,
            InvocationState.AgentFrame agentFrame,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next,
            ContextView reactorContext) {
        InvocationState.StepFrame step =
                get(reactorContext, STEP_KEY, InvocationState.StepFrame.class);
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
            AgentScopeMessageConverter.ConversionResult converted =
                    messageConverter.convertBounded(input.messages());
            MessageCapturePolicy.CapturedMessages captured =
                    messageCapturePolicy.capture(
                            converted.messages(), true, converted.complete());
            captured.observableHash()
                    .ifPresent(hash -> span.setAttribute("gen_ai.input.messages.hash", hash));
            captured.value()
                    .ifPresent(
                            node ->
                                    span.setAttribute(
                                            "gen_ai.input.messages", node.toString()));
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
                get(reactorContext, STEP_KEY, InvocationState.StepFrame.class);
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
                        .add(new ToolSpan(span, System.nanoTime()));
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
        InvocationState existing = get(reactorContext, INVOCATION_KEY, InvocationState.class);
        InvocationState state = existing == null ? createState(runtimeContext) : existing;
        InvocationState.AgentFrame agentFrame =
                state.newAgentFrame(agent.getAgentId(), agent.getName());
        List<io.agentscope.core.message.Msg> agentMessages =
                input.msgs() == null ? List.of() : input.msgs();
        Context parent = resolveOtelContext(Objects.requireNonNull(reactorContext));
        Span entry = null;
        Context agentParent = parent;
        if (existing == null) {
            entry =
                    common(
                                    tracer.spanBuilder("enter_application")
                                            .setSpanKind(SpanKind.INTERNAL)
                                            .setNoParent(),
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
            AgentScopeMessageConverter.ConversionResult converted =
                    messageConverter.convertBounded(agentMessages);
            MessageCapturePolicy.CapturedMessages captured =
                    messageCapturePolicy.capture(
                            converted.messages(), false, converted.complete());
            captured.value()
                    .ifPresent(
                            node -> {
                                agentSpan.setAttribute(
                                        "gen_ai.input.messages", node.toString());
                                if (inputEntry != null) {
                                    inputEntry.setAttribute(
                                            "gen_ai.input.messages", node.toString());
                                }
                            });
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
        Flux<AgentEvent> observed =
                downstream
                        .doOnNext(
                                event ->
                                        quietly(
                                                () ->
                                                        captureAgentResult(
                                                                agentSpan, rootEntry, event)))
                        .doOnComplete(
                                () ->
                                        quietly(
                                                () -> {
                                                    finishOpenStep(agentFrame, "stop", null);
                                                    endAgentTree(
                                                            ended,
                                                            agentSpan,
                                                            rootEntry,
                                                            agentFrame,
                                                            null,
                                                            false);
                                                }))
                        .doOnError(
                                error ->
                                        quietly(
                                                () -> {
                                                    finishOpenStep(agentFrame, "error", error);
                                                    endAgentTree(
                                                            ended,
                                                            agentSpan,
                                                            rootEntry,
                                                            agentFrame,
                                                            error,
                                                            false);
                                                }))
                        .doOnCancel(
                                () ->
                                        quietly(
                                                () -> {
                                                    finishOpenStep(agentFrame, "cancelled", null);
                                                    endAgentTree(
                                                            ended,
                                                            agentSpan,
                                                            rootEntry,
                                                            agentFrame,
                                                            null,
                                                            true);
                                                }));
        return propagate(observed, spanContext)
                .contextWrite(
                        context -> context.put(INVOCATION_KEY, state).put(AGENT_KEY, agentFrame));
    }

    private void captureAgentResult(Span agentSpan, @Nullable Span entry, AgentEvent event) {
        if (!(event instanceof AgentResultEvent result)) {
            return;
        }
        AgentScopeMessageConverter.ConversionResult converted =
                messageConverter.convertBounded(List.of(result.getResult()));
        messageCapturePolicy
                .capture(converted.messages(), false, converted.complete())
                .value()
                .ifPresent(
                        node -> {
                            agentSpan.setAttribute("gen_ai.output.messages", node.toString());
                            if (entry != null) {
                                entry.setAttribute("gen_ai.output.messages", node.toString());
                            }
                        });
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

    private static void acquireReactorHook() {
        if (REACTOR_HOOK_REGISTERED.compareAndSet(false, true)) {
            ContextPropagationOperator.builder().build().registerOnEachOperator();
        }
    }

    /**
     * Publishes the span context to nested middleware through the Reactor context.
     *
     * <p>The SDK carries its own key so parent and child spans stay connected without installing a
     * process wide Reactor hook. When the host explicitly enables the hook the context is also
     * side loaded into the OpenTelemetry operator so nested auto instrumentation can attach.
     */
    private Flux<AgentEvent> propagate(Flux<AgentEvent> flux, Context spanContext) {
        Flux<AgentEvent> published =
                reactorContextHookEnabled
                        ? ContextPropagationOperator.runWithContext(flux, spanContext)
                        : flux;
        return published.contextWrite(context -> context.put(OTEL_CONTEXT_KEY, spanContext));
    }

    private static Context resolveOtelContext(ContextView contextView) {
        Context stored = get(contextView, OTEL_CONTEXT_KEY, Context.class);
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

    private final class ToolSpan {
        private final Span span;
        private final long startedNanos;
        private final List<Object> results = new ArrayList<>();
        private final StringBuilder textResult = new StringBuilder();
        private final StreamingDigest digest = new StreamingDigest(true);
        private final AtomicBoolean ended = new AtomicBoolean();
        private int accumulatedBytes;
        private boolean hasText;
        private boolean truncated;

        private ToolSpan(Span span, long startedNanos) {
            this.span = span;
            this.startedNanos = startedNanos;
        }

        private void appendText(@Nullable String value) {
            if (value == null || ended.get() || !contentSanitizer.isCapturing()) {
                return;
            }
            synchronized (results) {
                if (contentSanitizer.isHashOnly()) {
                    digest.updateJson(value);
                    return;
                }
                hasText = true;
                int budget = contentSanitizer.maxBytes();
                if (textResult.length() >= budget) {
                    truncated = true;
                    return;
                }
                int allowed = Math.min(value.length(), budget - textResult.length());
                textResult.append(value, 0, allowed);
                if (allowed < value.length()) {
                    truncated = true;
                }
            }
        }

        private void appendData(@Nullable Object value) {
            if (value == null || ended.get() || !contentSanitizer.isCapturing()) {
                return;
            }
            synchronized (results) {
                if (contentSanitizer.isHashOnly()) {
                    digest.updateJson(value);
                    return;
                }
                if (results.size() >= MAX_TOOL_RESULT_PARTS
                        || accumulatedBytes >= contentSanitizer.maxBytes()) {
                    truncated = true;
                    return;
                }
                contentSanitizer
                        .capture(value)
                        .ifPresent(
                                captured -> {
                                    int bytes =
                                            captured.toString().getBytes(StandardCharsets.UTF_8).length;
                                    if (accumulatedBytes + bytes > contentSanitizer.maxBytes()) {
                                        truncated = true;
                                    } else {
                                        results.add(captured);
                                        accumulatedBytes += bytes;
                                    }
                                });
            }
        }

        private void success() {
            if (ended.compareAndSet(false, true)) {
                finishSpan(
                        span,
                        () -> {
                            try {
                                finishResult();
                            } finally {
                                setDuration(span, "gen_ai.tool.call.duration_ms", startedNanos);
                                span.setStatus(StatusCode.OK);
                            }
                        });
            }
        }

        private void error(String type, @Nullable Throwable error) {
            if (ended.compareAndSet(false, true)) {
                finishSpan(
                        span,
                        () -> {
                            try {
                                finishResult();
                            } finally {
                                setDuration(span, "gen_ai.tool.call.duration_ms", startedNanos);
                                span.setAttribute("gen_ai.tool.error.type", type);
                                span.setAttribute("error.type", type);
                                span.setStatus(StatusCode.ERROR, "tool execution failed");
                                String exceptionType =
                                        error == null ? type : error.getClass().getName();
                                span.addEvent(
                                        "exception",
                                        Objects.requireNonNull(
                                                Attributes.builder()
                                                        .put(
                                                                "exception.type",
                                                                Objects.requireNonNull(
                                                                        exceptionType))
                                                        .build()));
                            }
                        });
            }
        }

        private void finishResult() {
            synchronized (results) {
                if (contentSanitizer.isHashOnly()) {
                    if (digest.originalBytes() > 0) {
                        span.setAttribute(
                                "gen_ai.tool.call.result",
                                contentSanitizer
                                        .streamedContentHash(
                                                digest.hexDigest(), digest.originalBytes())
                                        .toString());
                    }
                    return;
                }
                List<Object> parts = new ArrayList<>();
                if (hasText) {
                    contentSanitizer.capture(textResult.toString()).ifPresent(parts::add);
                }
                parts.addAll(results);
                if (parts.isEmpty()) {
                    return;
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("parts", List.copyOf(parts));
                if (truncated) {
                    result.put("truncated", true);
                }
                capture(span, "gen_ai.tool.call.result", result);
            }
        }

        private void cancel() {
            error("cancelled", null);
        }
    }

    private final class StreamingDigest {
        private static final byte[] TOOL_PREFIX = "{\"parts\":[".getBytes(StandardCharsets.UTF_8);
        private static final byte[] TOOL_SUFFIX = "]}".getBytes(StandardCharsets.UTF_8);

        private final MessageDigest digest = sha256Digest();
        private final boolean toolResult;
        private long originalBytes;
        private int parts;
        private boolean finished;

        private StreamingDigest(boolean toolResult) {
            this.toolResult = toolResult;
            if (toolResult) {
                updateRaw(TOOL_PREFIX);
            }
        }

        private void updateText(String value) {
            updateRaw(value.getBytes(StandardCharsets.UTF_8));
        }

        private void updateJson(Object value) {
            if (!toolResult) {
                throw new IllegalStateException("JSON parts require a tool-result digest");
            }
            if (parts++ > 0) {
                updateRaw(new byte[] {','});
            }
            CountingDigestOutput output = new CountingDigestOutput(digest);
            try {
                canonicalWriter.writeValue(output, value);
                originalBytes += output.count();
            } catch (Exception exception) {
                telemetryFailed(exception);
            }
        }

        private void updateRaw(byte[] bytes) {
            digest.update(bytes);
            originalBytes += bytes.length;
        }

        private long originalBytes() {
            finishFraming();
            return originalBytes;
        }

        private String hexDigest() {
            finishFraming();
            return HexFormat.of().formatHex(digest.digest());
        }

        private void finishFraming() {
            if (finished) {
                return;
            }
            if (toolResult) {
                updateRaw(TOOL_SUFFIX);
            }
            finished = true;
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class CountingDigestOutput extends OutputStream {
        private final MessageDigest digest;
        private long count;

        private CountingDigestOutput(MessageDigest digest) {
            this.digest = digest;
        }

        @Override
        public void write(int value) {
            digest.update((byte) value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            digest.update(bytes, offset, length);
            count += length;
        }

        @Override
        public void close() throws IOException {
            // ObjectMapper owns this view, not the digest lifecycle.
        }

        private long count() {
            return count;
        }
    }
}
