package eu.avalanche7.paradigm.storage.model;

public final class PlayerProfileMutation {
    private PlayerProfileMutation() {}

    public static void requireCompatibleImport(StoredPlayerProfileState existing, StoredPlayerProfileState incoming) {
        boolean existingHistory = hasPlaytimeBreakdown(existing);
        boolean occupied = existing.profile().playtimeMs() > 0L || existingHistory;
        if (occupied && (existingHistory || hasPlaytimeBreakdown(incoming)) && (!existing.playtimeStreams().equals(incoming.playtimeStreams())
                || !existing.serverPlaytimes().equals(incoming.serverPlaytimes()) || !existing.streamServers().equals(incoming.streamServers())
                || existing.unattributedPlaytimeMs() != incoming.unattributedPlaytimeMs())) {
            throw new eu.avalanche7.paradigm.storage.StorageException("Incompatible playtime histories for " + incoming.profile().uuid()
                    + ": migrate into an empty destination or restore a coherent matching profile/watermark snapshot. Independent histories require explicit reconciliation.");
        }
    }

    private static boolean hasPlaytimeBreakdown(StoredPlayerProfileState state) {
        return !state.playtimeStreams().isEmpty() || !state.serverPlaytimes().isEmpty();
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
