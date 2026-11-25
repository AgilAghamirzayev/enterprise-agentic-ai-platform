package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;

import io.agentplatform.plan.PlanCompiler;
import io.agentplatform.store.PlatformStore;
import io.agentplatform.store.RunTraceContext;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.Span;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Map;

/** All API, event and scheduler submissions share the same invocation checks and transaction. */
@Service
class RunSubmission {
    private final PlatformStore store;

    private final ObservationRegistry observations;

    RunSubmission(PlatformStore store, ObservationRegistry observations) {
        this.observations = observations;
        this.store = store;
    }

    record Ref(@NotBlank @Size(max = 64) String name, @NotBlank @Size(max = 64) String version) {}

    record Start(
            @Valid Ref agent,
            @Valid Ref workflow,
            @NotNull JsonNode input,
            @Size(max = 128) String sessionId) {}

    record Receipt(String runId, String status, String agentVersion, Map<String, String> links) {
        ResponseEntity<Receipt> accepted() {
            return ResponseEntity.accepted().location(URI.create(links.get("status"))).body(this);
        }
    }

    Receipt start(Identity who, String key, Start request) {
        who.require("operator", "developer", "user");
        if ((request.agent() == null) == (request.workflow() == null)) {
            throw new IllegalArgumentException("Specify one agent or workflow");
        }
        if (request.input().toString().length() > 32768)
            throw new IllegalArgumentException("Run input exceeds 32 KiB");
        boolean isAgent = request.agent() != null;
        var ref = isAgent ? request.agent() : request.workflow();
        String kind = isAgent ? "agent" : "workflow";
        var definition =
                store.definition(who.tenant(), kind, ref.name(), ref.version()).required("spec");
        if (!PlanCompiler.parse(definition.toString()).permitsInvocation(who.roles())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Definition denies invocation");
        }
        var run =
                Observation.createNotStarted("agent.run.submit", observations)
                        .observe(
                                () -> {
                                    var accepted =
                                            store.startRun(
                                                    who.tenant(),
                                                    who.actor(),
                                                    who.roles(),
                                                    key,
                                                    kind,
                                                    ref.name(),
                                                    ref.version(),
                                                    request.input(),
                                                    request.sessionId());
                                    var span = Span.current();
                                    // A retried submission retains the original trace stored with
                                    // the run.
                                    var persisted = accepted.path("traceparent").asText("");
                                    if (span.isRecording()
                                            && persisted.equals(RunTraceContext.current())) {
                                        span.setAttribute("langfuse.observation.type", "span");
                                        span.setAttribute(
                                                "langfuse.trace.name", kind + "/" + ref.name());
                                        span.setAttribute("langfuse.version", ref.version());
                                        span.setAttribute(
                                                "langfuse.trace.metadata.runId",
                                                accepted.required("id").asText());
                                    }
                                    return accepted;
                                });
        String id = run.required("id").asText();
        String base = "/api/v1/runs/" + id;
        return new Receipt(
                id,
                run.required("status").asText(),
                ref.version(),
                Map.of("status", base, "events", base + "/events", "result", base + "/result"));
    }
}
