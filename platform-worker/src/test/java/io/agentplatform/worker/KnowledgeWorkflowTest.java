package io.agentplatform.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.agentplatform.store.PlatformStore.IngestionRef;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

class KnowledgeWorkflowTest {
    @Test
    void transientErrorsRetryAndPermanentErrorsProjectFailure() {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("index-test");
            var temporary = new AtomicInteger();
            var permanent = new AtomicInteger();
            var failed = new AtomicInteger();
            worker.registerWorkflowImplementationTypes(IndexDocumentWorkflow.class);
            worker.registerActivitiesImplementations(
                    new KnowledgeActivities() {
                        public void index(IngestionRef job) {
                            if (job.jobId().equals("broken")) {
                                permanent.incrementAndGet();
                                throw new IllegalStateException("Provider unavailable");
                            }
                            if (temporary.incrementAndGet() == 1)
                                throw new IllegalStateException("Transient error");
                        }

                        public void fail(IngestionRef job) {
                            failed.incrementAndGet();
                        }
                    });
            environment.start();
            var options = WorkflowOptions.newBuilder().setTaskQueue("index-test").build();
            environment
                    .getWorkflowClient()
                    .newWorkflowStub(KnowledgeWorkflow.class, options)
                    .ingest(new IngestionRef("tenant", "recover", 1));
            environment
                    .getWorkflowClient()
                    .newWorkflowStub(KnowledgeWorkflow.class, options)
                    .ingest(new IngestionRef("tenant", "broken", 1));
            assertEquals(2, temporary.get());
            assertEquals(3, permanent.get());
            assertEquals(1, failed.get());
        }
    }
}
