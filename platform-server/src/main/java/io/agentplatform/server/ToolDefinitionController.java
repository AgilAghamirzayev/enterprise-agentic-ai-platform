package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.connectors.ConfiguredTools;
import io.agentplatform.plan.ToolContract;
import io.agentplatform.store.PlatformStore;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/tools/{name}/versions")
class ToolDefinitionController {
    private final PlatformStore store;
    private final List<ConfiguredTools.Binding> bindings;

    ToolDefinitionController(PlatformStore store, ObjectMapper mapper) {
        this.store = store;
        this.bindings = ConfiguredTools.bindings(mapper);
    }

    @GetMapping
    List<JsonNode> versions(Authentication auth, @PathVariable String name) {
        var who = Identity.from(auth);
        who.require("developer", "auditor", "operator");
        return store.definitions(who.tenant(), "tool", name);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode publish(
            Authentication auth,
            @PathVariable String name,
            @Valid @RequestBody DefinitionController.Draft draft) {
        var who = Identity.from(auth);
        who.require("admin");
        if (!name.equals(draft.name()))
            throw new IllegalArgumentException("Version name does not match its resource");
        if (Set.of("uppercase", "word-count", "create-ticket", "document-search").contains(name)
                && !"1.0.0".equals(draft.version()))
            throw new IllegalArgumentException("Built-in tool version is 1.0.0");
        var installed =
                bindings.stream()
                        .filter(
                                binding ->
                                        binding.tenant().equals(who.tenant())
                                                && binding.name().equals(name))
                        .toList();
        if (!installed.isEmpty()
                && installed.stream()
                                .filter(binding -> binding.version().equals(draft.version()))
                                .count()
                        != 1)
            throw new IllegalArgumentException(
                    "Expected one installed binding for the published tool version");
        ToolContract.from(draft.spec());
        if (draft.spec().has("target")) {
            if (Set.of("uppercase", "word-count", "create-ticket", "document-search").contains(name)
                    || bindings.stream()
                            .anyMatch(
                                    b -> b.name().equals(name) && b.tenant().equals(who.tenant())))
                throw new IllegalArgumentException(
                        "Delegation name conflicts with an installed tool");
            var target = draft.spec().required("target");
            var ref = target.required("ref").asText().split(":", 2);
            store.definition(who.tenant(), target.required("kind").asText(), ref[0], ref[1]);
        }
        return store.publish(
                who.tenant(), who.actor(), "tool", name, draft.version(), draft.spec());
    }
}
