package io.agentplatform.sdk;

/** A deployment-owned Java handler. Instances may be invoked concurrently. */
@FunctionalInterface
public interface Agent<I, O> {
    O execute(I input, AgentExecutionContext context);
}
