package io.agentplatform.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;

import io.agentplatform.store.PlatformStore;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

class RegistryPublicationTest {
    @Test
    void inheritedMemoryRestrictionsAreCheckedThroughNestedPinnedVersions() throws Exception {
        var parent =
                json.readTree(
                        """
                        {"spec":{"memory":{"type":"NONE","maxMessages":0},"nodes":[{"agentRef":"middle:1.0.0"}]}}
                        """);
        when(store.draft("local", "workflow", "review", "1.0.0")).thenReturn(parent);
        when(store.definition("local", "agent", "middle", "1.0.0"))
                .thenReturn(
                        json.readTree(
                                """
                                {"spec":{"nodes":[{"workflowRef":"leaf:1.0.0"}]}}
                                """));
        when(store.definition("local", "workflow", "leaf", "1.0.0"))
                .thenReturn(
                        json.readTree(
                                """
                                {"spec":{"nodes":[{"javaRef":"handler:1.0.0"}]}}
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () -> workflows.publish(developer, "review", "1.0.0", null));
    }

    final ObjectMapper json = new ObjectMapper();
    final PlatformStore store = mock(PlatformStore.class);
    final DefinitionController resources = new AgentDefinitions(store, json);
    final DefinitionController workflows = new WorkflowDefinitions(store, json);
    final UsernamePasswordAuthenticationToken developer = identity("developer");

    private static UsernamePasswordAuthenticationToken identity(String role) {
        return new UsernamePasswordAuthenticationToken(
                "reviewer", "", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    void draftResolvesOnlyTenantPublishedVersionsAndRejectsConflictingDelegatePermissions()
            throws Exception {
        when(store.definitions("local", "model-profile", "chat"))
                .thenReturn(List.of(json.createObjectNode().put("version", "2.0.0")));
        when(store.upsertDraft(
                        anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(
                        call ->
                                json.createObjectNode()
                                        .set("spec", call.getArgument(5, JsonNode.class)));
        var result =
                resources.draft(
                        developer,
                        new DefinitionController.Draft(
                                "summary",
                                "1.0.0",
                                json.getNodeFactory()
                                        .textNode("type: SINGLE_SHOT\nmodel: {profile: chat}")));
        assertEquals(
                "chat:2.0.0",
                result.required("spec").required("nodes").get(0).path("modelProfileRef").asText());
        var draft =
                json.readTree(
                        """
                        {"spec":{"allowedTools":[],"nodes":[{"id":"specialist","agentRef":"private:1.0.0"}]}}
                        """);
        when(store.draft("local", "workflow", "review", "1.0.0")).thenReturn(draft);
        when(store.definition("local", "agent", "private", "1.0.0"))
                .thenReturn(
                        json.readTree(
                                """
                                {"spec":{"allowedTools":["finance-review"]}}
                                """));
        assertThrows(
                IllegalArgumentException.class,
                () -> workflows.publish(developer, "review", "1.0.0", null));
        verify(store, never())
                .publish(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void publishingAnAgentToolRequiresAdminAndAnExistingPinnedTarget() throws Exception {
        var tools = new ToolDefinitionController(store, json);
        var spec =
                json.readTree(
                        """
                        {"inputSchema":{"type":"object"},"outputSchema":{"type":"string"},"target":{"kind":"workflow","ref":"review:3.0.0"}}
                        """);
        var body = new DefinitionController.Draft("check", "1.0.0", spec);
        assertThrows(ResponseStatusException.class, () -> tools.publish(developer, "check", body));
        tools.publish(identity("admin"), "check", body);
        verify(store).definition("local", "workflow", "review", "3.0.0");
        verify(store).publish("local", "reviewer", "tool", "check", "1.0.0", spec);
    }
}
