package io.agentplatform.domain;

import java.time.Duration;

/** Limits for a trusted Java agent running in the local engine. */
public record ExecutionLimits(int maxModelCalls, long maxTokens, Duration timeout) {
    public ExecutionLimits {
        if (maxModelCalls < 0 || maxTokens < 0 || timeout == null || !timeout.isPositive()
                || timeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Invalid execution limits");
        }
    }

    public static ExecutionLimits defaults() {
        return new ExecutionLimits(4, 16_384, Duration.ofSeconds(30));
    }
}
