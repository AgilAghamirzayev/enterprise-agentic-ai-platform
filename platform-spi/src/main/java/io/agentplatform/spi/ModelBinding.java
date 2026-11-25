package io.agentplatform.spi;

import java.util.Objects;

/** Deployment-owned model implementation. Expose this as a worker bean to bind an inventory ID. */
public record ModelBinding(String modelId, ModelGateway gateway) {
    public ModelBinding {
        Objects.requireNonNull(modelId);
        Objects.requireNonNull(gateway);
    }
}
