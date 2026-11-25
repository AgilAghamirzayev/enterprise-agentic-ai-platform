package io.agentplatform.worker;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.agentplatform.plan.DeploymentModels;
import io.agentplatform.spi.ModelRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

class ProviderCatalogTest {
    @TempDir Path directory;

    private String endpoint(
            String provider,
            String model,
            String env,
            String origin,
            String region,
            String classification,
            String caps) {
        return """
        {"provider":"%s","model":"%s","credentialEnv":"%s","baseUrl":"%s","residency":"%s","confidentiality":"%s","capabilities":%s}
        """
                .formatted(provider, model, env, origin, region, classification, caps);
    }

    private DeploymentModels catalog(String first, String backup) throws Exception {
        return DeploymentModels.load(
                Files.writeString(
                        directory.resolve("models.json"),
                        "{\"primary\":\"main\",\"models\":{\"main\":"
                                + first
                                + ",\"backup\":"
                                + backup
                                + "}}"));
    }

    @Test
    void sendsDifferentCredentialsAndModelNamesToAllowedProvidersAndMetersEachAttempt()
            throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var json = new ObjectMapper();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        var body = json.readTree(exchange.getRequestBody());
                        var path = exchange.getRequestURI().getPath();
                        var auth = exchange.getRequestHeaders().getFirst("Authorization");
                        requests.add(
                                path
                                        + " "
                                        + auth
                                        + " "
                                        + body.path("model").asText()
                                        + " "
                                        + body.path("temperature").asDouble());
                        boolean unavailable = path.equals("/v1/chat/completions");
                        String response =
                                unavailable
                                        ? "{\"error\":{\"message\":\"busy\"}}"
                                        : """
                                        {"id":"c","object":"chat.completion","created":123,"model":"gemini-test",
                                         "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                                         "usage":{"prompt_tokens":8,"completion_tokens":1,"total_tokens":9}}
                                        """;
                        var bytes = response.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(unavailable ? 503 : 200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        http.start();
        try {
            String origin = "http://127.0.0.1:" + http.getAddress().getPort();
            var inventory =
                    catalog(
                            endpoint(
                                    "anthropic",
                                    "claude-test",
                                    "PLATFORM_MODEL_KEY_FIRST",
                                    origin,
                                    "eu",
                                    "internal",
                                    "[\"text\"]"),
                            endpoint(
                                    "gemini",
                                    "gemini-test",
                                    "PLATFORM_MODEL_KEY_SECOND",
                                    origin,
                                    "eu",
                                    "internal",
                                    "[\"text\"]"));
            var meters = new SimpleMeterRegistry();
            var installed =
                    CatalogModels.create(
                            inventory,
                            key -> key.endsWith("FIRST") ? "fixture-one" : "fixture-two",
                            ObservationRegistry.NOOP);
            var routes =
                    new ModelRoutes(
                            inventory, null, null, installed, meters, ObservationRegistry.NOOP);
            var reservations = new java.util.ArrayList<String>();
            assertEquals(
                    "ok",
                    routes.complete(
                                    "main",
                                    "backup",
                                    new ModelRequest("Help", "Sample", 10, 0.2),
                                    reservations::add)
                            .text());
            assertEquals(List.of("main", "backup"), reservations);
            assertEquals(
                    List.of(
                            "/v1/chat/completions Bearer fixture-one claude-test 0.2",
                            "/v1beta/openai/chat/completions Bearer fixture-two gemini-test 0.2"),
                    requests);
            assertEquals(1, meters.counter("model.requests", "provider", "anthropic").count());
            assertEquals(1, meters.counter("model.requests", "provider", "gemini").count());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CatalogModels.create(inventory, key -> null, ObservationRegistry.NOOP));
        } finally {
            http.stop(0);
        }
    }

    @Test
    void blocksFallbackBeforeReservationForIncompatibleResidencyConfidentialityOrCapabilities()
            throws Exception {
        var first =
                endpoint(
                        "openai",
                        "first",
                        "PLATFORM_MODEL_KEY_OPENAI",
                        "https://example.org",
                        "eu",
                        "private",
                        "[\"text\",\"streaming\"]");
        for (String second :
                List.of(
                        endpoint(
                                "gemini",
                                "second",
                                "PLATFORM_MODEL_KEY_GEMINI",
                                "https://example.org",
                                "us",
                                "private",
                                "[\"text\",\"streaming\"]"),
                        endpoint(
                                "gemini",
                                "second",
                                "PLATFORM_MODEL_KEY_GEMINI",
                                "https://example.org",
                                "eu",
                                "public",
                                "[\"text\",\"streaming\"]"),
                        endpoint(
                                "gemini",
                                "second",
                                "PLATFORM_MODEL_KEY_GEMINI",
                                "https://example.org",
                                "eu",
                                "private",
                                "[\"text\"]"))) {
            var inventory = catalog(first, second);
            assertThrows(
                    IllegalArgumentException.class, () -> inventory.validate("main", "backup"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> inventory.validate("main", null, Set.of("toolCalling")));
            var routes =
                    new ModelRoutes(
                            inventory,
                            null,
                            null,
                            CatalogModels.create(
                                    inventory, key -> "fixture", ObservationRegistry.NOOP));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            routes.complete(
                                    "main",
                                    "backup",
                                    new ModelRequest("help", "sample", 8),
                                    id -> fail()));
        }
    }

    @Test
    void readsIncrementalOllamaCompatibleHttpStreamWithoutProviderCredentials() throws Exception {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var streamed = new java.util.concurrent.atomic.AtomicBoolean();
        http.createContext(
                "/v1/chat/completions",
                exchange -> {
                    try (exchange) {
                        streamed.set(
                                new ObjectMapper()
                                        .readTree(exchange.getRequestBody())
                                        .path("stream")
                                        .asBoolean());
                        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        for (var delta : List.of("first", " second")) {
                            String event =
                                    """
                                    data: {"id":"chunk","object":"chat.completion.chunk","created":123,"model":"local","choices":[{"index":0,"delta":{"role":"assistant","content":"%s"}}]}

                                    """
                                            .formatted(delta);
                            exchange.getResponseBody()
                                    .write(event.getBytes(StandardCharsets.UTF_8));
                            exchange.getResponseBody().flush();
                        }
                        exchange.getResponseBody()
                                .write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                    }
                });
        http.start();
        try {
            var source =
                    """
                    {"primary":"local","models":{"local":{"provider":"ollama","model":"local",
                     "baseUrl":"http://127.0.0.1:%s","capabilities":["text","streaming"],"residency":"onprem","confidentiality":"restricted"}}}
                    """
                            .formatted(http.getAddress().getPort());
            var inventory =
                    DeploymentModels.load(
                            Files.writeString(directory.resolve("local.json"), source));
            var provider =
                    (io.agentplatform.spi.ModelProvider)
                            CatalogModels.create(
                                            inventory,
                                            name -> fail("unexpected credential lookup"),
                                            ObservationRegistry.NOOP)
                                    .getFirst()
                                    .gateway();
            var deltas =
                    reactor.adapter.JdkFlowAdapter.flowPublisherToFlux(
                                    provider.stream(new ModelRequest("help", "hello", 30)))
                            .map(io.agentplatform.spi.ModelChunk::text)
                            .collectList()
                            .block(java.time.Duration.ofSeconds(4));
            assertTrue(streamed.get());
            assertEquals(List.of("first", " second"), deltas);
        } finally {
            http.stop(0);
        }
    }
}
