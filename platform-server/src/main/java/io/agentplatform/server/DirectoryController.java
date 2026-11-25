package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;

import io.agentplatform.store.PlatformStore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
class DirectoryController {
    private final PlatformStore store;

    DirectoryController(PlatformStore store) {
        this.store = store;
    }

    record Label(@NotBlank @Size(max = 160) String displayName) {}

    record CreateWorkspace(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,62}") String name,
            @NotBlank @Size(max = 160) String displayName) {}

    record Membership(
            @NotBlank @Size(max = 160) String subject,
            @NotNull @Size(max = 32)
                    List<@NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{0,63}") String> roles) {}

    @GetMapping("/tenant")
    JsonNode tenant(Authentication auth) {
        return store.workspaces().tenant(Identity.from(auth).organization());
    }

    @PostMapping("/tenants/{tenant}")
    JsonNode configure(
            Authentication auth, @PathVariable String tenant, @Valid @RequestBody Label value) {
        var who = Identity.from(auth);
        if (!tenant.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid tenant");
        if (!who.workspace().equals("default")
                || !(who.roles().contains("platform_admin")
                        || who.has("admin") && who.organization().equals(tenant)))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Tenant administration required");
        return store.workspaces().nameTenant(tenant, who.actor(), value.displayName());
    }

    @GetMapping("/workspaces")
    List<JsonNode> list(Authentication auth) {
        var who = Identity.from(auth);
        var rows = store.workspaces().list(who.organization(), who.actor(), who.has("admin"));
        if (!who.workspace().equals("default"))
            return rows.stream()
                    .filter(r -> r.path("name").asText().equals(who.workspace()))
                    .toList();
        return rows;
    }

    @PostMapping("/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode create(Authentication auth, @Valid @RequestBody CreateWorkspace value) {
        var who = admin(auth);
        if (value.name().equals("default"))
            throw new IllegalArgumentException("Reserved workspace name");
        return store.workspaces()
                .create(who.organization(), who.actor(), value.name(), value.displayName());
    }

    @GetMapping("/workspaces/{name}/members")
    List<JsonNode> members(Authentication auth, @PathVariable String name) {
        var who = admin(auth);
        return store.workspaces().members(who.organization(), name);
    }

    @PostMapping("/workspaces/{name}/members")
    JsonNode member(
            Authentication auth, @PathVariable String name, @Valid @RequestBody Membership input) {
        var who = admin(auth);
        if (input.roles().stream()
                .anyMatch(r -> List.of("admin", "platform_admin", "tenant_admin").contains(r)))
            throw new IllegalArgumentException(
                    "Administrative roles are assigned by the identity provider");
        return store.workspaces()
                .assign(
                        who.organization(),
                        who.actor(),
                        name,
                        input.subject(),
                        input.roles().stream().distinct().sorted().toList());
    }

    private Identity admin(Authentication auth) {
        var who = Identity.from(auth);
        who.require("admin");
        if (!who.workspace().equals("default"))
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Manage the directory from the default workspace");
        return who;
    }
}
