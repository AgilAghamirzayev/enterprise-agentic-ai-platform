package io.agentplatform.plan;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * An immutable, bounded graph. References use name:version and never resolve a floating version.
 */
public record Plan(
        String start,
        List<Node> nodes,
        List<String> allowedTools,
        Limits limits,
        List<String> invokeRoles,
        List<String> policyRefs,
        Memory memory,
        String description) {
    public Plan {
        invokeRoles =
                invokeRoles == null
                        ? List.of("operator", "developer", "user")
                        : List.copyOf(invokeRoles);
        policyRefs = policyRefs == null ? List.of() : List.copyOf(policyRefs);
        memory = memory == null ? new Memory("DEFAULT", 8) : memory;
        nodes = List.copyOf(nodes);
        allowedTools = List.copyOf(allowedTools);
    }

    public Plan(
            String start,
            List<Node> nodes,
            List<String> allowedTools,
            Limits limits,
            List<String> invokeRoles) {
        this(start, nodes, allowedTools, limits, invokeRoles, null, null, null);
    }

    public record Memory(String type, int maxMessages) {
        public boolean permitsJavaHistory() {
            return type.equals("DEFAULT") || (type.equals("CONVERSATION") && maxMessages >= 20);
        }
    }

    public boolean permitsInvocation(java.util.Collection<String> roles) {
        return roles.contains("admin") || roles.stream().anyMatch(invokeRoles::contains);
    }

    public Node node(String id) {
        return nodes.stream()
                .filter(n -> n.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown node " + id));
    }

    public record Limits(
            int maxModelCalls,
            int maxTokens,
            int maxOutputTokens,
            int timeoutSeconds,
            int maxSteps,
            int maxDepth,
            int maxToolCalls,
            java.math.BigDecimal maxCostUsd,
            Integer maxIterations,
            Integer maxParallelAgents) {
        public Limits {
            if (maxIterations == null) maxIterations = 12;
            if (maxParallelAgents == null) maxParallelAgents = 8;
        }

        public Limits(
                int calls,
                int tokens,
                int output,
                int timeout,
                int steps,
                int depth,
                int tools,
                java.math.BigDecimal cost) {
            this(calls, tokens, output, timeout, steps, depth, tools, cost, null, null);
        }

        public Limits restrict(Limits other) {
            var cost =
                    maxCostUsd == null
                            ? other.maxCostUsd()
                            : other.maxCostUsd() == null
                                    ? maxCostUsd
                                    : maxCostUsd.min(other.maxCostUsd());
            return new Limits(
                    Math.min(maxModelCalls, other.maxModelCalls()),
                    Math.min(maxTokens, other.maxTokens()),
                    Math.min(maxOutputTokens, other.maxOutputTokens()),
                    Math.min(timeoutSeconds, other.timeoutSeconds()),
                    Math.min(maxSteps, other.maxSteps()),
                    Math.min(maxDepth, other.maxDepth()),
                    Math.min(maxToolCalls, other.maxToolCalls()),
                    cost,
                    Math.min(maxIterations, other.maxIterations()),
                    Math.min(maxParallelAgents, other.maxParallelAgents()));
        }
    }

    public enum Type {
        AGENT,
        TOOL_CALLING,
        MODEL_DECISION,
        SELECTED_TOOL,
        TOOL_GATE,
        TOOL_RECEIPT,
        LIMIT,
        TOOL,
        CONDITION,
        PARALLEL,
        JOIN,
        APPROVAL,
        WAIT,
        TRANSFORM,
        SUPERVISOR,
        SUB_WORKFLOW,
        END
    }

    public record Node(
            String id,
            Type type,
            String next,
            String prompt,
            String tool,
            String agentRef,
            String workflowRef,
            String knowledgeBase,
            String field,
            JsonNode equalsValue,
            String onTrue,
            String onFalse,
            String onApproved,
            String onRejected,
            String role,
            Integer timeoutSeconds,
            Integer seconds,
            List<String> branches,
            String promptRef,
            String modelProfileRef,
            String javaRef,
            String expression,
            String toolRef,
            List<String> knowledgeBases,
            Boolean iterative,
            java.util.Map<String, String> toolBindings,
            Integer iteration) {
        public Node {
            toolBindings =
                    toolBindings == null ? java.util.Map.of() : java.util.Map.copyOf(toolBindings);
            knowledgeBases = knowledgeBases == null ? List.of() : List.copyOf(knowledgeBases);
            branches = branches == null ? List.of() : List.copyOf(branches);
            equalsValue = equalsValue == null ? null : equalsValue.deepCopy();
        }
    }
}
