package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.plan.PlanCompiler;
import io.agentplatform.store.PlatformStore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

abstract class DefinitionController {
    private final PlatformStore store;
    private final ObjectMapper json;

    private final String kind;

    DefinitionController(PlatformStore store, ObjectMapper json, String kind) {
        this.kind = kind;
        this.store = store;
        this.json = json;
    }

    record Draft(
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,62}") String name,
            @NotBlank @Pattern(regexp = "[0-9]+\\.[0-9]+\\.[0-9]+") String version,
            @NotNull JsonNode spec) {}

    @GetMapping
    List<JsonNode> list(Authentication auth) {
        var who = Identity.from(auth);
        who.require("user", "operator", "developer", "auditor", "approver");
        return store.definitions(who.tenant(), kind);
    }

    @GetMapping("/{name}")
    List<JsonNode> versions(Authentication auth, @PathVariable String name) {
        var who = Identity.from(auth);
        who.require("user", "operator", "developer", "auditor", "approver");
        return store.definitions(who.tenant(), kind, name);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode draft(Authentication auth, @Valid @RequestBody Draft draft) {
        var who = Identity.from(auth);
        who.require("developer");
        var plan = compile(who.tenant(), kind, draft);
        return withRevision(
                store.upsertDraft(
                        who.tenant(), who.actor(), kind, draft.name(), draft.version(), plan));
    }

    @PostMapping("/{name}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode version(
            Authentication auth, @PathVariable String name, @Valid @RequestBody Draft draft) {
        if (!name.equals(draft.name()))
            throw new IllegalArgumentException("Version name does not match its resource");
        return draft(auth, draft);
    }

    @GetMapping("/{name}/versions/{version}/draft")
    JsonNode readDraft(
            Authentication auth, @PathVariable String name, @PathVariable String version) {
        var who = Identity.from(auth);
        who.require("developer");
        return withRevision(store.draft(who.tenant(), kind, name, version));
    }

    private static JsonNode withRevision(JsonNode record) {
        return ((com.fasterxml.jackson.databind.node.ObjectNode) record.deepCopy())
                .put("revision", revision(record.required("spec")));
    }

    private static String revision(JsonNode plan) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(
                            digest.digest(
                                    plan.toString()
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @PostMapping("/{name}/versions/{version}/validate")
    JsonNode validate(
            Authentication auth,
            @PathVariable String name,
            @PathVariable String version,
            @RequestHeader(name = "If-Match", required = false) String match) {
        return release(auth, name, version, false, match);
    }

    @PostMapping("/{name}/versions/{version}/publish")
    JsonNode publish(
            Authentication auth,
            @PathVariable String name,
            @PathVariable String version,
            @RequestHeader(name = "If-Match", required = false) String match) {
        return release(auth, name, version, true, match);
    }

    private JsonNode release(
            Authentication auth, String name, String version, boolean publish, String match) {
        var who = Identity.from(auth);
        who.require("developer");
        var plan = store.draft(who.tenant(), kind, name, version).required("spec");
        if (match != null && !match.equals("\"" + revision(plan) + "\""))
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.PRECONDITION_FAILED, "Draft changed since review");
        var allowed = new java.util.HashSet<String>();
        plan.path("allowedTools").forEach(tool -> allowed.add(tool.asText()));
        if (plan.has("memory")) {
            var memory =
                    json.convertValue(
                            plan.required("memory"), io.agentplatform.plan.Plan.Memory.class);
            if (!memory.permitsJavaHistory())
                checkRestrictedHistory(who, plan, new java.util.HashSet<>(), 0);
        }
        for (var node : plan.path("nodes")) {
            validateRef(who, node, "toolRef", "tool");
            for (var binding : node.path("toolBindings"))
                validateRef(who, binding.asText(), "tool");
            validateRef(who, node, "promptRef", "prompt");
            validateRef(who, node, "modelProfileRef", "model-profile");
            validateDelegate(who, node, "agentRef", "agent", allowed);
            validateDelegate(who, node, "workflowRef", "workflow", allowed);
        }
        for (var policy : plan.path("policyRefs")) validateRef(who, policy.asText(), "policy");
        if (!publish) return plan;
        return store.publish(who.tenant(), who.actor(), kind, name, version, plan);
    }

    private void checkRestrictedHistory(
            Identity who, JsonNode plan, java.util.Set<String> visited, int depth) {
        if (depth > 5 || visited.size() > 256)
            throw new IllegalArgumentException("Delegation graph exceeds compilation bounds");
        for (var node : plan.path("nodes")) {
            if (node.hasNonNull("javaRef"))
                throw new IllegalArgumentException(
                        "Delegated Java handler cannot enforce the parent memory policy");
            for (String field : List.of("agentRef", "workflowRef")) {
                if (!node.hasNonNull(field)) continue;
                var ref = node.required(field).asText();
                if (visited.add(field + "/" + ref)) {
                    var child =
                            validateRef(who, ref, field.equals("agentRef") ? "agent" : "workflow")
                                    .required("spec");
                    checkRestrictedHistory(who, child, visited, depth + 1);
                }
            }
        }
    }

    private void validateDelegate(
            Identity who, JsonNode node, String field, String kind, java.util.Set<String> allowed) {
        if (!node.hasNonNull(field)) return;
        var child = validateRef(who, node.required(field).asText(), kind).required("spec");
        for (var tool : child.path("allowedTools"))
            if (!allowed.contains(tool.asText()))
                throw new IllegalArgumentException(
                        "Delegated resource requests a tool outside the parent allowlist");
    }

    private void validateRef(Identity who, JsonNode node, String field, String kind) {
        if (!node.hasNonNull(field)) return;
        validateRef(who, node.get(field).asText(), kind);
    }

    private JsonNode validateRef(Identity who, String reference, String kind) {
        var ref = reference.split(":", -1);
        if (ref.length != 2)
            throw new IllegalArgumentException("An immutable reference is required");
        return store.definition(who.tenant(), kind, ref[0], ref[1]);
    }

    private JsonNode compile(String tenant, String resourceKind, Draft draft) {
        JsonNode spec = draft.spec();
        String source = spec.isTextual() ? spec.textValue() : spec.toString();
        if (source.length() > 65536) throw new IllegalArgumentException("Plan exceeds 64 KiB");
        try {
            var compiled =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            json.readTree(
                                    PlanCompiler.compile(
                                            source,
                                            (kind, name) -> {
                                                var versions =
                                                        store.definitions(tenant, kind, name);
                                                if (versions.isEmpty())
                                                    throw new IllegalArgumentException(
                                                            "No published version of "
                                                                    + kind
                                                                    + " "
                                                                    + name);
                                                return name
                                                        + ":"
                                                        + versions.getFirst()
                                                                .required("version")
                                                                .asText();
                                            },
                                            resourceKind,
                                            draft.name(),
                                            draft.version()));
            io.agentplatform.plan.RegistryTools.bind(
                    compiled,
                    reference -> {
                        String[] parts = reference.split(":", 2);
                        return store.definition(tenant, "tool", parts[0], parts[1])
                                .required("spec");
                    });
            return json.readTree(PlanCompiler.compile(compiled.toString()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException bad) {
            throw new IllegalArgumentException("Invalid plan", bad);
        }
    }
}

@RestController
@RequestMapping("/api/v1/agents")
class AgentDefinitions extends DefinitionController {
    AgentDefinitions(PlatformStore store, ObjectMapper json) {
        super(store, json, "agent");
    }
}

@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowDefinitions extends DefinitionController {
    WorkflowDefinitions(PlatformStore store, ObjectMapper json) {
        super(store, json, "workflow");
    }
}
