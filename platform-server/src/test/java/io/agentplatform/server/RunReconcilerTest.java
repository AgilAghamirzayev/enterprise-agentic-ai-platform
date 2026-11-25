package io.agentplatform.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.store.PlatformStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.*;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.*;

class RunReconcilerTest {
    @WorkflowInterface
    public interface Upstream {
        @WorkflowMethod(name = "AgentRunV1")
        void run(boolean hold);
    }

    public static class UpstreamImpl implements Upstream {
        public void run(boolean hold) {
            if (hold) Workflow.await(() -> false);
        }
    }

    @Test
    void usesTemporalDescribeToDistinguishUnprojectedClosureFromActiveAndRecentlyMissingHistory() {
        var store = mock(PlatformStore.class);
        var json = new ObjectMapper();
        String open = UUID.randomUUID().toString();
        String closed = UUID.randomUUID().toString();
        String missing = UUID.randomUUID().toString();
        var recentlyCreated = json.createObjectNode().put("createdAt", Instant.now().toString());
        when(store.run("company", missing)).thenReturn(recentlyCreated);
        when(store.dueReconciliations(16, 60))
                .thenReturn(
                        List.of(
                                new PlatformStore.StartCommand("company", open),
                                new PlatformStore.StartCommand("company", closed),
                                new PlatformStore.StartCommand("company", missing)));
        try (var temporal =
                TestWorkflowEnvironment.newInstance(
                        io.temporal.testing.TestEnvironmentOptions.newBuilder()
                                .setUseTimeskipping(false)
                                .build())) {
            temporal.newWorker("recovery").registerWorkflowImplementationTypes(UpstreamImpl.class);
            temporal.start();
            var client = temporal.getWorkflowClient();
            var options = WorkflowOptions.newBuilder().setTaskQueue("recovery");
            var running =
                    client.newWorkflowStub(Upstream.class, options.setWorkflowId(open).build());
            WorkflowClient.start(running::run, true);
            var done =
                    client.newWorkflowStub(Upstream.class, options.setWorkflowId(closed).build());
            done.run(false);
            new RunReconciler(store, client, new SimpleMeterRegistry(), json).reconcile();
            var evidence = ArgumentCaptor.forClass(JsonNode.class);
            verify(store).finish(eq("company"), eq(closed), eq("FAILED"), evidence.capture());
            assertEquals("RECONCILIATION_REQUIRED", evidence.getValue().get("code").asText());
            assertEquals(
                    "WORKFLOW_EXECUTION_STATUS_COMPLETED",
                    evidence.getValue().get("temporalStatus").asText());
            verify(store, never()).finish(eq("company"), eq(open), anyString(), any());
            verify(store, never()).finish(eq("company"), eq(missing), anyString(), any());
        }
    }
}
