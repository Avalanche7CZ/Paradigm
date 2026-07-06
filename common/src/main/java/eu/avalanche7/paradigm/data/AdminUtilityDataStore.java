package eu.avalanche7.paradigm.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IConfig;
import org.slf4j.Logger;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public class AdminUtilityDataStore {
    private static final String FILE_NAME = "paradigm/admin_utils.json";

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Logger logger;
    private final IConfig config;
    private final Object lock = new Object();
    private State state = new State();

    public AdminUtilityDataStore(Logger logger, IConfig config) {
        this.logger = logger;
        this.config = config;
        load();
    }

    public boolean toggleGod(String uuid) {
        return toggle(state().godPlayers, uuid);
    }

    public boolean isGod(String uuid) {
        return contains(state().godPlayers, uuid);
    }

    public void setGod(String uuid, boolean enabled) {
        set(state().godPlayers, uuid, enabled);
    }

    public boolean toggleVanish(String uuid) {
        return toggle(state().vanishedPlayers, uuid);
    }

    public boolean isVanished(String uuid) {
        return contains(state().vanishedPlayers, uuid);
    }

    public void setVanished(String uuid, boolean enabled) {
        set(state().vanishedPlayers, uuid, enabled);
    }

    public Set<String> godPlayers() {
        synchronized (lock) {
            state.normalize();
            return Set.copyOf(state.godPlayers);
        }
    }

    public Set<String> vanishedPlayers() {
        synchronized (lock) {
            state.normalize();
            return Set.copyOf(state.vanishedPlayers);
        }
    }

    private boolean toggle(Set<String> set, String uuid) {
        String key = normalize(uuid);
        if (key == null) return false;
        synchronized (lock) {
            boolean enabled;
            if (set.contains(key)) {
                set.remove(key);
                enabled = false;
            } else {
                set.add(key);
                enabled = true;
            }
            saveLocked();
            return enabled;
        }
    }

    private boolean contains(Set<String> set, String uuid) {
        String key = normalize(uuid);
        if (key == null) return false;
        synchronized (lock) {
            return set.contains(key);
        }
    }

    private void set(Set<String> set, String uuid, boolean enabled) {
        String key = normalize(uuid);
        if (key == null) return;
        synchronized (lock) {
            boolean changed = enabled ? set.add(key) : set.remove(key);
            if (changed) {
                saveLocked();
            }
        }
    }

    private State state() {
        synchronized (lock) {
            state.normalize();
            return state;
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
            if (logger != null) logger.warn("Paradigm: Failed to load admin_utils.json: {}", t.getMessage());
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
            if (logger != null) logger.warn("Paradigm: Failed to save admin_utils.json: {}", t.getMessage());
        }
    }

    private Path resolvePath() {
        return config != null ? config.resolveConfigPath(FILE_NAME) : null;
    }

    private static String normalize(String uuid) {
        String value = uuid != null ? uuid.trim().toLowerCase(Locale.ROOT) : "";
        return value.isBlank() ? null : value;
    }

    public static class State {
        public Set<String> godPlayers = new LinkedHashSet<>();
        public Set<String> vanishedPlayers = new LinkedHashSet<>();

        void normalize() {
            if (godPlayers == null) godPlayers = new LinkedHashSet<>();
            if (vanishedPlayers == null) vanishedPlayers = new LinkedHashSet<>();
        }
    }
}
