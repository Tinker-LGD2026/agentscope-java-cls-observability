package io.github.tinkerlgd2026.agentscope.cls;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.middleware.MiddlewareBase;
import io.github.tinkerlgd2026.agentscope.cls.exporter.ClsSpanExporter;
import io.github.tinkerlgd2026.agentscope.cls.instrumentation.ClsTracingMiddleware;
import io.github.tinkerlgd2026.agentscope.cls.internal.CaptureMemoryPool;
import io.github.tinkerlgd2026.agentscope.cls.internal.JsonSupport;
import io.github.tinkerlgd2026.agentscope.cls.internal.TelemetryCounters;
import io.github.tinkerlgd2026.agentscope.cls.privacy.ContentSanitizer;
import io.github.tinkerlgd2026.agentscope.cls.privacy.MessageCapturePolicy;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanEncoder;
import io.github.tinkerlgd2026.agentscope.cls.schema.ClsSpanValidator;
import io.github.tinkerlgd2026.agentscope.cls.transport.ConsoleSpanSink;
import io.github.tinkerlgd2026.agentscope.cls.transport.SpanSink;
import io.github.tinkerlgd2026.agentscope.cls.transport.TencentClsAsyncTransport;
import io.github.tinkerlgd2026.agentscope.cls.transport.TencentClsSpanSink;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ClsAgentObservability implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClsAgentObservability.class);
    private static final String INSTRUMENTATION_NAME = "io.github.tinkerlgd2026.agentscope.cls";
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_FLUSH_TIMEOUT = Duration.ofMinutes(10);

    private final SdkTracerProvider tracerProvider;
    private final ClsSpanExporter exporter;
    private final MiddlewareBase middleware;
    private final TelemetryCounters counters;
    private final ExecutorService lifecycleExecutor =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "agentscope-cls-lifecycle");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicReference<Future<?>> activeLifecycleTask = new AtomicReference<>();

    private ClsAgentObservability(
            SdkTracerProvider tracerProvider,
            ClsSpanExporter exporter,
            MiddlewareBase middleware,
            TelemetryCounters counters) {
        this.tracerProvider = tracerProvider;
        this.exporter = exporter;
        this.middleware = middleware;
        this.counters = counters;
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
                                new TencentClsAsyncTransport(config))
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
        ClsSpanExporter exporter =
                new ClsSpanExporter(
                        new ClsSpanEncoder(objectMapper),
                        new ClsSpanValidator(objectMapper),
                        sink,
                        counters);
        var resource =
                Resource.builder()
                        .put("service.name", Objects.requireNonNull(config.serviceName()))
                        .put("host.name", Objects.requireNonNull(hostName()));
        String deploymentEnvironment = config.deploymentEnvironment();
        if (deploymentEnvironment != null) {
            resource.put("deployment.environment.name", deploymentEnvironment);
        }
        var spanProcessor =
                BatchSpanProcessor.builder(exporter)
                        .setScheduleDelay(Objects.requireNonNull(config.exportScheduleDelay()))
                        .setMaxQueueSize(config.maxQueueSize())
                        .setMaxExportBatchSize(
                                Math.max(64, Math.min(256, config.maxQueueSize() / 8)))
                        .build();
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
                        new CaptureMemoryPool(config.maxCaptureMemoryBytes()),
                        config.maxInvocationCaptureMemoryBytes(),
                        active::get,
                        counters,
                        objectMapper,
                        config.reactorContextHookEnabled());
        return new ClsAgentObservability(
                provider, exporter, new LifecycleMiddleware(middleware, active), counters);
    }

    public MiddlewareBase middleware() {
        return middleware;
    }

    public boolean flush(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("flush timeout must be positive");
        }
        if (timeout.compareTo(MAX_FLUSH_TIMEOUT) > 0) {
            throw new IllegalArgumentException("flush timeout must not exceed 10 minutes");
        }
        if (closed.get() || !flushing.compareAndSet(false, true)) {
            return false;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        Future<?> flushTask = null;
        try {
            exporter.prepareFlush(timeout);
            var providerResult = tracerProvider.forceFlush();
            providerResult.join(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            if (!providerResult.isSuccess() || closed.get()) {
                counters.exportFailed(1);
                return false;
            }
            Duration remaining = Duration.ofNanos(Math.max(1L, deadline - System.nanoTime()));
            exporter.prepareFlush(remaining);
            Future<io.opentelemetry.sdk.common.CompletableResultCode> sinkFlushTask =
                    lifecycleExecutor.submit(exporter::flush);
            flushTask = sinkFlushTask;
            activeLifecycleTask.set(flushTask);
            if (closed.get()) {
                flushTask.cancel(true);
                return false;
            }
            var sinkResult = sinkFlushTask.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            sinkResult.join(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            if (!sinkResult.isSuccess()) {
                counters.exportFailed(1);
                return false;
            }
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            counters.exportFailed(1);
            LOGGER.warn("CLS observability flush interrupted");
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            counters.exportFailed(1);
            LOGGER.warn("CLS observability flush failed: {}", exception.getClass().getSimpleName());
            return false;
        } finally {
            if (flushTask != null) {
                flushTask.cancel(true);
                activeLifecycleTask.compareAndSet(flushTask, null);
            }
            flushing.set(false);
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        return Math.max(
                1L, TimeUnit.NANOSECONDS.toMillis(Math.max(0L, deadlineNanos - System.nanoTime())));
    }

    public ClsTelemetrySnapshot snapshot() {
        return counters.snapshot();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LifecycleMiddleware lifecycle =
                middleware instanceof LifecycleMiddleware value ? value : null;
        if (lifecycle != null) {
            lifecycle.deactivate();
        }
        Future<?> activeTask = activeLifecycleTask.getAndSet(null);
        if (activeTask != null) {
            activeTask.cancel(true);
        }
        try {
            long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
            var shutdownTask = lifecycleExecutor.submit(tracerProvider::shutdown);
            var result = shutdownTask.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            result.join(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            if (!result.isSuccess()) {
                counters.exportFailed(1);
                LOGGER.warn("CLS observability shutdown did not complete cleanly");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            counters.exportFailed(1);
            LOGGER.warn("CLS observability shutdown interrupted");
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            counters.exportFailed(1);
            LOGGER.warn(
                    "CLS observability shutdown failed: {}", exception.getClass().getSimpleName());
        } finally {
            lifecycleExecutor.shutdownNow();
            if (lifecycle != null) {
                lifecycle.release();
            }
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
