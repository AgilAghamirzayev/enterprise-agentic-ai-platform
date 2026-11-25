package io.agentplatform.spi;

import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

/** Trusted deployment extension. PostgreSQL authorizes each returned document and supplies its text. */
public interface KnowledgeProvider {
    /** Change this identifier when the embedding model, dimensions or index semantics change. */
    String revision();

    record IndexRequest(String tenant,String base,String documentKey,UUID documentId,int version,
                        List<String> roles,String sourceUri,String classification,List<String> chunks) {
        public IndexRequest {roles=List.copyOf(roles);chunks=List.copyOf(chunks);}
    }
    /** The built-in provider returns vectors for the local index. An external index returns managed(). */
    record IndexResult(List<float[]> vectors) {
        public IndexResult {vectors=List.copyOf(vectors);}
        public static IndexResult managed(){return new IndexResult(List.of());}
    }
    record Query(String tenant,String base,String text,List<String> roles,int limit) {
        public Query {roles=List.copyOf(roles);}
    }
    record Hit(UUID documentId,int chunkId,double score) {}
    record Retirement(String tenant,String base,String documentKey,int throughVersion) {}

    /** Idempotent by immutable document ID. Report progress when completing remote batches. */
    IndexResult index(IndexRequest request, IntConsumer progress);
    List<Hit> search(Query query);
    /** Repeated calls must preserve versions above the supplied watermark. */
    void retire(Retirement document);
}
