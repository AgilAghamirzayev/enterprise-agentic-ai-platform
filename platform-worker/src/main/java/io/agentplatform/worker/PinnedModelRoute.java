package io.agentplatform.worker;

import com.fasterxml.jackson.databind.JsonNode;

import io.agentplatform.spi.ModelRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

record PinnedModelRoute(String model, ModelRequest request, Duration timeout) {
    static List<PinnedModelRoute> resolve(
            ModelRoutes inventory,
            ModelRequest request,
            JsonNode profile,
            Function<String, JsonNode> loadProfile) {
        if (profile == null)
            return List.of(
                    new PinnedModelRoute(inventory.primary(), request, Duration.ofSeconds(60)));
        var options = new ArrayList<PinnedModelRoute>();
        var primary = from(inventory, request, profile);
        options.add(primary);
        if (profile.hasNonNull("fallbackModel")) {
            var id = profile.required("fallbackModel").asText();
            if (differs(profile, "fallbackProvider", inventory.provider(id))
                    || differs(profile, "fallbackPolicyDigest", inventory.policyDigest(id))
                    || differs(profile, "fallbackUpstreamModel", inventory.upstreamModel(id)))
                ToolExecutor.deny();
            options.add(new PinnedModelRoute(id, primary.request(), primary.timeout()));
        }
        for (var ref : profile.path("fallbackProfiles")) {
            var candidate = from(inventory, primary.request(), loadProfile.apply(ref.asText()));
            options.add(candidate);
        }
        return List.copyOf(options);
    }

    private static PinnedModelRoute from(
            ModelRoutes inventory, ModelRequest request, JsonNode profile) {
        String id = profile.required("model").asText();
        if (!inventory.provider(id).equals(profile.required("provider").asText())
                || differs(profile, "policyDigest", inventory.policyDigest(id))
                || differs(profile, "upstreamModel", inventory.upstreamModel(id)))
            ToolExecutor.deny();
        var parameters = profile.path("parameters");
        var cap = parameters.path("maxOutputTokens");
        var temperature = parameters.path("temperature");
        var effective =
                new ModelRequest(
                        request.system(),
                        request.input(),
                        cap.isIntegralNumber()
                                ? Math.min(request.maxOutputTokens(), cap.intValue())
                                : request.maxOutputTokens(),
                        temperature.isNumber()
                                ? Double.valueOf(temperature.doubleValue())
                                : request.temperature());
        return new PinnedModelRoute(
                id, effective, Duration.ofSeconds(profile.path("timeoutSeconds").asInt(60)));
    }

    private static boolean differs(JsonNode profile, String field, String expected) {
        return profile.has(field) && !expected.equals(profile.required(field).asText());
    }
}
