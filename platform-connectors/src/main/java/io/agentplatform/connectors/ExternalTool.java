package io.agentplatform.connectors;

import com.fasterxml.jackson.databind.JsonNode;

/** Installed by the operator and called through the platform's tool authorization path. */
public interface ExternalTool {
    String name();

    default String version() {
        return "1.0.0";
    }

    boolean readOnly();

    boolean allowsTenant(String tenant);

    JsonNode invoke(JsonNode arguments);

    default JsonNode invokeApproved(JsonNode arguments, String idempotencyKey) {
        throw new IllegalStateException("Connector does not support approved writes");
    }
}
