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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    public void setLastSeen(IPlayer player, long timestampMs) {
        if (player == null || player.getUUID() == null || player.getUUID().isBlank()) {
            return;
        }
        synchronized (lock) {
            PlayerEntry entry = getOrCreateEntry(player);
            entry.setLastSeenMs(Math.max(0L, timestampMs));
            saveEntryLocked(entry);
        }
    }

    public Long getLastSeen(String playerUuid) {
        String uuid = normalizePlayerKey(playerUuid);
        if (uuid == null) {
            return null;
        }
        synchronized (lock) {
            PlayerEntry entry = getOrCreateEntryByUuidLocked(uuid, "");
            return entry.getLastSeenMs() > 0L ? entry.getLastSeenMs() : null;
        }
    }

    public boolean addIgnoredPlayer(String ownerUuid, String targetUuid) {
        String owner = normalizePlayerKey(ownerUuid);
        String target = normalizePlayerKey(targetUuid);
        if (owner == null || target == null || owner.equals(target)) {
            return false;
        }
        synchronized (lock) {
            PlayerEntry entry = getOrCreateEntryByUuidLocked(owner, "");
            boolean changed = entry.getIgnoredPlayers().add(target);
            if (changed) {
                entry.normalize(owner);
                saveEntryLocked(entry);
            }
            return changed;
        }
    }

    public boolean removeIgnoredPlayer(String ownerUuid, String targetUuid) {
        String owner = normalizePlayerKey(ownerUuid);
        String target = normalizePlayerKey(targetUuid);
        if (owner == null || target == null) {
            return false;
        }
        synchronized (lock) {
            PlayerEntry entry = getOrCreateEntryByUuidLocked(owner, "");
            boolean changed = entry.getIgnoredPlayers().remove(target);
            if (changed) {
                entry.normalize(owner);
                saveEntryLocked(entry);
            }
            return changed;
        }
    }

    public boolean isIgnoring(String ownerUuid, String targetUuid) {
        String owner = normalizePlayerKey(ownerUuid);
        String target = normalizePlayerKey(targetUuid);
        if (owner == null || target == null) {
            return false;
        }
        synchronized (lock) {
            PlayerEntry entry = getOrCreateEntryByUuidLocked(owner, "");
            return entry.getIgnoredPlayers().contains(target);
        }
    }

    public boolean setTemporaryGroup(String playerUuid, String group, long expiresAtMs, long assignedAtMs, String assignedBy) {
        String uuid = normalizePlayerKey(playerUuid);
        String groupKey = normalizeHomeKey(group);
        if (uuid == null || groupKey == null || expiresAtMs <= 0L) {
            return false;
        }

        synchronized (lock) {
            migrateLegacyIfNeededLocked();
            PlayerEntry entry = getOrCreateEntryByUuidLocked(uuid, "");
            if (entry.getTempGroups() == null) {
                entry.setTempGroups(new ArrayList<>());
            }
            entry.getTempGroups().removeIf(temp -> temp != null && groupKey.equals(normalizeHomeKey(temp.getGroup())));
            entry.getTempGroups().add(new TemporaryGroupEntry(groupKey, expiresAtMs, assignedAtMs > 0L ? assignedAtMs : System.currentTimeMillis(), assignedBy));
            entry.normalize(uuid);
            saveEntryLocked(entry);
            return true;
        }
    }

    public boolean removeTemporaryGroup(String playerUuid, String group) {
        String uuid = normalizePlayerKey(playerUuid);
        String groupKey = normalizeHomeKey(group);
        if (uuid == null || groupKey == null) {
            return false;
        }

        synchronized (lock) {
            migrateLegacyIfNeededLocked();
            PlayerEntry entry = getOrCreateEntryByUuidLocked(uuid, "");
            boolean changed = entry.getTempGroups().removeIf(temp -> temp != null && groupKey.equals(normalizeHomeKey(temp.getGroup())));
            if (changed) {
                entry.normalize(uuid);
                saveEntryLocked(entry);
            }
            return changed;
        }
    }

    public List<TemporaryGroupEntry> getTemporaryGroups(String playerUuid) {
        String uuid = normalizePlayerKey(playerUuid);
        if (uuid == null) {
            return List.of();
        }

        synchronized (lock) {
            migrateLegacyIfNeededLocked();
            PlayerEntry entry = getOrCreateEntryByUuidLocked(uuid, "");
            long now = System.currentTimeMillis();
            boolean removedExpired = entry.getTempGroups().removeIf(temp -> temp == null || temp.getExpiresAtMs() <= now || temp.getGroup() == null || temp.getGroup().isBlank());
            if (removedExpired) {
                entry.normalize(uuid);
                saveEntryLocked(entry);
            }
            return List.copyOf(entry.getTempGroups());
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

        PlayerEntry entry = getOrCreateEntryByUuidLocked(uuid, player.getName());

        if (entry.getUuid() == null || entry.getUuid().isBlank()) {
            entry.setUuid(uuid);
        }
        if (player.getName() != null && !player.getName().isBlank()) {
            entry.setName(player.getName());
        }
        entry.normalize(uuid);
        return entry;
    }

    private PlayerEntry getOrCreateEntryByUuidLocked(String uuid, String nameHint) {
        String normalizedUuid = normalizePlayerKey(uuid);
        if (normalizedUuid == null) {
            return new PlayerEntry();
        }

        PlayerEntry entry = cache.computeIfAbsent(normalizedUuid, ignored -> loadEntryLocked(normalizedUuid, nameHint));
        if (entry.getUuid() == null || entry.getUuid().isBlank()) {
            entry.setUuid(normalizedUuid);
        }
        if (nameHint != null && !nameHint.isBlank()) {
            entry.setName(nameHint);
        }
        entry.normalize(normalizedUuid);
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
        private long lastSeenMs;
        private Set<String> ignoredPlayers = new LinkedHashSet<>();
        private List<TemporaryGroupEntry> tempGroups = new ArrayList<>();

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

        public long getLastSeenMs() {
            return lastSeenMs;
        }

        public void setLastSeenMs(long lastSeenMs) {
            this.lastSeenMs = lastSeenMs;
        }

        public Set<String> getIgnoredPlayers() {
            return ignoredPlayers;
        }

        public List<TemporaryGroupEntry> getTempGroups() {
            return tempGroups;
        }

        public void setTempGroups(List<TemporaryGroupEntry> tempGroups) {
            this.tempGroups = tempGroups;
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
            if (ignoredPlayers == null) {
                ignoredPlayers = new LinkedHashSet<>();
            }
            if (tempGroups == null) {
                tempGroups = new ArrayList<>();
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

            Set<String> normalizedIgnored = new LinkedHashSet<>();
            for (String ignored : ignoredPlayers) {
                String normalized = normalizePlayerKey(ignored);
                if (normalized != null && !normalized.equals(uuid)) {
                    normalizedIgnored.add(normalized);
                }
            }
            ignoredPlayers = normalizedIgnored;

            if (lastSeenMs < 0L) {
                lastSeenMs = 0L;
            }

            Set<String> seenGroups = new LinkedHashSet<>();
            List<TemporaryGroupEntry> normalizedTempGroups = new ArrayList<>();
            for (TemporaryGroupEntry tempGroup : tempGroups) {
                if (tempGroup == null) {
                    continue;
                }
                tempGroup.normalize();
                if (tempGroup.group == null || tempGroup.group.isBlank()) {
                    continue;
                }
                if (tempGroup.expiresAtMs <= System.currentTimeMillis()) {
                    continue;
                }
                if (seenGroups.add(tempGroup.group)) {
                    normalizedTempGroups.add(tempGroup);
                }
            }
            tempGroups = normalizedTempGroups;
        }
    }

    public static class TemporaryGroupEntry {
        private String group;
        private long expiresAtMs;
        private long assignedAtMs;
        private String assignedBy;

        public TemporaryGroupEntry() {
        }

        public TemporaryGroupEntry(String group, long expiresAtMs, long assignedAtMs, String assignedBy) {
            this.group = group;
            this.expiresAtMs = expiresAtMs;
            this.assignedAtMs = assignedAtMs;
            this.assignedBy = assignedBy;
        }

        public String getGroup() {
            return group;
        }

        public long getExpiresAtMs() {
            return expiresAtMs;
        }

        public long getAssignedAtMs() {
            return assignedAtMs;
        }

        public String getAssignedBy() {
            return assignedBy;
        }

        public void normalize() {
            if (group == null) {
                group = "";
            }
            group = normalizeHomeKey(group);
            if (group == null) {
                group = "";
            }
            if (expiresAtMs < 0L) {
                expiresAtMs = 0L;
            }
            if (assignedAtMs < 0L) {
                assignedAtMs = 0L;
            }
            if (assignedBy == null) {
                assignedBy = "";
            } else {
                assignedBy = assignedBy.trim();
            }
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



