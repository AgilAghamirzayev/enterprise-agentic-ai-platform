package io.agentplatform.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.application.AgentRegistry;
import io.agentplatform.plan.*;
import io.agentplatform.store.PlatformStore;
import io.agentplatform.testkit.FakeModelGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.*;
import io.temporal.failure.ApplicationFailure;

import org.junit.jupiter.api.Test;

import java.util.*;

class StoredActivitiesTest {
    final ObjectMapper json = new ObjectMapper();
    final PlatformStore store = mock(PlatformStore.class);
    final List<Observation.Context> traces = new ArrayList<>();
    final AgentRun.RunRef ref = new AgentRun.RunRef("acme", UUID.randomUUID().toString());
    final StoredActivities activities;

    StoredActivitiesTest() {
        var observations = ObservationRegistry.create();
        observations
                .observationConfig()
                .observationHandler(
                        new ObservationHandler<Observation.Context>() {
                            @Override
                            public boolean supportsContext(Observation.Context context) {
                                return true;
                            }

                            @Override
                            public void onStop(Observation.Context context) {
                                traces.add(context);
                            }
                        });
        var routes =
                new ModelRoutes(
                        DeploymentModels.from("demo", "", ""),
                        new FakeModelGateway(),
                        null,
                        List.of());
        var handlers = new JavaHandlers(new AgentRegistry(List.of()), store, json, "");
        activities =
                new StoredActivities(
                        store,
                        json,
                        routes,
                        List.of(),
                        new SimpleMeterRegistry(),
                        handlers,
                        observations);
    }

    @Test
    void nestedVersionCannotBeInvokedThroughMorePermissiveParent() throws Exception {
        var run = active("user", "AGENT");
        var restricted = run.required("plan").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restricted)
                .putArray("invokeRoles")
                .add("operator");
        var version = json.createObjectNode();
        version.set("spec", restricted);
        when(store.definition("acme", "agent", "private", "1.0.0")).thenReturn(version);
        assertNotNull(activities.graph(ref, null));
        var denied =
                assertThrows(
                        ApplicationFailure.class,
                        () ->
                                activities.graph(
                                        ref,
                                        new RunActivities.Definition("agent", "private:1.0.0")));
        assertEquals("DENIED", denied.getType());
    }

    @Test
    void storedParentMemoryRestrictionStopsJavaBeforeExecutingTheHandler() throws Exception {
        var parent = active("operator", "AGENT,agentRef: 'advisor:1.0.0'");
        ((com.fasterxml.jackson.databind.node.ObjectNode) parent.required("plan"))
                .putObject("memory")
                .put("type", "NONE")
                .put("maxMessages", 0);
        var published = json.createObjectNode();
        published.set(
                "spec",
                json.readTree(
                        PlanCompiler.compile(
                                """
                                start: run
                                nodes:
                                  - {id: run,type: AGENT,javaRef: 'advice:1.0.0',next: end}
                                  - {id: end,type: END}
                                """)));
        when(store.definition("acme", "agent", "advisor", "1.0.0")).thenReturn(published);
        var step =
                new RunActivities.Step(
                        ref,
                        new RunActivities.Definition("agent", "advisor:1.0.0"),
                        "run",
                        "act/run",
                        "",
                        0,
                        null);
        var denied = assertThrows(ApplicationFailure.class, () -> activities.execute(step));
        assertEquals("DENIED", denied.getType());
    }

    @Test
    void inferenceAndToolsEmitNestedObservationsWithoutPromptOrResultAttributes() throws Exception {
        active("operator", "AGENT");
        var step = new RunActivities.Step(ref, null, "act", "act", "", 0, null);
        assertEquals("DONE", activities.execute(step));
        active("operator", "TOOL,tool: uppercase");
        when(store.authorizeTool(
                        eq("acme"), anyString(), anyString(), eq("uppercase"), any(), anyList()))
                .thenReturn(json.createObjectNode().put("effect", "ALLOW"));
        assertEquals("DONE", activities.execute(step));
        assertEquals(
                List.of(
                        "agent.model.inference",
                        "agent.step",
                        "tool.execution.duration",
                        "agent.step"),
                traces.stream().map(Observation.Context::getName).toList());
        for (var trace : traces) {
            assertFalse(trace.getAllKeyValues().toString().contains("private-input"));
            assertFalse(trace.getLowCardinalityKeyValues().toString().contains(ref.runId()));
        }
        assertEquals(
                "agent.step", traces.getFirst().getParentObservation().getContextView().getName());
        assertEquals("agent.step", traces.get(2).getParentObservation().getContextView().getName());
    }

    private JsonNode active(String role, String type) throws Exception {
        String source =
                """
                start: act
                allowedTools: [uppercase]
                nodes:
                  - {id: act,type: %s,next: end}
                  - {id: end,type: END}
                """
                        .formatted(type);
        var run = json.createObjectNode().put("status", "RUNNING").put("input", "private-input");
        run.putArray("roles").add(role);
        run.set("plan", json.readTree(PlanCompiler.compile(source)));
        when(store.run(ref.tenant(), ref.runId())).thenReturn(run);
        return run;
    }
}
