package io.agentplatform.application;

import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.sdk.AgentDefinition;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class AgentRegistry {
    private final Map<AgentDescriptor, AgentDefinition<?, ?>> definitions;

    public AgentRegistry(Collection<AgentDefinition<?, ?>> definitions) {
        this.definitions = definitions.stream().collect(Collectors.toUnmodifiableMap(AgentDefinition::descriptor, Function.identity()));
    }

    public Collection<AgentDefinition<?, ?>> definitions() {
        return definitions.values();
    }

    public AgentDefinition<?, ?> require(AgentDescriptor descriptor) {
        var definition = definitions.get(descriptor);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown agent version");
        }
        return definition;
    }
}
