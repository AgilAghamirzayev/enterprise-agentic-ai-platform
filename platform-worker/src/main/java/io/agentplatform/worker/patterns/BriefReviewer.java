package io.agentplatform.worker.patterns;

import io.agentplatform.application.patterns.EvaluatorOptimizer;
import io.agentplatform.boot.AiAgent;

@AiAgent(name = "brief-reviewer", version = "1.0.0")
public final class BriefReviewer extends EvaluatorOptimizer {
    public BriefReviewer() {
        super(
                "The answer covers the request, uses its evidence and identifies missing"
                    + " information.",
                2,
                512);
    }
}
