package io.agentplatform.application;

import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.domain.ExecutionLimits;
import io.agentplatform.spi.ModelGateway;
import io.agentplatform.sdk.InvocationResources;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** In-process execution of trusted handlers. A slot is held until the handler actually exits. */
public final class LocalExecutionEngine {
    private final AgentRegistry registry;
    private final ModelGateway gateway;
    private final Semaphore slots;

    public LocalExecutionEngine(AgentRegistry registry, ModelGateway gateway, int maxConcurrentRuns) {
        this.registry = Objects.requireNonNull(registry);
        this.gateway = Objects.requireNonNull(gateway);
        if (maxConcurrentRuns < 1) {
            throw new IllegalArgumentException("Concurrency must be positive");
        }
        this.slots = new Semaphore(maxConcurrentRuns);
    }

    public <O> LocalRun<O> start(String tenant, AgentDescriptor agent, Object input, Class<O> outputType, ExecutionLimits limits) {
        return start(tenant, agent, input, outputType, limits, InvocationResources.denied());
    }

    public <O> LocalRun<O> start(String tenant, AgentDescriptor agent, Object input, Class<O> outputType,
                                 ExecutionLimits limits, InvocationResources resources) {
        if (tenant == null || tenant.isBlank() || tenant.length() > 128) {
            throw new IllegalArgumentException("Missing or invalid tenant");
        }
        Objects.requireNonNull(limits);
        var definition = registry.require(agent);
        if (!definition.inputType().isInstance(input) || !outputType.isAssignableFrom(definition.outputType())) {
            throw new IllegalArgumentException("Input or result type does not match the agent definition");
        }
        var result = new CompletableFuture<O>();
        var id = UUID.randomUUID();
        var context = new LocalContext(tenant, id, agent, gateway, limits, result, resources);
        var worker = Thread.ofVirtual().unstarted(() -> {
            O value;
            try {
                context.checkCancellation();
                value = outputType.cast(definition.invoke(input, context));
                context.checkCancellation();
            } catch (Throwable error) {
                slots.release();
                result.completeExceptionally(error);
                return;
            }
            slots.release();
            result.complete(value);
        });
        if (!slots.tryAcquire()) {
            throw new ExecutionRejected(ExecutionRejected.Reason.BUSY);
        }
        // The timeout future is cancelled by normal completion, including manual cancellation.
        result.copy().orTimeout(limits.timeout().toNanos(), TimeUnit.NANOSECONDS)
                .whenComplete((value, error) -> {
                    if (error instanceof TimeoutException) {
                        result.completeExceptionally(new ExecutionRejected(ExecutionRejected.Reason.TIMEOUT));
                    }
                });
        result.whenComplete((value, error) -> {
            if (error instanceof ExecutionRejected && Thread.currentThread() != worker) {
                worker.interrupt();
            }
        });
        try {
            worker.start();
        } catch (RuntimeException | Error error) {
            slots.release();
            result.completeExceptionally(error);
            throw error;
        }
        return new LocalRun<>(id, result);
    }
}
