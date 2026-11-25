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
class PolicyVersionController {
    private final PlatformStore store;
    PolicyVersionController(PlatformStore store) { this.store=store; }
    record Version(@NotBlank @Pattern(regexp="[a-z][a-z0-9-]{0,62}") String name,
                   @NotBlank @Pattern(regexp="[0-9]+\\.[0-9]+\\.[0-9]+") String version,@NotNull JsonNode spec) {}
    record Rules(@NotNull JsonNode rules,@NotBlank @Size(max=128) String requiredRole) {}

    @PostMapping("/policy-versions") @ResponseStatus(HttpStatus.CREATED)
    JsonNode publish(Authentication auth,@Valid @RequestBody Version body) {
        var who=Identity.from(auth);who.require("admin");
        return store.publishPolicy(who.tenant(),who.actor(),body.name(),body.version(),body.spec());
    }

    @GetMapping("/policy-versions")
    List<JsonNode> list(Authentication auth) {
        var who=Identity.from(auth);who.require("developer","auditor");
        return store.definitions(who.tenant(),"policy");
    }

    @PutMapping("/policies/{tool}")
    JsonNode update(Authentication auth,@PathVariable String tool,@Valid @RequestBody Rules body) {
        var who=Identity.from(auth);who.require("admin");
        return store.setPolicyRules(who.tenant(),who.actor(),tool,body.rules(),body.requiredRole());
    }
}
