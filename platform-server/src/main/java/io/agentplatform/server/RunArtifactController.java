package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;

import io.agentplatform.store.PlatformStore;

import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/runs/{run}/artifacts")
class RunArtifactController {
    private final PlatformStore store;

    RunArtifactController(PlatformStore store) {
        this.store = store;
    }

    private Identity authorize(Authentication auth, String run, boolean write) {
        var who = Identity.from(auth);
        var record = store.run(who.tenant(), run);
        if (write) who.require("operator", "developer", "user");
        else who.require("auditor", "operator", "developer", "user");
        if (!who.has(write ? "admin" : "auditor")
                && !who.actor().equals(record.path("actor").asText()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return who;
    }

    @GetMapping
    List<JsonNode> list(Authentication auth, @PathVariable String run) {
        return store.artifacts().list(authorize(auth, run, false).tenant(), run);
    }

    @PutMapping(value = "/{name}", consumes = MediaType.ALL_VALUE)
    JsonNode upload(
            Authentication auth,
            @PathVariable String run,
            @PathVariable String name,
            @RequestHeader("Content-Type") MediaType type,
            @RequestBody byte[] bytes) {
        var who = authorize(auth, run, true);
        return store.artifacts()
                .put(
                        who.tenant(),
                        run,
                        who.actor(),
                        name,
                        type.getType() + "/" + type.getSubtype(),
                        bytes);
    }

    @GetMapping("/{name}")
    ResponseEntity<byte[]> download(
            Authentication auth, @PathVariable String run, @PathVariable String name) {
        var who = authorize(auth, run, false);
        var result = store.artifacts().get(who.tenant(), run, who.actor(), name);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.mediaType()))
                .header(
                        "Content-Disposition",
                        ContentDisposition.attachment().filename(name).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(result.bytes());
    }
}
