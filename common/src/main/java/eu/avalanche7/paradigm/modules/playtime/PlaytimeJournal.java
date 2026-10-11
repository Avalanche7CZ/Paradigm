package eu.avalanche7.paradigm.modules.playtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;

import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.utils.AtomicFileIO;

final class PlaytimeJournal implements AutoCloseable {
    private final Path path;
    private final String storageIdentity;
    private final java.util.function.Supplier<eu.avalanche7.paradigm.storage.repository.PlayerRepository> repository;
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

    PlaytimeJournal(Path path, String storageIdentity) {
        this(path, storageIdentity, null);
    }

    PlaytimeJournal(Path path, String storageIdentity, java.util.function.Supplier<eu.avalanche7.paradigm.storage.repository.PlayerRepository> repository) {
        this.path = path;
        this.storageIdentity = storageIdentity;
        this.repository = repository;
    }

    synchronized Data open() {
        if (data != null) return data;
        try {
            AtomicFileIO.createDirectoriesDurable(path.toAbsolutePath().getParent());
            channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) throw new StorageException("Playtime journal is already in use: " + path);
            Data loaded;
            if (Files.exists(path)) {
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
            if (Files.exists(path) && (loaded.storageIdentity == null || loaded.storageIdentity.isBlank())) throw new StorageException("Missing storage identity in recovered playtime journal: " + path);
            loaded.streams.forEach((writerId, players) -> {
                UUID.fromString(writerId);
                if (players == null) throw new StorageException("Invalid playtime journal stream: " + writerId);
                players.forEach((uuid, entry) -> {
                    if (entry == null || entry.cumulativeMs < 0L) throw new StorageException("Invalid playtime journal counter: " + uuid);
                });
            });
            if (loaded.storageIdentity != null && !loaded.storageIdentity.equals(storageIdentity)) {
                if (repository == null || !loaded.streams.entrySet().stream().allMatch(stream -> stream.getValue().entrySet().stream().allMatch(entry ->
                        repository.get().getProfileState(entry.getKey()).playtimeStreams().getOrDefault(stream.getKey(), 0L) == entry.getValue().cumulativeMs()))) {
                    throw new StorageException("Playtime storage target changed; pending journal requires an explicit, quiesced migration: " + path);
                }
            }
            boolean changed = !Files.exists(path) || !java.util.Objects.equals(loaded.storageIdentity, storageIdentity);
            loaded.storageIdentity = storageIdentity;
            loaded.writerId = bootWriterId;
            loaded.players = loaded.streams.getOrDefault(bootWriterId, Map.of());
            if (changed) AtomicFileIO.writeUtf8Durable(path, writer -> gson.toJson(loaded, writer));
            data = loaded;
            return data;
        } catch (IOException | RuntimeException failure) {
            try { close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw new StorageException("Cannot open playtime journal: " + path, failure);
        }
    }

    synchronized void save(String uuid, Entry entry) {
        saveAll(Map.of(uuid, entry));
    }

    synchronized void saveAll(Map<String, Entry> entries) {
        Data current = open();
        entries.forEach((uuid, entry) -> {
            Entry old = current.players.get(uuid);
            if (old != null && old.cumulativeMs > entry.cumulativeMs) throw new StorageException("Playtime journal counter regressed: " + uuid);
        });
        if (entries.entrySet().stream().allMatch(entry -> entry.getValue().equals(current.players.get(entry.getKey())))) return;
        Data next = new Data();
        next.writerId = current.writerId;
        next.storageIdentity = current.storageIdentity;
        next.streams = new LinkedHashMap<>(current.streams);
        next.players = new LinkedHashMap<>(current.players);
        next.players.putAll(entries);
        next.streams.put(bootWriterId, next.players);
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
        Map<String, Map<String, Entry>> streams = new LinkedHashMap<>();
    }

    private static final class LegacyData {
        String writerId;
        String storageIdentity;
        Map<String, Entry> players;
    }

    record Entry(String name, long firstSeenMs, long lastSeenMs, long cumulativeMs) {}
}
