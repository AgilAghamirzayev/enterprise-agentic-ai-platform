package io.agentplatform.connectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

final class RemoteTool implements ExternalTool {
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_BYTES = 64 * 1024;
    private final HttpClient http;
    private final ConfiguredTools.Binding binding;
    private final ObjectMapper json;
    private final String token;
    private final org.springframework.security.oauth2.jwt.JwtDecoder verifier;

    RemoteTool(ConfiguredTools.Binding binding, ObjectMapper json, String token) {
        this(binding, json, token, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    RemoteTool(ConfiguredTools.Binding binding, ObjectMapper json, String token, HttpClient http) {
        this.http = http;
        this.binding = binding;
        this.json = json;
        this.token = token;
        this.verifier = binding.access() == null ? null : binding.access().verifier();
    }

    public String name() {
        return binding.name();
    }

    public boolean readOnly() {
        return binding.readOnly();
    }

    public String version() {
        return binding.version();
    }

    public boolean allowsTenant(String tenant) {
        return binding.tenant().equals(tenant);
    }

    public JsonNode invoke(JsonNode arguments) {
        binding.validate(arguments);
        if (!readOnly()) throw new IllegalStateException("Write requires approved dispatch");
        return filter(binding.type().equals("mcp") ? callMcp(arguments) : send(arguments, null));
    }

    @Override
    public JsonNode invokeApproved(JsonNode arguments, String key) {
        binding.validate(arguments);
        if (readOnly() || key == null || !key.matches("[a-zA-Z0-9:/_.-]{1,256}"))
            throw new IllegalArgumentException("Approved write requires an idempotency key");
        return filter(send(arguments, key));
    }

    private JsonNode filter(JsonNode raw) {
        if (binding.outputFields().isEmpty()) return raw;
        if (!raw.isObject())
            throw new IllegalStateException("Connector output must be a structured object");
        var projected = json.createObjectNode();
        for (String name : binding.outputFields())
            if (raw.has(name)) projected.set(name, raw.get(name));
        return projected;
    }

    private JsonNode callMcp(JsonNode args) {
        URI endpoint = binding.endpoint();
        var transport =
                HttpClientStreamableHttpTransport.builder("https://" + endpoint.getRawAuthority())
                        .endpoint(endpoint.getRawPath())
                        .connectTimeout(TIMEOUT)
                        .customizeClient(
                                builder ->
                                        builder.sslContext(http.sslContext())
                                                .followRedirects(HttpClient.Redirect.NEVER))
                        .customizeRequest(this::authorize)
                        .build();
        try (var client =
                McpClient.sync(transport)
                        .requestTimeout(TIMEOUT)
                        .initializationTimeout(TIMEOUT)
                        .build()) {
            client.initialize();
            if (binding.mcpInputSchema() != null) {
                String cursor = null;
                boolean matched = false;
                for (int page = 0; page < 8; page++) {
                    var list = client.listTools(cursor);
                    var match =
                            list.tools().stream()
                                    .filter(t -> t.name().equals(binding.remoteTool()))
                                    .findFirst();
                    if (match.isPresent()) {
                        if (!binding.mcpInputSchema()
                                .equals(json.valueToTree(match.get().inputSchema())))
                            throw new IllegalStateException("Installed MCP tool schema changed");
                        matched = true;
                        break;
                    }
                    cursor = list.nextCursor();
                    if (cursor == null || cursor.isBlank()) break;
                }
                if (!matched) throw new IllegalStateException("Installed MCP tool is unavailable");
            }
            Map<String, Object> values = json.convertValue(args, new TypeReference<>() {});
            var result = client.callTool(new CallToolRequest(binding.remoteTool(), values));
            if (Boolean.TRUE.equals(result.isError()))
                throw new IllegalStateException("MCP tool rejected request");
            JsonNode output =
                    json.valueToTree(
                            result.structuredContent() == null
                                    ? result.content()
                                    : result.structuredContent());
            if (output.toString().length() > MAX_BYTES)
                throw new IllegalStateException("MCP result exceeds limit");
            return output;
        }
    }

    private JsonNode send(JsonNode args, String key) {
        StringJoiner query = new StringJoiner("&");
        args.fields()
                .forEachRemaining(
                        entry ->
                                query.add(
                                        encode(entry.getKey())
                                                + "="
                                                + encode(entry.getValue().asText())));
        var uri =
                URI.create(
                        binding.endpoint()
                                + (key != null || query.length() == 0 ? "" : "?" + query));
        var request =
                HttpRequest.newBuilder(uri)
                        .timeout(TIMEOUT)
                        .GET()
                        .header("Accept", "application/json");
        if (key != null)
            request.POST(
                            HttpRequest.BodyPublishers.ofString(
                                    args.toString(), StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", key);
        authorize(request);
        var transfer = http.sendAsync(request.build(), ignored -> new BoundedBody(MAX_BYTES));
        try {
            var response =
                    transfer.get(TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("REST tool rejected request");
            JsonNode result = json.readTree(response.body());
            if (result == null || result.isMissingNode())
                throw new IllegalStateException("Empty REST response");
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("REST call interrupted", ex);
        } catch (IOException
                | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("REST tool unavailable", ex);
        } finally {
            if (!transfer.isDone()) transfer.cancel(true);
        }
    }

    private void authorize(HttpRequest.Builder builder) {
        if (verifier != null) verifier.decode(token);
        if (token != null) builder.header("Authorization", "Bearer " + token);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
