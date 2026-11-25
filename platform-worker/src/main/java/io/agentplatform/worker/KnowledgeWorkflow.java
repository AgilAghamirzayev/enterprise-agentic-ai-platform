package io.agentplatform.worker;

import io.agentplatform.store.PlatformStore.IngestionRef;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface KnowledgeWorkflow {
    @WorkflowMethod(name = "IngestDocumentV1")
    void ingest(IngestionRef job);
}
