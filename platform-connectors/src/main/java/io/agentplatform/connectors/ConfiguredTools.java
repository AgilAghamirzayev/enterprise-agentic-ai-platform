package io.agentplatform.connectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class ConfiguredTools {
    private ConfiguredTools() {}

    public static List<ExternalTool> load(ObjectMapper mapper) {
        return bindings(mapper).stream().map(binding -> binding.connect(mapper)).toList();
    }

    public static List<Binding> bindings(ObjectMapper mapper) {
        String file = System.getenv("PLATFORM_CONNECTORS_FILE");
        if (file == null || file.isBlank()) return List.of();
        try (var input = Files.newInputStream(Path.of(file))) {
            return mapper.readValue(input, new TypeReference<>() {});
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot read connector configuration", ex);
        }
    }

    public record Binding(
            String name,
            String tenant,
            String type,
            URI endpoint,
            String remoteTool,
            String tokenEnv,
            Set<String> arguments,
            String version,
            String method,
            boolean idempotencySupported,
            JsonNode mcpInputSchema,
            Set<String> outputFields,
            ConnectorAccess access) {
        public Binding(
                String name,
                String tenant,
                String type,
                URI endpoint,
                String remoteTool,
                String tokenEnv,
                Set<String> arguments) {
            this(
                    name,
                    tenant,
                    type,
                    endpoint,
                    remoteTool,
                    tokenEnv,
                    arguments,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null);
        }

        public boolean readOnly() {
            return !"POST".equals(method);
        }

        public Binding {
            if (name == null
                    || !name.matches("[a-z][a-z0-9-]{0,62}")
                    || tenant == null
                    || tenant.isBlank())
                throw new IllegalArgumentException("Each connector requires a name and tenant");
            if (endpoint == null
                    || !"https".equals(endpoint.getScheme())
                    || endpoint.getHost() == null
                    || endpoint.getRawUserInfo() != null
                    || endpoint.getRawQuery() != null
                    || endpoint.getFragment() != null)
                throw new IllegalArgumentException("Connector endpoint must be a fixed HTTPS URL");
            if (!Set.of("mcp", "rest").contains(type)
                    || ("mcp".equals(type) && (remoteTool == null || remoteTool.isBlank())))
                throw new IllegalArgumentException("Invalid connector type or tool");
            version = version == null ? "1.0.0" : version;
            method = method == null ? "GET" : method;
            if (!version.matches("[0-9]+[.][0-9]+[.][0-9]+")
                    || !(method.equals("GET")
                            || (type.equals("rest")
                                    && method.equals("POST")
                                    && idempotencySupported)))
                throw new IllegalArgumentException("Invalid connector version or write contract");
            if (type.equals("mcp") && tokenEnv != null && access == null)
                throw new IllegalArgumentException(
                        "Authenticated MCP requires an issuer and audience configuration");
            if (access != null && tokenEnv == null)
                throw new IllegalArgumentException("Missing connector credential reference");
            if (mcpInputSchema != null && (!type.equals("mcp") || !mcpInputSchema.isObject()))
                throw new IllegalArgumentException("Invalid expected MCP input schema");
            arguments = Set.copyOf(arguments);
            outputFields = outputFields == null ? Set.of() : Set.copyOf(outputFields);
        }

        ExternalTool connect(ObjectMapper mapper) {
            String token = tokenEnv == null ? null : System.getenv(tokenEnv);
            if (tokenEnv != null && (token == null || token.isBlank()))
                throw new IllegalArgumentException("Connector credential is unavailable");
            return new RemoteTool(this, mapper, token);
        }

        void validate(JsonNode args) {
            if (args == null || !args.isObject() || args.toString().length() > 8192)
                throw new IllegalArgumentException("Expected a small arguments object");
            args.fields()
                    .forEachRemaining(
                            entry -> {
                                if (!arguments.contains(entry.getKey())
                                        || !entry.getValue().isValueNode()
                                        || entry.getValue().isNull())
                                    throw new IllegalArgumentException(
                                            "Unexpected connector argument");
                            });
        }
    }
}
