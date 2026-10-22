package eu.avalanche7.paradigm.storage.model;

import java.util.Map;

public record StoredPlayerProfileState(StoredPlayerProfile profile, Map<String, Long> playtimeStreams,
                                      long unattributedPlaytimeMs, Map<String, StoredServerPlaytime> serverPlaytimes,
                                      Map<String, String> streamServers) {
    public StoredPlayerProfileState(StoredPlayerProfile profile, Map<String, Long> streams) {
        this(profile, streams, profile != null ? profile.playtimeMs() : 0L, Map.of(), Map.of());
    }
    public StoredPlayerProfileState {
        playtimeStreams = Map.copyOf(playtimeStreams);
        serverPlaytimes = Map.copyOf(serverPlaytimes);
        var scopes = new java.util.HashMap<>(streamServers);
        playtimeStreams.keySet().forEach(writer -> scopes.putIfAbsent(writer, ""));
        streamServers = Map.copyOf(scopes);
        long network = unattributedPlaytimeMs;
        if (network < 0) throw new eu.avalanche7.paradigm.storage.StorageCompatibilityException("Negative unattributed playtime.");
        for (var server : serverPlaytimes.values()) network = Math.addExact(network, server.playtimeMs());
        if (profile != null && profile.playtimeMs() != network) throw new eu.avalanche7.paradigm.storage.StorageCompatibilityException("Inconsistent network/server playtime for " + profile.uuid() + "; restore a coherent snapshot before writing.");
    }
    public long localPlaytimeMs(String server) {
        var local = serverPlaytimes.get(server);
        return local != null ? local.playtimeMs() : 0L;
    }
}
