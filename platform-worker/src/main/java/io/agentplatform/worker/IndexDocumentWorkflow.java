package io.agentplatform.worker;

import io.agentplatform.store.PlatformStore.IngestionRef;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.time.Duration;

@WorkflowImpl(taskQueues = "agent-runtime")
public final class IndexDocumentWorkflow implements KnowledgeWorkflow {
    private final KnowledgeActivities indexing =
            Workflow.newActivityStub(
                    KnowledgeActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofMinutes(20))
                            .setHeartbeatTimeout(Duration.ofMinutes(2))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setMaximumAttempts(3)
                                            .setInitialInterval(Duration.ofSeconds(2))
                                            .build())
                            .build());
    private final KnowledgeActivities projection =
            Workflow.newActivityStub(
                    KnowledgeActivities.class,
                    ActivityOptions.newBuilder()
                            .setStartToCloseTimeout(Duration.ofSeconds(15))
                            .setRetryOptions(
                                    RetryOptions.newBuilder()
                                            .setMaximumInterval(Duration.ofMinutes(1))
                                            .build())
                            .build());

    @Override
    public void ingest(IngestionRef job) {
        try {
            indexing.index(job);
        } catch (ActivityFailure failure) {
            projection.fail(job);
        }
    }
}
