package io.agentplatform.application;

import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.domain.ExecutionLimits;
import io.agentplatform.sdk.*;
import io.agentplatform.spi.ModelGateway;
import io.agentplatform.spi.ModelRequest;
import io.agentplatform.spi.ModelResult;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class LocalContext implements AgentExecutionContext {
    private final String tenant;
    private final UUID id;
    private final AgentDescriptor descriptor;
    private final ModelGateway delegate;
    private final ExecutionLimits limits;
    private final CompletableFuture<?> result;
    private final long deadlineNanos;
    private long remainingTokens;
    private int calls;
    private int resourceReads;
    private final InvocationResources resources;

    LocalContext(String tenant, UUID id, AgentDescriptor descriptor, ModelGateway delegate,
                 ExecutionLimits limits, CompletableFuture<?> result, InvocationResources resources) {
        this.tenant = tenant;
        this.id = id;
        this.descriptor = descriptor;
        this.delegate = delegate;
        this.limits = limits;
        this.result = result;
        this.deadlineNanos = System.nanoTime() + limits.timeout().toNanos();
        this.remainingTokens = limits.maxTokens();
        this.resources = resources.guarded(this::reserveRead);
    }

    @Override public String tenantId() { return tenant; }
    @Override public UUID runId() { return id; }
    @Override public AgentDescriptor agent() { return descriptor; }
    @Override public KnowledgeGateway knowledge() { return resources.knowledge(); }
    @Override public MemoryGateway memory() { return resources.memory(); }
    @Override public ToolGateway tools() { return resources.tools(); }
    @Override public ModelGateway models() { return this::complete; }

    @Override
    public void checkCancellation() {
        if (System.nanoTime() - deadlineNanos >= 0) {
            throw stop(ExecutionRejected.Reason.TIMEOUT);
        }
        if (result.isDone() || Thread.currentThread().isInterrupted()) {
            throw new ExecutionRejected(ExecutionRejected.Reason.CANCELLED);
        }
    }

    private synchronized void reserveRead() {
        checkCancellation();
        if (++resourceReads > 64) throw stop(ExecutionRejected.Reason.BUDGET_EXCEEDED);
    }

    private ModelResult complete(ModelRequest request) {
        long reservation = request.estimatedInputTokens() + (long) request.maxOutputTokens();
        synchronized (this) {
            checkCancellation();
            if (calls >= limits.maxModelCalls() || reservation > remainingTokens) {
                throw stop(ExecutionRejected.Reason.BUDGET_EXCEEDED);
            }
            calls++;
            remainingTokens -= reservation;
        }

        // An ambiguous provider failure retains its reservation.
        ModelResult response = delegate.complete(request);
        synchronized (this) {
            remainingTokens += reservation - response.totalTokens();
            if (remainingTokens < 0 || response.outputTokens() > request.maxOutputTokens()) {
                throw stop(ExecutionRejected.Reason.BUDGET_EXCEEDED);
            }
        }
        checkCancellation();
        return response;
    }

    private ExecutionRejected stop(ExecutionRejected.Reason reason) {
        var rejection = new ExecutionRejected(reason);
        result.completeExceptionally(rejection);
        return rejection;
    }
}
