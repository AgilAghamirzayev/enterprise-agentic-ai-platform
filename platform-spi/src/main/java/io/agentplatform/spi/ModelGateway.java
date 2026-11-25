package io.agentplatform.spi;

/** One bounded inference call. Implementations never execute model-proposed actions. */
@FunctionalInterface
public interface ModelGateway {
    ModelResult complete(ModelRequest request);
}
