package eu.avalanche7.paradigm.storage.model;

public record StoredServerPlaytime(String uuid, String serverId, long playtimeMs, long firstSeenMs, long lastSeenMs) {}
