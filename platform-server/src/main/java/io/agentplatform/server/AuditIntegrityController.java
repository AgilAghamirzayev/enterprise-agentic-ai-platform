package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentplatform.store.PlatformStore;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit-integrity")
class AuditIntegrityController {
    private final PlatformStore store;
    AuditIntegrityController(PlatformStore store) { this.store=store; }
    @GetMapping
    JsonNode inspect(Authentication auth,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="100") int limit) {
        var who=Identity.from(auth);who.require("auditor");
        return store.auditIntegrity(who.tenant(),after,limit);
    }
}
