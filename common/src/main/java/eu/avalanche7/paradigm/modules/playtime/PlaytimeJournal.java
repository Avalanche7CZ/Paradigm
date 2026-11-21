package eu.avalanche7.paradigm.modules.playtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;

import eu.avalanche7.paradigm.storage.StorageCompatibilityException;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfileState;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.utils.AtomicFileIO;

final class PlaytimeJournal implements AutoCloseable {
    private final Path path;
    private final String storageIdentity;
    private final String serverId;
    private final Supplier<String> targetIdentity;
    private final Supplier<PlayerRepository> repository;
    private final Gson gson = new GsonBuilder().registerTypeAdapter(Entry.class, (JsonDeserializer<Entry>) (json, type, context) -> {
        if (json.isJsonNull()) return null;
        var entry = json.getAsJsonObject();
        String name = entry.has("name") && !entry.get("name").isJsonNull() ? entry.get("name").getAsString() : null;
        return new Entry(name, entry.get("firstSeenMs").getAsLong(), entry.get("lastSeenMs").getAsLong(), entry.get("cumulativeMs").getAsLong());
    }).create();
    private final String bootWriterId = UUID.randomUUID().toString();
    private FileChannel channel;
    private FileLock lock;
    private Data data;
    private boolean createdHere;
    private boolean identityConflict;
    private String verifiedIdentity;

    PlaytimeJournal(Path path, String storageIdentity) {
        this(path, storageIdentity, null);
    }

    PlaytimeJournal(Path path, String storageIdentity, Supplier<PlayerRepository> repository) {
        this(path, storageIdentity, repository, "default");
    }

    PlaytimeJournal(Path path, String storageIdentity, Supplier<PlayerRepository> repository, String serverId) {
        this(path, storageIdentity, repository, serverId, () -> storageIdentity);
    }

    PlaytimeJournal(Path path, String storageIdentity, Supplier<PlayerRepository> repository,
                    String serverId, Supplier<String> targetIdentity) {
        this.path = path;
        this.targetIdentity = targetIdentity;
        this.storageIdentity = storageIdentity;
        this.repository = repository;
        this.serverId = serverId;
    }

    String serverId() { return serverId; }

    synchronized Data open() {
        if (data != null) return data;
        try {
            AtomicFileIO.createDirectoriesDurable(path.toAbsolutePath().getParent());
            channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) throw new StorageException("Playtime journal is already in use: " + path);
            Data loaded;
            boolean exists = Files.exists(path);
            if (exists) {
                var json = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                if (!json.has("storageIdentity")) throw new StorageException("Incomplete playtime journal: " + path);
                if (json.has("streams")) {
                    if (json.has("writerId") || json.has("players")) throw new StorageException("Ambiguous playtime journal format: " + path);
                    loaded = gson.fromJson(json, Data.class);
                } else if (json.has("writerId") && json.has("players")) {
                    LegacyData legacy = gson.fromJson(json, LegacyData.class);
                    if (legacy.writerId == null || legacy.players == null) throw new StorageException("Invalid legacy playtime journal: " + path);
                    loaded = new Data();
                    loaded.storageIdentity = legacy.storageIdentity;
                    loaded.streams.put(legacy.writerId, legacy.players);
                } else throw new StorageException("Incomplete playtime journal: " + path);
            } else loaded = new Data();
            if (loaded == null || loaded.streams == null) throw new StorageException("Invalid playtime journal: " + path);
            if (exists && (loaded.storageIdentity == null || loaded.storageIdentity.isBlank())) throw new StorageException("Missing storage identity in recovered playtime journal: " + path);
            if (loaded.serverId != null && !loaded.serverId.equals(serverId)) throw new StorageCompatibilityException("Playtime journal server identity changed from " + loaded.serverId + " to " + serverId + "; quiesced reconciliation required.");
            boolean legacyFormat = loaded.serverId == null;
            if (legacyFormat) {
                loaded.serverId = serverId;
                loaded.streams.keySet().forEach(writer -> loaded.streamServers.put(writer, ""));
            }
            loaded.streams.forEach((writerId, players) -> {
                if (!UUID.fromString(writerId).toString().equals(writerId)) throw new StorageException("Invalid journal writer: " + writerId);
                String scope = loaded.streamServers.get(writerId);
                if (scope == null || !scope.isEmpty() && !scope.equals(serverId)) throw new StorageException("Invalid journal stream server identity: " + writerId);
                if (players == null) throw new StorageException("Invalid playtime journal stream: " + writerId);
                players.forEach((uuid, entry) -> {
                    if (!UUID.fromString(uuid).toString().equals(uuid) || entry == null || entry.cumulativeMs < 0L) throw new StorageException("Invalid playtime journal counter: " + uuid);
                });
            });
            loaded.streamServers.keySet().retainAll(loaded.streams.keySet());
            if (!exists) {
                loaded.storageIdentity = storageIdentity;
                loaded.awaitingSqlIdentity = isSqlLocator(storageIdentity);
            }
            loaded.streamServers.put(bootWriterId, serverId);
            loaded.writerId = bootWriterId;
            loaded.players = loaded.streams.getOrDefault(bootWriterId, Map.of());
            if (!exists) AtomicFileIO.writeUtf8Durable(path, writer -> gson.toJson(loaded, writer));
            createdHere = !exists;
            data = loaded;
            return data;
        } catch (IOException | RuntimeException failure) {
            try { close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw new StorageException("Cannot open playtime journal: " + path, failure);
        }
    }

    private static boolean isSqlLocator(String identity) {
        return identity.startsWith("sqlite:") || identity.startsWith("mysql:");
    }

    synchronized boolean canSaveMeasurements() {
        Data current = open();
        return !identityConflict && (current.storageIdentity.equals(verifiedIdentity)
                || current.storageIdentity.equals(storageIdentity)
                && (!isSqlLocator(storageIdentity) || createdHere));
    }

    void verifyTarget() {
        Data current = open();
        try {
            String target = targetIdentity.get();
            if (target == null || target.isBlank()) throw new StorageException("Authoritative storage identity is unavailable.");
            if (!target.equals(current.storageIdentity)) {
                verifyIdentityChange(current, target);
                synchronized (this) {
                    Data next = copy(data);
                    next.storageIdentity = target;
                    next.awaitingSqlIdentity = false;
                    replace(next);
                }
            }
            synchronized (this) {
                verifiedIdentity = target;
                identityConflict = false;
            }
        } catch (StorageCompatibilityException failure) {
            synchronized (this) { identityConflict = true; }
            throw failure;
        }
    }

    private void verifyIdentityChange(Data current, String target) {
        if (current.awaitingSqlIdentity && target.startsWith("sql:") && current.storageIdentity.equals(storageIdentity)
                && (createdHere || current.streams.values().stream().flatMap(players -> players.values().stream())
                        .noneMatch(entry -> entry.cumulativeMs() > 0L))) return;
        if (current.storageIdentity.startsWith("sql:") || repository == null)
            throw identityConflict(current, target, "Logical database ownership could not be established.");

        var states = new HashMap<String, StoredPlayerProfileState>();
        boolean evidence = false;
        for (var stream : current.streams.entrySet()) {
            if (stream.getKey().equals(bootWriterId)) continue;
            for (var player : stream.getValue().entrySet()) {
                var state = states.computeIfAbsent(player.getKey(), uuid -> repository.get().readPlaytime(uuid));
                long saved = player.getValue().cumulativeMs();
                long watermark = state.playtimeStreams().getOrDefault(stream.getKey(), 0L);
                if (watermark != saved || state.playtimeStreams().containsKey(stream.getKey())
                        && !current.streamServers.get(stream.getKey()).equals(state.streamServers().getOrDefault(stream.getKey(), "")))
                    throw identityConflict(current, target, "writer=" + stream.getKey() + "; player=" + player.getKey()
                            + "; journal watermark=" + saved + "; target watermark=" + watermark + ".");
                evidence |= saved > 0L;
            }
        }
        if (!evidence) throw identityConflict(current, target, "No matching positive watermark proves ownership.");
    }

    private StorageCompatibilityException identityConflict(Data current, String target, String details) {
        return new StorageCompatibilityException("Playtime storage identity conflict: journal=" + current.storageIdentity
                + ", active=" + target + "; server=" + serverId + "; file=" + path + ". " + details
                + " Original journal retained; pending streams require explicit, quiesced migration. Stop writers and back up both storage targets and the journal before reconciliation."
                + " See Playtime journal recovery in README.md.");
    }

    synchronized long pendingMs(String uuid, StoredPlayerProfileState state, boolean localOnly) {
        long pending = 0L;
        for (var stream : data.streams.entrySet()) {
            if (stream.getKey().equals(bootWriterId) || localOnly && !serverId.equals(data.streamServers.get(stream.getKey()))) continue;
            var entry = stream.getValue().get(uuid);
            if (entry != null) pending = Math.addExact(pending, Math.max(0L, entry.cumulativeMs() - (state == null ? 0L : state.playtimeStreams().getOrDefault(stream.getKey(), 0L))));
        }
        return pending;
    }

    synchronized void save(String uuid, Entry entry) {
        saveAll(Map.of(uuid, entry));
    }

    synchronized void saveAll(Map<String, Entry> entries) {
        Data current = open();
        if (!canSaveMeasurements()) throw new StorageCompatibilityException("Cannot attach playtime measurements to an unverified journal: " + path);
        entries.forEach((uuid, entry) -> {
            Entry old = current.players.get(uuid);
            if (old != null && old.cumulativeMs > entry.cumulativeMs) throw new StorageException("Playtime journal counter regressed: " + uuid);
        });
        if (entries.entrySet().stream().allMatch(entry -> entry.getValue().equals(current.players.get(entry.getKey())))) return;
        Data next = copy(current);
        next.players.putAll(entries);
        next.streams.put(bootWriterId, next.players);
        replace(next);
    }

    synchronized void compact(Map<String, StoredPlayerProfileState> acknowledged) {
        Data current = open();
        Data next = copy(current);
        boolean changed = next.streams.entrySet().removeIf(entry -> !entry.getKey().equals(bootWriterId) && entry.getValue().isEmpty());
        for (var stream : next.streams.entrySet()) {
            if (stream.getKey().equals(bootWriterId)) continue;
            var iterator = stream.getValue().entrySet().iterator();
            while (iterator.hasNext()) {
                var player = iterator.next();
                var state = acknowledged.get(player.getKey());
                if (state != null && state.playtimeStreams().getOrDefault(stream.getKey(), 0L) == player.getValue().cumulativeMs()
                        && (!state.playtimeStreams().containsKey(stream.getKey())
                        || current.streamServers.get(stream.getKey()).equals(state.streamServers().get(stream.getKey())))) {
                    iterator.remove(); changed = true;
                }
            }
        }
        if (!changed) return;
        next.streams.entrySet().removeIf(entry -> !entry.getKey().equals(bootWriterId) && entry.getValue().isEmpty());
        next.streamServers.keySet().removeIf(writer -> !writer.equals(bootWriterId) && !next.streams.containsKey(writer));
        replace(next);
    }

    private static Data copy(Data current) {
        Data next = new Data();
        next.writerId = current.writerId;
        next.storageIdentity = current.storageIdentity;
        next.serverId = current.serverId;
        next.awaitingSqlIdentity = current.awaitingSqlIdentity;
        next.streamServers = new LinkedHashMap<>(current.streamServers);
        current.streams.forEach((writer, players) -> next.streams.put(writer, new LinkedHashMap<>(players)));
        next.players = new LinkedHashMap<>(current.players);
        if (next.streams.containsKey(next.writerId)) next.players = next.streams.get(next.writerId);
        return next;
    }

    private void replace(Data next) {
        try {
            AtomicFileIO.writeUtf8Durable(path, writer -> gson.toJson(next, writer));
            data = next;
        } catch (IOException failure) {
            throw new StorageException("Cannot persist playtime journal; replacement outcome may be uncertain: " + path, failure);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        data = null;
        verifiedIdentity = null;
        identityConflict = false;
        try {
            if (lock != null) lock.release();
        } finally {
            lock = null;
            if (channel != null) channel.close();
            channel = null;
        }
    }

    static final class Data {
        transient String writerId;
        transient Map<String, Entry> players;
        String storageIdentity;
        String serverId;
        boolean awaitingSqlIdentity;
        Map<String, String> streamServers = new LinkedHashMap<>();
        Map<String, Map<String, Entry>> streams = new LinkedHashMap<>();
    }

    private static final class LegacyData {
        String writerId;
        String storageIdentity;
        Map<String, Entry> players;
    }

    record Entry(String name, long firstSeenMs, long lastSeenMs, long cumulativeMs) {}
}
