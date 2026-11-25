package io.agentplatform.spi;

import java.util.Objects;

/** Additional deployment policy for an exact proposed tool invocation. */
@FunctionalInterface
public interface PolicyEngine {
    Decision evaluate(Request request);

    record Request(String tenant, String runId, String step, String tool, String argumentsJson) {}

    enum Effect { ALLOW, REQUIRE_APPROVAL, DENY }

    record Decision(Effect effect, String requiredRole) {
        public Decision {
            Objects.requireNonNull(effect);
            if (effect == Effect.REQUIRE_APPROVAL && (requiredRole == null || requiredRole.isBlank()))
                throw new IllegalArgumentException("Approval requires a role");
        }
    }
}
