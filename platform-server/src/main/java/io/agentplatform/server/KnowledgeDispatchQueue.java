package io.agentplatform.server;

import io.agentplatform.store.PlatformStore;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class KnowledgeDispatchQueue {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeDispatchQueue.class);
    private final PlatformStore store;
    private final WorkflowClient client;

    KnowledgeDispatchQueue(PlatformStore store, WorkflowClient client) {
        this.store = store;
        this.client = client;
    }

    @Scheduled(fixedDelayString = "${platform.dispatch.interval:500ms}")
    void drain() {
        for (var command : store.pendingIngestions(16)) {
            var options =
                    WorkflowOptions.newBuilder()
                            .setWorkflowId("index-" + command.jobId() + "-" + command.generation())
                            .setTaskQueue("agent-runtime")
                            .setWorkflowIdReusePolicy(
                                    WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                            .build();
            try {
                client.newUntypedWorkflowStub("IngestDocumentV1", options).start(command);
            } catch (WorkflowExecutionAlreadyStarted delivered) {
                // Lost acknowledgements reuse the same workflow ID.
            } catch (RuntimeException unavailable) {
                LOG.warn("Ingestion dispatch deferred for job {}", command.jobId());
                continue;
            }
            store.acknowledgeIngestion(command);
        }
    }
}
