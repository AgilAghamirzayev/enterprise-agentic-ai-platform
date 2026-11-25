package io.agentplatform.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;

import java.util.*;
import java.util.function.Function;

/**
 * Expands registered agent and workflow tools before publication, keeping every target immutable.
 */
public final class RegistryTools {
    private RegistryTools() {}

    public static void bind(ObjectNode plan, Function<String, JsonNode> definition) {
        ArrayNode nodes = (ArrayNode) plan.required("nodes");
        var original = new ArrayList<ObjectNode>();
        nodes.forEach(node -> original.add((ObjectNode) node));
        var targets = new HashMap<String, JsonNode>();
        Function<String, JsonNode> load =
                ref -> targets.computeIfAbsent(ref, key -> definition.apply(key).path("target"));
        for (var node : original) {
            String type = node.required("type").asText();
            if (type.equals("TOOL") && node.hasNonNull("toolRef")) {
                String ref = node.required("toolRef").asText();
                var target = load.apply(ref);
                if (target.isObject()) expand(nodes, node, ref, target);
            } else if (type.equals("SELECTED_TOOL")) {
                var aliases = new TreeMap<String, String>();
                node.path("toolBindings")
                        .fields()
                        .forEachRemaining(
                                binding -> {
                                    String ref = binding.getValue().asText();
                                    if (load.apply(ref).isObject())
                                        aliases.put(binding.getKey(), ref);
                                });
                if (!aliases.isEmpty()) select(nodes, node, aliases, load);
            }
        }
    }

    private static void select(
            ArrayNode nodes,
            ObjectNode node,
            Map<String, String> aliases,
            Function<String, JsonNode> load) {
        String id = node.required("id").asText(), next = node.required("next").asText();
        var fallback = node.deepCopy().put("id", id + "-local");
        nodes.add(fallback);
        var choices = new ArrayList<>(aliases.entrySet());
        for (int i = 0; i < choices.size(); i++) {
            var entry = choices.get(i);
            String branch = id + "-t" + i;
            var test = i == 0 ? node.removeAll() : nodes.addObject();
            test.put("id", i == 0 ? id : id + "-s" + i)
                    .put("type", "CONDITION")
                    .put("field", "tool")
                    .put("equalsValue", entry.getKey())
                    .put("onTrue", branch + "-args")
                    .put("onFalse", i + 1 == choices.size() ? id + "-local" : id + "-s" + (i + 1));
            nodes.addObject()
                    .put("id", branch + "-args")
                    .put("type", "TRANSFORM")
                    .put("field", "arguments")
                    .put("next", branch);
            var gate = nodes.addObject().put("id", branch).put("type", "TOOL").put("next", next);
            expand(nodes, gate, entry.getValue(), load.apply(entry.getValue()));
        }
    }

    private static void expand(ArrayNode nodes, ObjectNode gate, String ref, JsonNode target) {
        String id = gate.required("id").asText(), next = gate.required("next").asText();
        gate.put("type", "TOOL_GATE").put("toolRef", ref).put("next", id + "-delegate");
        boolean agent = target.required("kind").asText().equals("agent");
        nodes.addObject()
                .put("id", id + "-delegate")
                .put("type", agent ? "AGENT" : "SUB_WORKFLOW")
                .put(agent ? "agentRef" : "workflowRef", target.required("ref").asText())
                .put("next", id + "-receipt");
        nodes.addObject()
                .put("id", id + "-receipt")
                .put("type", "TOOL_RECEIPT")
                .put("field", id)
                .put("toolRef", ref)
                .put("next", next);
    }
}
