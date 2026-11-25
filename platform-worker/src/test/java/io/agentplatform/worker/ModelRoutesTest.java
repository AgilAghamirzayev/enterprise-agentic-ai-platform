package io.agentplatform.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.plan.DeploymentModels;
import io.agentplatform.plan.PlanCompiler;
import io.agentplatform.spi.*;
import io.agentplatform.store.PlatformStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

class ModelRoutesTest {
    private final DeploymentModels inventory = DeploymentModels.from("demo", "", "standby");
    private final ModelRequest request = new ModelRequest("summarize", "sample", 20);
    private final ModelGateway backup = input -> new ModelResult("ready", 5, 1);

    @Test
    void attemptsOnlyReservedAllowedModelsAndReservesAgainForFallback() {
        var routes =
                new ModelRoutes(
                        inventory,
                        input -> {
                            throw new UncheckedIOException(new IOException("timeout"));
                        },
                        null,
                        List.of(new ModelBinding("standby", backup)));
        var reserved = new ArrayList<String>();
        assertEquals("ready", routes.complete("echo", "standby", request, reserved::add).text());
        assertEquals(List.of("echo", "standby"), reserved);
        assertThrows(
                IllegalArgumentException.class,
                () -> routes.complete("other", null, request, reserved::add));
        assertEquals(2, reserved.size());
    }

    @Test
    void aBudgetDenialOrInvalidResponseDoesNotTriggerFallback() {
        var routes =
                new ModelRoutes(
                        inventory,
                        input -> {
                            throw new IllegalStateException("invalid output");
                        },
                        null,
                        List.of(new ModelBinding("standby", input -> fail("unexpected fallback"))));
        var reserved = new ArrayList<String>();
        assertThrows(
                IllegalStateException.class,
                () -> routes.complete("echo", "standby", request, reserved::add));
        assertEquals(List.of("echo"), reserved);
        assertThrows(
                SecurityException.class,
                () ->
                        routes.complete(
                                "echo",
                                "standby",
                                request,
                                id -> {
                                    throw new SecurityException("budget refused");
                                }));
    }

    @Test
    void usesVersionPinnedPromptAndProviderWithinTenantAndRecordsSelectedModel() throws Exception {
        var store = mock(PlatformStore.class);
        var json = new ObjectMapper();
        var routes =
                new ModelRoutes(
                        inventory,
                        ignored -> fail("unexpected default"),
                        null,
                        List.of(
                                new ModelBinding(
                                        "standby",
                                        input -> {
                                            assertEquals("Use policy v3.", input.system());
                                            assertEquals("sample", input.input());
                                            return backup.complete(input);
                                        })));
        var plan =
                PlanCompiler.parse(
                        """
                        start: classify
                        nodes:
                          - {id: classify, type: AGENT, promptRef: 'policy:3.0.0', modelProfileRef: 'support:1.0.0', next: done}
                          - {id: done, type: END}
                        """);
        when(store.definition("north", "prompt", "policy", "3.0.0"))
                .thenReturn(
                        json.readTree(
                                """
                                {"spec":{"text":"Use policy v3."}}
                                """));
        when(store.definition("north", "model-profile", "support", "1.0.0"))
                .thenReturn(
                        json.readTree(
                                """
                                {"spec":{"provider":"demo","model":"standby"}}
                                """));
        var ctx =
                new StepContext(
                        new AgentRun.RunRef("north", "run"),
                        "classify",
                        json.createObjectNode(),
                        plan,
                        plan,
                        plan.node("classify"),
                        json.getNodeFactory().textNode("sample"));
        new ModelSteps(
                        store,
                        json,
                        routes,
                        new SimpleMeterRegistry(),
                        io.micrometer.observation.ObservationRegistry.NOOP)
                .generate(ctx);
        verify(store)
                .reserveModel(
                        eq("north"),
                        eq("run"),
                        eq("classify"),
                        anyInt(),
                        anyInt(),
                        anyLong(),
                        anyInt(),
                        isNull(),
                        eq("standby"));
        verify(store)
                .saveStep(
                        "north", "run", "classify", json.getNodeFactory().textNode("ready"), 5, 1);
    }
}
