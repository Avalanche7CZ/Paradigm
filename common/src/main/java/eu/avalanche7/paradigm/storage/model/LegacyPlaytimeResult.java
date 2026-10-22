package eu.avalanche7.paradigm.storage.model;

public record LegacyPlaytimeResult(String uuid, String name, long historicalMs, long existingMs,
                                   long totalMs, boolean alreadyImported, long unattributedMs, String skippedReason) {
    public static final String WRITER_ID = "legacy:gtnh:stats:v1";

    public static String writerId(String serverId) {
        return java.util.UUID.nameUUIDFromBytes(("paradigm:world-stats:v1:" + serverId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    public static boolean hasHistoricalImport(java.util.Map<String, String> scopes) {
        return scopes.entrySet().stream().anyMatch(entry -> WRITER_ID.equals(entry.getKey())
                || !entry.getValue().isEmpty() && writerId(entry.getValue()).equals(entry.getKey()));
    }
    public long addedMs() { return totalMs - existingMs; }
}
