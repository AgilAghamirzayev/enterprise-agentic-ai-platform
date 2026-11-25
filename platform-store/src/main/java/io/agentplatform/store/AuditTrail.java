package io.agentplatform.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

final class AuditTrail {
    private static final String COLUMNS="""
        id,event_uuid,tenant,actor,run_id,event_type,resource,resource_type,policy_version,
        trace_id,payload_hash,occurred_at,chain_position,previous_hash,event_hash
        """;
    private final Database db;
    AuditTrail(Database db) { this.db=db; }

    List<JsonNode> search(String tenant,String query,Long before,int limit) {
        if(limit<1 || limit>500 || before!=null && before<1) throw new StoreException(400,"Invalid audit page");
        String q=query==null?"":query.strip().toLowerCase(java.util.Locale.ROOT);
        if(q.length()>200) throw new StoreException(400,"Audit query is too long");
        return db.scoped(tenant,()->db.rows("SELECT "+ COLUMNS + """
             FROM audit_events WHERE tenant=? AND id<? AND occurred_at>=coalesce((SELECT now()-make_interval(days=>r.audit_days) FROM retention_settings r WHERE r.tenant=audit_events.tenant),'-infinity'::timestamptz) AND (?='' OR
              strpos(lower(concat_ws(' ',actor,event_type,resource,run_id::text,trace_id)),?)>0)
             ORDER BY id DESC LIMIT ?
             """,tenant,before==null?Long.MAX_VALUE:before,q,q,limit));
    }

    JsonNode inspect(String tenant,long after,int limit) {
        if(after<0 || limit<1 || limit>1000) throw new StoreException(400,"Invalid chain cursor");
        return db.scoped(tenant, () -> {
            var result=db.mapper.createObjectNode();
            // A single snapshot pins a contiguous, immutable prefix. New appends can be paged later.
            var head=db.first("SELECT position,digest FROM audit_heads WHERE tenant=?",tenant);
            long position=head.map(row->row.path("position").asLong()).orElse(0L);
            if(after>position) throw new StoreException(400,"Chain cursor exceeds current head");
            String previous=after==0?"0".repeat(64):db.one("SELECT event_hash FROM audit_events WHERE tenant=? AND chain_position=?",tenant,after).required("eventHash").asText();
            List<JsonNode> entries=db.rows("SELECT "+COLUMNS+",audit_material(e) AS canonical,audit_digest(e)=event_hash AS valid FROM audit_events e WHERE tenant=? AND chain_position>? AND chain_position<=? ORDER BY chain_position LIMIT ?",tenant,after,position,limit);
            var records=result.putArray("events");
            long last=after;
            boolean valid=true;
            for(var row:entries) {
                if(!row.path("valid").asBoolean() || !previous.equals(row.path("previousHash").asText()) || row.path("chainPosition").asLong()!=last+1) valid=false;
                last=row.required("chainPosition").asLong();
                previous=row.path("eventHash").asText();
                records.add(row);
            }
            if(last < position && entries.size()<limit) valid=false;
            String digest=head.map(row->row.path("digest").asText()).orElse("0".repeat(64));
            if(last==position && !digest.equals(previous)) valid=false;
            long unchained=db.jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE tenant=? AND chain_position IS NULL",Long.class,tenant);
            return result.put("valid",valid).put("after",after).put("next",last).put("head",position).put("headHash",digest)
                    .put("endHash",previous).put("legacyEvents",unchained);
        });
    }
}
