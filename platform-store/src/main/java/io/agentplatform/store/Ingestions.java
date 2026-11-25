package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

final class Ingestions {
    private static final String STATUS =
            """
            id,actor,base,document_key,revision,title,roles,digest,status,generation,document_id,created_at,updated_at
            """;
    private final Database db;
    private final Knowledge knowledge;

    Ingestions(Database db, Knowledge knowledge) {
        this.db = db;
        this.knowledge = knowledge;
    }

    JsonNode submit(
            String tenant,
            String actor,
            String key,
            String base,
            String documentKey,
            String title,
            String content,
            List<String> roles,
            boolean admin,DocumentLabels labels) {
        Database.requireText(actor, "actor", 200);
        Database.requireText(key, "idempotency key", 200);
        Database.requireText(base, "knowledge base", 128);
        Database.requireText(documentKey, "document key", 128);
        Database.requireText(title, "document title", 512);
        Database.requireText(content, "document content", 262144);
        String digest = Database.digest(content);
        String acl = Knowledge.roleArray(db, roles);
        return db.scoped(
                tenant,
                () -> {
                    String lock =
                            db.mapper
                                    .createArrayNode()
                                    .add("ingest")
                                    .add(tenant)
                                    .add(actor)
                                    .add(key)
                                    .toString();
                    db.jdbc.queryForList(
                            "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", lock);
                    var prior =
                            db.first(
                                    "SELECT "
                                            + STATUS
                                            + " FROM ingestion_jobs WHERE tenant=? AND actor=? AND"
                                            + " request_key=?",
                                    tenant,
                                    actor,
                                    key);
                    if (prior.isPresent()) {
                        JsonNode job = prior.get();
                        if (!job.required("base").asText().equals(base)
                                || !job.required("documentKey").asText().equals(documentKey)
                                || !job.required("title").asText().equals(title)
                                || !job.required("digest").asText().equals(digest)
                                || !job.required("roles").toString().equals(acl))
                            throw new StoreException(
                                    409, "Ingestion idempotency key reused with different request");
                        var old=db.first("SELECT source_uri,classification FROM document_labels WHERE tenant=? AND id=?",tenant,Database.id(job.get("id").asText()));
                        DocumentLabels priorLabels=old.map(row->new DocumentLabels(row.path("sourceUri").textValue(),row.path("classification").asText()))
                            .orElseGet(()->DocumentLabels.from(null,null,roles));
                        if(!labels.equals(priorLabels)) throw new StoreException(409,"Ingestion labels changed");
                        return job;
                    }
                    db.jdbc.update(
                            """
                            INSERT INTO document_artifacts(tenant,digest,content) VALUES (?,?,?) ON CONFLICT DO NOTHING
                            """,
                            tenant,
                            digest,
                            content);
                    UUID id = UUID.randomUUID();
                    int revision = allocateRevision(tenant, base, documentKey, actor, admin);
                    var job =
                            db.one(
                                    """
                                    INSERT INTO ingestion_jobs(tenant,id,actor,request_key,base,document_key,revision,title,roles,digest)
                                    VALUES (?,?,?,?,?,?,?,?,?::jsonb,?) RETURNING
                                    """
                                            + STATUS,
                                    tenant,
                                    id,
                                    actor,
                                    key,
                                    base,
                                    documentKey,
                                    revision,
                                    title,
                                    acl,
                                    digest);
                    labels.insert(db,tenant,id);
                    enqueue(tenant, id, 1);
                    db.audit(tenant, actor, null, "ingestion.queued", id.toString());
                    return job;
                });
    }

    private int allocateRevision(
            String tenant, String base, String key, String actor, boolean admin) {
        // The upsert serializes competing publishers for this document key.
        JsonNode head =
                db.one(
                        """
                        INSERT INTO document_heads(tenant,base,document_key,last_revision,active_revision,active_id,owner_id)
                        SELECT ?,?,?,CASE WHEN d.id IS NULL THEN 1 ELSE 2 END, CASE WHEN d.id IS NULL THEN 0 ELSE 1 END,d.id,
                               CASE WHEN d.id IS NULL THEN ?::text ELSE NULL END
                        FROM (VALUES (1)) seed(x) LEFT JOIN documents d ON d.tenant=? AND d.base=? AND d.document_key IS NULL AND d.id::text=?
                        ON CONFLICT(tenant,base,document_key) DO UPDATE SET last_revision=document_heads.last_revision+1
                        RETURNING last_revision,owner_id
                        """,
                        tenant,
                        base,
                        key,
                        actor,
                        tenant,
                        base,
                        key);
        String owner = head.get("ownerId").textValue();
        if (owner == null) {
            // Resolve ownership of documents created before owner_id was introduced.
            owner =
                    db.one(
                                    """
                                    SELECT coalesce(
                                      (SELECT actor FROM audit_events WHERE tenant=? AND resource=? AND event_type='document.indexed' ORDER BY id LIMIT 1),
                                      (SELECT actor FROM ingestion_jobs WHERE tenant=? AND base=? AND document_key=? ORDER BY revision LIMIT 1)
                                    ) AS owner
                                    """,
                                    tenant,
                                    key,
                                    tenant,
                                    base,
                                    key)
                            .get("owner")
                            .textValue();
            if (owner == null && admin) owner = actor;
            if (owner != null)
                db.jdbc.update(
                        "UPDATE document_heads SET owner_id=? WHERE tenant=? AND base=? AND"
                            + " document_key=?",
                        owner,
                        tenant,
                        base,
                        key);
        }
        if (!admin && !actor.equals(owner))
            throw new StoreException(403, "Document publisher or tenant administrator required");
        return head.required("lastRevision").asInt();
    }

    private void enqueue(String tenant, UUID job, int generation) {
        db.jdbc.update(
                "INSERT INTO ingestion_commands(tenant,job_id,generation) VALUES (?,?,?)",
                tenant,
                job,
                generation);
    }

    JsonNode get(String tenant, String actor, boolean auditor, String base, String id) {
        return db.scoped(
                tenant,
                () ->
                        db.one(
                                "SELECT "
                                        + STATUS
                                        + " FROM ingestion_jobs WHERE tenant=? AND base=? AND id=?"
                                        + " AND (actor=? OR ?)",
                                tenant,
                                base,
                                Database.id(id),
                                actor,
                                auditor));
    }

    JsonNode retry(String tenant, String actor, boolean admin, String base, String id) {
        return db.scoped(
                tenant,
                () -> {
                    var job =
                            db.one(
                                    "SELECT "
                                            + STATUS
                                            + " FROM ingestion_jobs WHERE tenant=? AND base=? AND"
                                            + " id=? AND (actor=? OR ?) FOR UPDATE",
                                    tenant,
                                    base,
                                    Database.id(id),
                                    actor,
                                    admin);
                    if (!job.required("status").asText().equals("FAILED"))
                        throw new StoreException(409, "Only a failed ingestion may be retried");
                    JsonNode queued =
                            db.one(
                                    """
                                    UPDATE ingestion_jobs SET generation=generation+1,status='QUEUED',updated_at=now()
                                    WHERE tenant=? AND id=? RETURNING
                                    """
                                            + STATUS,
                                    tenant,
                                    Database.id(id));
                    enqueue(tenant, Database.id(id), queued.required("generation").asInt());
                    db.audit(tenant, actor, null, "ingestion.retried", id);
                    return queued;
                });
    }

    void index(PlatformStore.IngestionRef job, IntConsumer progress) {
        String tenant = job.tenant();
        UUID id = Database.id(job.jobId());
        JsonNode pending =
                db.scoped(
                        tenant,
                        () -> {
                            var claim =
                                    db.first(
                                            """
                                            UPDATE ingestion_jobs SET status='INDEXING',updated_at=now()
                                            WHERE tenant=? AND id=? AND generation=? AND status IN ('QUEUED','INDEXING')
                                            RETURNING base,document_key,revision,title,roles,digest
                                            """,
                                            tenant,
                                            id,
                                            job.generation());
                            if (claim.isEmpty()) return null;
                            var data = (com.fasterxml.jackson.databind.node.ObjectNode) claim.get();
                            var labels=db.first("SELECT source_uri,classification FROM document_labels WHERE tenant=? AND id=?",tenant,id);
                            labels.ifPresent(row->{data.set("sourceUri",row.get("sourceUri"));data.set("classification",row.get("classification"));});
                            data.set(
                                    "content",
                                    db.one(
                                                    "SELECT content FROM document_artifacts WHERE"
                                                            + " tenant=? AND digest=?",
                                                    tenant,
                                                    data.required("digest").asText())
                                            .required("content"));
                            return data;
                        });
        if (pending == null) return;
        // Embedding I/O is outside the database transaction. Only the winning attempt publishes.
        List<String> roles=new java.util.ArrayList<>();
        pending.required("roles").forEach(role->roles.add(role.asText()));
        var labels=DocumentLabels.from(pending.path("sourceUri").textValue(),pending.path("classification").textValue(),roles);
        var chunks=knowledge.prepare(tenant,pending.required("base").asText(),pending.required("documentKey").asText(),id,
                pending.required("revision").asInt(),roles,labels,pending.required("content").asText(),progress);
        db.scoped(
                tenant,
                () -> {
                    var owned =
                            db.first(
                                    """
                                    SELECT 1 FROM ingestion_jobs WHERE tenant=? AND id=? AND generation=? AND status='INDEXING' FOR UPDATE
                                    """,
                                    tenant,
                                    id,
                                    job.generation());
                    if (owned.isEmpty()) return null;
                    String base = pending.required("base").asText(),
                            key = pending.required("documentKey").asText();
                    int revision = pending.required("revision").asInt();
                    knowledge.insert(
                            tenant,
                            id,
                            base,
                            key,
                            revision,
                            pending.required("title").asText(),
                            pending.required("roles").toString(),
                            pending.required("digest").asText(),
                            chunks);
                    db.jdbc.update(
                            """
                            UPDATE document_heads SET active_revision=?,active_id=?
                            WHERE tenant=? AND base=? AND document_key=? AND greatest(active_revision,retired_revision)<?
                            """,
                            revision,
                            id,
                            tenant,
                            base,
                            key,
                            revision);
                    db.jdbc.update(
                            "UPDATE ingestion_jobs SET"
                                    + " status='SUCCEEDED',document_id=?,updated_at=now() WHERE"
                                    + " tenant=? AND id=?",
                            id,
                            tenant,
                            id);
                    db.audit(tenant, "worker", null, "ingestion.completed", id.toString());
                    return null;
                });
    }

    void fail(PlatformStore.IngestionRef job) {
        db.scoped(
                job.tenant(),
                () -> {
                    int changed =
                            db.jdbc.update(
                                    """
                                    UPDATE ingestion_jobs SET status='FAILED',updated_at=now()
                                    WHERE tenant=? AND id=? AND generation=? AND status IN ('QUEUED','INDEXING')
                                    """,
                                    job.tenant(),
                                    Database.id(job.jobId()),
                                    job.generation());
                    if (changed == 1)
                        db.audit(job.tenant(), "worker", null, "ingestion.failed", job.jobId());
                    return null;
                });
    }

    List<PlatformStore.IngestionRef> pending(int limit) {
        if (limit < 1 || limit > 1000)
            throw new IllegalArgumentException("Invalid ingestion batch size");
        return db.jdbc.query(
                """
                SELECT tenant,job_id,generation FROM ingestion_commands WHERE dispatched_at IS NULL ORDER BY created_at,job_id LIMIT ?
                """,
                (rs, n) ->
                        new PlatformStore.IngestionRef(
                                rs.getString(1), rs.getString(2), rs.getInt(3)),
                limit);
    }

    void acknowledge(PlatformStore.IngestionRef job) {
        db.scoped(
                job.tenant(),
                () ->
                        db.jdbc.update(
                                """
                                UPDATE ingestion_commands SET dispatched_at=clock_timestamp()
                                WHERE tenant=? AND job_id=? AND generation=? AND dispatched_at IS NULL
                                """,
                                job.tenant(),
                                Database.id(job.jobId()),
                                job.generation()));
    }
}
