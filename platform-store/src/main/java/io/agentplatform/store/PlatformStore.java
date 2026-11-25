package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Tenant-scoped persistence API. Callers derive the tenant, actor and roles from verified identity.
 */
public final class PlatformStore {
    public record StartCommand(String tenant, String runId) {}

    public record IngestionRef(String tenant, String jobId, int generation) {}

    private final Database db;
    private final WorkspaceDirectory workspaces;
    private final RunArtifacts artifacts;
    public RunArtifacts artifacts() { return artifacts; }

    public WorkspaceDirectory workspaces() { return workspaces; }
    private final Runs runs;
    private final Approvals approvals;
    private final ToolPolicies policies;
    private final Definitions definitions;
    private final Knowledge knowledge;
    private final Quotas quotas;
    private final EvaluationSuites suites;
    private final Ingestions ingestions;

    public PlatformStore(DataSource source, ObjectMapper mapper) {
        this(source, mapper, new TokenHashVectorizer());
    }

    public PlatformStore(DataSource source, ObjectMapper mapper, Vectorizer vectorizer) {
        this(source, mapper, vectorizer, new ModelPricing(null, null));
    }

    public PlatformStore(
            DataSource source, ObjectMapper mapper, Vectorizer vectorizer, ModelPricing pricing) {
        this(source,mapper,vectorizer,pricing,List.of());
    }

    public PlatformStore(DataSource source,ObjectMapper mapper,Vectorizer vectorizer,ModelPricing pricing,List<io.agentplatform.spi.PolicyEngine> engines) {
        this(source,mapper,vectorizer,pricing,engines,new io.micrometer.core.instrument.composite.CompositeMeterRegistry());
    }
    public PlatformStore(DataSource source,ObjectMapper mapper,Vectorizer vectorizer,ModelPricing pricing,List<io.agentplatform.spi.PolicyEngine> engines,io.micrometer.core.instrument.MeterRegistry metrics) {
        this(source,mapper,vectorizer,pricing,engines,metrics,null);
    }
    public PlatformStore(DataSource source,ObjectMapper mapper,Vectorizer vectorizer,ModelPricing pricing,List<io.agentplatform.spi.PolicyEngine> engines,io.micrometer.core.instrument.MeterRegistry metrics,io.agentplatform.spi.KnowledgeProvider provider) {
        db = new Database(source, mapper, metrics);
        workspaces = new WorkspaceDirectory(db);
        artifacts = new RunArtifacts(db);
        quotas = new Quotas(db, pricing);
        runs = new Runs(db, quotas);
        policies = new ToolPolicies(db,engines);
        approvals = new Approvals(db, runs, policies);
        definitions = new Definitions(db);
        suites = new EvaluationSuites(db, definitions);
        knowledge = new Knowledge(db,provider==null?new PgVectorKnowledgeProvider(db,vectorizer):provider);
        ingestions = new Ingestions(db, knowledge);
    }

    public JsonNode publish(
            String tenant, String actor, String kind, String name, String version, JsonNode spec) {
        return definitions.save(tenant, actor, kind, name, version, spec, true);
    }

    public JsonNode upsertDraft(
            String tenant, String actor, String kind, String name, String version, JsonNode spec) {
        return definitions.save(tenant, actor, kind, name, version, spec, false);
    }

    public JsonNode draft(String tenant, String kind, String name, String version) {
        return definitions.get(tenant, kind, name, version, true);
    }

    public JsonNode definition(String tenant, String kind, String name, String version) {
        return definitions.get(tenant, kind, name, version, false);
    }

    public List<JsonNode> definitions(String tenant, String kind) {
        return definitions.list(tenant, kind);
    }

    public List<JsonNode> definitions(String tenant, String kind, String name) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                "SELECT * FROM definitions WHERE tenant=? AND kind=? AND name=?"
                                        + " ORDER BY created_at DESC,version LIMIT 200",
                                tenant,
                                kind,
                                name));
    }

    public List<JsonNode> runsForActor(String tenant, String actor) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                """
                                SELECT id,id AS run_id,kind,name,version,status,cancel_requested,created_at,updated_at
                                FROM runs WHERE tenant=? AND actor=? ORDER BY created_at DESC,id LIMIT 200
                                """,
                                tenant,
                                actor));
    }

    public JsonNode publishEvaluation(String tenant, String actor, String kind, JsonNode spec) {
        return suites.publish(tenant, actor, kind, spec);
    }

    public JsonNode scoreSuite(
            String tenant,
            String actor,
            boolean auditor,
            String suite,
            JsonNode runIds,
            String baselineId) {
        return scoreSuite(tenant,actor,auditor,suite,runIds,baselineId,null);
    }

    public JsonNode scoreSuite(String tenant,String actor,boolean auditor,String suite,JsonNode runIds,String baselineId,JsonNode judgeRuns){
        return suites.report(tenant,actor,auditor,suite,runIds,baselineId,judgeRuns);
    }

    public List<JsonNode> reports(String tenant, String actor, boolean auditor) {
        return suites.list(tenant, actor, auditor);
    }

    public JsonNode report(String tenant, String actor, boolean auditor, String id) {
        return db.scoped(tenant, () -> suites.read(tenant, actor, auditor, Database.id(id)));
    }

    public JsonNode startRun(
            String tenant,
            String actor,
            String key,
            String kind,
            String name,
            String version,
            JsonNode input) {
        return startRun(tenant, actor, List.of(), key, kind, name, version, input, null);
    }

    public JsonNode startRun(
            String tenant,
            String actor,
            List<String> roles,
            String key,
            String kind,
            String name,
            String version,
            JsonNode input) {
        return startRun(tenant, actor, roles, key, kind, name, version, input, null);
    }

    public JsonNode startRun(
            String tenant,
            String actor,
            List<String> roles,
            String key,
            String kind,
            String name,
            String version,
            JsonNode input,
            String sessionId) {
        return runs.start(tenant, actor, roles, key, kind, name, version, input, sessionId);
    }

    /**
     * Only the dispatcher may consume this cross-tenant routing view. Workflow IDs deduplicate
     * concurrent dispatches.
     */
    public List<StartCommand> pendingStarts(int limit) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Batch size outside 1..1000");
        return db.jdbc.query(
                """
                SELECT tenant,run_id FROM start_commands WHERE dispatched_at IS NULL ORDER BY created_at,run_id LIMIT ?
                """,
                (rs, i) -> new StartCommand(rs.getString(1), rs.getString(2)),
                limit);
    }

    public void acknowledgeStart(String tenant, String runId) {
        db.scoped(
                tenant,
                () ->
                        db.jdbc.update(
                                """
                                    UPDATE start_commands SET dispatched_at=clock_timestamp(),next_check=clock_timestamp()+interval '60 seconds'
                                    WHERE tenant=? AND run_id=? AND dispatched_at IS NULL
                                """,
                                tenant,
                                Database.id(runId)));
    }

    public JsonNode run(String tenant, String runId) {
        return runs.get(tenant, runId);
    }

    public JsonNode runControl(String tenant, String runId) {
        return db.scoped(
                tenant,
                () ->
                        db.one(
                                "SELECT status,cancel_requested FROM runs WHERE tenant=? AND id=?",
                                tenant,
                                Database.id(runId)));
    }

    public List<StartCommand> dueReconciliations(int limit, int delaySeconds) {
        if (limit < 1 || limit > 500 || delaySeconds < 1 || delaySeconds > 86400)
            throw new IllegalArgumentException("Invalid reconciliation lease");
        var candidates =
                db.jdbc.query(
                        """
                        SELECT tenant,run_id FROM start_commands WHERE dispatched_at IS NOT NULL AND NOT closed AND next_check<=clock_timestamp()
                        ORDER BY next_check,run_id LIMIT ?
                        """,
                        (rs, n) -> new StartCommand(rs.getString(1), rs.getString(2)),
                        limit);
        var leased = new java.util.ArrayList<StartCommand>();
        for (var command : candidates) {
            var claim =
                    db.scoped(
                            command.tenant(),
                            () ->
                                    db.first(
                                            """
                                            UPDATE start_commands c SET next_check=clock_timestamp()+(? * interval '1 second'),
                                              closed=(r.status NOT IN ('QUEUED','RUNNING'))
                                            FROM runs r WHERE c.tenant=? AND c.run_id=? AND c.tenant=r.tenant AND c.run_id=r.id
                                              AND c.dispatched_at IS NOT NULL AND NOT c.closed AND c.next_check<=clock_timestamp()
                                            RETURNING c.closed
                                            """,
                                            delaySeconds,
                                            command.tenant(),
                                            Database.id(command.runId())));
            if (claim.isPresent() && !claim.get().get("closed").asBoolean()) leased.add(command);
        }
        return leased;
    }

    public List<JsonNode> runs(String tenant) {
        return runs.list(tenant);
    }

    public JsonNode cancel(String tenant, String actor, String runId) {
        return runs.cancel(tenant, actor, runId);
    }

    public void begin(String tenant, String runId) {
        runs.begin(tenant, runId);
    }

    public void finish(String tenant, String runId, String status, JsonNode result) {
        runs.finish(tenant, runId, status, result);
    }

    public Optional<JsonNode> step(String tenant, String runId, String key) {
        return db.scoped(tenant, () -> runs.stepOutput(tenant, runId, key));
    }

    public List<JsonNode> steps(String tenant, String runId) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                """
                                SELECT step_key,output,input_tokens,output_tokens,created_at FROM step_payloads WHERE tenant=? AND run_id=?
                                ORDER BY created_at,step_key LIMIT 1000
                                """,
                                tenant,
                                Database.id(runId)));
    }

    public JsonNode saveStep(
            String tenant,
            String runId,
            String key,
            JsonNode output,
            int inputTokens,
            int outputTokens) {
        return db.scoped(
                tenant,
                () -> {
                    runs.active(tenant, runId);
                    return runs.writeStep(tenant, runId, key, output, inputTokens, outputTokens);
                });
    }

    public void reserveModel(
            String tenant, String runId, String key, int tokens, long maxTokens, int maxCalls) {
        reserveModel(tenant, runId, key, tokens, 0, maxTokens, maxCalls, null);
    }

    public void reserveModel(
            String tenant,
            String runId,
            String key,
            int inputTokens,
            int maxOutputTokens,
            long maxTokens,
            int maxCalls,
            BigDecimal maxCostUsd) {
        reserveModel(
                tenant,
                runId,
                key,
                inputTokens,
                maxOutputTokens,
                maxTokens,
                maxCalls,
                maxCostUsd,
                "default");
    }

    public void reserveModel(
            String tenant,
            String runId,
            String key,
            int inputTokens,
            int maxOutputTokens,
            long maxTokens,
            int maxCalls,
            BigDecimal maxCostUsd,
            String modelId) {
        runs.reserveModel(
                tenant,
                runId,
                key,
                inputTokens,
                maxOutputTokens,
                maxTokens,
                maxCalls,
                maxCostUsd,
                Database.requireText(modelId, "model identifier", 200));
    }

    public JsonNode setQuota(
            String tenant, String actor, String scope, Long monthlyTokens, BigDecimal monthlyUsd) {
        return quotas.set(tenant, actor, scope, monthlyTokens, monthlyUsd);
    }

    public JsonNode saveToolStep(
            String tenant, String runId, String key, String tool, JsonNode args, JsonNode output) {
        return db.scoped(tenant, () -> runs.writeToolStep(tenant, runId, key, tool, args, output));
    }

    public List<JsonNode> quotas(String tenant) {
        return quotas.list(tenant);
    }

    public void reserveTool(String tenant, String runId, String key, int maxCalls) {
        runs.reserveTool(tenant, runId, key, maxCalls);
    }

    public JsonNode requestApproval(
            String tenant,
            String runId,
            String key,
            String tool,
            JsonNode args,
            String role,
            long ttlSeconds) {
        return approvals.request(tenant, runId, key, tool, args, role, ttlSeconds);
    }

    public JsonNode applyTicket(
            String tenant, String runId, String key, JsonNode args, String role, long ttlSeconds) {
        return applyTicket(tenant,runId,key,args,role,ttlSeconds,ignored->{});
    }

    public JsonNode applyTicket(String tenant,String runId,String key,JsonNode args,String role,long ttlSeconds,java.util.function.Consumer<JsonNode> validateOutput) {
        return approvals.applyTicket(tenant,runId,key,args,role,validateOutput);
    }

    public JsonNode decide(
            String tenant,
            String actor,
            List<String> roles,
            String approvalId,
            String actionHash,
            boolean approved) {
        return approvals.decide(tenant, actor, roles, approvalId, actionHash, approved);
    }

    public boolean claimExternalWrite(String tenant,String runId,String key,String tool,JsonNode args,String role) {
        return approvals.claimWrite(tenant,runId,key,tool,args,role);
    }

    public JsonNode completeExternalWrite(String tenant,String runId,String key,String tool,JsonNode args,JsonNode output) {
        return approvals.completeWrite(tenant,runId,key,tool,args,output);
    }

    public List<JsonNode> externalEffects(String tenant) {
        return db.scoped(tenant,()->db.rows("SELECT run_id,step_key,tool,action_hash,outcome,dispatched_at,resolved_at FROM external_effects WHERE tenant=? ORDER BY dispatched_at DESC LIMIT 200",tenant));
    }

    public JsonNode reconcileEffect(String tenant,String actor,String runId,String key,String hash,String outcome,JsonNode receipt) {
        return db.scoped(tenant,()->{runs.lock(tenant,runId);return approvals.resolve(tenant,actor,runId,key,hash,outcome,receipt);});
    }

    public List<JsonNode> approvals(String tenant) {
        return approvals.list(tenant);
    }

    public JsonNode toolPolicy(String tenant, String tool) {
        return db.scoped(tenant, () -> policies.current(tenant, tool));
    }

    public JsonNode setToolPolicy(
            String tenant, String actor, String tool, String effect, String requiredRole) {
        return policies.update(tenant, actor, tool, effect, requiredRole,null);
    }

    public JsonNode setPolicyRules(String tenant,String actor,String tool,JsonNode rules,String role) {
        return policies.update(tenant,actor,tool,"DENY",role,rules);
    }

    public JsonNode publishPolicy(String tenant,String actor,String name,String version,JsonNode spec) {
        PolicyRules.allowed(spec,java.util.Set.of("defaultEffect","rules","requiredRole"));
        if(!spec.path("defaultEffect").asText("DENY").equals("DENY")) throw new StoreException(400,"Default policy effect must be DENY");
        PolicyRules.validate(spec.path("rules"),true);
        if(spec.has("requiredRole")) Database.requireText(spec.get("requiredRole").textValue(),"role",128);
        return publish(tenant,actor,"policy",name,version,spec);
    }

    public JsonNode authorizeTool(String tenant,String runId,String key,String tool,JsonNode args,List<String> refs) {
        return db.scoped(tenant,()->{runs.active(tenant,runId);return policies.authorize(tenant,runId,key,tool,args,refs);});
    }

    public List<JsonNode> toolPolicies(String tenant) {
        return policies.list(tenant);
    }

    public List<JsonNode> events(String tenant, String runId, long after) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                """
                                SELECT id,run_id,event_type,resource,occurred_at FROM audit_events WHERE tenant=? AND run_id=? AND id>?
                                ORDER BY id LIMIT 200
                                """,
                                tenant,
                                Database.id(runId),
                                after));
    }

    public List<JsonNode> audit(String tenant) {
        return audit(tenant,null,null,200);
    }
    public List<JsonNode> audit(String tenant,String q,Long before,int limit) {
        return new AuditTrail(db).search(tenant,q,before,limit);
    }
    public JsonNode retention(String tenant) {
        return db.scoped(tenant, () -> db.one("""
            SELECT s.run_days,s.audit_days,s.updated_at FROM (SELECT ?::text tenant) t LEFT JOIN retention_settings s USING(tenant)
            """,tenant));
    }

    public JsonNode configureRetention(String tenant,String actor,Integer runDays,Integer auditDays) {
        if (java.util.stream.Stream.of(runDays,auditDays).anyMatch(n -> n!=null && (n<1 || n>36500)))
            throw new StoreException(400,"Retention days must be in [1,36500]");
        return db.scoped(tenant, () -> {
            var settings=db.one("""
                    INSERT INTO retention_settings(tenant,run_days,audit_days) VALUES(?,?,?)
                    ON CONFLICT(tenant) DO UPDATE SET run_days=excluded.run_days,audit_days=excluded.audit_days,updated_at=clock_timestamp()
                    RETURNING run_days,audit_days,updated_at
                    """,tenant,runDays,auditDays);
            db.jdbc.update("""
                INSERT INTO retention_routes(tenant,enabled,next_check) VALUES(?,?,now())
                ON CONFLICT(tenant) DO UPDATE SET enabled=excluded.enabled,next_check=excluded.next_check
                """,tenant,runDays!=null);
            db.audit(tenant,actor,null,"retention.updated","workspace",null,Database.digest(settings.toString()));
            return settings;
        });
    }

    public List<String> dueArchiveScopes() {
        return db.jdbc.queryForList("""
            WITH due AS (SELECT tenant FROM retention_routes WHERE enabled AND next_check<=now()
                        ORDER BY next_check,tenant LIMIT 16 FOR UPDATE SKIP LOCKED)
            UPDATE retention_routes r SET next_check=now()+interval '5 minutes'
            FROM due WHERE due.tenant=r.tenant RETURNING r.tenant
            """,String.class);
    }

    public JsonNode archiveDue(String tenant,String actor,int limit) {
        if(limit<1 || limit>100) throw new StoreException(400,"Archive batch must be in [1,100]");
        return db.scoped(tenant, () -> {
            var result=db.one("SELECT archive_due_runs(?) AS archived",limit);
            if(result.path("archived").asInt()>0) db.audit(tenant,actor,null,"retention.archived",result.path("archived").asText());
            return result;
        });
    }

    public JsonNode auditIntegrity(String tenant,long after,int limit) {
        return new AuditTrail(db).inspect(tenant,after,limit);
    }

    public JsonNode ingest(
            String tenant,
            String actor,
            String base,
            String title,
            String content,
            List<String> roles) {
        return ingest(tenant,actor,base,title,content,roles,null,null);
    }
    public JsonNode ingest(String tenant,String actor,String base,String title,String content,List<String> roles,String sourceUri,String classification){
        return knowledge.ingest(tenant,actor,base,title,content,roles,DocumentLabels.from(sourceUri,classification,roles));
    }

    public List<JsonNode> search(
            String tenant, String base, String query, List<String> roles, int limit) {
        return knowledge.search(tenant, base, query, roles, limit);
    }

    public List<JsonNode> searchForRun(
            String tenant,
            String runId,
            String key,
            String base,
            String query,
            List<String> roles,
            int limit) {
        db.scoped(
                tenant,
                () -> {
                    ensureRunRoles(runs.active(tenant, runId), roles);
                    return null;
                });
        var candidates=knowledge.candidates(tenant,base,query,roles,limit);
        return db.scoped(
                tenant,
                () -> {
                    ensureRunRoles(runs.active(tenant, runId), roles);
                    var documents=knowledge.hydrate(tenant,base,roles,limit,candidates);
                    for (var doc : documents) {
                        db.jdbc.update(
                                """
                                INSERT INTO retrieval_evidence(tenant,run_id,step_key,document_id,chunk_id,digest)
                                VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING
                                """,
                                tenant,
                                Database.id(runId),
                                key,
                                Database.id(doc.required("documentId").asText()),
                                doc.required("chunkId").asInt(),
                                doc.required("digest").asText());
                        db.jdbc.update(
                                "INSERT INTO memory_sources(tenant,run_id,document_id) VALUES"
                                        + " (?,?,?) ON CONFLICT DO NOTHING",
                                tenant,
                                Database.id(runId),
                                Database.id(doc.required("documentId").asText()));
                    }
                    db.audit(tenant, "worker", runId, "knowledge.retrieved", key);
                    return documents;
                });
    }

    private static void ensureRunRoles(JsonNode run, List<String> roles) {
        var granted = new java.util.HashSet<String>();
        run.required("roles").forEach(role -> granted.add(role.asText()));
        if (!granted.containsAll(roles))
            throw new StoreException(403, "Run knowledge role mismatch");
    }

    public List<JsonNode> documents(String tenant, List<String> roles) {
        return knowledge.list(tenant, roles);
    }

    public JsonNode submitIngestion(
            String tenant,
            String actor,
            String key,
            String base,
            String documentKey,
            String title,
            String content,
            List<String> roles) {
        return submitIngestion(tenant, actor, key, base, documentKey, title, content, roles, false);
    }

    public JsonNode submitIngestion(
            String tenant,
            String actor,
            String key,
            String base,
            String documentKey,
            String title,
            String content,
            List<String> roles,
            boolean admin) {
        return submitIngestion(tenant,actor,key,base,documentKey,title,content,roles,admin,null,null);
    }
    public JsonNode submitIngestion(String tenant,String actor,String key,String base,String documentKey,String title,String content,List<String> roles,boolean admin,String sourceUri,String classification){
        return ingestions.submit(tenant,actor,key,base,documentKey,title,content,roles,admin,DocumentLabels.from(sourceUri,classification,roles));
    }

    public JsonNode ingestion(
            String tenant, String actor, boolean auditor, String base, String id) {
        return ingestions.get(tenant, actor, auditor, base, id);
    }

    public JsonNode retireDocument(String tenant,String actor,boolean admin,String base,String docKey){
        var retirement=db.scoped(tenant,()->{
            var head=db.one("SELECT owner_id,active_revision,last_revision,retired_revision FROM document_heads WHERE tenant=? AND base=? AND document_key=? FOR UPDATE",tenant,base,docKey);
            if(!admin && !actor.equals(head.path("ownerId").textValue())) throw new StoreException(403,"Document publisher or tenant administrator required");
            if(head.path("retiredRevision").intValue()<head.path("lastRevision").intValue()){
                db.jdbc.update("UPDATE document_heads SET retired_revision=last_revision WHERE tenant=? AND base=? AND document_key=?",tenant,base,docKey);
                db.audit(tenant,actor,null,"document.retired",docKey);
            }
            return db.mapper.createObjectNode().put("documentKey",docKey).put("retiredRevision",head.path("lastRevision").intValue());
        });
        knowledge.provider.retire(new io.agentplatform.spi.KnowledgeProvider.Retirement(tenant,base,docKey,retirement.path("retiredRevision").intValue()));
        return retirement;
    }

    public JsonNode retryIngestion(
            String tenant, String actor, boolean admin, String base, String id) {
        return ingestions.retry(tenant, actor, admin, base, id);
    }

    public List<IngestionRef> pendingIngestions(int limit) {
        return ingestions.pending(limit);
    }

    public void acknowledgeIngestion(IngestionRef job) {
        ingestions.acknowledge(job);
    }

    public void indexIngestion(IngestionRef job, java.util.function.IntConsumer progress) {
        ingestions.index(job, progress);
    }

    public void failIngestion(IngestionRef job) {
        ingestions.fail(job);
    }

    public List<JsonNode> messagesForRun(String tenant,String runId,int limit) {
        return memory(tenant,runId,null,limit);
    }

    public List<JsonNode> episodicForRun(String tenant,String runId,String query,int limit) {
        Database.requireText(query,"episode query",8192);
        return memory(tenant,runId,query,limit);
    }

    private List<JsonNode> memory(String tenant,String runId,String query,int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid history limit");
        UUID id = Database.id(runId);
        return db.scoped(
                tenant,
                () -> {
                    var run = runs.active(tenant, runId);
                    if (!run.get("sessionId").isTextual()) return List.of();
                    List<JsonNode> messages =
                            db.rows(
                                    """
                                    SELECT * FROM (
                                      SELECT m.id,m.role,m.run_id,
                                        CASE WHEN ?::text IS NULL THEN m.content ELSE ts_headline('simple',m.content,
                                          plainto_tsquery('simple',?),'MaxWords=70,MinWords=10,ShortWord=1,MaxFragments=2') END AS content
                                      FROM messages m
                                      JOIN runs origin ON origin.tenant=m.tenant AND origin.id=m.run_id
                                      WHERE m.tenant=? AND m.actor=? AND m.agent=? AND m.session_id=?
                                        AND origin.archived_at IS NULL AND origin.kind=? AND origin.roles <@ ?::jsonb AND m.run_id<>?
                                        AND NOT EXISTS (
                                          SELECT 1 FROM memory_sources s JOIN documents d ON (d.tenant,d.id)=(s.tenant,s.document_id)
                                          LEFT JOIN document_heads h ON h.tenant=d.tenant AND h.base=d.base AND h.document_key=coalesce(d.document_key,d.id::text)
                                          WHERE s.tenant=m.tenant AND s.run_id=m.run_id AND
                                            ((h.active_id IS NOT NULL AND (h.active_id<>d.id OR h.active_revision<=h.retired_revision))
                                             OR (d.roles<>'[]'::jsonb AND NOT jsonb_exists_any(d.roles,ARRAY(SELECT jsonb_array_elements_text(?::jsonb))))))
                                      AND (?::text IS NULL OR m.search_tokens @@ plainto_tsquery('simple',?))
                                      ORDER BY CASE WHEN ?::text IS NOT NULL THEN ts_rank_cd(m.search_tokens,plainto_tsquery('simple',?)) END DESC,m.id DESC LIMIT ?
                                    ) recent ORDER BY id
                                    """,
                                    query,query,tenant,
                                    run.get("actor").asText(),
                                    run.get("name").asText(),
                                    run.get("sessionId").asText(),
                                    run.get("kind").asText(),
                                    run.get("roles").toString(),
                                    id,
                                    run.get("roles").toString(),
                                    query,query,query,query,limit);
                    if (!messages.isEmpty()) {
                        var origins = db.mapper.createArrayNode();
                        messages.stream()
                                .map(m -> m.get("runId").asText())
                                .distinct()
                                .forEach(origins::add);
                        db.jdbc.update(
                                """
                                INSERT INTO memory_sources(tenant,run_id,document_id)
                                SELECT tenant,?,document_id FROM memory_sources WHERE tenant=?
                                AND run_id IN (SELECT jsonb_array_elements_text(?::jsonb)::uuid) ON CONFLICT DO NOTHING
                                """,
                                id,
                                tenant,
                                origins.toString());
                    }
                    return messages;
                });
    }

    public JsonNode usage(String tenant) {
        return UsageReport.load(db, quotas, tenant);
    }

    public JsonNode recordEvaluation(String tenant, String actor, String runId, JsonNode expected) {
        return db.scoped(
                tenant,
                () -> {
                    UUID id = UUID.randomUUID();
                    var result =
                            db.first(
                                    """
                                    INSERT INTO evaluations(tenant,id,run_id,actor,expected,passed)
                                    SELECT tenant,?,id,?,?::jsonb,coalesce(result=?::jsonb,false) FROM run_payloads WHERE tenant=? AND id=?
                                    AND status IN ('SUCCEEDED','COMPLETED') RETURNING *
                                    """,
                                    id,
                                    actor,
                                    Database.json(expected),
                                    Database.json(expected),
                                    tenant,
                                    Database.id(runId));
                    if (result.isEmpty())
                        throw new StoreException(409, "A completed run is required");
                    db.audit(tenant, actor, runId, "evaluation.recorded", id.toString());
                    return result.get();
                });
    }

    public List<JsonNode> evaluations(String tenant) {
        return db.scoped(
                tenant,
                () ->
                        db.rows(
                                "SELECT * FROM evaluations WHERE tenant=? ORDER BY created_at"
                                        + " DESC,id LIMIT 200",
                                tenant));
    }
}
