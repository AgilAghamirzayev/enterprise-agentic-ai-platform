package io.agentplatform.spi;

/** Usage supplied by the provider or conservatively estimated by its adapter. */
public record ModelResult(String text, int inputTokens, int outputTokens) {
    public ModelResult {
        if (text == null || text.isBlank() || text.length() > 1_048_576 || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("Invalid model result");
        }
    }

    public long totalTokens() {
        return (long) inputTokens + outputTokens;
    }

    @Override
    public String toString() {
        return "ModelResult[inputTokens=" + inputTokens + ", outputTokens=" + outputTokens + "]";
    }
}
