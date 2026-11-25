package io.agentplatform.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Lowers public manifests to the canonical graph stored at publication. */
final class Blueprint {
    private static final Pattern DURATION = Pattern.compile("([0-9]{1,6})(s|m|h)");

    private Blueprint() {}

    static ObjectNode normalize(ObjectNode spec, BiFunction<String, String, String> pin) {
        if (spec.has("type") && !spec.has("nodes")) spec = agent(spec, pin);
        if (!spec.path("nodes").isArray())
            throw new IllegalArgumentException("Missing workflow nodes");
        expandDialogue(spec);
        var explicit = new LinkedHashSet<String>();
        for (JsonNode element : spec.required("nodes")) {
            if (!(element instanceof ObjectNode node))
                throw new IllegalArgumentException("Invalid workflow node");
            if (node.has("toolRef")) {
                String ref = node.required("toolRef").asText();
                String name = ref.split(":", 2)[0];
                if (node.has("tool") && !name.equals(node.get("tool").asText()))
                    throw new IllegalArgumentException("Conflicting tool bindings");
                node.put("tool", name);
                explicit.add(name);
            }
            duration(node, "timeout", "timeoutSeconds");
            duration(node, "duration", "seconds");
            if (node.hasNonNull("javaRef")
                    && node.path("knowledgeBases").isArray()
                    && node.path("knowledgeBases").size() == 1
                    && !node.hasNonNull("knowledgeBase")) {
                node.set("knowledgeBase", node.remove("knowledgeBases").get(0));
            }
        }
        if (!spec.has("allowedTools")) {
            var tools = spec.putArray("allowedTools");
            explicit.forEach(tools::add);
        }
        return spec;
    }

    /** The interpreter can replay every decision and tool call using ordinary graph checkpoints. */
    private static void expandDialogue(ObjectNode spec) {
        ArrayNode nodes = (ArrayNode) spec.get("nodes");
        var source = new ArrayList<ObjectNode>();
        for (var n : nodes)
            if (n.path("iterative").asBoolean() && n instanceof ObjectNode obj) source.add(obj);
        for (var node : source) {
            if (!"TOOL_CALLING".equals(node.path("type").asText()))
                throw new IllegalArgumentException("Expected TOOL_CALLING");
            int turns = spec.path("limits").path("maxIterations").asInt(12);
            if (turns < 1 || turns > 60)
                throw new IllegalArgumentException("An iterative graph supports 1 to 60 decisions");
            String id = node.required("id").asText(), end = node.required("next").asText();
            node.remove("iterative");
            node.remove("next");
            node.put("type", "MODEL_DECISION");
            var prototype = node.deepCopy();
            for (int turn = 1; turn <= turns; turn++) {
                var decision = turn == 1 ? node : prototype.deepCopy();
                if (turn > 1) nodes.add(decision);
                String call = id + "-call-" + turn;
                decision.put("id", turn == 1 ? id : id + "-decide-" + turn)
                        .put("iteration", turn)
                        .put("onTrue", id + "-answer")
                        .put("onFalse", call);
                var dispatch =
                        nodes.addObject()
                                .put("id", call)
                                .put("type", "SELECTED_TOOL")
                                .put(
                                        "next",
                                        turn == turns
                                                ? id + "-limit"
                                                : id + "-decide-" + (turn + 1));
                if (node.has("toolBindings"))
                    dispatch.set("toolBindings", node.get("toolBindings"));
                if (node.has("knowledgeBases"))
                    dispatch.set("knowledgeBases", node.get("knowledgeBases"));
            }
            nodes.addObject()
                    .put("id", id + "-answer")
                    .put("type", "TRANSFORM")
                    .put("field", "answer")
                    .put("next", end);
            nodes.addObject().put("id", id + "-limit").put("type", "LIMIT");
        }
    }

    private static ObjectNode agent(ObjectNode input, BiFunction<String, String, String> pin) {
        keys(
                input,
                Set.of(
                        "description",
                        "type",
                        "model",
                        "prompt",
                        "tools",
                        "knowledgeBases",
                        "execution",
                        "budget",
                        "policyRef",
                        "memory",
                        "invokeRoles"));
        ObjectNode plan = input.objectNode().put("start", "invoke");
        copy(input, plan, "description", "invokeRoles", "memory");
        var nodes = plan.putArray("nodes");
        var node = nodes.addObject().put("id", "invoke").put("next", "end");
        String type = input.required("type").asText();
        switch (type) {
            case "SINGLE_SHOT", "RAG", "RAG_AGENT" -> node.put("type", "AGENT");
            case "TOOL_CALLING" -> node.put("type", "TOOL_CALLING").put("iterative", true);
            default -> throw new IllegalArgumentException("Use a workflow graph for type " + type);
        }
        nodes.addObject().put("id", "end").put("type", "END");
        if (input.has("tools")) {
            if (!input.get("tools").isArray())
                throw new IllegalArgumentException("Expected a tool list");
            var allowed = plan.putArray("allowedTools");
            var bindings = node.putObject("toolBindings");
            for (var tool : input.get("tools")) {
                String ref = reference("tool", tool.asText(), pin);
                String name = ref.split(":", 2)[0];
                if (bindings.has(name))
                    throw new IllegalArgumentException("Duplicate tool binding");
                allowed.add(name);
                bindings.put(name, ref);
            }
        }
        if (input.has("knowledgeBases")) node.set("knowledgeBases", input.get("knowledgeBases"));
        if (type.startsWith("RAG") && node.path("knowledgeBases").isEmpty())
            throw new IllegalArgumentException("RAG requires a knowledge base");
        if (input.has("policyRef"))
            plan.putArray("policyRefs")
                    .add(reference("policy", input.required("policyRef").asText(), pin));
        if (input.has("model")) {
            var model = input.required("model");
            keys(model, Set.of("profile"));
            node.put(
                    "modelProfileRef",
                    reference("model-profile", model.required("profile").asText(), pin));
        }
        if (input.has("prompt")) {
            var prompt = input.required("prompt");
            keys(prompt, Set.of("templateRef", "version"));
            String ref = prompt.required("templateRef").asText();
            if (prompt.has("version")) {
                String version = prompt.required("version").asText();
                if (version.matches("[0-9]+")) version += ".0.0";
                ref += ":" + version;
            }
            node.put("promptRef", reference("prompt", ref, pin));
        }
        ObjectNode limits = plan.putObject("limits");
        if (input.has("execution")) {
            var execution = input.required("execution");
            keys(
                    execution,
                    Set.of(
                            "maxIterations",
                            "timeout",
                            "maxModelCalls",
                            "maxToolCalls",
                            "maxDelegationDepth",
                            "maxParallelAgents"));
            copy(
                    execution,
                    limits,
                    "maxIterations",
                    "timeout",
                    "maxModelCalls",
                    "maxToolCalls",
                    "maxParallelAgents");
            if (execution.has("maxDelegationDepth"))
                limits.set("maxDepth", execution.get("maxDelegationDepth"));
            duration(limits, "timeout", "timeoutSeconds");
        }
        if (input.has("budget")) {
            var budget = input.required("budget");
            keys(budget, Set.of("maxTokens", "maxCostUsd"));
            copy(budget, limits, "maxTokens", "maxCostUsd");
        }
        return plan;
    }

    private static void copy(JsonNode from, ObjectNode to, String... names) {
        for (String name : names) if (from.has(name)) to.set(name, from.get(name));
    }

    private static String reference(
            String kind, String ref, BiFunction<String, String, String> pin) {
        return ref.contains(":") ? ref : pin.apply(kind, ref);
    }

    private static void keys(JsonNode object, Set<String> allowed) {
        if (!object.isObject()) throw new IllegalArgumentException("Expected a manifest object");
        object.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!allowed.contains(name))
                                throw new IllegalArgumentException(
                                        "Unknown manifest field " + name);
                        });
    }

    private static void duration(ObjectNode node, String source, String target) {
        if (!node.has(source)) return;
        if (node.has(target)) throw new IllegalArgumentException("Conflicting duration fields");
        var matcher = DURATION.matcher(node.remove(source).asText());
        if (!matcher.matches())
            throw new IllegalArgumentException("Duration requires s, m or h units");
        int multiplier =
                switch (matcher.group(2)) {
                    case "h" -> 3600;
                    case "m" -> 60;
                    default -> 1;
                };
        long seconds = Long.parseLong(matcher.group(1)) * multiplier;
        if (seconds > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Duration exceeds supported range");
        node.put(target, seconds);
    }
}
