package io.agentplatform.worker;

import io.agentplatform.plan.DeploymentModels;
import io.agentplatform.spi.ModelBinding;
import io.agentplatform.springai.SpringAiModelGateway;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

final class CatalogModels {
    private CatalogModels() {}

    static List<ModelBinding> create(
            DeploymentModels inventory,
            Function<String, String> credentials,
            ObservationRegistry observations) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(Duration.ofSeconds(30));
        return inventory.endpoints().entrySet().stream()
                .map(
                        entry -> {
                            var endpoint = entry.getValue();
                            // Unauthenticated Ollama accepts any opaque token on its compatibility
                            // endpoint.
                            String key =
                                    endpoint.credentialEnv() == null
                                            ? "ollama"
                                            : credentials.apply(endpoint.credentialEnv());
                            if (key == null || key.isBlank()) {
                                throw new IllegalArgumentException(
                                        "Missing model credential for " + entry.getKey());
                            }
                            var api =
                                    OpenAiApi.builder()
                                            .baseUrl(endpoint.baseUrl().toString())
                                            .completionsPath(endpoint.completionsPath())
                                            .apiKey(key)
                                            .restClientBuilder(
                                                    RestClient.builder().requestFactory(requests))
                                            .build();
                            var chat =
                                    OpenAiChatModel.builder()
                                            .openAiApi(api)
                                            .defaultOptions(
                                                    OpenAiChatOptions.builder()
                                                            .model(endpoint.model())
                                                            .build())
                                            .retryTemplate(
                                                    RetryTemplate.builder().maxAttempts(1).build())
                                            .observationRegistry(observations)
                                            .build();
                            return new ModelBinding(
                                    entry.getKey(),
                                    new SpringAiModelGateway(
                                            chat,
                                            endpoint.model(),
                                            endpoint.provider(),
                                            endpoint.capabilities()));
                        })
                .toList();
    }
}
