package io.agentplatform.server;

import io.agentplatform.store.PlatformStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class DispatchQueue {
    private static final Logger log = LoggerFactory.getLogger(DispatchQueue.class);
    private final PlatformStore store;
    private final WorkflowClient client;
    private final MeterRegistry metrics;

    DispatchQueue(PlatformStore store, WorkflowClient client, MeterRegistry metrics) {
        this.store = store;
        this.client = client;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${platform.dispatch.interval:500ms}")
    void drain() {
        for (var command : store.pendingStarts(32)) {
            try {
                var options =
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(command.runId())
                                .setTaskQueue("agent-runtime")
                                .setWorkflowIdReusePolicy(
                                        WorkflowIdReusePolicy
                                                .WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                                .build();
                client.newUntypedWorkflowStub("AgentRunV1", options)
                        .start(new RunRef(command.tenant(), command.runId()));
            } catch (WorkflowExecutionAlreadyStarted delivered) {
                // The workflow ID also covers a crash between start and acknowledgement.
            } catch (RuntimeException unavailable) {
                metrics.counter("platform.dispatch.failures").increment();
                log.warn("Temporal dispatch deferred for run {}", command.runId());
                continue;
            }
            store.acknowledgeStart(command.tenant(), command.runId());
        }
    }

    record RunRef(String tenant, String runId) {}
}
