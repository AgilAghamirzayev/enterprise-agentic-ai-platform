package io.agentplatform.sdk;

/** Read-only calls to explicitly registered tools. Arguments and results are JSON values. */
@FunctionalInterface
public interface ToolGateway {
    String read(String name, String argumentsJson);
}
