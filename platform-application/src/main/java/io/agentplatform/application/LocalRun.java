package io.agentplatform.application;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class LocalRun<O> {
    private final UUID id;
    private final CompletableFuture<O> result;

    LocalRun(UUID id, CompletableFuture<O> result) {
        this.id = id;
        this.result = result;
    }

    public UUID id() {
        return id;
    }

    /** Failure may report an agent exception or an explicit runtime rejection. */
    public CompletionStage<O> result() {
        return result.minimalCompletionStage();
    }

    public boolean cancel() {
        return result.completeExceptionally(new ExecutionRejected(ExecutionRejected.Reason.CANCELLED));
    }
}
