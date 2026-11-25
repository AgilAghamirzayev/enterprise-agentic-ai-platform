package io.agentplatform.worker;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface AgentRun {
    record RunRef(String tenant, String runId) {}

    @WorkflowMethod(name = "AgentRunV1")
    void run(RunRef ref);

    @SignalMethod(name = "wake")
    void wake();
}
