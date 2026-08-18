package eu.avalanche7.paradigm.modules.profile;

import java.util.Locale;
import java.util.Optional;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;

public final class PlayerProfileService {
    private final Services services;
    private final Object mutationLock = new Object();

    public PlayerProfileService(Services services) {
        this.services = services;
    }

    public Optional<StoredPlayerProfile> find(String uuid) {
        String key = normalize(uuid);
        if (key == null || services == null || services.getStorageService() == null) {
            return Optional.empty();
        }
        return services.getStorageService().players().getProfile(key).filter(PlayerProfileService::hasData);
    }

    public Optional<StoredPlayerProfile> findByNameOrUuid(String input) {
        String value = input != null ? input.trim() : "";
        if (value.isEmpty() || services == null || services.getStorageService() == null) {
            return Optional.empty();
        }
        if (isUuid(value)) {
            Optional<StoredPlayerProfile> direct = find(value);
            if (direct.isPresent()) {
                return direct;
            }
        }
        return services.getStorageService().players().listProfiles().stream()
                .filter(profile -> profile != null && hasData(profile)
                        && (value.equalsIgnoreCase(profile.uuid()) || value.equalsIgnoreCase(profile.name())))
                .findFirst();
    }

    public static boolean hasData(StoredPlayerProfile profile) {
        return profile != null
                && (profile.name() != null && !profile.name().isBlank()
                || profile.firstSeenMs() > 0L
                || profile.lastSeenMs() > 0L
                || profile.playtimeMs() > 0L);
    }

    private static boolean isUuid(String value) {
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public StoredPlayerProfile merge(String uuid, String name, boolean touchSeen, long playtimeDeltaMs) {
        String key = normalize(uuid);
        if (key == null || services == null || services.getStorageService() == null) {
            return null;
        }
        synchronized (mutationLock) {
            StoredPlayerProfile existing = services.getStorageService().players().getProfile(key).orElse(null);
            StoredPlayerProfile updated = merged(existing, key, name, touchSeen, playtimeDeltaMs, System.currentTimeMillis());
            services.getStorageService().players().upsertProfile(updated);
            return updated;
        }
    }

    public static StoredPlayerProfile merged(StoredPlayerProfile existing, String uuid, String name,
                                             boolean touchSeen, long playtimeDeltaMs, long nowMs) {
        long firstSeen = existing != null && existing.firstSeenMs() > 0L ? existing.firstSeenMs() : nowMs;
        long lastSeen = existing != null ? existing.lastSeenMs() : 0L;
        if (touchSeen || lastSeen <= 0L) {
            lastSeen = nowMs;
        }
        long playtime = existing != null ? existing.playtimeMs() : 0L;
        if (playtimeDeltaMs > 0L) {
            playtime += playtimeDeltaMs;
        }
        String resolvedName = name != null && !name.isBlank()
                ? name
                : existing != null ? existing.name() : null;
        return new StoredPlayerProfile(uuid, resolvedName, firstSeen, lastSeen, playtime);
    }

    public void mergeAsync(String operation, String uuid, String name, boolean touchSeen, long playtimeDeltaMs) {
        if (normalize(uuid) == null || services == null || services.getStorageService() == null) {
            return;
        }
        services.getStorageService().runStorageAsync(operation, () -> merge(uuid, name, touchSeen, playtimeDeltaMs));
    }

    private static String normalize(String uuid) {
        if (uuid == null) {
            return null;
        }
        String value = uuid.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }
}
