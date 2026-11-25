package io.agentplatform.application;

public final class ExecutionRejected extends RuntimeException {
    public enum Reason { BUSY, CANCELLED, TIMEOUT, BUDGET_EXCEEDED }

    private final Reason reason;

    public ExecutionRejected(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
