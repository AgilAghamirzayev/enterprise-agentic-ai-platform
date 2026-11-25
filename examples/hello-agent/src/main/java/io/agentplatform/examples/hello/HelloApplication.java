package io.agentplatform.examples.hello;

import io.agentplatform.application.LocalExecutionEngine;
import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.domain.ExecutionLimits;
import io.agentplatform.spi.ModelGateway;
import io.agentplatform.testkit.FakeModelGateway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class HelloApplication {
    public static void main(String[] args) {
        SpringApplication.run(HelloApplication.class, args).close();
    }

    @Bean
    ModelGateway greetingModel() {
        return new FakeModelGateway(request -> "Hello, " + request.input() + "!");
    }

    @Bean
    CommandLineRunner greet(LocalExecutionEngine runtime) {
        return args -> {
            var name = args.length == 0 ? "world" : args[0];
            var agent = new AgentDescriptor("hello-agent", "1.0.0");
            var run = runtime.start("demo", agent, name, String.class, ExecutionLimits.defaults());
            System.out.println(run.result().toCompletableFuture().join());
        };
    }
}
