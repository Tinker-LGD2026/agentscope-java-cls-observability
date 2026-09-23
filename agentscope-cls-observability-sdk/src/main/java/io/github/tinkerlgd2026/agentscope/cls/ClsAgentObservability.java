package io.github.tinkerlgd2026.agentscope.cls;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.middleware.MiddlewareBase;
import io.github.tinkerlgd2026.agentscope.cls.exporter.ClsBatchSpanProcessor;
import io.github.tinkerlgd2026.agentscope.cls.exporter.EncodedSpanQueue;
import io.github.tinkerlgd2026.agentscope.cls.exporter.SpanRecordExporter;
import io.github.tinkerlgd2026.agentscope.cls.instrumentation.ClsTracingMiddleware;
import io.github.tinkerlgd2026.agentscope.cls.internal.CaptureMemoryPool;
import io.github.tinkerlgd2026.agentscope.cls.internal.DeadlineBudget;
import io.github.tinkerlgd2026.agentscope.cls.internal.JsonSupport;
import io.github.tinkerlgd2026.agentscope.cls.internal.LifecycleCoordinator;
import io.github.tinkerlgd2026.agentscope.cls.internal.SinkLifecycleAdapter;
import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentSanitizer;
import io.github.tinkerlgd2026.agentscope.cls.privacy.MessageCapturePolicy;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanEncoder;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanValidator;
import io.github.tinkerlgd2026.agentscope.cls.transport.ConsoleSpanSink;
import io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink;
import io.github.tinkerlgd2026.agentscope.cls.transport.TencentClsAsyncTransport;
import io.github.tinkerlgd2026.agentscope.cls.transport.TencentClsSpanSink;
import io.github.tinkerlgd2026.agentscope.cls.transport.TencentExportBatchPlanner;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClsAgentObservability implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClsAgentObservability.class);
    private static final String INSTRUMENTATION_NAME = "io.github.tinkerlgd2026.agentscope.cls";
    private static final Duration MAX_FLUSH_TIMEOUT = Duration.ofMinutes(10);

    private final SdkTracerProvider tracerProvider;
    private final MiddlewareBase middleware;
    private final TelemetryCounters counters;
    private final LifecycleCoordinator coordinator;
    private final Duration shutdownTimeout;
    private final ExecutorService lifecycleExecutor;
    private final AtomicBoolean resourcesReleased = new AtomicBoolean();

    private ClsAgentObservability(
            SdkTracerProvider tracerProvider,
            MiddlewareBase middleware,
            TelemetryCounters counters,
            LifecycleCoordinator coordinator,
            Duration shutdownTimeout,
            ExecutorService lifecycleExecutor) {
        this.tracerProvider = tracerProvider;
        this.middleware = middleware;
        this.counters = counters;
        this.coordinator = coordinator;
        this.shutdownTimeout = shutdownTimeout;
        this.lifecycleExecutor = lifecycleExecutor;
    }

    /** Fixed-size, daemon, bounded-queue executor for flush/shutdown chain tasks. */
    private static ExecutorService newLifecycleExecutor() {
        return new java.util.concurrent.ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(128),
                runnable -> {
                    Thread thread = new Thread(runnable, "agentscope-cls-lifecycle");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public static ClsAgentObservability create(ClsObservabilityConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        ObjectMapper objectMapper = JsonSupport.newObjectMapper();
        SpanSink sink =
                config.transportMode() == ClsObservabilityConfig.TransportMode.CLOUD
                        ? new TencentClsSpanSink(
                                Objects.requireNonNull(config.topicId()),
                                new TencentClsAsyncTransport(config),
                                config.exportTimeout(),
                                new TencentExportBatchPlanner(
                                        config.maxExportBatchBytes(),
                                        config.maxExportBatchCount()))
                        : new ConsoleSpanSink(objectMapper, System.out);
        return create(config, sink, objectMapper);
    }

    public static ClsAgentObservability create(ClsObservabilityConfig config, SpanSink sink) {
        return create(config, sink, JsonSupport.newObjectMapper());
    }

    private static ClsAgentObservability create(
            ClsObservabilityConfig config, SpanSink sink, ObjectMapper objectMapper) {
        if (config == null || sink == null) {
            throw new IllegalArgumentException("config and sink are required");
        }
        TelemetryCounters counters = new TelemetryCounters();
        CaptureMemoryPool memoryPool = new CaptureMemoryPool(config.maxCaptureMemoryBytes());
        EncodedSpanQueue queue = new EncodedSpanQueue(memoryPool, config.maxQueueSize());
        ClsBatchSpanProcessor spanProcessor =
                new ClsBatchSpanProcessor(
                        new ClsSpanEncoder(objectMapper, config.maxContentBytes()),
                        new ClsSpanValidator(objectMapper),
                        queue,
                        new SpanRecordExporter(sink, counters),
                        counters,
                        Objects.requireNonNull(config.exportScheduleDelay()),
                        Math.max(64, Math.min(256, config.maxQueueSize() / 8)),
                        config.maxExportBatchBytes(),
                        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                                runnable -> {
                                    Thread thread =
                                            new Thread(runnable, "agentscope-cls-export");
                                    thread.setDaemon(true);
                                    return thread;
                                }));
        var resource =
                Resource.builder()
                        .put("service.name", Objects.requireNonNull(config.serviceName()))
                        .put("host.name", Objects.requireNonNull(hostName()));
        String deploymentEnvironment = config.deploymentEnvironment();
        if (deploymentEnvironment != null) {
            resource.put("deployment.environment.name", deploymentEnvironment);
        }
        SdkTracerProvider provider =
                SdkTracerProvider.builder()
                        .setResource(Objects.requireNonNull(resource.build()))
                        .addSpanProcessor(Objects.requireNonNull(spanProcessor))
                        .build();
        AtomicBoolean active = new AtomicBoolean(true);
        ClsTracingMiddleware middleware =
                new ClsTracingMiddleware(
                        provider.get(INSTRUMENTATION_NAME),
                        new ContentSanitizer(
                                objectMapper,
                                config.contentCaptureMode(),
                                config.maxContentBytes()),
                        new MessageCapturePolicy(
                                objectMapper,
                                config.contentCaptureMode(),
                                config.reasoningCaptureMode(),
                                config.maxContentBytes()),
                        config.contentCaptureMode(),
                        config.reasoningCaptureMode(),
                        config.providerPayloadCaptureMode(),
                        config.truncatePreviewBytes(),
                        config.maxContentBytes(),
                        memoryPool,
                        config.maxInvocationCaptureMemoryBytes(),
                        active::get,
                        counters,
                        objectMapper,
                        config.reactorContextMode(),
                        config.hostTraceLinkEnabled(),
                        config.hitlWaitTimeout());
        LifecycleMiddleware lifecycle = new LifecycleMiddleware(middleware, active);
        ClsTracingMiddleware tracing = middleware;
        ExecutorService lifecycleExecutor = newLifecycleExecutor();
        LifecycleCoordinator.Stage flushStage =
                budget -> {
                    spanProcessor.prepareFlush(budget.remaining());
                    try {
                        var result = provider.forceFlush();
                        result.join(
                                Math.max(1L, budget.remaining().toMillis()),
                                TimeUnit.MILLISECONDS);
                        return result.isSuccess();
                    } catch (RuntimeException exception) {
                        return false;
                    }
                };
        SinkLifecycleAdapter sinkAdapter = new SinkLifecycleAdapter(sink);
        LifecycleCoordinator coordinator =
                new LifecycleCoordinator(
                        lifecycleExecutor,
                        flushStage,
                        new LifecycleCoordinator.ShutdownStages(
                                () -> {
                                    lifecycle.deactivate();
                                    tracing.beginDrain();
                                },
                                budget -> tracing.awaitQuiescence(budget.remaining()),
                                tracing::freezeInvocations,
                                flushStage,
                                sinkAdapter.barrierStage(),
                                budget -> {
                                    try {
                                        var result = provider.shutdown();
                                        result.join(
                                                Math.max(1L, budget.remaining().toMillis()),
                                                TimeUnit.MILLISECONDS);
                                        return result.isSuccess();
                                    } catch (RuntimeException exception) {
                                        return false;
                                    }
                                }));
        return new ClsAgentObservability(
                provider,
                lifecycle,
                counters,
                coordinator,
                config.shutdownTimeout(),
                lifecycleExecutor);
    }

    public MiddlewareBase middleware() {
        return middleware;
    }

    /**
     * Flushes queued spans and waits for the sink barrier. Concurrent callers share one
     * single-flight operation; each caller's own timeout only bounds its wait. Returns false
     * on timeout/failure and after shutdown begins; true once closed.
     */
    public boolean flush(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("flush timeout must be positive");
        }
        if (timeout.compareTo(MAX_FLUSH_TIMEOUT) > 0) {
            throw new IllegalArgumentException("flush timeout must not exceed 10 minutes");
        }
        boolean flushed = coordinator.flush(timeout);
        if (!flushed && coordinator.state() == LifecycleCoordinator.State.RUNNING) {
            counters.flushFailed(1);
        }
        return flushed;
    }

    /**
     * Graceful shutdown: enters DRAINING immediately (new roots are rejected, registered
     * leases continue), then runs quiescence(40%)/processor flush(65%)/sink barrier(85%)/
     * provider close(100%) against one absolute deadline. The first call owns the unique
     * shutdown chain; later calls wait on it; after CLOSE_FAILED a later call resumes from
     * the first incomplete stage. Returns false on timeout/failure and never throws for
     * telemetry problems.
     */
    public boolean shutdown(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("shutdown timeout must be positive");
        }
        if (timeout.compareTo(MAX_FLUSH_TIMEOUT) > 0) {
            throw new IllegalArgumentException("shutdown timeout must not exceed 10 minutes");
        }
        boolean stopped = coordinator.shutdown(timeout);
        if (!stopped) {
            counters.shutdownFailed(1);
        }
        return stopped;
    }

    public ClsTelemetrySnapshot snapshot() {
        return counters.snapshot();
    }

    /**
     * Detailed telemetry: per-category counters plus active/waiting invocation gauges read at
     * call time. All counter values are monotonic longs; the legacy {@link #snapshot()}
     * aggregates export/flush/shutdown failures for 0.2 compatibility.
     */
    public ClsDetailedTelemetrySnapshot detailedSnapshot() {
        int active = 0;
        int waiting = 0;
        if (middleware instanceof LifecycleMiddleware lifecycle) {
            active = lifecycle.activeInvocations();
            waiting = lifecycle.waitingInvocations();
        }
        return counters.detailedSnapshot(active, waiting);
    }

    /** Equivalent to {@code shutdown(shutdownTimeout)}; failures are counted, never thrown. */
    @Override
    public void close() {
        shutdown(shutdownTimeout);
        if (!resourcesReleased.compareAndSet(false, true)) {
            return;
        }
        // shutdown() rather than shutdownNow(): a stuck single-flight flush keeps running on
        // the daemon pool and completes when its sink frees; interruption would falsify it.
        lifecycleExecutor.shutdown();
        LifecycleMiddleware lifecycle =
                middleware instanceof LifecycleMiddleware value ? value : null;
        if (lifecycle != null) {
            lifecycle.release();
        }
    }

    private static String hostName() {
        try {
            String value = InetAddress.getLocalHost().getHostName();
            return value == null || value.isBlank() ? "unknown" : value;
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private static final class LifecycleMiddleware implements MiddlewareBase {
        private final ClsTracingMiddleware delegate;
        private final AtomicBoolean active;

        private LifecycleMiddleware(ClsTracingMiddleware delegate, AtomicBoolean active) {
            this.delegate = delegate;
            this.active = active;
        }

        private void deactivate() {
            active.set(false);
        }

        private int activeInvocations() {
            return delegate.activeInvocationCount();
        }

        private int waitingInvocations() {
            return delegate.waitingInvocationCount();
        }

        private void release() {
            delegate.close();
        }

        @Override
        public int order() {
            return delegate.order();
        }

        @Override
        public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> onAgent(
                io.agentscope.core.agent.Agent agent,
                io.agentscope.core.agent.RuntimeContext context,
                io.agentscope.core.middleware.AgentInput input,
                java.util.function.Function<
                                io.agentscope.core.middleware.AgentInput,
                                reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent>>
                        next) {
            return delegate.onAgent(agent, context, input, next);
        }

        @Override
        public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> onReasoning(
                io.agentscope.core.agent.Agent agent,
                io.agentscope.core.agent.RuntimeContext context,
                io.agentscope.core.middleware.ReasoningInput input,
                java.util.function.Function<
                                io.agentscope.core.middleware.ReasoningInput,
                                reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent>>
                        next) {
            return delegate.onReasoning(agent, context, input, next);
        }

        @Override
        public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> onActing(
                io.agentscope.core.agent.Agent agent,
                io.agentscope.core.agent.RuntimeContext context,
                io.agentscope.core.middleware.ActingInput input,
                java.util.function.Function<
                                io.agentscope.core.middleware.ActingInput,
                                reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent>>
                        next) {
            return delegate.onActing(agent, context, input, next);
        }

        @Override
        public reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent> onModelCall(
                io.agentscope.core.agent.Agent agent,
                io.agentscope.core.agent.RuntimeContext context,
                io.agentscope.core.middleware.ModelCallInput input,
                java.util.function.Function<
                                io.agentscope.core.middleware.ModelCallInput,
                                reactor.core.publisher.Flux<io.agentscope.core.event.AgentEvent>>
                        next) {
            return delegate.onModelCall(agent, context, input, next);
        }
    }
}
