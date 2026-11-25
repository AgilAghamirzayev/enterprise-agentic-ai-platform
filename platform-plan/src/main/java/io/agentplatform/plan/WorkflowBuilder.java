package io.agentplatform.plan;

import com.fasterxml.jackson.databind.node.*;

import java.time.Duration;
import java.util.Arrays;

/**
 * A Java DSL for the same publishable plan as a YAML definition. Call compile after adding all
 * nodes.
 */
public final class WorkflowBuilder {
    private final ObjectNode spec = JsonNodeFactory.instance.objectNode();
    private final ArrayNode nodes = spec.putArray("nodes");

    private WorkflowBuilder(String start) {
        spec.put("start", start);
    }

    public static WorkflowBuilder start(String node) {
        return new WorkflowBuilder(node);
    }

    public WorkflowBuilder allowTools(String... names) {
        strings(spec, "allowedTools", names);
        return this;
    }

    public WorkflowBuilder invokeRoles(String... names) {
        strings(spec, "invokeRoles", names);
        return this;
    }

    public WorkflowBuilder policies(String... refs) {
        strings(spec, "policyRefs", refs);
        return this;
    }

    public WorkflowBuilder limits(Plan.Limits limits) {
        spec.set("limits", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(limits));
        return this;
    }

    public WorkflowBuilder toolCalling(String id, String prompt, String next) {
        node(id, "TOOL_CALLING", next).put("prompt", prompt).put("iterative", true);
        return this;
    }

    public WorkflowBuilder agent(String id, String ref, String next) {
        node(id, "AGENT", next).put("agentRef", ref);
        return this;
    }

    public WorkflowBuilder infer(String id, String prompt, String next) {
        node(id, "AGENT", next).put("prompt", prompt);
        return this;
    }

    public WorkflowBuilder tool(String id, String ref, String next) {
        node(id, "TOOL", next).put("toolRef", ref);
        return this;
    }

    public WorkflowBuilder subWorkflow(String id, String ref, String next) {
        node(id, "SUB_WORKFLOW", next).put("workflowRef", ref);
        return this;
    }

    public WorkflowBuilder condition(String id, String expression, String onTrue, String onFalse) {
        node(id, "CONDITION", null)
                .put("expression", expression)
                .put("onTrue", onTrue)
                .put("onFalse", onFalse);
        return this;
    }

    public WorkflowBuilder approval(
            String id, String role, Duration timeout, String onApproved, String onRejected) {
        node(id, "APPROVAL", null)
                .put("role", role)
                .put("timeoutSeconds", seconds(timeout))
                .put("onApproved", onApproved)
                .put("onRejected", onRejected);
        return this;
    }

    public WorkflowBuilder parallel(String id, String join, String... branches) {
        strings(node(id, "PARALLEL", join), "branches", branches);
        return this;
    }

    public WorkflowBuilder supervisor(String id, String... branches) {
        strings(node(id, "SUPERVISOR", null), "branches", branches);
        return this;
    }

    public WorkflowBuilder join(String id, String next) {
        node(id, "JOIN", next);
        return this;
    }

    public WorkflowBuilder waitFor(String id, Duration duration, String next) {
        node(id, "WAIT", next).put("seconds", seconds(duration));
        return this;
    }

    public WorkflowBuilder select(String id, String field, String next) {
        node(id, "TRANSFORM", next).put("field", field);
        return this;
    }

    public WorkflowBuilder end(String id) {
        node(id, "END", null);
        return this;
    }

    public String compile() {
        return PlanCompiler.compile(spec.toString());
    }

    private static int seconds(Duration duration) {
        if (duration.getNano() != 0)
            throw new IllegalArgumentException("Specify a whole number of seconds");
        return Math.toIntExact(duration.toSeconds());
    }

    private ObjectNode node(String id, String type, String next) {
        var result = nodes.addObject().put("id", id).put("type", type);
        if (next != null) result.put("next", next);
        return result;
    }

    private static void strings(ObjectNode target, String field, String[] values) {
        var array = target.putArray(field);
        Arrays.stream(values).forEach(array::add);
    }
}
