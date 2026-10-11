package eu.avalanche7.paradigm.storage.model;

public final class PlayerProfileMutation {
    private PlayerProfileMutation() {}

    public static void requireCompatibleImport(StoredPlayerProfileState existing, StoredPlayerProfileState incoming) {
        boolean occupied = existing.profile().playtimeMs() > 0L || !existing.playtimeStreams().isEmpty();
        boolean tracked = !existing.playtimeStreams().isEmpty() || !incoming.playtimeStreams().isEmpty();
        if (occupied && tracked && !existing.playtimeStreams().equals(incoming.playtimeStreams())) {
            throw new eu.avalanche7.paradigm.storage.StorageException("Incompatible playtime histories for " + incoming.profile().uuid()
                    + ": migrate into an empty destination or restore a coherent matching profile/watermark snapshot. Independent histories require explicit reconciliation.");
        }
    }

    public static StoredPlayerProfile merge(StoredPlayerProfile old, StoredPlayerProfile incoming, boolean increment) {
        long first = old != null ? old.firstSeenMs() : 0L;
        if (incoming.firstSeenMs() > 0L && (first <= 0L || incoming.firstSeenMs() < first)) first = incoming.firstSeenMs();
        long last = Math.max(old != null ? old.lastSeenMs() : 0L, incoming.lastSeenMs());
        String name = old != null ? old.name() : null;
        if (incoming.name() != null && !incoming.name().isBlank()
                && (name == null || name.isBlank() || old == null || incoming.lastSeenMs() >= old.lastSeenMs())) name = incoming.name();
        long total = old != null ? old.playtimeMs() : 0L;
        total = increment ? Math.addExact(total, incoming.playtimeMs()) : Math.max(total, incoming.playtimeMs());
        return new StoredPlayerProfile(incoming.uuid(), name, first, last, total);
    }
}
