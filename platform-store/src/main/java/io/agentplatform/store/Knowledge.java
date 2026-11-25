package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import io.agentplatform.spi.KnowledgeProvider;
import java.util.List;
import java.util.UUID;

final class Knowledge {
    static final String ACTIVE =
            """
            ((d.document_key IS NULL AND NOT EXISTS (SELECT 1 FROM document_heads h WHERE h.tenant=d.tenant
              AND h.base=d.base AND h.document_key=d.id::text AND (h.active_id IS DISTINCT FROM d.id OR h.active_revision<=h.retired_revision)))
             OR EXISTS (SELECT 1 FROM document_heads h WHERE h.tenant=d.tenant
              AND h.base=d.base AND h.document_key=d.document_key AND h.active_id=d.id AND h.active_revision>h.retired_revision))
            """;
    static final String VISIBLE =
            """
            (d.roles='[]'::jsonb OR jsonb_exists_any(d.roles,ARRAY(SELECT jsonb_array_elements_text(?::jsonb))))
            """;
    private final Database db;
    final KnowledgeProvider provider;

    Knowledge(Database db, KnowledgeProvider provider) {
        this.db = db;
        this.provider = provider;
        Database.requireText(provider.revision(),"knowledge index revision",200);
    }

    static String roleArray(Database db, List<String> roles) {
        if (roles == null || roles.size() > 128) throw new StoreException(400, "Invalid roles");
        roles.forEach(role -> Database.requireText(role, "role", 128));
        return db.mapper.valueToTree(roles.stream().distinct().sorted().toList()).toString();
    }

    JsonNode ingest(
            String tenant,
            String actor,
            String base,
            String title,
            String text,
            List<String> roles,DocumentLabels labels) {
        Database.requireText(base, "knowledge base", 128);
        Database.requireText(title, "document title", 512);
        Database.requireText(text, "document content", 262144);
        String acl = roleArray(db, roles);
        UUID doc = UUID.randomUUID();
        String key = doc.toString();
        var chunks = prepare(tenant,base,key,doc,1,roles,labels,text,ignored->{});
        return db.scoped(
                tenant,
                () -> {
                    JsonNode document =
                            insert(
                                    tenant,
                                    doc,
                                    base,
                                    key,
                                    1,
                                    title,
                                    acl,
                                    Database.digest(text),
                                    chunks);
                    db.jdbc.update(
                            "INSERT INTO"
                                + " document_heads(tenant,base,document_key,last_revision,active_revision,active_id,owner_id)"
                                + " VALUES (?,?,?,1,1,?,?)",
                            tenant,
                            base,
                            key,
                            doc,
                            actor);
                    labels.insert(db,tenant,doc);
                    ((com.fasterxml.jackson.databind.node.ObjectNode)document).put("sourceUri",labels.sourceUri()==null?"internal://documents/"+doc:labels.sourceUri()).put("classification",labels.classification());
                    db.audit(tenant, actor, null, "document.indexed", doc.toString());
                    return document;
                });
    }

    List<Object[]> prepare(
            String tenant,String base,String key,UUID doc,int revision,List<String> roles,DocumentLabels labels,String text,java.util.function.IntConsumer progress) {
        var texts=new ArrayList<String>();
        progress.accept(0);
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(text.length(), offset + 1200);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            String chunk = text.substring(offset, end);
            texts.add(chunk);
            if (end == text.length()) break;
            offset = end - 120;
            if (Character.isLowSurrogate(text.charAt(offset))) offset++;
        }
        var request=new KnowledgeProvider.IndexRequest(tenant,base,key,doc,revision,roles,
            labels.sourceUri()==null?"internal://documents/"+doc:labels.sourceUri(),labels.classification(),texts);
        var result=provider.index(request,progress);
        if(result==null || !result.vectors().isEmpty() && result.vectors().size()!=texts.size())
            throw new StoreException(502,"Knowledge provider returned an incomplete index");
        var chunks=new ArrayList<Object[]>(texts.size());
        for(int i=0;i<texts.size();i++){
            String vector=result.vectors().isEmpty()?null:PgVectorKnowledgeProvider.encode(result.vectors().get(i));
            chunks.add(new Object[]{tenant,doc,i,texts.get(i),vector});
        }
        return chunks;
    }

    JsonNode insert(
            String tenant,
            UUID doc,
            String base,
            String key,
            int revision,
            String title,
            String acl,
            String digest,
            List<Object[]> chunks) {
        String model = Database.requireText(provider.revision(), "embedding model", 200);
        JsonNode record =
                db.one(
                        """
                        INSERT INTO documents(tenant,id,base,title,roles,digest,embedding_model,document_key,revision)
                        VALUES (?,?,?,?,?::jsonb,?,?,?,?) RETURNING id,id AS document_id,base,title,roles,digest,embedding_model,document_key,revision,created_at
                        """,
                        tenant,
                        doc,
                        base,
                        title,
                        acl,
                        digest,
                        model,
                        key,
                        revision);
        db.jdbc.batchUpdate(
                "INSERT INTO chunks(tenant,document_id,chunk_id,content,embedding) VALUES"
                        + " (?,?,?,?,?::vector)",
                chunks);
        return record;
    }

    List<JsonNode> list(String tenant, List<String> roles) {
        String sql =
                "SELECT d.id,d.id AS"
                    + " document_id,d.base,d.title,d.digest,d.embedding_model,coalesce(l.source_uri,'internal://documents/'||d.id) AS source_uri,coalesce(l.classification,CASE WHEN d.roles='[]'::jsonb THEN 'INTERNAL' ELSE 'CONFIDENTIAL' END) AS classification,coalesce(d.document_key,d.id::text)"
                    + " AS document_key,d.revision,d.created_at FROM documents d LEFT JOIN document_labels l ON (l.tenant,l.id)=(d.tenant,d.id) WHERE d.tenant=?"
                    + " AND "
                        + ACTIVE
                        + " AND "
                        + VISIBLE
                        + " ORDER BY d.created_at DESC,d.id LIMIT 200";
        return db.scoped(tenant, () -> db.rows(sql, tenant, roleArray(db, roles)));
    }

    List<JsonNode> search(String tenant, String base, String query, List<String> roles, int limit) {
        return hydrate(tenant,base,roles,limit,candidates(tenant,base,query,roles,limit));
    }

    List<KnowledgeProvider.Hit> candidates(String tenant,String base,String query,List<String> roles,int limit) {
        Database.requireText(query,"search query",8000);
        roleArray(db,roles);
        if(limit<1 || limit>50) throw new StoreException(400,"Search limit outside 1..50");
        return provider.search(new KnowledgeProvider.Query(tenant,base,query,roles,limit));
    }

    List<JsonNode> hydrate(String tenant,String base,List<String> roles,int limit,List<KnowledgeProvider.Hit> hits){
        if(hits==null || hits.size()>200) throw new StoreException(502,"Invalid knowledge candidate count");
        if(hits.isEmpty()) return List.of();
        var ids=db.mapper.createArrayNode();
        for(var hit:hits) {
            if(hit==null || hit.documentId()==null || hit.chunkId()<0 || !Double.isFinite(hit.score())) throw new StoreException(502,"Invalid knowledge candidate");
            ids.addObject().put("id",hit.documentId().toString()).put("chunk",hit.chunkId()).put("score",hit.score());
        }
        String sql="""
            WITH candidates AS (SELECT id,chunk,max(score) score FROM jsonb_to_recordset(?::jsonb)
                    AS candidate(id uuid,chunk integer,score double precision) GROUP BY id,chunk)
            SELECT d.id AS document_id,c.chunk_id,d.title,d.digest,d.base,d.revision,
              coalesce(l.source_uri,'internal://documents/'||d.id) source_uri,
              coalesce(l.classification,CASE WHEN d.roles='[]'::jsonb THEN 'INTERNAL' ELSE 'CONFIDENTIAL' END) classification,
              coalesce(d.document_key,d.id::text) document_key,c.content AS text,k.score
            FROM candidates k JOIN documents d ON d.id=k.id JOIN chunks c ON (c.tenant,c.document_id,c.chunk_id)=(d.tenant,d.id,k.chunk)
            LEFT JOIN document_labels l ON (l.tenant,l.id)=(d.tenant,d.id)
            WHERE d.tenant=? AND d.base=? AND d.embedding_model=? AND
            """+ACTIVE+" AND "+VISIBLE+" ORDER BY k.score DESC,d.id,c.chunk_id LIMIT ?";
        return db.scoped(tenant,()->db.rows(sql,ids.toString(),tenant,base,provider.revision(),roleArray(db,roles),limit));
    }
}
