package io.agentplatform.sdk;

import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.spi.ModelGateway;
import java.util.UUID;

/** Gateways belong to the current invocation and expire when it finishes. */
public interface AgentExecutionContext {
    String tenantId();
    UUID runId();
    AgentDescriptor agent();
    ModelGateway models();
    default KnowledgeGateway knowledge() { return InvocationResources.denied().knowledge(); }
    default MemoryGateway memory() { return InvocationResources.denied().memory(); }
    default ToolGateway tools() { return InvocationResources.denied().tools(); }
    void checkCancellation();
}
