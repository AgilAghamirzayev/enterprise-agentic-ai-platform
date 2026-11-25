package io.agentplatform.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.agentplatform.plan.DeploymentModels;
import io.agentplatform.spi.ModelRequest;
import io.agentplatform.springai.SpringAiModelGateway;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

class OpenAiRoutingTest {
    @Test
    void sendsConfiguredModelAndCapThenSwitchesOnTransientProviderFailure() throws Exception {
        var json = new ObjectMapper();
        var sent = new CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext(
                "/v1/chat/completions",
                exchange -> {
                    try (exchange) {
                        var body = json.readTree(exchange.getRequestBody());
                        sent.add(body);
                        boolean down = "primary".equals(body.path("model").asText());
                        String response =
                                down
                                        ? "{\"error\":{\"message\":\"temporarily unavailable\"}}"
                                        : """
                                        {"id":"chat-1","object":"chat.completion","created":123,"model":"backup",
                                        "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                                        "usage":{"prompt_tokens":9,"completion_tokens":1,"total_tokens":10}}
                                        """;
                        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(down ? 503 : 200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        endpoint.start();
        try {
            var api =
                    OpenAiApi.builder()
                            .baseUrl("http://127.0.0.1:" + endpoint.getAddress().getPort())
                            .apiKey("offline-test")
                            .build();
            var chat =
                    OpenAiChatModel.builder()
                            .openAiApi(api)
                            .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                            .defaultOptions(OpenAiChatOptions.builder().model("primary").build())
                            .build();
            var inventory = DeploymentModels.from("openai", "primary", "backup");
            var routes =
                    new ModelRoutes(inventory, new SpringAiModelGateway(chat), chat, List.of());
            var reserved = new ArrayList<String>();
            var result =
                    routes.complete(
                            "primary",
                            "backup",
                            new ModelRequest("Help.", "User data.", 16),
                            reserved::add);
            assertEquals(List.of("primary", "backup"), reserved);
            assertEquals(
                    List.of("primary", "backup"),
                    sent.stream().map(r -> r.path("model").asText()).toList());
            assertTrue(sent.stream().allMatch(r -> r.path("max_tokens").asInt() == 16));
            assertEquals("system", sent.getFirst().at("/messages/0/role").asText());
            assertEquals("user", sent.getFirst().at("/messages/1/role").asText());
            assertEquals("ok", result.text());
            assertEquals(10, result.totalTokens());
        } finally {
            endpoint.stop(0);
        }
    }
}
