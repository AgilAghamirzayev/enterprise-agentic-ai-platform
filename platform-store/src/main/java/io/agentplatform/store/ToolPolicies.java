package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import io.agentplatform.spi.PolicyEngine;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

final class ToolPolicies {
    private static final Set<String> EFFECTS = Set.of("INHERIT", "DENY", "REQUIRE_APPROVAL");
    private final Database db;

    private final List<PolicyEngine> engines;

    ToolPolicies(Database db,List<PolicyEngine> engines) {
        this.engines=List.copyOf(engines);
        this.db = db;
    }

    JsonNode current(String tenant, String tool) {
        return db.first(
                        "SELECT tool,effect,required_role,rules,revision FROM tool_policies WHERE"
                                + " tenant=? AND tool=?",
                        tenant,
                        tool)
                .orElseGet(
                        () ->
                                db.mapper
                                        .createObjectNode()
                                        .put("tool", tool)
                                        .put("effect", "INHERIT")
                                        .put("requiredRole", "approver")
                                        .put("revision", 0));
    }

    JsonNode locked(String tenant, String tool) {
        // Cover the first policy insertion as well as updates to an existing policy.
        String lockKey = db.mapper.createArrayNode().add("policy").add(tenant).add(tool).toString();
        db.jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", lockKey);
        return current(tenant, tool);
    }

    JsonNode update(String tenant, String actor, String tool, String effect, String role,JsonNode rules) {
        if(rules!=null) PolicyRules.validate(rules,false);
        Database.requireText(tool, "tool", 128);
        Database.requireText(role, "role", 128);
        if (!EFFECTS.contains(effect)) throw new StoreException(400, "Unknown policy effect");
        return db.scoped(
                tenant,
                () -> {
                    locked(tenant, tool);
                    JsonNode result =
                            db.one(
                                    """
                                    INSERT INTO tool_policies(tenant,tool,effect,required_role,updated_by,revision,rules) VALUES (?,?,?,?,?,1,?::jsonb)
                                    ON CONFLICT(tenant,tool) DO UPDATE SET effect=excluded.effect,required_role=excluded.required_role,
                                       updated_by=excluded.updated_by,updated_at=now(),revision=tool_policies.revision+1,rules=excluded.rules
                                    RETURNING tool,effect,required_role,rules,revision
                                    """,
                                    tenant,
                                    tool,
                                    effect,
                                    role,
                                    actor,rules==null?null:Database.json(rules));
                    db.audit(
                            tenant,
                            actor,
                            null,
                            "policy.updated",
                            tool + "/" + result.get("revision").asLong());
                    return result;
                });
    }

    JsonNode authorize(String tenant,String runId,String step,String tool,JsonNode args,List<String> refs) {
        if(refs==null || refs.size()>16) throw new StoreException(400,"Invalid policy bindings");
        JsonNode bindings=db.mapper.valueToTree(refs.stream().distinct().sorted().toList());
        String json=Database.json(args);
        int added=db.jdbc.update("""
            INSERT INTO step_policy_bindings(tenant,run_id,step_key,tool,args_hash,policy_refs)
            VALUES (?,?,?,?,encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex'),?::jsonb) ON CONFLICT DO NOTHING
            """,tenant,Database.id(runId),step,tool,json,bindings.toString());
        JsonNode decision=effective(tenant,runId,step,tool,args,bindings);
        if(added==1 || decision.path("effect").asText().equals("DENY"))
            db.audit(tenant,"worker",runId,"tool.authorization."+decision.path("effect").asText().toLowerCase(java.util.Locale.ROOT),tool,decision.path("revision").asText(),Database.digest(json));
        return decision;
    }

    JsonNode effective(String tenant,String runId,String step,String tool,JsonNode args,JsonNode expectedRefs) {
        var mutable=(ObjectNode) locked(tenant,tool);
        var binding=db.first("""
            SELECT tool,policy_refs,args_hash=encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex') AS matches
            FROM step_policy_bindings WHERE tenant=? AND run_id=? AND step_key=?
            """,Database.json(args),tenant,Database.id(runId),step);
        JsonNode refs=db.mapper.createArrayNode();
        if(binding.isPresent()) {
            var row=binding.get();
            refs=row.required("policyRefs");
            if(!row.required("matches").asBoolean() || !row.required("tool").asText().equals(tool)
                    || expectedRefs!=null && !refs.equals(expectedRefs)) throw new StoreException(409,"Tool invocation changed on retry");
        }
        String role=mutable.required("requiredRole").asText();
        String effect=mutable.required("effect").asText();
        var decision=mutable.hasNonNull("rules") ? PolicyRules.evaluate(mutable.get("rules"),tool,args,role)
             :new PolicyEngine.Decision(effect.equals("INHERIT")?PolicyEngine.Effect.ALLOW:PolicyEngine.Effect.valueOf(effect),role);
        for(var ref:refs) {
            String[] parts=ref.asText().split(":",-1);
            if(parts.length!=2) throw new StoreException(403,"Invalid pinned policy reference");
            JsonNode pinned=db.one("SELECT spec FROM definitions WHERE tenant=? AND kind='policy' AND name=? AND version=?",tenant,parts[0],parts[1]).required("spec");
            decision=PolicyRules.intersect(decision,PolicyRules.evaluate(pinned.required("rules"),tool,args,pinned.path("requiredRole").asText("approver")));
        }
        for(var engine:engines) {
            try {
                decision=PolicyRules.intersect(decision,engine.evaluate(new PolicyEngine.Request(tenant,runId,step,tool,Database.json(args))));
            } catch(RuntimeException failure) {
                decision=new PolicyEngine.Decision(PolicyEngine.Effect.DENY,null);
                break;
            }
        }
        mutable.remove("rules");
        return mutable.put("effect",decision.effect().name()).put("requiredRole",decision.requiredRole()==null?role:decision.requiredRole());
    }

    List<JsonNode> list(String tenant) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                """
                                SELECT tool,effect,required_role,rules,revision,updated_by,updated_at FROM tool_policies
                                WHERE tenant=? ORDER BY tool LIMIT 200
                                """,
                                tenant));
    }
}
