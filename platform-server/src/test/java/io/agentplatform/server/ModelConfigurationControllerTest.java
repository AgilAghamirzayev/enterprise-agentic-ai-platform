package io.agentplatform.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.store.PlatformStore;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

class ModelConfigurationControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PlatformStore store = mock(PlatformStore.class);
    private final ModelConfigurationController controller =
            new ModelConfigurationController(store, json, "openai", "fast", "accurate");

    private JwtAuthenticationToken identity(String role) {
        var token =
                Jwt.withTokenValue("test")
                        .header("alg", "none")
                        .subject("alice")
                        .claim("tenant_id", "north")
                        .issuedAt(Instant.now())
                        .build();
        return new JwtAuthenticationToken(
                token, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void restrictsModelsToTenantAdminAndDeploymentInventory() {
        var profile =
                new ModelConfigurationController.Profile("support", "1.0.0", "fast", "accurate");
        assertThrows(
                ResponseStatusException.class,
                () -> controller.publishModel(identity("developer"), profile));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        controller.publishModel(
                                identity("admin"),
                                new ModelConfigurationController.Profile(
                                        "support", "1.0.0", "unregistered", null)));
        verifyNoInteractions(store);
    }

    @Test
    void publishesTenantOwnedPromptAndListsActualModelMetadata() {
        var prompt =
                new ModelConfigurationController.Prompt("triage", "1.0.0", "Classify the ticket.");
        var spec = json.createObjectNode().put("text", prompt.text());
        var row =
                json.createObjectNode()
                        .put("name", prompt.name())
                        .put("version", prompt.version())
                        .set("spec", spec);
        when(store.publish("north", "alice", "prompt", "triage", "1.0.0", spec)).thenReturn(row);
        assertEquals(
                "Classify the ticket.",
                controller.publishPrompt(identity("developer"), prompt).get("text").asText());
        var deployed = controller.models(identity("operator")).getFirst();
        assertEquals("fast", deployed.path("model").asText());
        assertEquals("openai", deployed.path("provider").asText());
        verify(store).definitions("north", "model-profile");
    }

    @Test
    void publishesOnlyDistinctTenantOwnedFallbackVersionsWithPinnedMetadata() throws Exception {
        var fallback =
                json.readTree(
                        """
                        {"spec":{"provider":"openai","model":"accurate","policyDigest":"openai/accurate"}}
                        """);
        when(store.definition("north", "model-profile", "review", "2.0.0")).thenReturn(fallback);
        when(store.publish(
                        eq("north"),
                        eq("alice"),
                        eq("model-profile"),
                        eq("support"),
                        eq("1.0.0"),
                        any()))
                .thenAnswer(
                        invocation ->
                                json.createObjectNode()
                                        .put("name", "support")
                                        .put("version", "1.0.0")
                                        .set(
                                                "spec",
                                                invocation.getArgument(
                                                        5,
                                                        com.fasterxml.jackson.databind.JsonNode
                                                                .class)));
        var profile =
                new ModelConfigurationController.Profile(
                        "support",
                        "1.0.0",
                        "fast",
                        null,
                        new ModelConfigurationController.Parameters(0.2, 128),
                        10,
                        java.util.Set.of("text"),
                        List.of("review:2.0.0"));
        var published = controller.publishModel(identity("admin"), profile);
        assertEquals("openai/fast", published.path("policyDigest").asText());
        assertEquals(128, published.at("/parameters/maxOutputTokens").asInt());
        assertEquals("review:2.0.0", published.at("/fallbackProfiles/0").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) fallback.get("spec"))
                .put("policyDigest", "rebound");
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.publishModel(identity("admin"), profile));
    }
}
