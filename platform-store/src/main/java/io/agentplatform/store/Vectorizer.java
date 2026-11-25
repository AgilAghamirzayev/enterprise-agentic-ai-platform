package io.agentplatform.store;

/** Embeddings for one immutable model revision. Ingestion and search must use the same revision. */
public interface Vectorizer {
    String model();

    float[] embed(String text);
}
