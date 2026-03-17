package eu.avalanche7.paradigm.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
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

public class WarpStore {
    private static final String FILE_NAME = "paradigm/warps.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Logger logger;
    private final DebugLogger debugLogger;
    private final IConfig config;

    private final Object lock = new Object();
    private DataFile data;

    public WarpStore(Logger logger, DebugLogger debugLogger, IConfig config) {
        this.logger = logger;
        this.debugLogger = debugLogger;
        this.config = config;
    }

    public WarpEntry upsert(String warpName, PlayerDataStore.StoredLocation location, String permission) {
        String key = normalizeWarpKey(warpName);
        if (key == null || location == null) {
            return null;
        }

        synchronized (lock) {
            DataFile file = loadIfNeeded();
            WarpEntry entry = new WarpEntry();
            entry.name = warpName.trim();
            entry.location = location;
            entry.permission = (permission == null || permission.isBlank()) ? defaultPermissionFor(key) : permission.trim().toLowerCase(Locale.ROOT);
            file.warps.put(key, entry);
            saveLocked();
            return entry;
        }
    }

    public WarpEntry get(String warpName) {
        String key = normalizeWarpKey(warpName);
        if (key == null) {
            return null;
        }
        synchronized (lock) {
            return loadIfNeeded().warps.get(key);
        }
    }

    public boolean delete(String warpName) {
        String key = normalizeWarpKey(warpName);
        if (key == null) {
            return false;
        }

        synchronized (lock) {
            WarpEntry removed = loadIfNeeded().warps.remove(key);
            if (removed != null) {
                saveLocked();
                return true;
            }
            return false;
        }
    }

    public List<String> listNames() {
        synchronized (lock) {
            List<String> names = new ArrayList<>();
            for (Map.Entry<String, WarpEntry> entry : loadIfNeeded().warps.entrySet()) {
                WarpEntry w = entry.getValue();
                names.add(w != null && w.name != null && !w.name.isBlank() ? w.name : entry.getKey());
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names;
        }
    }

    public String permissionFor(String warpName) {
        String key = normalizeWarpKey(warpName);
        if (key == null) {
            return null;
        }
        synchronized (lock) {
            WarpEntry entry = loadIfNeeded().warps.get(key);
            if (entry == null) {
                return null;
            }
            return entry.permission != null ? entry.permission : defaultPermissionFor(key);
        }
    }

    private DataFile loadIfNeeded() {
        if (data != null) {
            return data;
        }

        Path path = resolvePath();
        DataFile fallback = new DataFile();
        if (path == null || !Files.exists(path)) {
            data = fallback;
            saveLocked();
            return data;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            DataFile loaded = gson.fromJson(reader, DataFile.class);
            data = loaded != null ? loaded : fallback;
            data.normalize();
            return data;
        } catch (Exception e) {
            logger.warn("Paradigm: Failed to load warps.json: {}", e.getMessage());
            debugLogger.debugLog("WarpStore: load failed", e);
            data = fallback;
            saveLocked();
            return data;
        }
    }

    private void saveLocked() {
        if (data == null) {
            data = new DataFile();
        }
        data.normalize();

        Path path = resolvePath();
        if (path == null) {
            return;
        }

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                gson.toJson(data, writer);
            }
        } catch (Exception e) {
            logger.warn("Paradigm: Failed to save warps.json: {}", e.getMessage());
            debugLogger.debugLog("WarpStore: save failed", e);
        }
    }

    private Path resolvePath() {
        if (config == null) {
            return null;
        }
        return config.resolveConfigPath(FILE_NAME);
    }

    public static String normalizeWarpKey(String value) {
        if (value == null) {
            return null;
        }
        String key = value.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    public static String defaultPermissionFor(String normalizedWarpName) {
        if (normalizedWarpName == null || normalizedWarpName.isBlank()) {
            return null;
        }
        return "paradigm.warp." + normalizedWarpName;
    }

    private static class DataFile {
        private int version = 1;
        private Map<String, WarpEntry> warps = new LinkedHashMap<>();

        private void normalize() {
            if (version <= 0) {
                version = 1;
            }
            if (warps == null) {
                warps = new LinkedHashMap<>();
            }

            Map<String, WarpEntry> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, WarpEntry> entry : warps.entrySet()) {
                String key = normalizeWarpKey(entry.getKey());
                if (key == null) {
                    continue;
                }
                WarpEntry warp = entry.getValue() != null ? entry.getValue() : new WarpEntry();
                warp.normalize(key);
                if (warp.location != null && warp.location.getWorldId() != null && !warp.location.getWorldId().isBlank()) {
                    normalized.put(key, warp);
                }
            }
            warps = normalized;
        }
    }

    public static class WarpEntry {
        private String name;
        private String permission;
        private String description;
        private PlayerDataStore.StoredLocation location;

        public String getName() {
            return name;
        }

        public String getPermission() {
            return permission;
        }

        public String getDescription() {
            return description;
        }

        public PlayerDataStore.StoredLocation getLocation() {
            return location;
        }

        private void normalize(String key) {
            if (name == null || name.isBlank()) {
                name = key;
            } else {
                name = name.trim();
            }
            if (permission == null || permission.isBlank()) {
                permission = defaultPermissionFor(key);
            } else {
                permission = permission.trim().toLowerCase(Locale.ROOT);
            }
            if (description == null) {
                description = "";
            } else {
                description = description.trim();
            }
        }
    }
}

