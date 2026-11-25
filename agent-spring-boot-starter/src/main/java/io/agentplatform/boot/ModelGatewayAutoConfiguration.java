package io.agentplatform.boot;

import io.agentplatform.spi.ModelGateway;
import io.agentplatform.springai.SpringAiModelGateway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration")
public class ModelGatewayAutoConfiguration {
    @Bean
    @ConditionalOnSingleCandidate(ChatModel.class)
    @ConditionalOnMissingBean(ModelGateway.class)
    ModelGateway springAiModelGateway(ChatModel model) {
        return new SpringAiModelGateway(model);
    }
}
