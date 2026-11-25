package io.agentplatform.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.*;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.*;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

class RunWakeTest {
    @WorkflowInterface
    public interface WaitingRun {
        @WorkflowMethod
        void waitForDecision();

        @SignalMethod(name = "wake")
        void wake();
    }

    public static class WaitingRunImpl implements WaitingRun {
        private boolean ready;

        @Override
        public void waitForDecision() {
            Workflow.await(() -> ready);
        }

        @Override
        public void wake() {
            ready = true;
        }
    }

    @Test
    void notifiesWaitingWorkflowThroughTemporalWithoutBlockingCaller() throws Exception {
        try (var temporal = TestWorkflowEnvironment.newInstance()) {
            temporal.newWorker("wake-test")
                    .registerWorkflowImplementationTypes(WaitingRunImpl.class);
            temporal.start();
            var client = temporal.getWorkflowClient();
            String id = UUID.randomUUID().toString();
            var waiting =
                    client.newWorkflowStub(
                            WaitingRun.class,
                            WorkflowOptions.newBuilder()
                                    .setTaskQueue("wake-test")
                                    .setWorkflowId(id)
                                    .build());
            WorkflowClient.start(waiting::waitForDecision);
            new RunWake(client, new SimpleMeterRegistry()).notifyWorker(id);
            WorkflowStub.fromTyped(waiting).getResult(5, TimeUnit.SECONDS, Void.class);
        }
    }

    @Test
    void transportFailureLeavesCommittedCallerResultAvailable() {
        var client = mock(WorkflowClient.class);
        when(client.getOptions()).thenThrow(new IllegalStateException("closed client"));
        var metrics = new SimpleMeterRegistry();
        assertDoesNotThrow(
                () -> new RunWake(client, metrics).notifyWorker(UUID.randomUUID().toString()));
        assertEquals(1.0, metrics.counter("platform.wake.deferred").count());
    }
}
