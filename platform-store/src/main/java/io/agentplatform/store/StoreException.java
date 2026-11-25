package io.agentplatform.store;

public final class StoreException extends RuntimeException {
    private final int status;

    public StoreException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
