package io.agentplatform.boot;

import io.agentplatform.application.AgentRegistry;
import io.agentplatform.application.LocalExecutionEngine;
import io.agentplatform.domain.AgentDescriptor;
import io.agentplatform.sdk.Agent;
import io.agentplatform.sdk.AgentDefinition;
import io.agentplatform.spi.ModelGateway;
import java.util.ArrayList;
import java.util.List;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;

@AutoConfiguration(after = ModelGatewayAutoConfiguration.class)
public class AgentAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AgentRegistry agentRegistry(List<Agent<?, ?>> agents) {
        var definitions = new ArrayList<AgentDefinition<?, ?>>();
        for (var agent : agents) {
            var target = AopUtils.getTargetClass(agent);
            var annotation = AnnotatedElementUtils.findMergedAnnotation(target, AiAgent.class);
            if (annotation != null) {
                definitions.add(definition(agent, ResolvableType.forClass(target).as(Agent.class), annotation));
            }
        }
        return new AgentRegistry(definitions);
    }

    @SuppressWarnings("unchecked")
    private static <I, O> AgentDefinition<I, O> definition(Agent<I, O> agent, ResolvableType type, AiAgent metadata) {
        if (type.hasUnresolvableGenerics() || type.getGeneric(0).hasGenerics() || type.getGeneric(1).hasGenerics()) {
            throw new IllegalArgumentException("Agent handler must declare concrete input and output classes");
        }
        var descriptor = new AgentDescriptor(metadata.name(), metadata.version());
        return new AgentDefinition<>(descriptor, (Class<I>) type.getGeneric(0).resolve(),
                (Class<O>) type.getGeneric(1).resolve(), agent);
    }

    @Bean
    @ConditionalOnBean(ModelGateway.class)
    @ConditionalOnMissingBean
    LocalExecutionEngine localExecutionEngine(AgentRegistry registry, ModelGateway gateway,
            @Value("${agentplatform.local.max-concurrent-runs:16}") int concurrency) {
        return new LocalExecutionEngine(registry, gateway, concurrency);
    }
}
