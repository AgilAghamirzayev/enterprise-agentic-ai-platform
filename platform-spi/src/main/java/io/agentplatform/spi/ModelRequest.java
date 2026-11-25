package io.agentplatform.spi;

import java.nio.charset.StandardCharsets;

/** System instructions and untrusted input travel in separate model messages. */
public record ModelRequest(String system, String input, int maxOutputTokens, Double temperature) {
    public ModelRequest(String system, String input, int maxOutputTokens) {
        this(system, input, maxOutputTokens, null);
    }

    public ModelRequest {
        if (system == null
                || system.isBlank()
                || input == null
                || input.isBlank()
                || system.length() + (long) input.length() > 262_144
                || (temperature != null
                        && (!Double.isFinite(temperature) || temperature < 0 || temperature > 2))
                || maxOutputTokens < 1
                || maxOutputTokens > 131_072) {
            throw new IllegalArgumentException("Invalid model request");
        }
    }

    /** Conservative reservation for text and ordinary chat message framing. */
    public int estimatedInputTokens() {
        return 32
                + system.getBytes(StandardCharsets.UTF_8).length
                + input.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public String toString() {
        return "ModelRequest[maxOutputTokens=" + maxOutputTokens + "]";
    }
}
