package io.agentplatform.server;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentplatform.store.PlatformStore;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/retention")
class RetentionController {
    record Policy(Integer runDays,Integer auditDays) {}
    private final PlatformStore store;
    RetentionController(PlatformStore store){this.store=store;}
    @GetMapping JsonNode settings(Authentication auth) {
        var who=Identity.from(auth);who.require("auditor");
        return store.retention(who.tenant());
    }
    @PutMapping JsonNode configure(Authentication auth,@RequestBody Policy policy) {
        var who=Identity.from(auth);who.require("admin");
        return store.configureRetention(who.tenant(),who.actor(),policy.runDays(),policy.auditDays());
    }
    @PostMapping("/archive") JsonNode archive(Authentication auth,@RequestParam(defaultValue="25") int limit) {
        var who=Identity.from(auth);who.require("admin");
        return store.archiveDue(who.tenant(),who.actor(),limit);
    }
}
