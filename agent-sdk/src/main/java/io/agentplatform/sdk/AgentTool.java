package io.agentplatform.sdk;

/** A deployment-installed typed tool. Writes receive the durable invocation's idempotency key. */
public interface AgentTool<I, O> {
    Class<I> inputType();

    O execute(I arguments, String idempotencyKey);
}
