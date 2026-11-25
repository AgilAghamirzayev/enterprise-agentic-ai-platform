package io.agentplatform.testkit;

import io.agentplatform.sdk.InvocationResources;
import io.agentplatform.sdk.KnowledgeGateway;
import io.agentplatform.sdk.MemoryGateway;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Scripted, invocation-local resource responses. Unknown tool calls fail closed. */
public final class ResourceFixtures {
    private ResourceFixtures() {}

    public record Read(String tool, String argumentsJson) {}

    public static InvocationResources of(Map<String, List<KnowledgeGateway.Chunk>> searches,
                                         List<MemoryGateway.Message> history, Map<Read, String> toolResults) {
        var chunks = searches.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        var messages = List.copyOf(history);
        var tools = Map.copyOf(toolResults);
        return new InvocationResources(
                (query, limit) -> chunks.getOrDefault(query, List.of()).stream().limit(limit).toList(),
                limit -> messages.subList(Math.max(0, messages.size() - limit), messages.size()),
                (tool, args) -> {
                    var result = tools.get(new Read(tool, args));
                    if (result == null) throw new SecurityException("Tool call has no configured fixture");
                    return result;
                });
    }
}
