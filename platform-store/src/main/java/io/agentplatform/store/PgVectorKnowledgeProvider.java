package io.agentplatform.store;

import io.agentplatform.spi.KnowledgeProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

final class PgVectorKnowledgeProvider implements KnowledgeProvider {
    private final Database db;
    private final Vectorizer embeddings;
    PgVectorKnowledgeProvider(Database db,Vectorizer embeddings){this.db=db;this.embeddings=embeddings;}

    @Override public String revision(){return embeddings.model();}
    @Override public IndexResult index(IndexRequest request,IntConsumer progress) {
        var vectors=new ArrayList<float[]>(request.chunks().size());
        for(String text:request.chunks()){
            vectors.add(embeddings.embed(text));progress.accept(vectors.size());
        }
        return new IndexResult(vectors);
    }

    static String encode(float[] values) {
        if(values==null || values.length==0 || values.length>16000) throw new StoreException(502,"Invalid embedding dimensions");
        for(float v:values) if(!Float.isFinite(v)) throw new StoreException(502,"Non-finite embedding");
        return Arrays.toString(values);
    }

    @Override public List<Hit> search(Query query) {
        String vector=encode(embeddings.embed(query.text()));
        String sql="""
            WITH q AS (SELECT plainto_tsquery('simple',?) AS tokens,?::vector AS embedding), scores AS (
            SELECT d.id,c.chunk_id,ts_rank_cd(c.lexemes,q.tokens) AS keyword_score,
               CASE WHEN vector_norm(c.embedding)=0 OR vector_norm(q.embedding)=0 THEN 0 ELSE greatest(0,1-(c.embedding<=>q.embedding)) END similarity
             FROM documents d JOIN chunks c ON c.tenant=d.tenant AND c.document_id=d.id CROSS JOIN q
             WHERE d.tenant=? AND d.base=? AND d.embedding_model=? AND
            """+Knowledge.ACTIVE+" AND "+Knowledge.VISIBLE+"""
             ) SELECT id,chunk_id,0.6*similarity+0.4*keyword_score AS score FROM scores WHERE similarity>0 OR keyword_score>0
             ORDER BY score DESC,id,chunk_id LIMIT ?
             """;
        return db.scoped(query.tenant(),()->db.jdbc.query(sql,
            (rs,i)->new Hit(rs.getObject("id",UUID.class),rs.getInt("chunk_id"),rs.getDouble("score")),
            query.text(),vector,query.tenant(),query.base(),revision(),Knowledge.roleArray(db,query.roles()),query.limit()));
    }

    @Override public void retire(Retirement document) {
        // PostgreSQL searches join the active pointer. The store has committed its retirement watermark.
    }
}
