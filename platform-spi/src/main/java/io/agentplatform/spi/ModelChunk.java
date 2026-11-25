package io.agentplatform.spi;

/** Text delta and any cumulative token counts supplied by the provider for this chunk. */
public record ModelChunk(String text, int inputTokens, int outputTokens) {
    public ModelChunk {
        if (text == null || text.length() > 1_048_576 || inputTokens < 0 || outputTokens < 0)
            throw new IllegalArgumentException("Invalid model chunk");
    }

    @Override
    public String toString() {
        return "ModelChunk[inputTokens=" + inputTokens + ", outputTokens=" + outputTokens + "]";
    }
}
