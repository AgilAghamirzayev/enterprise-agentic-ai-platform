package io.agentplatform.plan;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Only deployment configuration can introduce a provider, URL, credential reference or model. */
public record DeploymentModels(
        String provider, String primary, Set<String> allowed, Map<String, Endpoint> endpoints) {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

    public record Endpoint(
            String provider,
            String model,
            URI baseUrl,
            String credentialEnv,
            Set<String> capabilities,
            String residency,
            String confidentiality) {
        public Endpoint {
            capabilities = capabilities == null ? Set.of("text") : Set.copyOf(capabilities);
            if (!capabilities.contains("text")
                    || !Set.of("text", "streaming", "structuredOutput", "toolCalling")
                            .containsAll(capabilities)
                    || residency == null
                    || !residency.matches("[a-z0-9][a-z0-9.-]{0,63}")
                    || confidentiality == null
                    || !confidentiality.matches("[a-z0-9][a-z0-9.-]{0,63}")) {
                throw new IllegalArgumentException(
                        "Set capabilities, data residency and confidentiality for each route");
            }
            if (provider == null
                    || !Set.of("openai", "anthropic", "gemini", "ollama").contains(provider)
                    || model == null
                    || !ID.matcher(model).matches()) {
                throw new IllegalArgumentException("Invalid provider or upstream model");
            }
            if (baseUrl == null) {
                baseUrl =
                        URI.create(
                                switch (provider) {
                                    case "openai" -> "https://api.openai.com";
                                    case "anthropic" -> "https://api.anthropic.com";
                                    case "gemini" -> "https://generativelanguage.googleapis.com";
                                    default -> "http://localhost:11434";
                                });
            }
            boolean local =
                    "localhost".equals(baseUrl.getHost())
                            || "127.0.0.1".equals(baseUrl.getHost())
                            || "[::1]".equals(baseUrl.getHost());
            boolean cleartext =
                    "http".equals(baseUrl.getScheme())
                            && (local || ("ollama".equals(provider) && credentialEnv == null));
            if (baseUrl.getHost() == null
                    || baseUrl.getUserInfo() != null
                    || baseUrl.getQuery() != null
                    || baseUrl.getFragment() != null
                    || (!"https".equals(baseUrl.getScheme()) && !cleartext)
                    || !(baseUrl.getPath().isEmpty() || "/".equals(baseUrl.getPath()))) {
                throw new IllegalArgumentException(
                        "Configure the provider origin without credentials, path or query");
            }
            if (credentialEnv == null
                    ? !"ollama".equals(provider)
                    : !credentialEnv.matches("PLATFORM_MODEL_KEY_[A-Z0-9_]{1,64}")) {
                throw new IllegalArgumentException(
                        "Invalid provider credential environment reference");
            }
        }

        public String completionsPath() {
            return "gemini".equals(provider)
                    ? "/v1beta/openai/chat/completions"
                    : "/v1/chat/completions";
        }
    }

    private record Catalog(String primary, Map<String, Endpoint> models) {}

    public DeploymentModels {
        allowed = Set.copyOf(allowed);
        endpoints = Map.copyOf(endpoints);
        if (provider == null
                || !Set.of("openai", "demo", "catalog").contains(provider)
                || primary == null
                || !allowed.contains(primary)
                || allowed.size() > 32
                || allowed.stream().anyMatch(id -> !ID.matcher(id).matches())
                || ("catalog".equals(provider)
                        ? !allowed.equals(endpoints.keySet())
                        : !endpoints.isEmpty())) {
            throw new IllegalArgumentException("Invalid deployment model inventory");
        }
    }

    public static DeploymentModels from(
            String provider, String configuredPrimary, String additional) {
        if ("catalog".equals(provider)) {
            var path = System.getenv("PLATFORM_MODEL_CATALOG");
            if (path == null || path.isBlank())
                throw new IllegalArgumentException("Configure PLATFORM_MODEL_CATALOG");
            return load(Path.of(path));
        }
        String primary = "demo".equals(provider) ? "echo" : configuredPrimary;
        var allowed = new LinkedHashSet<String>();
        allowed.add(primary);
        if (!additional.isBlank()) {
            for (String id : additional.split(",", -1)) allowed.add(id.strip());
        }
        return new DeploymentModels(provider, primary, allowed, Map.of());
    }

    public static DeploymentModels load(Path path) {
        try (var input = Files.newInputStream(path)) {
            var bytes = input.readNBytes(65_537);
            if (bytes.length > 65_536)
                throw new IllegalArgumentException("Model catalog exceeds 64 KiB");
            var catalog =
                    new ObjectMapper()
                            .enable(
                                    com.fasterxml.jackson.core.JsonParser.Feature
                                            .STRICT_DUPLICATE_DETECTION)
                            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readValue(bytes, Catalog.class);
            if (catalog.models() == null)
                throw new IllegalArgumentException("Missing deployment model catalog");
            return new DeploymentModels(
                    "catalog", catalog.primary(), catalog.models().keySet(), catalog.models());
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Cannot read deployment model catalog", invalid);
        }
    }

    public String providerFor(String id) {
        return endpoints.containsKey(id) ? endpoints.get(id).provider() : provider;
    }

    public String upstreamModel(String id) {
        return endpoints.containsKey(id) ? endpoints.get(id).model() : id;
    }

    public String policyDigest(String id) {
        var endpoint = endpoints.get(id);
        if (endpoint == null) return provider + "/" + id;
        var policy =
                String.join(
                        "|",
                        endpoint.provider(),
                        endpoint.model(),
                        endpoint.baseUrl().normalize().toString(),
                        endpoint.residency(),
                        endpoint.confidentiality(),
                        String.join(",", new java.util.TreeSet<>(endpoint.capabilities())));
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            policy.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public void validate(String model, String fallbackModel) {
        validate(model, fallbackModel, Set.of());
    }

    public void validate(String model, String fallbackModel, Set<String> required) {
        if (model == null
                || !allowed.contains(model)
                || (fallbackModel != null
                        && (!allowed.contains(fallbackModel) || model.equals(fallbackModel)))) {
            throw new IllegalArgumentException(
                    "Choose distinct models from the deployment inventory");
        }
        if (!endpoints.isEmpty()) {
            var selected = endpoints.get(model);
            if (!selected.capabilities().containsAll(required)) {
                throw new IllegalArgumentException("Primary model lacks required capabilities");
            }
            if (fallbackModel != null) {
                var backup = endpoints.get(fallbackModel);
                if (!backup.residency().equals(selected.residency())
                        || !backup.confidentiality().equals(selected.confidentiality())
                        || !backup.capabilities().containsAll(selected.capabilities())) {
                    throw new IllegalArgumentException("Fallback does not satisfy provider policy");
                }
            }
        } else if (!Set.of("text", "structuredOutput").containsAll(required)) {
            throw new IllegalArgumentException(
                    "Declare provider capabilities through a deployment catalog");
        }
    }
}
