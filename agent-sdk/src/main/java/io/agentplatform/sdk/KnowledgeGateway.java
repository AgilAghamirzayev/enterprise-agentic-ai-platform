package io.agentplatform.sdk;

import java.util.List;

/** Retrieval within the knowledge base and ACLs bound to the current invocation. */
@FunctionalInterface
public interface KnowledgeGateway {
    List<Chunk> search(String query, int limit);

    record Chunk(String documentId, int chunkId, String title, String digest, String text) {}
}
