package io.agentplatform.server;

import io.agentplatform.store.StoreConfiguration;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@Import(StoreConfiguration.class)
@EnableScheduling
public class PlatformServer {
    public static void main(String[] args) {
        SpringApplication.run(PlatformServer.class, args);
    }

    @Bean(destroyMethod = "shutdown")
    WorkflowServiceStubs temporal(
            @Value("${platform.temporal.target}") String target,
            @Value("${platform.temporal.tls:false}") boolean tls) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(target)
                        .setEnableHttps(tls)
                        .build());
    }

    @Bean
    WorkflowClient workflowClient(
            WorkflowServiceStubs temporal,
            @Value("${platform.temporal.namespace}") String namespace) {
        return WorkflowClient.newInstance(
                temporal, WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
    }
}
