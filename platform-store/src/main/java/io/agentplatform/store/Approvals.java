package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;
import java.util.Set;

final class Approvals {
    private static final String COLUMNS =
            """
            id,run_id,step_key,tool,args,action_hash,required_role,policy_revision,created_at,expires_at,decided_by,decided_at,
            CASE WHEN status IN ('APPROVED','PENDING') AND expires_at<=clock_timestamp() THEN 'EXPIRED' ELSE status END AS status
            """;
    private final Database db;
    private final Runs runs;
    private final ToolPolicies policies;

    Approvals(Database db, Runs runs, ToolPolicies policies) {
        this.db = db;
        this.runs = runs;
        this.policies = policies;
    }

    private String actionHash(
            String tenant,
            String runId,
            String stepKey,
            String tool,
            JsonNode args,
            String role,
            long revision) {
        String canonical =
                db.jdbc.queryForObject(
                        """
                        SELECT jsonb_build_array(?::text,?::uuid,?::text,?::text,?::jsonb,?::text,?::bigint)::text
                        """,
                        String.class,
                        tenant,
                        runId,
                        stepKey,
                        tool,
                        Database.json(args),
                        role,
                        revision);
        return Database.digest(canonical);
    }

    JsonNode request(
            String tenant,
            String runId,
            String key,
            String tool,
            JsonNode args,
            String role,
            long ttl) {
        Database.requireText(tool, "tool", 128);
        Database.requireText(role, "approval role", 128);
        Database.requireText(key, "step key", 512);
        if (ttl < 1 || ttl > 2_592_000) throw new StoreException(400, "Invalid approval lifetime");
        return db.scoped(
                tenant,
                () -> {
                    runs.active(tenant, runId);
                    JsonNode policy = authorizedPolicy(tenant,runId,key,tool,args);
                    long revision = policy.get("revision").asLong();
                    String requiredRole = requiredRole(policy, role);
                    String hash =
                            actionHash(tenant, runId, key, tool, args, requiredRole, revision);
                    int inserted =
                            db.jdbc.update(
                                    """
                                    INSERT INTO approvals(tenant,id,run_id,step_key,tool,args,action_hash,required_role,policy_revision,expires_at)
                                    VALUES (?,?,?,?,?,?::jsonb,?,?,?,clock_timestamp()+(? * interval '1 second'))
                                    ON CONFLICT (tenant,run_id,step_key) DO NOTHING
                                    """,
                                    tenant,
                                    UUID.randomUUID(),
                                    Database.id(runId),
                                    key,
                                    tool,
                                    Database.json(args),
                                    hash,
                                    requiredRole,
                                    revision,
                                    ttl);
                    if(inserted==1) db.afterCommit(()->db.metrics.counter("approval.requests").increment());
                    if (inserted == 1) db.audit(tenant, "worker", runId, "approval.requested", key);
                    JsonNode stored =
                            db.one(
                                    "SELECT "
                                            + COLUMNS
                                            + " FROM approvals WHERE tenant=? AND run_id=? AND"
                                            + " step_key=?",
                                    tenant,
                                    Database.id(runId),
                                    key);
                    if (!hash.equals(stored.path("actionHash").asText()))
                        throw new StoreException(409, "Approval action changed");
                    return stored;
                });
    }

    JsonNode decide(
            String tenant,
            String actor,
            List<String> roles,
            String approvalId,
            String hash,
            boolean approve) {
        return db.scoped(
                tenant,
                () -> {
                    UUID id = Database.id(approvalId);
                    String runId =
                            db.one(
                                            "SELECT run_id FROM approvals WHERE tenant=? AND id=?",
                                            tenant,
                                            id)
                                    .get("runId")
                                    .asText();
                    runs.active(tenant, runId);
                    var approval =
                            db.one(
                                    "SELECT "
                                            + COLUMNS
                                            + " FROM approvals WHERE tenant=? AND id=? FOR UPDATE",
                                    tenant,
                                    id);
                    JsonNode policy = authorizedPolicy(tenant,runId,approval.required("stepKey").asText(),approval.required("tool").asText(),approval.required("args"));
                    if (approval.required("policyRevision").asLong()
                            != policy.required("revision").asLong())
                        throw new StoreException(409, "Approval policy changed");
                    if (roles == null || !roles.contains(approval.path("requiredRole").asText()))
                        throw new StoreException(403, "Approval role required");
                    if (!approval.path("actionHash").asText().equals(hash))
                        throw new StoreException(409, "Approval action changed");
                    String target = approve ? "APPROVED" : "DENIED";
                    if (target.equals(approval.path("status").asText())
                            && actor.equals(approval.path("decidedBy").asText())) return approval;
                    if (!"PENDING".equals(approval.path("status").asText()))
                        throw new StoreException(409, "Approval is closed");
                    int count =
                            db.jdbc.update(
                                    """
                                    UPDATE approvals SET status=?,decided_by=?,decided_at=clock_timestamp()
                                    WHERE tenant=? AND id=? AND expires_at>clock_timestamp() AND status='PENDING'
                                    """,
                                    target,
                                    actor,
                                    tenant,
                                    id);
                    if (count != 1) throw new StoreException(409, "Approval expired");
                    if(approval.path("createdAt").isTextual()){
                        var duration=java.time.Duration.between(java.time.Instant.parse(approval.get("createdAt").asText()),java.time.Instant.now());
                        if(!duration.isNegative()) db.afterCommit(()->db.metrics.timer("approval.wait","outcome",target).record(duration));
                    }
                    db.audit(
                            tenant,
                            actor,
                            runId,
                            approve ? "approval.approved" : "approval.denied",
                            approvalId);
                    return db.one(
                            "SELECT " + COLUMNS + " FROM approvals WHERE tenant=? AND id=?",
                            tenant,
                            id);
                });
    }

    JsonNode applyTicket(String tenant, String runId, String key, JsonNode args, String role,java.util.function.Consumer<JsonNode> validateOutput) {
        return db.scoped(
                tenant,
                () -> {
                    runs.lock(tenant, runId);
                    JsonNode policy = authorizedPolicy(tenant,runId,key,"create-ticket",args);
                    String hash =
                            actionHash(
                                    tenant,
                                    runId,
                                    key,
                                    "create-ticket",
                                    args,
                                    requiredRole(policy, role),
                                    policy.required("revision").asLong());
                    var appliedBefore =
                            db.first(
                                    """
                                    SELECT s.output FROM approvals a JOIN step_payloads s USING(tenant,run_id,step_key)
                                    WHERE a.tenant=? AND a.run_id=? AND a.step_key=? AND a.action_hash=? AND a.status='APPLIED'
                                    """,
                                    tenant,
                                    Database.id(runId),
                                    key,
                                    hash);
                    if(appliedBefore.isPresent()){
                        var output=appliedBefore.get().required("output");
                        validateOutput.accept(output);
                        return output;
                    }
                    runs.active(tenant, runId);
                    int applied =
                            db.jdbc.update(
                                    """
                                    UPDATE approvals SET status='APPLIED' WHERE tenant=? AND run_id=? AND step_key=?
                                    AND status='APPROVED' AND action_hash=? AND expires_at>clock_timestamp()
                                    """,
                                    tenant,
                                    Database.id(runId),
                                    key,
                                    hash);
                    if (applied != 1)
                        throw new StoreException(
                                403, "A current approval for this action is required");
                    var ticket =
                            db.mapper
                                    .createObjectNode()
                                    .put("ticketId", UUID.randomUUID().toString())
                                    .put("status", "OPEN");
                    ticket.set("details", args);
                    validateOutput.accept(ticket);
                    db.audit(
                            tenant,
                            "worker",
                            runId,
                            "ticket.created",
                            ticket.get("ticketId").asText());
                    return runs.writeToolStep(tenant, runId, key, "create-ticket", args, ticket);
                });
    }

    boolean claimWrite(String tenant,String runId,String key,String tool,JsonNode args,String role) {
        return db.scoped(tenant,()->{
            runs.active(tenant,runId);
            UUID id=Database.id(runId);
            JsonNode policy=authorizedPolicy(tenant,runId,key,tool,args);
            String hash=actionHash(tenant,runId,key,tool,args,requiredRole(policy,role),policy.required("revision").asLong());
            var prior=db.first("SELECT action_hash FROM external_effects WHERE tenant=? AND run_id=? AND step_key=?",tenant,id,key);
            if(prior.isPresent()){
                if(!prior.get().path("actionHash").asText().equals(hash)) throw new StoreException(409,"External action changed");
                return false;
            }
            int approved=db.jdbc.update("""
                UPDATE approvals SET status='DISPATCHED' WHERE tenant=? AND run_id=? AND step_key=?
                AND status='APPROVED' AND action_hash=? AND expires_at>clock_timestamp()
                """,tenant,id,key,hash);
            if(approved!=1) throw new StoreException(403,"Current approval required for external action");
            db.jdbc.update("""
                INSERT INTO external_effects(tenant,run_id,step_key,tool,args_hash,action_hash)
                VALUES (?,?,?,?,encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex'),?)
                """,tenant,id,key,tool,Database.json(args),hash);
            db.audit(tenant,"worker",runId,"external.dispatched",tool,policy.path("revision").asText(),hash);
            return true;
        });
    }

    JsonNode completeWrite(String tenant,String runId,String key,String tool,JsonNode args,JsonNode output) {
        return db.scoped(tenant,()->{
            runs.lock(tenant,runId);
            UUID id=Database.id(runId);
            JsonNode effect=db.one("""
                SELECT outcome,action_hash FROM external_effects WHERE tenant=? AND run_id=? AND step_key=? AND tool=?
                AND args_hash=encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex') FOR UPDATE
                """,tenant,id,key,tool,Database.json(args));
            if(effect.path("outcome").asText().equals("NO_EFFECT")) throw new StoreException(409,"External effect already reconciled");
            var existing=runs.stepOutput(tenant,runId,key);
            if(existing.isPresent()) return existing.get();
            runs.writeStep(tenant,runId,key,output,0,0);
            db.jdbc.update("""
                INSERT INTO tool_evidence(tenant,run_id,step_key,tool,argument_hash)
                SELECT tenant,run_id,step_key,tool,args_hash FROM external_effects WHERE tenant=? AND run_id=? AND step_key=?
                ON CONFLICT DO NOTHING
                """,tenant,id,key);
            resolve(tenant,"worker",runId,key,effect.required("actionHash").asText(),"CONFIRMED",db.mapper.createObjectNode().put("step",key));
            return output;
        });
    }

    JsonNode resolve(String tenant,String actor,String runId,String key,String hash,String outcome,JsonNode receipt) {
        if(!Set.of("CONFIRMED","NO_EFFECT").contains(outcome) || receipt==null || !receipt.isObject() || receipt.isEmpty() || Database.json(receipt).length()>8192)
            throw new StoreException(400,"Invalid external reconciliation");
        UUID id=Database.id(runId);
        var previous=db.one("SELECT outcome,receipt,action_hash,tool FROM external_effects WHERE tenant=? AND run_id=? AND step_key=? FOR UPDATE",tenant,id,key);
        if(!hash.equals(previous.path("actionHash").asText())) throw new StoreException(409,"External action hash mismatch");
        if(!previous.path("outcome").asText().equals("DISPATCHED")){
            if(outcome.equals(previous.path("outcome").asText())) return previous;
            throw new StoreException(409,"External action already resolved");
        }
        JsonNode result=db.one("""
            UPDATE external_effects SET outcome=?,receipt=?::jsonb,attributed_to=?,resolved_at=clock_timestamp()
            WHERE tenant=? AND run_id=? AND step_key=? RETURNING run_id,step_key,tool,action_hash,outcome,receipt,attributed_to,resolved_at
            """,outcome,Database.json(receipt),actor,tenant,id,key);
        db.jdbc.update("UPDATE approvals SET status=? WHERE tenant=? AND run_id=? AND step_key=?",outcome.equals("CONFIRMED")?"APPLIED":"NO_EFFECT",tenant,id,key);
        db.audit(tenant,actor,runId,"external."+outcome.toLowerCase(java.util.Locale.ROOT),previous.path("tool").asText(),null,hash);
        return result;
    }

    List<JsonNode> list(String tenant) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                "SELECT "
                                        + COLUMNS
                                        + " FROM approvals WHERE tenant=? ORDER BY expires_at"
                                        + " DESC,id LIMIT 200",
                                tenant));
    }

    private JsonNode authorizedPolicy(String tenant,String runId,String key,String tool,JsonNode args) {
        JsonNode policy = policies.effective(tenant,runId,key,tool,args,null);
        if (policy.required("effect").asText().equals("DENY"))
            throw new StoreException(403, "Tool policy denies execution");
        return policy;
    }

    private String requiredRole(JsonNode policy, String fallback) {
        return policy.required("effect").asText().equals("REQUIRE_APPROVAL")
                ? policy.required("requiredRole").asText()
                : fallback;
    }
}
