package eu.avalanche7.paradigm.storage;

public final class StorageTransactionException extends StorageException {
    public enum Outcome { UNKNOWN, COMMITTED }
    private final Outcome outcome;

    public StorageTransactionException(String message, Throwable cause, Outcome outcome) {
        super(message, cause);
        this.outcome = outcome;
    }

    public Outcome outcome() { return outcome; }
}
