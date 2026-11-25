package io.agentplatform.examples.hello;

import io.agentplatform.application.AgentRegistry;
import io.agentplatform.application.ExecutionRejected;
import io.agentplatform.application.LocalExecutionEngine;
import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.domain.ExecutionLimits;
import io.agentplatform.sdk.*;
import io.agentplatform.testkit.FakeModelGateway;
import io.agentplatform.testkit.ResourceFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ResourceFixturesTest {
    @Test
    void scopedResourcesExpireAndAnInvocationWithoutFixturesDeniesAccess() {
        var reference = new AgentDescriptor("lookup", "1.0.0");
        var retained = new AtomicReference<KnowledgeGateway>();
        var definition = new AgentDefinition<>(reference, String.class, String.class, (query, ctx) -> {
            retained.set(ctx.knowledge());
            var chunk = ctx.knowledge().search(query, 1).getFirst();
            var message = ctx.memory().recent(1).getFirst();
            return chunk.title() + message.content() + ctx.tools().read("lookup", "{}");
        });
        var runtime = new LocalExecutionEngine(new AgentRegistry(List.of(definition)), new FakeModelGateway(), 1);
        var limits = ExecutionLimits.defaults();
        var fixtures = ResourceFixtures.of(
                Map.of("query", List.of(new KnowledgeGateway.Chunk("doc", 0, "A", "hash", "text"))),
                List.of(new MemoryGateway.Message("user", "B")), Map.of(new ResourceFixtures.Read("lookup", "{}"), "\"C\""));
        assertEquals("AB\"C\"", runtime.start("tenant", reference, "query", String.class, limits, fixtures)
                .result().toCompletableFuture().join());
        assertThrows(ExecutionRejected.class, () -> retained.get().search("query", 1));
        var failed = runtime.start("other", reference, "query", String.class, limits).result().toCompletableFuture();
        assertInstanceOf(SecurityException.class, assertThrows(CompletionException.class, failed::join).getCause());
    }
}
