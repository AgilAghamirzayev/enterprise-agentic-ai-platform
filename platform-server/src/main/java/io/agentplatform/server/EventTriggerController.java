package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentplatform.store.PlatformStore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@RestController
@RequestMapping("/api/v1/triggers")
class EventTriggerController {
    private static final String NAME = "[a-z][a-z0-9-]{0,62}";
    private static final String VERSION = "[0-9]+\\.[0-9]+\\.[0-9]+";
    private final PlatformStore store;
    private final ObjectMapper json;
    private final RunSubmission submissions;

    EventTriggerController(PlatformStore store, ObjectMapper json, RunSubmission submissions) {
        this.store = store;
        this.json = json;
        this.submissions = submissions;
    }

    record Trigger(
            @NotBlank @Pattern(regexp = NAME) String name,
            @NotBlank @Size(max = 64) @Pattern(regexp = VERSION) String version,
            @NotBlank @Size(max = 256) String source,
            @NotBlank @Size(max = 128) String type,
            @NotBlank @Size(max = 160) String subject,
            @Valid @NotNull RunSubmission.Ref workflow) {}

    record Event(
            @NotBlank @Pattern(regexp = "1\\.0") String specversion,
            @NotBlank @Size(max = 128) String id,
            @NotBlank @Size(max = 256) String source,
            @NotBlank @Size(max = 128) String type,
            Instant time,
            @NotNull JsonNode data) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode publish(Authentication auth, @Valid @RequestBody Trigger trigger) {
        var who = Identity.from(auth);
        who.require("admin");
        store.definition(
                who.tenant(), "workflow", trigger.workflow().name(), trigger.workflow().version());
        return store.publish(
                who.tenant(),
                who.actor(),
                "trigger",
                trigger.name(),
                trigger.version(),
                json.valueToTree(trigger));
    }

    @GetMapping
    List<JsonNode> triggers(Authentication auth) {
        var who = Identity.from(auth);
        who.require("developer", "auditor", "operator");
        return store.definitions(who.tenant(), "trigger");
    }

    @PostMapping(
            value = "/{name}/versions/{version}/events",
            consumes = {"application/cloudevents+json", "application/json"})
    ResponseEntity<?> deliver(
            Authentication auth,
            @PathVariable String name,
            @PathVariable String version,
            @Valid @RequestBody Event event) {
        var who = Identity.from(auth);
        who.require("operator", "developer", "user");
        var spec = store.definition(who.tenant(), "trigger", name, version).required("spec");
        if (!who.actor().equals(spec.required("subject").asText())
                || !event.type().equals(spec.required("type").asText())
                || !event.source().equals(spec.required("source").asText())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Event does not match the registered producer");
        }
        var ref = json.convertValue(spec.required("workflow"), RunSubmission.Ref.class);
        String identity =
                json.createArrayNode()
                        .add(name)
                        .add(version)
                        .add(event.source())
                        .add(event.id())
                        .toString();
        var receipt =
                submissions.start(
                        who,
                        "event-" + digest(identity),
                        new RunSubmission.Start(null, ref, event.data(), null));
        return receipt.accepted();
    }

    private static String digest(String identity) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
