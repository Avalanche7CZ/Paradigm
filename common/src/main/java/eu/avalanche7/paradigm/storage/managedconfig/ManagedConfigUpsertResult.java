package eu.avalanche7.paradigm.storage.managedconfig;

public record ManagedConfigUpsertResult(boolean ok, long revision, String conflictReason) {
    public static ManagedConfigUpsertResult ok(long revision) {
        return new ManagedConfigUpsertResult(true, revision, null);
    }

    public static ManagedConfigUpsertResult conflict(long currentRevision, String reason) {
        return new ManagedConfigUpsertResult(false, currentRevision, reason);
    }
}
