package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.plan.DeploymentModels;
import io.agentplatform.store.PlatformStore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
class ModelConfigurationController {
    private static final String NAME = "[a-z][a-z0-9-]{0,62}";
    private static final String VERSION = "[0-9]+\\.[0-9]+\\.[0-9]+";
    private final PlatformStore store;
    private final ObjectMapper json;
    private final DeploymentModels inventory;

    ModelConfigurationController(
            PlatformStore store,
            ObjectMapper json,
            @Value("${PLATFORM_MODEL:demo}") String provider,
            @Value("${OPENAI_MODEL:gpt-4o-mini}") String primary,
            @Value("${PLATFORM_ALLOWED_MODELS:}") String additional) {
        this.store = store;
        this.json = json;
        this.inventory = DeploymentModels.from(provider, primary, additional);
    }

    record Profile(
            @NotBlank @Pattern(regexp = NAME) String name,
            @NotBlank @Size(max = 64) @Pattern(regexp = VERSION) String version,
            @NotBlank String model,
            String fallbackModel,
            @Valid Parameters parameters,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(120)
                    Integer timeoutSeconds,
            @Size(max = 4) java.util.Set<@NotBlank String> capabilities,
            @Size(max = 4)
                    List<
                                    @Pattern(
                                            regexp =
                                                    "[a-z][a-z0-9-]{0,62}:[0-9]+[.][0-9]+[.][0-9]+")
                                    @Size(max = 128) String>
                            fallbackProfiles) {
        Profile(String name, String version, String model, String fallbackModel) {
            this(name, version, model, fallbackModel, null, null, null, null);
        }
    }

    record Parameters(
            @jakarta.validation.constraints.DecimalMin("0")
                    @jakarta.validation.constraints.DecimalMax("2")
                    Double temperature,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(131072)
                    Integer maxOutputTokens) {}

    record Prompt(
            @NotBlank @Pattern(regexp = NAME) String name,
            @NotBlank @Size(max = 64) @Pattern(regexp = VERSION) String version,
            @NotBlank @Size(max = 4096) String text) {}

    @GetMapping("/model-profiles")
    List<JsonNode> models(Authentication authentication) {
        var who = Identity.from(authentication);
        who.require("developer", "auditor", "operator", "user", "approver");
        var profiles = new ArrayList<JsonNode>();
        profiles.add(
                json.createObjectNode()
                        .put("name", "deployment-default")
                        .put("provider", inventory.providerFor(inventory.primary()))
                        .put("model", inventory.primary())
                        .putNull("version"));
        store.definitions(who.tenant(), "model-profile").stream()
                .map(this::resource)
                .forEach(profiles::add);
        return profiles;
    }

    @GetMapping("/model-inventory")
    List<JsonNode> inventory(Authentication authentication) {
        var who = Identity.from(authentication);
        who.require("developer", "auditor", "operator");
        return inventory.allowed().stream()
                .sorted()
                .<JsonNode>map(
                        id ->
                                json.createObjectNode()
                                        .put("id", id)
                                        .put("provider", inventory.providerFor(id))
                                        .put("model", inventory.upstreamModel(id)))
                .toList();
    }

    @PostMapping("/model-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode publishModel(Authentication authentication, @Valid @RequestBody Profile profile) {
        var who = Identity.from(authentication);
        who.require("admin");
        if (profile.name().equals("deployment-default"))
            throw new IllegalArgumentException("Reserved profile name");
        inventory.validate(
                profile.model(),
                profile.fallbackModel(),
                profile.capabilities() == null ? java.util.Set.of() : profile.capabilities());
        var spec =
                json.createObjectNode()
                        .put("provider", inventory.providerFor(profile.model()))
                        .put("upstreamModel", inventory.upstreamModel(profile.model()))
                        .put("model", profile.model())
                        .put("policyDigest", inventory.policyDigest(profile.model()));
        if (profile.parameters() != null)
            spec.set("parameters", json.valueToTree(profile.parameters()));
        if (profile.timeoutSeconds() != null) spec.put("timeoutSeconds", profile.timeoutSeconds());
        if (profile.capabilities() != null)
            spec.set("capabilities", json.valueToTree(profile.capabilities()));
        if (profile.fallbackModel() != null) {
            spec.put("fallbackModel", profile.fallbackModel())
                    .put("fallbackPolicyDigest", inventory.policyDigest(profile.fallbackModel()))
                    .put("fallbackProvider", inventory.providerFor(profile.fallbackModel()))
                    .put("fallbackUpstreamModel", inventory.upstreamModel(profile.fallbackModel()));
        }
        if (profile.fallbackProfiles() != null && !profile.fallbackProfiles().isEmpty()) {
            if (profile.fallbackModel() != null)
                throw new IllegalArgumentException("Choose fallbackModel or fallbackProfiles");
            var seen = new java.util.HashSet<String>();
            seen.add(profile.model());
            for (var ref : profile.fallbackProfiles()) {
                var parts = ref.split(":", 2);
                var fallback =
                        store.definition(who.tenant(), "model-profile", parts[0], parts[1])
                                .required("spec");
                var id = fallback.required("model").asText();
                if (!seen.add(id)
                        || fallback.hasNonNull("fallbackModel")
                        || fallback.path("fallbackProfiles").size() > 0)
                    throw new IllegalArgumentException(
                            "Use distinct single-model fallback profiles");
                inventory.validate(profile.model(), id);
                if (!inventory.providerFor(id).equals(fallback.required("provider").asText())
                        || (fallback.has("policyDigest")
                                && !inventory
                                        .policyDigest(id)
                                        .equals(fallback.required("policyDigest").asText())))
                    throw new IllegalArgumentException("Fallback profile binding has changed");
            }
            spec.set("fallbackProfiles", json.valueToTree(profile.fallbackProfiles()));
        }
        return resource(
                store.publish(
                        who.tenant(),
                        who.actor(),
                        "model-profile",
                        profile.name(),
                        profile.version(),
                        spec));
    }

    @GetMapping("/prompts")
    List<JsonNode> prompts(Authentication authentication) {
        var who = Identity.from(authentication);
        who.require("developer", "auditor", "operator");
        return store.definitions(who.tenant(), "prompt").stream().map(this::resource).toList();
    }

    @PostMapping("/prompts")
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode publishPrompt(Authentication authentication, @Valid @RequestBody Prompt prompt) {
        var who = Identity.from(authentication);
        who.require("developer");
        var spec = json.createObjectNode().put("text", prompt.text());
        return resource(
                store.publish(
                        who.tenant(),
                        who.actor(),
                        "prompt",
                        prompt.name(),
                        prompt.version(),
                        spec));
    }

    private JsonNode resource(JsonNode definition) {
        var resource = json.createObjectNode();
        resource.setAll(
                (com.fasterxml.jackson.databind.node.ObjectNode) definition.required("spec"));
        resource.set("name", definition.required("name"));
        resource.set("version", definition.required("version"));
        return resource;
    }
}
