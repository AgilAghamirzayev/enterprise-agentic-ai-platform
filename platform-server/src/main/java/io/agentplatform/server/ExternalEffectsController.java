package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentplatform.store.PlatformStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/external-effects")
class ExternalEffectsController {
    private final PlatformStore store;
    ExternalEffectsController(PlatformStore store){this.store=store;}
    record Resolution(@NotBlank String stepKey,@NotBlank String actionHash,@NotBlank String outcome,@NotNull JsonNode receipt) {}
    @GetMapping
    List<JsonNode> list(Authentication auth){var who=Identity.from(auth);who.require("admin","auditor");return store.externalEffects(who.tenant());}
    @PostMapping("/{runId}/reconcile")
    JsonNode reconcile(Authentication auth,@PathVariable String runId,@Valid @RequestBody Resolution body){
        var who=Identity.from(auth);who.require("admin");
        return store.reconcileEffect(who.tenant(),who.actor(),runId,body.stepKey(),body.actionHash(),body.outcome(),body.receipt());
    }
}
