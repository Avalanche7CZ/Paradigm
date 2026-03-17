package eu.avalanche7.paradigm.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.DebugLogger;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PlayerDataStore {
    private static final String PLAYERDATA_DIR = "paradigm/playerdata";
    private static final String LEGACY_FILE = "paradigm/playerdata.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Logger logger;
    private final DebugLogger debugLogger;
    private final IConfig config;

    private final Object lock = new Object();
    private final ConcurrentMap<String, PlayerEntry> cache = new ConcurrentHashMap<>();
    private boolean migrationChecked;

    public PlayerDataStore(Logger logger, DebugLogger debugLogger, IConfig config) {
        this.logger = logger;
        this.debugLogger = debugLogger;
        this.config = config;
    }

    public void ensureExists(IPlayer player) {
        synchronized (lock) {
            PlayerEntry entry = getOrCreateEntry(player);
            saveEntryLocked(entry);
        }
    }

    public HomeEntry setHome(IPlayer player, String homeName, StoredLocation location) {
        if (player == null || location == null) {
            return null;
        }

        String key = normalizeHomeKey(homeName);
        if (key == null) {
            return null;
        }

        synchronized (lock) {
            PlayerEntry entry = getOrCreateEntry(player);
            String visibleName = homeName.trim();
            HomeEntry home = new HomeEntry(visibleName, location);
            entry.getHomes().put(key, home);
            saveEntryLocked(entry);
            return home;
        }
    }

    public HomeEntry getHome(IPlayer player, String homeName) {
        if (player == null) {
            return null;
        }

        String key = normalizeHomeKey(homeName);
        if (key == null) {
            return null;
        }

        synchronized (lock) {
            PlayerEntry entry = getOrCreateEntry(player);
            return entry.getHomes().get(key);
        }
    }

    public boolean deleteHome(IPlayer player, String homeName) {
        if (player == null) {
            return false;
        }

        String key = normalizeHomeKey(homeName);
        if (key == null) {
            return false;
        }

        synchronized (lock) {
            PlayerEntry entry = getOrCreateEntry(player);
            HomeEntry removed = entry.getHomes().remove(key);
            if (removed != null) {
                saveEntryLocked(entry);
                return true;
            }
            return false;
        }
    }

    public List<String> getHomeNames(IPlayer player) {
        if (player == null) {
            return List.of();
        }

        synchronized (lock) {
            PlayerEntry entry = getOrCreateEntry(player);
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, HomeEntry> home : entry.getHomes().entrySet()) {
                HomeEntry value = home.getValue();
                names.add(value != null && value.getName() != null && !value.getName().isBlank() ? value.getName() : home.getKey());
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }
    }

    public void setLastLocation(IPlayer player, StoredLocation location) {
        if (player == null || location == null) {
            return;
        }

        synchronized (lock) {
            PlayerEntry entry = getOrCreateEntry(player);
            entry.setLastLocation(location);
            saveEntryLocked(entry);
        }
    }

    public StoredLocation getLastLocation(IPlayer player) {
        if (player == null) {
            return null;
        }

        synchronized (lock) {
            PlayerEntry entry = getOrCreateEntry(player);
            return entry.getLastLocation();
        }
    }

    private PlayerEntry getOrCreateEntry(IPlayer player) {
        if (player == null || player.getUUID() == null || player.getUUID().isBlank()) {
            return new PlayerEntry();
        }

        migrateLegacyIfNeededLocked();

        String uuid = normalizePlayerKey(player.getUUID());
        if (uuid == null) {
            return new PlayerEntry();
        }

        PlayerEntry entry = cache.computeIfAbsent(uuid, ignored -> loadEntryLocked(uuid, player.getName()));

        if (entry.getUuid() == null || entry.getUuid().isBlank()) {
            entry.setUuid(uuid);
        }
        if (player.getName() != null && !player.getName().isBlank()) {
            entry.setName(player.getName());
        }
        entry.normalize(uuid);
        return entry;
    }

    private PlayerEntry loadEntryLocked(String uuid, String nameHint) {
        Path path = resolvePlayerPath(uuid);
        PlayerEntry fallback = new PlayerEntry();
        fallback.setUuid(uuid);
        fallback.setName(nameHint != null ? nameHint : "");
        fallback.normalize(uuid);

        if (path == null || !Files.exists(path)) {
            return fallback;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            PlayerEntry loaded = gson.fromJson(reader, PlayerEntry.class);
            if (loaded == null) {
                return fallback;
            }
            loaded.setUuid(uuid);
            if (nameHint != null && !nameHint.isBlank()) {
                loaded.setName(nameHint);
            }
            loaded.normalize(uuid);
            return loaded;
        } catch (Exception e) {
            logger.warn("Paradigm: Failed to load playerdata for {}: {}", uuid, e.getMessage());
            debugLogger.debugLog("PlayerDataStore: load failed for " + uuid, e);
            return fallback;
        }
    }

    private void saveEntryLocked(PlayerEntry entry) {
        if (entry == null || entry.getUuid() == null || entry.getUuid().isBlank()) {
            return;
        }
        entry.normalize(entry.getUuid());

        Path path = resolvePlayerPath(entry.getUuid());
        if (path == null) {
            return;
        }

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                gson.toJson(entry, writer);
            }
        } catch (Exception e) {
            logger.warn("Paradigm: Failed to save playerdata for {}: {}", entry.getUuid(), e.getMessage());
            debugLogger.debugLog("PlayerDataStore: save failed for " + entry.getUuid(), e);
        }
    }

    private Path resolvePlayerPath(String uuid) {
        if (config == null) {
            return null;
        }
        return config.resolveConfigPath(PLAYERDATA_DIR + "/" + uuid + ".json");
    }

    private void migrateLegacyIfNeededLocked() {
        if (migrationChecked || config == null) {
            migrationChecked = true;
            return;
        }
        migrationChecked = true;

        Path legacyPath = config.resolveConfigPath(LEGACY_FILE);
        if (legacyPath == null || !Files.exists(legacyPath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(legacyPath, StandardCharsets.UTF_8)) {
            LegacyDataFile legacy = gson.fromJson(reader, LegacyDataFile.class);
            if (legacy == null || legacy.players == null || legacy.players.isEmpty()) {
                return;
            }

            for (Map.Entry<String, PlayerEntry> e : legacy.players.entrySet()) {
                String uuid = normalizePlayerKey(e.getKey());
                if (uuid == null) {
                    continue;
                }
                PlayerEntry entry = e.getValue() != null ? e.getValue() : new PlayerEntry();
                entry.normalize(uuid);
                cache.put(uuid, entry);
                saveEntryLocked(entry);
            }
            debugLogger.debugLog("PlayerDataStore: migrated legacy playerdata.json to per-player files.");
        } catch (Exception e) {
            logger.warn("Paradigm: Failed to migrate legacy playerdata.json: {}", e.getMessage());
            debugLogger.debugLog("PlayerDataStore: migration failed", e);
        }
    }

    public static String normalizePlayerKey(String uuid) {
        if (uuid == null) {
            return null;
        }
        String normalized = uuid.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public static String normalizeHomeKey(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    private static class LegacyDataFile {
        private Map<String, PlayerEntry> players = new LinkedHashMap<>();
    }

    public static class PlayerEntry {
        private String uuid;
        private String name;
        private Map<String, HomeEntry> homes = new LinkedHashMap<>();
        private StoredLocation lastLocation;

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Map<String, HomeEntry> getHomes() {
            return homes;
        }

        public StoredLocation getLastLocation() {
            return lastLocation;
        }

        public void setLastLocation(StoredLocation lastLocation) {
            this.lastLocation = lastLocation;
        }

        public void normalize(String fallbackUuid) {
            if (uuid == null || uuid.isBlank()) {
                uuid = fallbackUuid;
            } else {
                uuid = normalizePlayerKey(uuid);
            }
            if (name == null) {
                name = "";
            } else {
                name = name.trim();
            }
            if (homes == null) {
                homes = new LinkedHashMap<>();
            }

            Map<String, HomeEntry> normalizedHomes = new LinkedHashMap<>();
            for (Map.Entry<String, HomeEntry> entry : homes.entrySet()) {
                String key = normalizeHomeKey(entry.getKey());
                if (key == null) {
                    continue;
                }
                HomeEntry homeEntry = entry.getValue() != null ? entry.getValue() : new HomeEntry();
                homeEntry.normalize(key);
                if (homeEntry.getLocation() != null && homeEntry.getLocation().getWorldId() != null && !homeEntry.getLocation().getWorldId().isBlank()) {
                    normalizedHomes.put(key, homeEntry);
                }
            }
            homes = normalizedHomes;
        }
    }

    public static class HomeEntry {
        private String name;
        private StoredLocation location;

        public HomeEntry() {
        }

        public HomeEntry(String name, StoredLocation location) {
            this.name = name;
            this.location = location;
        }

        public String getName() {
            return name;
        }

        public StoredLocation getLocation() {
            return location;
        }

        public void normalize(String fallbackName) {
            if (name == null || name.isBlank()) {
                name = fallbackName;
            } else {
                name = name.trim();
            }
        }
    }

    public static class StoredLocation {
        private String worldId;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        public StoredLocation() {
        }

        public StoredLocation(String worldId, double x, double y, double z, float yaw, float pitch) {
            this.worldId = worldId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public String getWorldId() {
            return worldId;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getZ() {
            return z;
        }

        public float getYaw() {
            return yaw;
        }

        public float getPitch() {
            return pitch;
        }
    }
}



