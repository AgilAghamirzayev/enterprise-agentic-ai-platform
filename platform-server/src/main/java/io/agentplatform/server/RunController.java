package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;

import io.agentplatform.store.PlatformStore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api/v1/runs")
class RunController {
    private final PlatformStore store;
    private final RunSubmission submissions;
    private final RunWake wake;
    private final Semaphore streams = new Semaphore(32);

    RunController(PlatformStore store, RunSubmission submissions, RunWake wake) {
        this.wake = wake;
        this.store = store;
        this.submissions = submissions;
    }

    @PostMapping
    ResponseEntity<?> start(
            Authentication auth,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RunSubmission.Start request) {
        return submissions.start(Identity.from(auth), key, request).accepted();
    }

    @GetMapping
    List<JsonNode> list(Authentication auth) {
        var who = Identity.from(auth);
        who.require("operator", "developer", "auditor", "user");
        return who.has("auditor")
                ? store.runs(who.tenant())
                : store.runsForActor(who.tenant(), who.actor());
    }

    @GetMapping("/{id}")
    JsonNode detail(Authentication auth, @PathVariable String id) {
        var who = Identity.from(auth);
        who.require("operator", "developer", "user", "auditor", "approver");
        var run = store.run(who.tenant(), id);
        if (!who.has("auditor") && !run.path("actor").asText().equals(who.actor()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return run;
    }

    @GetMapping("/{id}/result")
    JsonNode result(Authentication auth, @PathVariable String id) {
        var run = detail(auth, id);
        if (!Set.of("COMPLETED", "SUCCEEDED").contains(run.path("status").asText()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run has no successful result");
        return run.path("result");
    }

    @GetMapping("/{id}/steps")
    List<JsonNode> steps(Authentication auth, @PathVariable String id) {
        detail(auth, id);
        return store.steps(Identity.from(auth).tenant(), id);
    }

    @PostMapping("/{id}/cancel")
    JsonNode cancel(Authentication auth, @PathVariable String id) {
        var who = Identity.from(auth);
        who.require("operator", "developer", "user");
        detail(auth, id);
        var updated = store.cancel(who.tenant(), who.actor(), id);
        if (updated.path("status").asText().equals("RUNNING")) wake.notifyWorker(id);
        return updated;
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
            Authentication auth,
            @PathVariable String id,
            @RequestHeader(name = "Last-Event-ID", defaultValue = "0") long cursor) {
        if (cursor < 0) throw new IllegalArgumentException("Invalid event cursor");
        detail(auth, id);
        if (!streams.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Stream limit reached");
        var emitter = new SseEmitter(30000L);
        var who = Identity.from(auth);
        Instant end = Instant.now().plusSeconds(25);
        Instant expiry =
                auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getExpiresAt() : end;
        Thread.startVirtualThread(
                () -> {
                    long after = cursor;
                    try {
                        while (Instant.now().isBefore(end)
                                && (expiry == null || Instant.now().isBefore(expiry))) {
                            if (!who.workspace().equals("default")
                                    && !who.roles().contains("admin")) {
                                var currentRoles =
                                        store.workspaces()
                                                .grants(
                                                        who.organization(),
                                                        who.workspace(),
                                                        who.actor(),
                                                        false);
                                if (!currentRoles.containsAll(who.roles())) break;
                            }
                            var events = store.events(who.tenant(), id, after);
                            for (var event : events) {
                                after = event.required("id").asLong();
                                emitter.send(
                                        SseEmitter.event()
                                                .id(Long.toString(after))
                                                .name(event.path("eventType").asText("run.event"))
                                                .data(event));
                            }
                            if (events.isEmpty())
                                emitter.send(SseEmitter.event().comment("keepalive"));
                            Thread.sleep(1000);
                        }
                        emitter.complete();
                    } catch (Exception disconnected) {
                        emitter.completeWithError(disconnected);
                    } finally {
                        streams.release();
                    }
                });
        return emitter;
    }
}
