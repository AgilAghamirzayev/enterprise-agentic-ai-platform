package io.agentplatform.plan;

import static io.agentplatform.plan.Plan.Type.*;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.*;
import java.util.regex.Pattern;

/** Shared publish-time and worker validation for YAML or JSON workflow definitions. */
public final class PlanCompiler {
    private static final ObjectMapper YAML =
            new ObjectMapper(new YAMLFactory())
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private static final ObjectMapper JSON =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern REF =
            Pattern.compile("[a-z][a-z0-9-]{0,63}:[0-9]+[.][0-9]+[.][0-9]+");
    private static final Plan.Limits DEFAULTS =
            new Plan.Limits(12, 16384, 1024, 300, 64, 3, 16, null);

    private PlanCompiler() {}

    private static String requirePinned(String kind, String name) {
        throw new IllegalArgumentException(
                "A registry or pinned version is required for " + kind + " " + name);
    }

    public static String compile(String source) {
        return compile(source, PlanCompiler::requirePinned);
    }

    public static String compile(
            String source, java.util.function.BiFunction<String, String, String> resolver) {
        return compile(source, resolver, null, null, null);
    }

    public static String compile(
            String source,
            java.util.function.BiFunction<String, String, String> resolver,
            String expectedKind,
            String expectedName,
            String expectedVersion) {
        try {
            return JSON.writeValueAsString(
                    parse(source, resolver, expectedKind, expectedName, expectedVersion));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode workflow", e);
        }
    }

    public static Plan parse(String source) {
        return parse(source, PlanCompiler::requirePinned, null, null, null);
    }

    private static Plan parse(
            String source,
            java.util.function.BiFunction<String, String, String> resolver,
            String expectedKind,
            String expectedName,
            String expectedVersion) {
        require(source != null && source.length() <= 65536, "Plan exceeds 64 Ki characters");
        try {
            JsonNode parsed = YAML.readTree(source);
            require(parsed instanceof ObjectNode, "Plan must be an object");
            ObjectNode spec = (ObjectNode) parsed;
            if (spec.has("spec")) {
                require(
                        "platform.ai/v1alpha1".equals(spec.path("apiVersion").asText()),
                        "Unsupported apiVersion");
                require(
                        Set.of("Workflow", "Agent").contains(spec.path("kind").asText()),
                        "Unsupported kind");
                if (expectedKind != null) {
                    require(
                            expectedKind.equalsIgnoreCase(spec.path("kind").asText()),
                            "Manifest kind does not match resource");
                    if (spec.has("metadata")) {
                        require(
                                expectedName.equals(spec.path("metadata").path("name").asText())
                                        && expectedVersion.equals(
                                                spec.path("metadata").path("version").asText()),
                                "Manifest metadata does not match resource");
                    }
                }
                require(spec.get("spec") instanceof ObjectNode, "spec must be an object");
                spec = (ObjectNode) spec.get("spec");
            }
            spec = Blueprint.normalize(spec, resolver);
            ObjectNode limits = JSON.valueToTree(DEFAULTS);
            if (spec.has("limits")) {
                require(spec.get("limits") instanceof ObjectNode, "limits must be an object");
                limits.setAll((ObjectNode) spec.get("limits"));
            }
            spec.set("limits", limits);
            Plan plan = YAML.treeToValue(spec, Plan.class);
            validate(plan);
            return plan;
        } catch (java.io.IOException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid workflow definition", e);
        }
    }

    private static void validate(Plan plan) {
        var l = plan.limits();
        range(l.maxIterations(), 1, 128, "maxIterations");
        range(l.maxParallelAgents(), 1, 8, "maxParallelAgents");
        require(
                plan.description() == null || plan.description().length() <= 2048,
                "Invalid description");
        require(
                plan.policyRefs().size() <= 12
                        && plan.policyRefs().stream().allMatch(ref -> REF.matcher(ref).matches()),
                "Unpinned policy reference");
        require(
                Set.of("NONE", "DEFAULT", "CONVERSATION", "EPISODIC")
                        .contains(plan.memory().type()),
                "Unsupported memory type");
        range(plan.memory().maxMessages(), 0, 100, "maxMessages");
        require(
                !plan.memory().type().equals("DEFAULT") || plan.memory().maxMessages() == 8,
                "Invalid default memory window");
        range(l.maxModelCalls(), 0, 128, "maxModelCalls");
        require(
                l.maxCostUsd() == null
                        || (l.maxCostUsd().signum() >= 0
                                && l.maxCostUsd().scale() >= -6
                                && l.maxCostUsd().scale() <= 12
                                && l.maxCostUsd().precision() <= 18),
                "Invalid maxCostUsd");
        range(l.maxToolCalls(), 0, 128, "maxToolCalls");
        range(l.maxTokens(), 1, 1000000, "maxTokens");
        range(l.maxOutputTokens(), 1, 8192, "maxOutputTokens");
        range(l.timeoutSeconds(), 1, 86400, "timeoutSeconds");
        range(l.maxSteps(), 1, 256, "maxSteps");
        range(l.maxDepth(), 0, 5, "maxDepth");
        require(
                plan.nodes().size() > 0 && plan.nodes().size() <= 128,
                "Graph requires 1 to 128 nodes");
        require(
                plan.allowedTools().size() <= 64
                        && plan.allowedTools().stream().allMatch(PlanCompiler::name),
                "Invalid tool allowlist");
        require(
                plan.invokeRoles().size() <= 32
                        && plan.invokeRoles().stream().allMatch(PlanCompiler::name),
                "Invalid invocation roles");
        Map<String, Plan.Node> nodes = new LinkedHashMap<>();
        for (Plan.Node node : plan.nodes()) {
            require(
                    name(node.id()) && node.type() != null && nodes.put(node.id(), node) == null,
                    "Invalid or duplicate node");
        }
        require(nodes.containsKey(plan.start()), "Missing start node");
        Map<String, List<String>> edges = new HashMap<>();
        for (Plan.Node node : plan.nodes()) {
            var links = edges(plan, node);
            require(links.stream().allMatch(nodes::containsKey), "Unknown target in " + node.id());
            edges.put(node.id(), links);
            if (node.type() == PARALLEL) {
                require(
                        node.next() != null
                                && nodes.containsKey(node.next())
                                && nodes.get(node.next()).type() == JOIN,
                        "Parallel requires JOIN");
                for (String branch : node.branches()) {
                    var child = nodes.get(branch);
                    require(
                            Set.of(AGENT, TOOL, TOOL_CALLING, TRANSFORM, SUB_WORKFLOW)
                                            .contains(child.type())
                                    && node.next().equals(child.next()),
                            "Parallel branches must each perform one operation then join");
                }
            }
        }
        Set<String> reached = new HashSet<>();
        for (var receipt : plan.nodes()) {
            if (receipt.type() != TOOL_RECEIPT) continue;
            var gate = nodes.get(receipt.field());
            require(
                    gate != null
                            && gate.type() == TOOL_GATE
                            && gate.toolRef().equals(receipt.toolRef()),
                    "Invalid tool receipt binding");
            var handler = nodes.get(gate.next());
            require(
                    handler != null
                            && (handler.agentRef() != null || handler.workflowRef() != null)
                            && receipt.id().equals(handler.next()),
                    "Invalid delegated tool target");
            require(
                    !plan.start().equals(handler.id()) && !plan.start().equals(receipt.id()),
                    "Delegated tool needs authorization");
            for (var edge : edges.entrySet()) {
                require(
                        !edge.getValue().contains(handler.id()) || edge.getKey().equals(gate.id()),
                        "Delegated tool has another entry");
                require(
                        !edge.getValue().contains(receipt.id())
                                || edge.getKey().equals(handler.id()),
                        "Tool receipt has another entry");
            }
        }
        visit(plan.start(), edges, new HashSet<>(), reached);
        require(reached.size() == nodes.size(), "Unreachable workflow node");
    }

    private static List<String> edges(Plan plan, Plan.Node n) {
        n.toolBindings()
                .forEach(
                        (name, ref) ->
                                require(
                                        plan.allowedTools().contains(name)
                                                && REF.matcher(ref).matches()
                                                && ref.startsWith(name + ":"),
                                        "Invalid versioned tool binding"));
        if (n.toolRef() != null)
            require(
                    Set.of(TOOL, TOOL_GATE, TOOL_RECEIPT).contains(n.type())
                            && REF.matcher(n.toolRef()).matches(),
                    "Invalid toolRef");
        require(
                n.knowledgeBases().size() <= 8
                        && n.knowledgeBases().stream().allMatch(PlanCompiler::name),
                "Invalid knowledge bases");
        require(
                n.knowledgeBase() == null || n.knowledgeBases().isEmpty(),
                "Conflicting knowledge bases");
        require(
                n.iterative() == null || n.type() == TOOL_CALLING,
                "iterative requires TOOL_CALLING");
        if (n.promptRef() != null) {
            require(
                    Set.of(AGENT, TOOL_CALLING, MODEL_DECISION).contains(n.type())
                            && n.prompt() == null
                            && n.agentRef() == null
                            && n.javaRef() == null
                            && REF.matcher(n.promptRef()).matches(),
                    "Invalid or conflicting promptRef");
        }
        if (n.modelProfileRef() != null) {
            require(
                    Set.of(AGENT, TOOL_CALLING, MODEL_DECISION, SUPERVISOR).contains(n.type())
                            && n.agentRef() == null
                            && REF.matcher(n.modelProfileRef()).matches(),
                    "Invalid modelProfileRef");
        }
        if (n.javaRef() != null) {
            require(
                    plan.memory().permitsJavaHistory(),
                    "Java handlers need the default memory settings or a conversation window of at"
                        + " least 20 messages");
            require(n.knowledgeBases().isEmpty(), "Java handlers support a single knowledge base");
            require(
                    n.type() == AGENT
                            && n.agentRef() == null
                            && n.prompt() == null
                            && REF.matcher(n.javaRef()).matches(),
                    "Invalid or conflicting javaRef");
        }
        if (n.knowledgeBase() != null) require(name(n.knowledgeBase()), "Invalid knowledge base");
        if (n.prompt() != null) require(n.prompt().length() <= 4096, "Prompt exceeds limit");
        if (n.role() != null)
            require(n.role().matches("[A-Za-z][A-Za-z0-9_-]{0,63}"), "Invalid approval role");
        return switch (n.type()) {
            case MODEL_DECISION -> {
                require(
                        n.iteration() != null
                                && n.iteration() > 0
                                && !plan.allowedTools().isEmpty(),
                        "Invalid tool decision");
                yield links(n.onTrue(), n.onFalse());
            }
            case SELECTED_TOOL -> {
                require(!plan.allowedTools().isEmpty(), "Selected tool needs an allowlist");
                yield links(n.next());
            }
            case LIMIT -> List.of();
            case TOOL_GATE, TOOL_RECEIPT -> {
                require(
                        n.toolRef() != null && plan.allowedTools().contains(n.tool()),
                        "Delegated tool requires an explicit binding");
                if (n.type() == TOOL_RECEIPT)
                    require(name(n.field()), "Receipt needs its input checkpoint");
                yield links(n.next());
            }
            case END -> {
                require(n.next() == null, "END cannot have next");
                yield List.of();
            }
            case CONDITION -> {
                Condition.compile(n);
                yield links(n.onTrue(), n.onFalse());
            }
            case APPROVAL -> {
                range(
                        n.timeoutSeconds() == null ? 300 : n.timeoutSeconds(),
                        1,
                        86400,
                        "approval timeout");
                yield links(n.onApproved(), n.onRejected());
            }
            case SUPERVISOR, PARALLEL -> {
                range(
                        n.branches().size(),
                        1,
                        n.type() == PARALLEL ? plan.limits().maxParallelAgents() : 8,
                        "branch count");
                require(
                        new HashSet<>(n.branches()).size() == n.branches().size(),
                        "Duplicate branch");
                yield n.branches();
            }
            case WAIT -> {
                range(n.seconds() == null ? 0 : n.seconds(), 1, 3600, "wait seconds");
                yield links(n.next());
            }
            case TOOL_CALLING -> {
                require(
                        !plan.allowedTools().isEmpty(),
                        "Tool calling requires an explicit allowlist");
                yield links(n.next());
            }
            case TOOL -> {
                require(
                        name(n.tool()) && plan.allowedTools().contains(n.tool()),
                        "Tool requires an explicit binding");
                yield links(n.next());
            }
            case TRANSFORM -> {
                field(n.field());
                yield links(n.next());
            }
            case AGENT -> {
                if (n.agentRef() != null)
                    require(REF.matcher(n.agentRef()).matches(), "Unpinned agentRef");
                yield links(n.next());
            }
            case SUB_WORKFLOW -> {
                require(
                        n.workflowRef() != null && REF.matcher(n.workflowRef()).matches(),
                        "Unpinned workflowRef");
                yield links(n.next());
            }
            case JOIN -> links(n.next());
        };
    }

    private static void visit(
            String id, Map<String, List<String>> edges, Set<String> visiting, Set<String> done) {
        if (done.contains(id)) return;
        require(visiting.add(id), "Cycle in workflow");
        for (String next : edges.get(id)) visit(next, edges, visiting, done);
        visiting.remove(id);
        done.add(id);
    }

    private static List<String> links(String... targets) {
        require(
                Arrays.stream(targets).allMatch(PlanCompiler::name),
                "Invalid or missing transition");
        return List.of(targets);
    }

    private static boolean name(String value) {
        return value != null && NAME.matcher(value).matches();
    }

    private static void field(String value) {
        require(
                value != null && value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"),
                "Expected a top-level field name");
    }

    private static void range(int value, int min, int max, String label) {
        require(value >= min && value <= max, label + " must be between " + min + " and " + max);
    }

    private static void require(boolean predicate, String message) {
        if (!predicate) throw new IllegalArgumentException(message);
    }
}
