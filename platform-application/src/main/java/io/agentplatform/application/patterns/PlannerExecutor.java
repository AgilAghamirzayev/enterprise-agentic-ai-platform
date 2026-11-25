package io.agentplatform.application.patterns;

import io.agentplatform.sdk.Agent;
import io.agentplatform.sdk.AgentExecutionContext;
import io.agentplatform.spi.ModelRequest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** A model may sequence named inference operations. Only the deployment defines their instructions. */
public class PlannerExecutor implements Agent<String, PlannerExecutor.Result> {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private final Map<String, String> operations;
    private final int maxSteps;
    private final int maxOutputTokens;

    public record Result(List<String> operations, String output) {
        public Result {
            operations = List.copyOf(operations);
        }
    }

    public PlannerExecutor(Map<String, String> operations, int maxSteps, int maxOutputTokens) {
        if (operations.isEmpty() || operations.size() > 32 || maxSteps < 1 || maxSteps > 8
                || maxOutputTokens < 1 || maxOutputTokens > 8192) {
            throw new IllegalArgumentException("Invalid planner limits");
        }
        operations.forEach((name, instruction) -> {
            if (!NAME.matcher(name).matches() || instruction.isBlank() || instruction.length() > 4096) {
                throw new IllegalArgumentException("Invalid planner operation");
            }
        });
        this.operations = Collections.unmodifiableMap(new TreeMap<>(operations));
        this.maxSteps = maxSteps;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public Result execute(String task, AgentExecutionContext context) {
        String instruction = "Choose up to " + maxSteps + " distinct operations for the request. "
                + "Output one exact operation ID per line, in execution order. Available IDs: " + operations.keySet();
        var proposal = context.models().complete(new ModelRequest(instruction, task, Math.min(256, maxOutputTokens)));
        var steps = proposal.text().strip().lines().toList();
        if (steps.isEmpty() || steps.size() > maxSteps || new HashSet<>(steps).size() != steps.size()
                || !steps.stream().allMatch(operations::containsKey)) {
            throw new IllegalArgumentException("Model proposed an invalid execution plan");
        }
        String output = task;
        for (String step : steps) {
            context.checkCancellation();
            String input = "Original request:\n" + task + "\nCurrent work:\n" + output;
            output = context.models().complete(new ModelRequest(operations.get(step), input, maxOutputTokens)).text();
        }
        return new Result(steps, output);
    }
}
