package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;

import io.agentplatform.store.PlatformStore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
class GovernanceController {
    private final PlatformStore store;

    private final List<io.agentplatform.connectors.ConfiguredTools.Binding> bindings;

    GovernanceController(PlatformStore store, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.store = store;
        this.bindings = io.agentplatform.connectors.ConfiguredTools.bindings(mapper);
    }

    record Policy(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,63}") String tool,
            @NotBlank @Pattern(regexp = "INHERIT|DENY|REQUIRE_APPROVAL") String effect,
            @NotBlank String requiredRole) {}

    record Quota(
            @NotBlank @Size(max = 100) String scope,
            @PositiveOrZero Long monthlyTokens,
            @DecimalMin("0") BigDecimal monthlyUsd) {}

    @GetMapping("/policies")
    List<JsonNode> policies(Authentication auth) {
        var who = Identity.from(auth);
        who.require("auditor");
        return store.toolPolicies(who.tenant());
    }

    @PostMapping("/policies")
    JsonNode policy(Authentication auth, @Valid @RequestBody Policy policy) {
        var who = Identity.from(auth);
        who.require("admin");
        return store.setToolPolicy(
                who.tenant(), who.actor(), policy.tool(), policy.effect(), policy.requiredRole());
    }

    @GetMapping("/quotas")
    List<JsonNode> quotas(Authentication auth) {
        var who = Identity.from(auth);
        who.require("auditor");
        return store.quotas(who.tenant());
    }

    @PostMapping("/quotas")
    JsonNode quota(Authentication auth, @Valid @RequestBody Quota quota) {
        var who = Identity.from(auth);
        who.require("admin");
        return store.setQuota(
                who.tenant(),
                who.actor(),
                quota.scope(),
                quota.monthlyTokens(),
                quota.monthlyUsd());
    }

    @GetMapping("/tools")
    List<Map<String, String>> tools(Authentication auth) {
        var who = Identity.from(auth);
        who.require("developer", "operator", "auditor");
        var inventory =
                new java.util.ArrayList<>(
                        List.of(
                                tool("uppercase", "read"),
                                tool("word-count", "read"),
                                tool("document-search", "read"),
                                tool("create-ticket", "write with approval")));
        bindings.stream()
                .filter(binding -> binding.tenant().equals(who.tenant()))
                .forEach(
                        binding ->
                                inventory.add(
                                        Map.of(
                                                "name",
                                                binding.name(),
                                                "effect",
                                                binding.readOnly() ? "read" : "write with approval",
                                                "source",
                                                binding.type(),
                                                "version",
                                                binding.version())));
        return inventory;
    }

    private static Map<String, String> tool(String name, String effect) {
        return Map.of("name", name, "effect", effect, "source", "Java");
    }
}
