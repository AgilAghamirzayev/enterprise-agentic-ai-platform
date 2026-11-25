package io.agentplatform.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.application.AgentRegistry;
import io.agentplatform.connectors.ExternalTool;
import io.agentplatform.plan.*;
import io.agentplatform.store.PlatformStore;
import io.agentplatform.testkit.FakeModelGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.temporal.client.*;
import io.temporal.testing.*;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.*;
import java.util.concurrent.*;

class ExternalWriteWorkflowTest {
    @Test
    void approvedWriteWithLostReceiptFailsOnceAcrossTemporalHistoryReplay() throws Exception {
        var ref = new AgentRun.RunRef("acme", UUID.randomUUID().toString());
        var store = mock(PlatformStore.class);
        var json = new ObjectMapper();
        var args = json.createObjectNode().put("reference", "sample");
        var run = json.createObjectNode().put("status", "RUNNING");
        run.set("input", args);
        run.putArray("roles").add("operator");
        run.set(
                "plan",
                json.readTree(
                        PlanCompiler.compile(
                                """
                                start: submit
                                nodes:
                                  - {id: submit,type: TOOL,toolRef: 'submit:1.0.0',next: end}
                                  - {id: end,type: END}
                                """)));
        var schema = json.createObjectNode();
        schema.set(
                "spec",
                json.readTree(
                        """
                        {"inputSchema":{"type":"object","required":["reference"]},"outputSchema":{"type":"object"}}
                        """));
        when(store.run("acme", ref.runId())).thenReturn(run);
        when(store.runControl("acme", ref.runId())).thenReturn(run);
        when(store.definition("acme", "tool", "submit", "1.0.0")).thenReturn(schema);
        when(store.authorizeTool(
                        anyString(), anyString(), anyString(), anyString(), any(), anyList()))
                .thenReturn(json.createObjectNode().put("effect", "ALLOW"));
        var pending = new CountDownLatch(1);
        var approved = new java.util.concurrent.atomic.AtomicBoolean();
        when(store.requestApproval("acme", ref.runId(), "submit", "submit", args, "approver", 300))
                .thenAnswer(
                        call -> {
                            pending.countDown();
                            return json.createObjectNode()
                                    .put("status", approved.get() ? "APPROVED" : "PENDING");
                        });
        when(store.claimExternalWrite("acme", ref.runId(), "submit", "submit", args, "approver"))
                .thenReturn(true, false);
        var connector = mock(ExternalTool.class);
        when(connector.name()).thenReturn("submit");
        when(connector.version()).thenReturn("1.0.0");
        when(connector.allowsTenant("acme")).thenReturn(true);
        when(connector.invokeApproved(args, ref.runId() + "/submit"))
                .thenThrow(new IllegalStateException("Remote receipt unavailable"));
        var activities =
                new StoredActivities(
                        store,
                        json,
                        new ModelRoutes(
                                DeploymentModels.from("demo", "", ""),
                                new FakeModelGateway(),
                                null,
                                List.of()),
                        List.of(connector),
                        new SimpleMeterRegistry(),
                        new JavaHandlers(new AgentRegistry(List.of()), store, json, ""),
                        ObservationRegistry.NOOP);
        try (var service =
                new GenericContainer<>("temporalio/temporal:1.5.1")
                        .withExposedPorts(7233)
                        .withCommand("server", "start-dev", "--headless", "--ip", "0.0.0.0")) {
            service.start();
            var options =
                    TestEnvironmentOptions.newBuilder()
                            .setUseExternalService(true)
                            .setTarget(service.getHost() + ":" + service.getMappedPort(7233))
                            .build();
            try (var env = TestWorkflowEnvironment.newInstance(options)) {
                var worker = env.newWorker("external-write");
                worker.registerWorkflowImplementationTypes(AgentRunWorkflow.class);
                worker.registerActivitiesImplementations(activities);
                env.start();
                var wf =
                        env.getWorkflowClient()
                                .newWorkflowStub(
                                        AgentRun.class,
                                        WorkflowOptions.newBuilder()
                                                .setWorkflowId(ref.runId())
                                                .setTaskQueue("external-write")
                                                .build());
                var execution = WorkflowClient.start(wf::run, ref);
                assertTrue(pending.await(10, TimeUnit.SECONDS));
                verify(connector, never()).invokeApproved(any(), anyString());
                approved.set(true);
                wf.wake();
                WorkflowStub.fromTyped(wf).getResult(15, TimeUnit.SECONDS, Void.class);
                verify(store).finish("acme", ref.runId(), "FAILED", null);
                WorkflowReplayer.replayWorkflowExecution(
                        env.getWorkflowExecutionHistory(execution), AgentRunWorkflow.class);
                verify(connector, times(1)).invokeApproved(args, ref.runId() + "/submit");
                verify(store, never())
                        .completeExternalWrite(
                                anyString(), anyString(), anyString(), anyString(), any(), any());
            }
        }
    }
}
