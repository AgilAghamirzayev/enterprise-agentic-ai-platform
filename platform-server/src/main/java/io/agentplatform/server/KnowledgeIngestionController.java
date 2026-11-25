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
@RequestMapping("/api/v1/knowledge-bases/{base}/ingestions")
class KnowledgeIngestionController {
    private final PlatformStore store;

    KnowledgeIngestionController(PlatformStore store) {
        this.store = store;
    }

    record Upload(
            @NotBlank @Size(max = 128) String documentKey,
            @NotBlank @Size(max = 512) String title,
            @NotBlank @Size(max = 262144) String content,
            @NotNull @Size(max = 32) List<@NotBlank String> roles,@Size(max=1024) String sourceUri,@Size(max=32) String classification) {}

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    JsonNode submit(
            Authentication auth,
            @PathVariable String base,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Upload upload) {
        var who = Identity.from(auth);
        who.require("developer");
        return store.submitIngestion(
                who.tenant(),
                who.actor(),
                key,
                base,
                upload.documentKey(),
                upload.title(),
                upload.content(),
                upload.roles(),
                who.has("admin"),upload.sourceUri(),upload.classification());
    }

    @GetMapping("/{id}")
    JsonNode status(Authentication auth, @PathVariable String base, @PathVariable String id) {
        var who = Identity.from(auth);
        who.require("developer", "auditor");
        return store.ingestion(who.tenant(), who.actor(), who.has("auditor"), base, id);
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    JsonNode retry(Authentication auth, @PathVariable String base, @PathVariable String id) {
        var who = Identity.from(auth);
        who.require("developer");
        return store.retryIngestion(who.tenant(), who.actor(), who.has("admin"), base, id);
    }
}
