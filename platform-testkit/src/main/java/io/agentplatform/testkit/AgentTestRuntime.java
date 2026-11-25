package io.agentplatform.testkit;

import io.agentplatform.application.AgentRegistry;
import io.agentplatform.application.ExecutionRejected;
import io.agentplatform.application.LocalExecutionEngine;
import io.agentplatform.domain.ExecutionLimits;
import io.agentplatform.sdk.AgentDefinition;
import io.agentplatform.sdk.InvocationResources;
import io.agentplatform.spi.ModelGateway;
import io.agentplatform.spi.ModelRequest;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Predicate;

/**
 * Runs typed agents through the local engine and records fixture calls separately for each
 * invocation.
 */
public final class AgentTestRuntime {
    private final ModelGateway model;
    private final ExecutionLimits limits;
    private final InvocationResources resources;
    private final Predicate<ResourceFixtures.Read> toolPolicy;

    public enum Status {
        COMPLETED,
        DENIED,
        FAILED,
        BUSY,
        TIMEOUT,
        CANCELLED,
        BUDGET_EXCEEDED
    }

    public record Run<O>(
            Status status,
            O output,
            Throwable failure,
            List<ModelRequest> modelCalls,
            List<ResourceFixtures.Read> executedTools,
            List<ResourceFixtures.Read> deniedTools) {}

    private AgentTestRuntime(Builder builder) {
        model = builder.model;
        limits = builder.limits;
        resources = builder.resources;
        toolPolicy = builder.toolPolicy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ModelGateway model = new FakeModelGateway();
        private ExecutionLimits limits = ExecutionLimits.defaults();
        private InvocationResources resources = InvocationResources.denied();
        private Predicate<ResourceFixtures.Read> toolPolicy = ignored -> true;

        private Builder() {}

        public Builder model(ModelGateway gateway) {
            model = Objects.requireNonNull(gateway);
            return this;
        }

        public Builder limits(ExecutionLimits value) {
            limits = Objects.requireNonNull(value);
            return this;
        }

        public Builder resources(InvocationResources value) {
            resources = Objects.requireNonNull(value);
            return this;
        }

        public Builder toolPolicy(Predicate<ResourceFixtures.Read> policy) {
            toolPolicy = Objects.requireNonNull(policy);
            return this;
        }

        public AgentTestRuntime build() {
            return new AgentTestRuntime(this);
        }
    }

    public <I, O> Run<O> run(AgentDefinition<I, O> agent, I input) {
        var calls = new ConcurrentLinkedQueue<ModelRequest>();
        var executed = new ConcurrentLinkedQueue<ResourceFixtures.Read>();
        var denied = new ConcurrentLinkedQueue<ResourceFixtures.Read>();
        var registry = new AgentRegistry(List.of(agent));
        var local =
                new LocalExecutionEngine(
                        registry,
                        request -> {
                            calls.add(request);
                            return model.complete(request);
                        },
                        1);
        var scoped =
                new InvocationResources(
                        resources.knowledge(),
                        resources.memory(),
                        (name, args) -> {
                            var call = new ResourceFixtures.Read(name, args);
                            try {
                                if (!toolPolicy.test(call))
                                    throw new SecurityException("Tool denied by test policy");
                                String value = resources.tools().read(name, args);
                                executed.add(call);
                                return value;
                            } catch (SecurityException refused) {
                                denied.add(call);
                                throw refused;
                            }
                        });
        O output = null;
        Status status = Status.COMPLETED;
        Throwable failure = null;
        try {
            output =
                    local.start(
                                    "test",
                                    agent.descriptor(),
                                    input,
                                    agent.outputType(),
                                    limits,
                                    scoped)
                            .result()
                            .toCompletableFuture()
                            .join();
        } catch (CompletionException e) {
            failure = e.getCause();
            status =
                    switch (failure) {
                        case ExecutionRejected rejected -> Status.valueOf(rejected.reason().name());
                        case SecurityException deniedCall -> Status.DENIED;
                        default -> Status.FAILED;
                    };
        }
        return new Run<>(
                status,
                output,
                failure,
                List.copyOf(calls),
                List.copyOf(executed),
                List.copyOf(denied));
    }
}
