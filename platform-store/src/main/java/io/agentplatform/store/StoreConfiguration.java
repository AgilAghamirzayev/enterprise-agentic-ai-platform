package io.agentplatform.store;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Objects;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class StoreConfiguration {
    @Bean
    public PlatformStore platformStore(
            DataSource source,
            ObjectMapper mapper,
            ObjectProvider<EmbeddingModel> models,
            ObjectProvider<io.agentplatform.spi.KnowledgeProvider> knowledge,
            java.util.List<io.agentplatform.spi.PolicyEngine> engines,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> metrics,
            @Value("${platform.embedding.mode:lexical}") String mode,
            @Value("${platform.embedding.revision:}") String revision,
            @Value("${platform.cost.input-usd-per-million:#{null}}") BigDecimal inputPrice,
            @Value("${platform.cost.output-usd-per-million:#{null}}") BigDecimal outputPrice) {
        Vectorizer vectorizer =
                switch (mode) {
                    case "lexical" -> new TokenHashVectorizer();
                    case "learned" -> new SpringEmbeddingVectorizer(models.getObject(), revision);
                    default ->
                            throw new IllegalArgumentException("Unknown platform.embedding.mode");
                };
        return new PlatformStore(
                source, mapper, vectorizer, new ModelPricing(inputPrice, outputPrice),engines,metrics.getIfAvailable(io.micrometer.core.instrument.composite.CompositeMeterRegistry::new),knowledge.getIfAvailable());
    }

    private record SpringEmbeddingVectorizer(EmbeddingModel embeddings, String model)
            implements Vectorizer {
        private SpringEmbeddingVectorizer {
            Objects.requireNonNull(embeddings);
            Database.requireText(model, "platform.embedding.revision", 200);
            if (model.equals("token-hash-256-v1"))
                throw new IllegalArgumentException("Reserved lexical embedding revision");
        }

        @Override
        public float[] embed(String text) {
            return embeddings.embed(text);
        }
    }
}
