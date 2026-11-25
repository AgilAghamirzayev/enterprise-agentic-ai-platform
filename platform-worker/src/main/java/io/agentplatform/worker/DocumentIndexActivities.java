package io.agentplatform.worker;

import io.agentplatform.store.PlatformStore;
import io.agentplatform.store.PlatformStore.IngestionRef;
import io.temporal.activity.Activity;
import io.temporal.client.ActivityCompletionException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;

import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = "agent-runtime")
public final class DocumentIndexActivities implements KnowledgeActivities {
    private final PlatformStore store;

    public DocumentIndexActivities(PlatformStore store) {
        this.store = store;
    }

    @Override
    public void index(IngestionRef job) {
        try {
            store.indexIngestion(
                    job,
                    completedChunks -> Activity.getExecutionContext().heartbeat(completedChunks));
        } catch (ActivityCompletionException interrupted) {
            throw interrupted;
        } catch (RuntimeException failure) {
            // Provider and database exception messages can contain private document text.
            throw ApplicationFailure.newFailure("Document indexing failed", "KNOWLEDGE_INDEXING");
        }
    }

    @Override
    public void fail(IngestionRef job) {
        store.failIngestion(job);
    }
}
