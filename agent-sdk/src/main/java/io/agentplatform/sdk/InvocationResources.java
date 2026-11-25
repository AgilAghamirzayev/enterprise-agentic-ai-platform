package io.agentplatform.sdk;

import java.util.Objects;

/** Pass scoped adapters explicitly when running an agent in the local engine. */
public record InvocationResources(KnowledgeGateway knowledge, MemoryGateway memory, ToolGateway tools) {
    private static final InvocationResources NONE = new InvocationResources(
            (query, limit) -> { throw new SecurityException("No knowledge access for this invocation"); },
            limit -> { throw new SecurityException("No memory access for this invocation"); },
            (name, arguments) -> { throw new SecurityException("No tool access for this invocation"); });

    public InvocationResources {
        Objects.requireNonNull(knowledge);
        Objects.requireNonNull(memory);
        Objects.requireNonNull(tools);
    }

    public static InvocationResources denied() {
        return NONE;
    }

    /** Bound resource sizes and gate retained handles on every call, including after cancellation. */
    public InvocationResources guarded(Runnable checkpoint) {
        return new InvocationResources((query, limit) -> {
            checkpoint.run();
            if (query == null || query.isBlank() || query.length() > 2048 || limit < 1 || limit > 8) {
                throw new IllegalArgumentException("Invalid retrieval query or result limit");
            }
            return java.util.List.copyOf(knowledge.search(query, limit));
        }, limit -> {
            checkpoint.run();
            if (limit < 1 || limit > 20) throw new IllegalArgumentException("Request between 1 and 20 recent messages");
            return java.util.List.copyOf(memory.recent(limit));
        }, (name, arguments) -> {
            checkpoint.run();
            if (name == null || !name.matches("[a-z][a-z0-9-]{0,62}") || arguments == null
                    || arguments.isBlank() || arguments.length() > 8192) {
                throw new IllegalArgumentException("Invalid tool name or arguments");
            }
            return tools.read(name, arguments);
        });
    }
}
