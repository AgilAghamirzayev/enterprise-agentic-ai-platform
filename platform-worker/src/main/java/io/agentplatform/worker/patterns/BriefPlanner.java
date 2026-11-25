package io.agentplatform.worker.patterns;

import io.agentplatform.application.patterns.PlannerExecutor;
import io.agentplatform.boot.AiAgent;

import java.util.Map;

@AiAgent(name = "brief-planner", version = "1.0.0")
public final class BriefPlanner extends PlannerExecutor {
    public BriefPlanner() {
        super(
                Map.of(
                        "extract",
                        "Extract relevant facts from the supplied material.",
                        "summarize",
                        "Summarize the material for the requester.",
                        "verify",
                        "Check factual claims against the supplied material. Remove unsupported"
                            + " claims."),
                3,
                512);
    }
}
