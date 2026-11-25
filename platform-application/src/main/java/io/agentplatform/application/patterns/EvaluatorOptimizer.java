package io.agentplatform.application.patterns;

import io.agentplatform.sdk.Agent;
import io.agentplatform.sdk.AgentExecutionContext;
import io.agentplatform.spi.ModelRequest;
import java.util.Objects;

/** Runs a bounded draft-review-revision loop against a deployment-owned rubric. */
public class EvaluatorOptimizer implements Agent<String, EvaluatorOptimizer.Result> {
    private final String rubric;
    private final int maxRevisions;
    private final int maxOutputTokens;

    public record Result(String draft, boolean accepted, int revisions, String feedback) {}

    public EvaluatorOptimizer(String rubric, int maxRevisions, int maxOutputTokens) {
        if (Objects.requireNonNull(rubric).isBlank() || rubric.length() > 4096 || maxRevisions < 0 || maxRevisions > 4
                || maxOutputTokens < 1 || maxOutputTokens > 8192) {
            throw new IllegalArgumentException("Invalid review configuration");
        }
        this.rubric = rubric;
        this.maxRevisions = maxRevisions;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public Result execute(String task, AgentExecutionContext context) {
        var draft = context.models().complete(new ModelRequest("Draft an answer to the request.", task, maxOutputTokens)).text();
        String instruction = "Evaluate the draft against this rubric: " + rubric
                + "\nReturn ACCEPT or REVISE followed by a newline and specific editorial feedback.";
        for (int revisions = 0; revisions <= maxRevisions; revisions++) {
            context.checkCancellation();
            String material = "Request:\n" + task + "\nDraft:\n" + draft;
            String verdict = context.models().complete(new ModelRequest(instruction, material, Math.min(256, maxOutputTokens))).text()
                    .replace("\r\n", "\n").strip();
            if (verdict.equals("ACCEPT")) return new Result(draft, true, revisions, "");
            if (!verdict.startsWith("REVISE\n") || verdict.length() > 2055) {
                throw new IllegalArgumentException("Model returned an invalid review verdict");
            }
            String feedback = verdict.substring(7).strip();
            if (feedback.isEmpty()) throw new IllegalArgumentException("Missing review feedback");
            if (revisions == maxRevisions) return new Result(draft, false, revisions, feedback);
            draft = context.models().complete(new ModelRequest("Revise the draft using the review feedback.",
                    material + "\nFeedback:\n" + feedback, maxOutputTokens)).text();
        }
        throw new IllegalStateException("Review iteration invariant violated");
    }
}
