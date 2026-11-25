package io.agentplatform.application;

import io.agentplatform.application.patterns.EvaluatorOptimizer;
import io.agentplatform.application.patterns.PlannerExecutor;
import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.domain.ExecutionLimits;
import io.agentplatform.sdk.Agent;
import io.agentplatform.sdk.AgentDefinition;
import io.agentplatform.spi.ModelRequest;
import io.agentplatform.spi.ModelResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReasoningPatternsTest {
    private final List<ModelRequest> calls = new ArrayList<>();

    private <O> O run(Agent<String, O> agent, Class<O> type, String... answers) {
        var id = new AgentDescriptor("brief", "1.0.0");
        var definition = new AgentDefinition<>(id, String.class, type, agent);
        var script = new ArrayDeque<>(List.of(answers));
        var engine = new LocalExecutionEngine(new AgentRegistry(List.of(definition)), input -> {
            calls.add(input);
            return new ModelResult(script.remove(), 10, 5);
        }, 1);
        return engine.start("demo", id, "Review the invoice.", type,
                new ExecutionLimits(8, 24_000, java.time.Duration.ofSeconds(2))).result().toCompletableFuture().join();
    }

    @Test
    void plannerExecutesOnlySelectedOperationsInOrder() {
        var agent = new PlannerExecutor(Map.of("extract", "List facts.", "summarize", "Write the brief."), 2, 64);
        var result = run(agent, PlannerExecutor.Result.class, "extract\nsummarize", "Two items", "Brief");
        assertEquals(List.of("extract", "summarize"), result.operations());
        assertEquals("Brief", result.output());
        assertEquals("List facts.", calls.get(1).system());
        assertTrue(calls.get(2).input().contains("Two items"));
    }

    @Test
    void plannerRejectsUnlistedOrDuplicateOperationBeforeExecution() {
        var agent = new PlannerExecutor(Map.of("extract", "List facts."), 2, 64);
        for (String invalid : List.of("send-email", "extract\nextract", "extract\nextract\nextract")) {
            calls.clear();
            assertThrows(CompletionException.class, () -> run(agent, PlannerExecutor.Result.class, invalid));
            assertEquals(1, calls.size());
        }
    }

    @Test
    void reviewerRevisesWithFeedbackAndStopsOnAcceptance() {
        var agent = new EvaluatorOptimizer("Cite the invoice.", 2, 64);
        var result = run(agent, EvaluatorOptimizer.Result.class, "draft", "REVISE\nAdd the invoice number.",
                "Invoice 123", "ACCEPT");
        assertEquals(new EvaluatorOptimizer.Result("Invoice 123", true, 1, ""), result);
        assertTrue(calls.get(2).input().contains("Add the invoice number."));
        assertEquals(4, calls.size());
    }

    @Test
    void reviewExhaustionAndInvalidVerdictRemainObservable() {
        var agent = new EvaluatorOptimizer("Cite the invoice.", 0, 64);
        var rejected = run(agent, EvaluatorOptimizer.Result.class, "Draft", "REVISE\nAdd sources.");
        assertFalse(rejected.accepted());
        assertEquals("Add sources.", rejected.feedback());
        assertThrows(CompletionException.class, () -> run(agent, EvaluatorOptimizer.Result.class, "Draft", "maybe"));
    }
}
