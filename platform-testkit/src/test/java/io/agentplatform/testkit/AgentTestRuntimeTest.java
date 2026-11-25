package io.agentplatform.testkit;

import static org.junit.jupiter.api.Assertions.*;

import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.domain.ExecutionLimits;
import io.agentplatform.sdk.AgentDefinition;
import io.agentplatform.spi.ModelRequest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

class AgentTestRuntimeTest {
    private final ResourceFixtures.Read call = new ResourceFixtures.Read("lookup", "{}");
    private final AgentDefinition<String, String> agent =
            new AgentDefinition<>(
                    new AgentDescriptor("support", "1.0.0"),
                    String.class,
                    String.class,
                    (input, ctx) ->
                            ctx.models()
                                    .complete(
                                            new ModelRequest(
                                                    "summarize",
                                                    ctx.tools().read("lookup", input),
                                                    32))
                                    .text());

    @Test
    void policyDenialPrecedesExecutionAndModelInference() {
        var runtime =
                AgentTestRuntime.builder()
                        .toolPolicy(ignored -> false)
                        .resources(
                                ResourceFixtures.of(Map.of(), List.of(), Map.of(call, "fixture")))
                        .build();
        var result = runtime.run(agent, "{}");
        assertEquals(AgentTestRuntime.Status.DENIED, result.status());
        assertEquals(List.of(call), result.deniedTools());
        assertTrue(result.modelCalls().isEmpty());
        assertTrue(result.executedTools().isEmpty());
        assertEquals(
                AgentTestRuntime.Status.DENIED,
                AgentTestRuntime.builder().build().run(agent, "{}").status());
    }

    @Test
    void recordsBoundedInteractionsAndHonorsRealRuntimeLimits() {
        var builder =
                AgentTestRuntime.builder()
                        .resources(
                                ResourceFixtures.of(Map.of(), List.of(), Map.of(call, "fixture")));
        var result = builder.build().run(agent, "{}");
        assertEquals("fixture", result.output());
        assertEquals(List.of(call), result.executedTools());
        assertEquals("fixture", result.modelCalls().getFirst().input());
        var blocked =
                builder.limits(new ExecutionLimits(0, 256, Duration.ofSeconds(2)))
                        .build()
                        .run(agent, "{}");
        assertEquals(AgentTestRuntime.Status.BUDGET_EXCEEDED, blocked.status());
        assertTrue(blocked.modelCalls().isEmpty());
    }
}
