package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class Runs {
    private static final String SELECT_RUN =
            """
            SELECT r.id,r.id AS run_id,r.kind,r.name,r.version,r.actor,r.roles,r.session_id,r.input,r.result,
                   r.status,r.cancel_requested,r.created_at,r.updated_at,r.archived_at,r.model_calls,r.reserved_tokens,r.traceparent,d.spec AS plan
            FROM run_payloads r JOIN definitions d USING(tenant,kind,name,version) WHERE r.tenant=? AND r.id=?
            """;
    private static final Set<String> TERMINAL =
            Set.of(
                    "SUCCEEDED",
                    "COMPLETED",
                    "FAILED",
                    "DENIED",
                    "CANCELLED",
                    "EXPIRED",
                    "TIMED_OUT",
                    "BUDGET_EXCEEDED");
    final Database db;
    private final Quotas quotas;

    Runs(Database db, Quotas quotas) {
        this.db = db;
        this.quotas = quotas;
    }

    JsonNode start(
            String tenant,
            String actor,
            List<String> roles,
            String key,
            String kind,
            String name,
            String version,
            JsonNode input,
            String sessionId) {
        Database.requireText(key, "idempotency key", 200);
        Database.requireText(actor, "actor", 200);
        if (sessionId != null) Database.requireText(sessionId, "session", 128);
        String payload = Database.json(input);
        String roleJson = Knowledge.roleArray(db, roles);
        return db.scoped(
                tenant,
                () -> {
                    UUID runId = UUID.randomUUID();
                    db.one(
                            "SELECT 1 FROM definitions WHERE tenant=? AND kind=? AND name=? AND"
                                    + " version=?",
                            tenant,
                            kind,
                            name,
                            version);
                    int inserted =
                            db.jdbc.update(
                                    """
                                    INSERT INTO runs(tenant,id,actor,roles,request_key,kind,name,version,input,session_id,traceparent)
                                    VALUES (?,?,?,?::jsonb,?,?,?,?,?::jsonb,?,?) ON CONFLICT (tenant,actor,request_key) DO NOTHING
                                    """,
                                    tenant,
                                    runId,
                                    actor,
                                    roleJson,
                                    key,
                                    kind,
                                    name,
                                    version,
                                    payload,
                                    sessionId,
                                    RunTraceContext.current());
                    if (inserted == 0) {
                        var prior =
                                db.first(
                                        """
                                        SELECT id FROM run_payloads WHERE tenant=? AND actor=? AND request_key=? AND kind=? AND name=?
                                        AND version=? AND input=?::jsonb AND session_id IS NOT DISTINCT FROM ?
                                        """,
                                        tenant,
                                        actor,
                                        key,
                                        kind,
                                        name,
                                        version,
                                        payload,
                                        sessionId);
                        runId =
                                Database.id(
                                        prior.orElseThrow(
                                                        () ->
                                                                new StoreException(
                                                                        409,
                                                                        "Idempotency key reused"
                                                                                + " with different"
                                                                                + " input"))
                                                .get("id")
                                                .asText());
                    } else {
                        db.jdbc.update(
                                "INSERT INTO start_commands(tenant,run_id) VALUES (?,?)",
                                tenant,
                                runId);
                        db.audit(tenant, actor, runId.toString(), "run.queued", name,null,Database.digest(payload));
                    }
                    return db.one(SELECT_RUN, tenant, runId);
                });
    }

    JsonNode get(String tenant, String runId) {
        return db.scoped(tenant, () -> db.one(SELECT_RUN, tenant, Database.id(runId)));
    }

    List<JsonNode> list(String tenant) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                """
                                SELECT id,id AS run_id,kind,name,version,status,cancel_requested,created_at,updated_at
                                FROM runs WHERE tenant=? ORDER BY created_at DESC,id LIMIT 200
                                """,
                                tenant));
    }

    JsonNode lock(String tenant, String runId) {
        return db.one(
                "SELECT status,cancel_requested,actor,roles,session_id,kind,name,created_at FROM runs WHERE"
                        + " tenant=? AND id=? FOR UPDATE",
                tenant,
                Database.id(runId));
    }

    JsonNode active(String tenant, String runId) {
        JsonNode run = lock(tenant, runId);
        if (run.path("cancelRequested").asBoolean()
                || !"RUNNING".equals(run.path("status").asText()))
            throw new StoreException(409, "Execution no longer active");
        return run;
    }

    void begin(String tenant, String runId) {
        db.scoped(
                tenant,
                () -> {
                    JsonNode state = lock(tenant, runId);
                    if (!state.path("cancelRequested").asBoolean()
                            && "QUEUED".equals(state.path("status").asText())) {
                        db.jdbc.update(
                                "UPDATE runs SET status='RUNNING',updated_at=now() WHERE tenant=?"
                                        + " AND id=?",
                                tenant,
                                Database.id(runId));
                        db.audit(tenant, "worker", runId, "run.started", "execution");
                    }
                    return null;
                });
    }

    JsonNode cancel(String tenant, String actor, String runId) {
        return db.scoped(
                tenant,
                () -> {
                    JsonNode state = lock(tenant, runId);
                    if (!TERMINAL.contains(state.path("status").asText())
                            && !state.path("cancelRequested").asBoolean()) {
                        db.jdbc.update(
                                "UPDATE runs SET cancel_requested=true,updated_at=now() WHERE"
                                        + " tenant=? AND id=?",
                                tenant,
                                Database.id(runId));
                        db.audit(tenant, actor, runId, "run.cancel_requested", "execution");
                    }
                    return db.one(SELECT_RUN, tenant, Database.id(runId));
                });
    }

    void finish(String tenant, String runId, String status, JsonNode result) {
        if (!TERMINAL.contains(status))
            throw new IllegalArgumentException("Unknown terminal status");
        db.scoped(
                tenant,
                () -> {
                    JsonNode state = lock(tenant, runId);
                    if (!TERMINAL.contains(state.path("status").asText())) {
                        boolean cancelled = state.path("cancelRequested").asBoolean();
                        String terminal = cancelled ? "CANCELLED" : status;
                        if (Set.of("SUCCEEDED", "COMPLETED").contains(terminal)
                                && state.path("sessionId").isTextual()) {
                            JsonNode run =
                                    db.one(
                                            "SELECT r.input,coalesce(d.spec#>>'{memory,type}','CONVERSATION') AS memory FROM run_payloads r JOIN definitions d USING(tenant,kind,name,version) WHERE r.tenant=? AND r.id=?",
                                            tenant,
                                            Database.id(runId));
                            if(!run.required("memory").asText().equals("NONE")) {
                                remember(tenant, runId, state, "user", run.required("input"));
                                if (result != null) remember(tenant, runId, state, "assistant", result);
                            }
                        }
                        var duration=java.time.Duration.between(java.time.Instant.parse(state.required("createdAt").asText()),java.time.Instant.now());
                        db.afterCommit(()->{
                            db.metrics.counter("agent.runs","outcome",terminal).increment();
                            if(!duration.isNegative()) db.metrics.timer("agent.run.duration","outcome",terminal).record(duration);
                            if(!Set.of("COMPLETED","SUCCEEDED","CANCELLED").contains(terminal)) db.metrics.counter("agent.run.failures","outcome",terminal).increment();
                        });
                        db.jdbc.update(
                                "UPDATE runs SET status=?,result=?::jsonb,updated_at=now() WHERE"
                                        + " tenant=? AND id=?",
                                terminal,
                                cancelled || result == null ? null : Database.json(result),
                                tenant,
                                Database.id(runId));
                        db.jdbc.update(
                                "UPDATE start_commands SET closed=true WHERE tenant=? AND run_id=?",
                                tenant,
                                Database.id(runId));
                        db.audit(
                                tenant,
                                "worker",
                                runId,
                                "run." + terminal.toLowerCase(java.util.Locale.ROOT),
                                "execution");
                    }
                    return null;
                });
    }

    private void remember(
            String tenant, String runId, JsonNode state, String role, JsonNode value) {
        String text = value.isTextual() ? value.textValue() : value.toString();
        int length = Math.min(65536, text.length());
        if (length > 0 && Character.isHighSurrogate(text.charAt(length - 1))) length--;
        db.jdbc.update(
                """
                INSERT INTO messages(tenant,actor,agent,session_id,run_id,step_key,role,content)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (tenant,run_id,step_key,role) DO NOTHING
                """,
                tenant,
                state.get("actor").asText(),
                state.get("name").asText(),
                state.get("sessionId").asText(),
                Database.id(runId),
                role,
                role,
                text.substring(0, length));
    }

    Optional<JsonNode> stepOutput(String tenant, String runId, String key) {
        return db.first(
                        "SELECT output FROM step_payloads WHERE tenant=? AND run_id=? AND step_key=?",
                        tenant,
                        Database.id(runId),
                        key)
                .map(row -> row.get("output"));
    }

    JsonNode writeStep(
            String tenant, String runId, String key, JsonNode value, int inTokens, int outTokens) {
        Database.requireText(key, "step key", 512);
        if (inTokens < 0 || outTokens < 0) throw new IllegalArgumentException("Negative usage");
        var existing = stepOutput(tenant, runId, key);
        if (existing.isPresent()) return existing.get();
        db.jdbc.update(
                """
                INSERT INTO run_steps(tenant,run_id,step_key,output,input_tokens,output_tokens)
                VALUES (?,?,?,?::jsonb,?,?)
                """,
                tenant,
                Database.id(runId),
                key,
                Database.json(value),
                inTokens,
                outTokens);
        db.audit(tenant, "worker", runId, "step.completed", key,null,Database.digest(Database.json(value)));
        quotas.account(tenant, runId, key, inTokens, outTokens);
        return value;
    }

    JsonNode writeToolStep(
            String tenant,
            String runId,
            String key,
            String tool,
            JsonNode arguments,
            JsonNode output) {
        active(tenant, runId);
        JsonNode result = writeStep(tenant, runId, key, output, 0, 0);
        db.jdbc.update(
                """
                INSERT INTO tool_evidence(tenant,run_id,step_key,tool,argument_hash)
                VALUES (?,?,?,?,encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex'))
                ON CONFLICT(tenant,run_id,step_key) DO NOTHING
                """,
                tenant,
                Database.id(runId),
                key,
                tool,
                Database.json(arguments));
        return result;
    }

    void reserveModel(
            String tenant,
            String runId,
            String key,
            int inTokens,
            int outTokens,
            long maxTokens,
            int maxCalls,
            BigDecimal maxUsd,
            String modelId) {
        long tokens = (long) inTokens + outTokens;
        if (inTokens < 0 || outTokens < 0 || tokens <= 0 || maxTokens <= 0 || maxCalls <= 0)
            throw new StoreException(429, "Model budget exhausted");
        db.scoped(
                tenant,
                () -> {
                    JsonNode run = active(tenant, runId);
                    int updated =
                            db.jdbc.update(
                                    """
                                    UPDATE runs SET reserved_tokens=reserved_tokens+?,model_calls=model_calls+1
                                    WHERE tenant=? AND id=? AND reserved_tokens<=? AND model_calls<?
                                    """,
                                    tokens,
                                    tenant,
                                    Database.id(runId),
                                    maxTokens - tokens,
                                    maxCalls);
                    if (updated == 0) throw new StoreException(429, "Model budget exhausted");
                    quotas.reserve(
                            tenant,
                            runId,
                            run.required("kind").asText() + ":" + run.required("name").asText(),
                            key,
                            inTokens,
                            outTokens,
                            maxUsd,
                            modelId);
                    db.audit(tenant, "worker", runId, "model.reserved", modelId + "/" + key);
                    return null;
                });
    }

    void reserveTool(String tenant, String runId, String key, int maxCalls) {
        db.scoped(
                tenant,
                () -> {
                    active(tenant, runId);
                    UUID id = Database.id(runId);
                    int inserted =
                            db.jdbc.update(
                                    """
                                    INSERT INTO tool_reservations(tenant,run_id,step_key) VALUES (?,?,?) ON CONFLICT DO NOTHING
                                    """,
                                    tenant,
                                    id,
                                    key);
                    if (inserted == 1) {
                        long calls =
                                db.jdbc.queryForObject(
                                        "SELECT count(*) FROM tool_reservations WHERE tenant=? AND"
                                                + " run_id=?",
                                        Long.class,
                                        tenant,
                                        id);
                        if (calls > maxCalls)
                            throw new StoreException(429, "Tool budget exhausted");
                        db.audit(tenant, "worker", runId, "tool.reserved", key);
                    }
                    return null;
                });
    }
}
