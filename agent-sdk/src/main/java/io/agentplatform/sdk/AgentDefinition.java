package io.agentplatform.sdk;

import io.agentplatform.domain.AgentDescriptor;
import java.util.Objects;

/** Type information is retained at registration to validate public entry points. */
public record AgentDefinition<I, O>(AgentDescriptor descriptor, Class<I> inputType, Class<O> outputType, Agent<I, O> handler) {
    public AgentDefinition {
        Objects.requireNonNull(descriptor);
        Objects.requireNonNull(inputType);
        Objects.requireNonNull(outputType);
        Objects.requireNonNull(handler);
    }

    public O invoke(Object input, AgentExecutionContext context) {
        return outputType.cast(Objects.requireNonNull(handler.execute(inputType.cast(input), context), "Missing agent output"));
    }
}
