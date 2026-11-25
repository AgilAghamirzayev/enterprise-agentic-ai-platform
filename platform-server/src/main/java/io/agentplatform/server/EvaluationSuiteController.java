package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;

import io.agentplatform.store.PlatformStore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
class EvaluationSuiteController {
    private final PlatformStore store;

    EvaluationSuiteController(PlatformStore store) {
        this.store = store;
    }

    record Score(
            @NotBlank String suite,
            @NotNull JsonNode runs,
            String baselineReportId,
            JsonNode judgeRuns) {}

    @GetMapping("/evaluation-datasets")
    List<JsonNode> datasets(Authentication auth) {
        return list(auth, "evaluation-dataset");
    }

    @GetMapping("/evaluation-suites")
    List<JsonNode> suites(Authentication auth) {
        return list(auth, "evaluation-suite");
    }

    private List<JsonNode> list(Authentication auth, String kind) {
        var who = Identity.from(auth);
        who.require("developer", "auditor");
        return store.definitions(who.tenant(), kind);
    }

    @PostMapping("/evaluation-datasets")
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode publishDataset(Authentication auth, @RequestBody JsonNode spec) {
        return publish(auth, "evaluation-dataset", spec);
    }

    @PostMapping("/evaluation-suites")
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode publishSuite(Authentication auth, @RequestBody JsonNode spec) {
        return publish(auth, "evaluation-suite", spec);
    }

    private JsonNode publish(Authentication auth, String kind, JsonNode spec) {
        var who = Identity.from(auth);
        who.require("developer");
        return store.publishEvaluation(who.tenant(), who.actor(), kind, spec);
    }

    @PostMapping("/evaluation-reports")
    @ResponseStatus(HttpStatus.CREATED)
    JsonNode score(Authentication auth, @Valid @RequestBody Score score) {
        var who = Identity.from(auth);
        who.require("developer");
        return store.scoreSuite(
                who.tenant(),
                who.actor(),
                who.has("auditor"),
                score.suite(),
                score.runs(),
                score.baselineReportId(),
                score.judgeRuns());
    }

    @GetMapping("/evaluation-reports")
    List<JsonNode> reports(Authentication auth) {
        var who = Identity.from(auth);
        who.require("developer", "auditor");
        return store.reports(who.tenant(), who.actor(), who.has("auditor"));
    }

    @GetMapping("/evaluation-reports/{id}")
    JsonNode report(Authentication auth, @PathVariable String id) {
        var who = Identity.from(auth);
        who.require("developer", "auditor");
        return store.report(who.tenant(), who.actor(), who.has("auditor"), id);
    }
}
