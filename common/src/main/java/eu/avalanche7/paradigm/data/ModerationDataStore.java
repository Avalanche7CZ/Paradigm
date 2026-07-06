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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ModerationDataStore {
    private static final String FILE_NAME = "paradigm/moderation.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Logger logger;
    private final DebugLogger debugLogger;
    private final IConfig config;
    private final Object lock = new Object();
    private State state = new State();

    public ModerationDataStore(Logger logger, DebugLogger debugLogger, IConfig config) {
        this.logger = logger;
        this.debugLogger = debugLogger;
        this.config = config;
        load();
        pruneExpired(System.currentTimeMillis());
    }

    public void setMute(String uuid, String name, long expiresAtMs, String reason, String actor) {
        String key = normalizeUuid(uuid);
        if (key == null) return;
        synchronized (lock) {
            MuteEntry entry = new MuteEntry();
            entry.uuid = key;
            entry.name = clean(name);
            entry.expiresAtMs = Math.max(0L, expiresAtMs);
            entry.reason = clean(reason);
            entry.actor = clean(actor);
            entry.createdAtMs = System.currentTimeMillis();
            state.mutes.put(key, entry);
            saveLocked();
        }
    }

    public boolean clearMute(String uuid) {
        String key = normalizeUuid(uuid);
        if (key == null) return false;
        synchronized (lock) {
            boolean changed = state.mutes.remove(key) != null;
            if (changed) saveLocked();
            return changed;
        }
    }

    public MuteEntry getMute(String uuid) {
        String key = normalizeUuid(uuid);
        if (key == null) return null;
        synchronized (lock) {
            MuteEntry entry = state.mutes.get(key);
            if (entry != null && entry.isExpired(System.currentTimeMillis())) {
                state.mutes.remove(key);
                saveLocked();
                return null;
            }
            return entry != null ? entry.copy() : null;
        }
    }

    public void setTempBan(String name, long expiresAtMs, String reason, String actor) {
        String key = normalizeName(name);
        if (key == null) return;
        synchronized (lock) {
            TempBanEntry entry = new TempBanEntry();
            entry.name = name.trim();
            entry.expiresAtMs = Math.max(0L, expiresAtMs);
            entry.reason = clean(reason);
            entry.actor = clean(actor);
            entry.createdAtMs = System.currentTimeMillis();
            state.tempBans.put(key, entry);
            saveLocked();
        }
    }

    public boolean clearTempBan(String name) {
        String key = normalizeName(name);
        if (key == null) return false;
        synchronized (lock) {
            boolean changed = state.tempBans.remove(key) != null;
            if (changed) saveLocked();
            return changed;
        }
    }

    public TempBanEntry getTempBan(String name) {
        String key = normalizeName(name);
        if (key == null) return null;
        synchronized (lock) {
            TempBanEntry entry = state.tempBans.get(key);
            if (entry != null && entry.isExpired(System.currentTimeMillis())) {
                state.tempBans.remove(key);
                saveLocked();
                return null;
            }
            return entry != null ? entry.copy() : null;
        }
    }

    public void setBan(String name, String reason, String actor) {
        String key = normalizeName(name);
        if (key == null) return;
        synchronized (lock) {
            BanEntry entry = new BanEntry();
            entry.name = name.trim();
            entry.reason = clean(reason);
            entry.actor = clean(actor);
            entry.createdAtMs = System.currentTimeMillis();
            state.bans.put(key, entry);
            saveLocked();
        }
    }

    public boolean clearBan(String name) {
        String key = normalizeName(name);
        if (key == null) return false;
        synchronized (lock) {
            boolean changed = state.bans.remove(key) != null;
            if (changed) saveLocked();
            return changed;
        }
    }

    public BanEntry getBan(String name) {
        String key = normalizeName(name);
        if (key == null) return null;
        synchronized (lock) {
            BanEntry entry = state.bans.get(key);
            return entry != null ? entry.copy() : null;
        }
    }

    public List<TempBanEntry> consumeExpiredTempBans(long nowMs) {
        synchronized (lock) {
            List<TempBanEntry> expired = new ArrayList<>();
            Iterator<Map.Entry<String, TempBanEntry>> it = state.tempBans.entrySet().iterator();
            while (it.hasNext()) {
                TempBanEntry entry = it.next().getValue();
                if (entry != null && entry.isExpired(nowMs)) {
                    expired.add(entry.copy());
                    it.remove();
                }
            }
            if (!expired.isEmpty()) saveLocked();
            return expired;
        }
    }

    public void addWarning(String uuid, String name, String reason, String actor) {
        String key = normalizeUuid(uuid);
        if (key == null) return;
        synchronized (lock) {
            WarnEntry entry = new WarnEntry();
            entry.uuid = key;
            entry.name = clean(name);
            entry.reason = clean(reason);
            entry.actor = clean(actor);
            entry.createdAtMs = System.currentTimeMillis();
            state.warnings.add(entry);
            saveLocked();
        }
    }

    public List<WarnEntry> warningsFor(String uuid) {
        String key = normalizeUuid(uuid);
        if (key == null) return List.of();
        synchronized (lock) {
            List<WarnEntry> result = new ArrayList<>();
            for (WarnEntry warning : state.warnings) {
                if (warning != null && key.equals(normalizeUuid(warning.uuid))) {
                    result.add(warning.copy());
                }
            }
            return result;
        }
    }

    public List<WarnEntry> warnings() {
        synchronized (lock) {
            List<WarnEntry> result = new ArrayList<>();
            for (WarnEntry warning : state.warnings) {
                if (warning != null) {
                    result.add(warning.copy());
                }
            }
            return result;
        }
    }

    public List<MuteEntry> activeMutes() {
        synchronized (lock) {
            List<MuteEntry> result = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (MuteEntry entry : state.mutes.values()) {
                if (entry != null && !entry.isExpired(now)) {
                    result.add(entry.copy());
                }
            }
            return result;
        }
    }

    public List<TempBanEntry> activeTempBans() {
        synchronized (lock) {
            List<TempBanEntry> result = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (TempBanEntry entry : state.tempBans.values()) {
                if (entry != null && !entry.isExpired(now)) {
                    result.add(entry.copy());
                }
            }
            return result;
        }
    }

    public List<BanEntry> activeBans() {
        synchronized (lock) {
            List<BanEntry> result = new ArrayList<>();
            for (BanEntry entry : state.bans.values()) {
                if (entry != null) {
                    result.add(entry.copy());
                }
            }
            return result;
        }
    }

    public void setJailLocation(PlayerDataStore.StoredLocation location) {
        if (location == null || location.getWorldId() == null || location.getWorldId().isBlank()) return;
        synchronized (lock) {
            state.jailLocation = location;
            saveLocked();
        }
    }

    public PlayerDataStore.StoredLocation getJailLocation() {
        synchronized (lock) {
            return state.jailLocation;
        }
    }

    public void setJail(String uuid, String name, long expiresAtMs, String reason, String actor) {
        String key = normalizeUuid(uuid);
        if (key == null) return;
        synchronized (lock) {
            JailEntry entry = new JailEntry();
            entry.uuid = key;
            entry.name = clean(name);
            entry.expiresAtMs = Math.max(0L, expiresAtMs);
            entry.reason = clean(reason);
            entry.actor = clean(actor);
            entry.createdAtMs = System.currentTimeMillis();
            state.jails.put(key, entry);
            saveLocked();
        }
    }

    public boolean clearJail(String uuid) {
        String key = normalizeUuid(uuid);
        if (key == null) return false;
        synchronized (lock) {
            boolean changed = state.jails.remove(key) != null;
            if (changed) saveLocked();
            return changed;
        }
    }

    public JailEntry getJail(String uuid) {
        String key = normalizeUuid(uuid);
        if (key == null) return null;
        synchronized (lock) {
            JailEntry entry = state.jails.get(key);
            if (entry != null && entry.isExpired(System.currentTimeMillis())) {
                state.jails.remove(key);
                saveLocked();
                return null;
            }
            return entry != null ? entry.copy() : null;
        }
    }

    public List<JailEntry> consumeExpiredJails(long nowMs) {
        synchronized (lock) {
            List<JailEntry> expired = new ArrayList<>();
            Iterator<Map.Entry<String, JailEntry>> it = state.jails.entrySet().iterator();
            while (it.hasNext()) {
                JailEntry entry = it.next().getValue();
                if (entry != null && entry.isExpired(nowMs)) {
                    expired.add(entry.copy());
                    it.remove();
                }
            }
            if (!expired.isEmpty()) saveLocked();
            return expired;
        }
    }

    public List<JailEntry> activeJails() {
        synchronized (lock) {
            List<JailEntry> result = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (JailEntry entry : state.jails.values()) {
                if (entry != null && !entry.isExpired(now)) {
                    result.add(entry.copy());
                }
            }
            return result;
        }
    }

    public void pruneExpired(long nowMs) {
        consumeExpiredTempBans(nowMs);
        consumeExpiredJails(nowMs);
        synchronized (lock) {
            boolean changed = state.mutes.entrySet().removeIf(entry -> entry.getValue() != null && entry.getValue().isExpired(nowMs));
            if (changed) saveLocked();
        }
    }

    private void load() {
        Path path = resolvePath();
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            State loaded = gson.fromJson(reader, State.class);
            synchronized (lock) {
                state = loaded != null ? loaded : new State();
                state.normalize();
            }
        } catch (Throwable t) {
            if (logger != null) logger.warn("Paradigm: Failed to load moderation.json: {}", t.getMessage());
            debug("Failed to load moderation.json: " + t);
            save();
        }
    }

    public void save() {
        synchronized (lock) {
            saveLocked();
        }
    }

    private void saveLocked() {
        Path path = resolvePath();
        if (path == null) return;
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                gson.toJson(state, writer);
            }
        } catch (Throwable t) {
            if (logger != null) logger.warn("Paradigm: Failed to save moderation.json: {}", t.getMessage());
        }
    }

    private Path resolvePath() {
        return config != null ? config.resolveConfigPath(FILE_NAME) : null;
    }

    private void debug(String message) {
        try {
            if (debugLogger != null) debugLogger.debugLog("[ModerationDataStore] " + message);
        } catch (Throwable ignored) {
        }
    }

    private static String normalizeUuid(String uuid) {
        String value = uuid != null ? uuid.trim().toLowerCase(Locale.ROOT) : "";
        return value.isBlank() ? null : value;
    }

    private static String normalizeName(String name) {
        String value = name != null ? name.trim().toLowerCase(Locale.ROOT) : "";
        return value.isBlank() ? null : value;
    }

    private static String clean(String value) {
        return value != null ? value.trim() : "";
    }

    public static class State {
        public Map<String, MuteEntry> mutes = new LinkedHashMap<>();
        public Map<String, TempBanEntry> tempBans = new LinkedHashMap<>();
        public Map<String, BanEntry> bans = new LinkedHashMap<>();
        public Map<String, JailEntry> jails = new LinkedHashMap<>();
        public List<WarnEntry> warnings = new ArrayList<>();
        public PlayerDataStore.StoredLocation jailLocation;

        void normalize() {
            if (mutes == null) mutes = new LinkedHashMap<>();
            if (tempBans == null) tempBans = new LinkedHashMap<>();
            if (bans == null) bans = new LinkedHashMap<>();
            if (jails == null) jails = new LinkedHashMap<>();
            if (warnings == null) warnings = new ArrayList<>();
        }
    }

    public static class TimedEntry {
        public long expiresAtMs;

        public boolean isExpired(long nowMs) {
            return expiresAtMs > 0L && expiresAtMs <= nowMs;
        }
    }

    public static class MuteEntry extends TimedEntry {
        public String uuid;
        public String name;
        public String reason;
        public String actor;
        public long createdAtMs;

        public MuteEntry copy() {
            MuteEntry copy = new MuteEntry();
            copy.uuid = uuid;
            copy.name = name;
            copy.reason = reason;
            copy.actor = actor;
            copy.createdAtMs = createdAtMs;
            copy.expiresAtMs = expiresAtMs;
            return copy;
        }
    }

    public static class TempBanEntry extends TimedEntry {
        public String name;
        public String reason;
        public String actor;
        public long createdAtMs;

        public TempBanEntry copy() {
            TempBanEntry copy = new TempBanEntry();
            copy.name = name;
            copy.reason = reason;
            copy.actor = actor;
            copy.createdAtMs = createdAtMs;
            copy.expiresAtMs = expiresAtMs;
            return copy;
        }
    }

    public static class BanEntry {
        public String name;
        public String reason;
        public String actor;
        public long createdAtMs;

        public BanEntry copy() {
            BanEntry copy = new BanEntry();
            copy.name = name;
            copy.reason = reason;
            copy.actor = actor;
            copy.createdAtMs = createdAtMs;
            return copy;
        }
    }

    public static class JailEntry extends TimedEntry {
        public String uuid;
        public String name;
        public String reason;
        public String actor;
        public long createdAtMs;

        public JailEntry copy() {
            JailEntry copy = new JailEntry();
            copy.uuid = uuid;
            copy.name = name;
            copy.reason = reason;
            copy.actor = actor;
            copy.createdAtMs = createdAtMs;
            copy.expiresAtMs = expiresAtMs;
            return copy;
        }
    }

    public static class WarnEntry {
        public String uuid;
        public String name;
        public String reason;
        public String actor;
        public long createdAtMs;

        public WarnEntry copy() {
            WarnEntry copy = new WarnEntry();
            copy.uuid = uuid;
            copy.name = name;
            copy.reason = reason;
            copy.actor = actor;
            copy.createdAtMs = createdAtMs;
            return copy;
        }
    }
}
