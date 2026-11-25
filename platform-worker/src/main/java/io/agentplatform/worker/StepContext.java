package io.agentplatform.worker;

import com.fasterxml.jackson.databind.JsonNode;

import io.agentplatform.plan.Plan;

import java.util.List;
import java.util.stream.StreamSupport;

record StepContext(
        AgentRun.RunRef ref,
        String key,
        JsonNode run,
        Plan root,
        Plan plan,
        Plan.Node node,
        JsonNode input) {
    String tenant() {
        return ref.tenant();
    }

    String runId() {
        return ref.runId();
    }

    List<String> roles() {
        return StreamSupport.stream(run.path("roles").spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    String role() {
        return node.role() == null ? "approver" : node.role();
    }

    long ttl() {
        return node.timeoutSeconds() == null ? 300 : node.timeoutSeconds();
    }

    static String text(JsonNode node) {
        return node.isTextual() ? node.textValue() : node.toString();
    }
}
