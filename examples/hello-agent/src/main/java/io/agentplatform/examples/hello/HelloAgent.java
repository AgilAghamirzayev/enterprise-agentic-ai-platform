package io.agentplatform.examples.hello;

import io.agentplatform.boot.AiAgent;
import io.agentplatform.sdk.Agent;
import io.agentplatform.sdk.AgentExecutionContext;
import io.agentplatform.spi.ModelRequest;

@AiAgent(name = "hello-agent", version = "1.0.0")
public final class HelloAgent implements Agent<String, String> {
    @Override
    public String execute(String name, AgentExecutionContext context) {
        if (name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("Supply a name between 1 and 64 characters");
        }
        return context.models().complete(new ModelRequest("Greet the user by name.", name, 512)).text();
    }
}
