package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;

import io.agentplatform.store.PlatformStore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
class WorkspaceController {
    private final PlatformStore store;
    private final RunWake wake;

    WorkspaceController(PlatformStore store, RunWake wake) {
        this.wake = wake;
        this.store = store;
    }

    @GetMapping("/me")
    Identity identity(Authentication auth) {
        return Identity.from(auth);
    }

    record Document(
            @NotBlank @Size(max = 100) String base,
            @NotBlank @Size(max = 256) String title,
            @NotBlank @Size(max = 65536) String content,
            @NotNull @Size(max = 32) List<@NotBlank String> roles,@Size(max=1024) String sourceUri,@Size(max=32) String classification) {}

    record Decision(@NotBlank String actionHash, @NotNull Boolean approved) {}

    record Evaluation(@NotBlank String runId, @NotNull JsonNode expected) {}

    @PostMapping("/knowledge-bases/documents")
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode index(Authentication auth, @Valid @RequestBody Document doc) {
        var who = Identity.from(auth);
        who.require("developer");
        return store.ingest(
                who.tenant(), who.actor(), doc.base(), doc.title(), doc.content(), doc.roles(),doc.sourceUri(),doc.classification());
    }

    @GetMapping("/knowledge-bases/documents")
    List<JsonNode> documents(Authentication auth) {
        var who = Identity.from(auth);
        who.require("developer", "operator", "user", "auditor");
        return store.documents(who.tenant(), who.roles());
    }

    @GetMapping("/knowledge-bases/{base}/search")
    List<JsonNode> search(Authentication auth, @PathVariable String base, @RequestParam String q) {
        var who = Identity.from(auth);
        who.require("developer", "operator", "user");
        return store.search(who.tenant(), base, q, who.roles(), 8);
    }

    @PostMapping("/knowledge-bases/{base}/documents/{key}/retire")
    JsonNode retireDocument(Authentication auth,@PathVariable String base,@PathVariable String key){
        var who=Identity.from(auth);who.require("developer");
        return store.retireDocument(who.tenant(),who.actor(),who.has("admin"),base,key);
    }

    @GetMapping("/approvals")
    List<JsonNode> approvals(Authentication auth) {
        var who = Identity.from(auth);
        who.require("approver", "auditor");
        return store.approvals(who.tenant()).stream()
                .filter(
                        row ->
                                who.has("auditor")
                                        || who.roles().contains(row.path("requiredRole").asText()))
                .toList();
    }

    @PostMapping("/approvals/{id}/decision")
    JsonNode decide(
            Authentication auth, @PathVariable String id, @Valid @RequestBody Decision decision) {
        var who = Identity.from(auth);
        who.require("approver");
        var persisted =
                store.decide(
                        who.tenant(),
                        who.actor(),
                        who.roles(),
                        id,
                        decision.actionHash(),
                        decision.approved());
        wake.notifyWorker(persisted.required("runId").asText());
        return persisted;
    }

    @GetMapping("/audit-events")
    List<JsonNode> audit(Authentication auth,@RequestParam(required=false) String q,@RequestParam(required=false) Long before,@RequestParam(defaultValue="50") int limit) {
        var who = Identity.from(auth);
        who.require("auditor");
        return store.audit(who.tenant(),q,before,limit);
    }

    @GetMapping("/usage")
    JsonNode usage(Authentication auth) {
        var who = Identity.from(auth);
        who.require("auditor", "operator", "developer");
        return store.usage(who.tenant());
    }

    @GetMapping("/evaluations")
    List<JsonNode> evaluations(Authentication auth) {
        var who = Identity.from(auth);
        who.require("developer", "auditor");
        return store.evaluations(who.tenant()).stream()
                .filter(row -> who.has("auditor") || who.actor().equals(row.path("actor").asText()))
                .toList();
    }

    @PostMapping("/evaluations")
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode evaluate(Authentication auth, @Valid @RequestBody Evaluation request) {
        var who = Identity.from(auth);
        who.require("developer");
        if (!who.has("auditor")
                && !store.run(who.tenant(), request.runId())
                        .path("actor")
                        .asText()
                        .equals(who.actor()))
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
        return store.recordEvaluation(
                who.tenant(), who.actor(), request.runId(), request.expected());
    }
}
