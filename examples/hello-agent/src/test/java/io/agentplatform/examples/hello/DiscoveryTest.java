package io.agentplatform.examples.hello;

import io.agentplatform.application.AgentRegistry;
import io.agentplatform.application.LocalExecutionEngine;
import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.domain.ExecutionLimits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryTest {
    @Test
    void discoversAndExecutesVersionedAgentWithoutCredentials() {
        try (var context = new SpringApplicationBuilder(HelloApplication.class)
                .web(WebApplicationType.NONE).logStartupInfo(false).run()) {
            var id = new AgentDescriptor("hello-agent", "1.0.0");
            assertEquals(String.class, context.getBean(AgentRegistry.class).require(id).inputType());
            var result = context.getBean(LocalExecutionEngine.class)
                    .start("test", id, "Ada", String.class, ExecutionLimits.defaults()).result();
            assertEquals("Hello, Ada!", result.toCompletableFuture().join());
        }
    }
}
