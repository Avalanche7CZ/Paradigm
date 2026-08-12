package eu.avalanche7.paradigm.modules.holograms;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;

public final class HologramStore {

    public static final int MAX_HOLOGRAMS = 500;
    public static final int MAX_LINES = 100;
    public static final int MAX_LINE_LENGTH = 4096;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Object lock = new Object();
    private final Supplier<Path> pathSupplier;
    private final Logger logger;

    private HologramService.Config config;

    public HologramStore(Supplier<Path> pathSupplier, Logger logger) {
        this.pathSupplier = Objects.requireNonNull(pathSupplier, "pathSupplier");
        this.logger = logger;
        this.config = load();
    }

    public HologramService.Config snapshot() {
        synchronized (lock) {
            return config.copy();
        }
    }

    public boolean globallyEnabled() {
        synchronized (lock) {
            return config.enabled;
        }
    }

    public HologramDefinition definition(String id) {
        String normalized = normalizeId(id);
        if (normalized == null) {
            return null;
        }
        synchronized (lock) {
            HologramDefinition definition = config.holograms.get(normalized);
            return definition != null ? definition.copy() : null;
        }
    }

    public void reload() {
        synchronized (lock) {
            config = load();
        }
    }

    public void create(String id, String dimension, double x, double y, double z) {
        String normalized = requireId(id);
        synchronized (lock) {
            if (config.holograms.containsKey(normalized)) {
                throw new IllegalArgumentException("Hologram already exists: " + normalized);
            }
            requireCapacity();
            HologramDefinition definition = new HologramDefinition();
            definition.dimension = requireDimension(dimension);
            definition.x = finite(x, "x");
            definition.y = finite(y, "y");
            definition.z = finite(z, "z");
            definition.viewDistance = config.defaultViewDistance;
            definition.refreshIntervalSeconds = config.defaultRefreshIntervalSeconds;
            definition.lines.add("<color:white><bold>New hologram</bold></color>");
            config.holograms.put(normalized, validated(definition, config));
            save();
        }
    }

    public void put(String id, HologramDefinition definition) {
        String normalized = requireId(id);
        if (definition == null) {
            throw new IllegalArgumentException("Hologram definition is required.");
        }
        synchronized (lock) {
            if (!config.holograms.containsKey(normalized)) {
                requireCapacity();
            }
            config.holograms.put(normalized, validated(definition.copy(), config));
            save();
        }
    }

    public void delete(String id) {
        String normalized = requireId(id);
        synchronized (lock) {
            if (config.holograms.remove(normalized) == null) {
                throw new IllegalArgumentException("Unknown hologram: " + normalized);
            }
            save();
        }
    }

    public void duplicate(String sourceId, String targetId) {
        HologramDefinition source = definition(sourceId);
        if (source == null) {
            throw new IllegalArgumentException("Unknown hologram: " + sourceId);
        }
        String normalized = requireId(targetId);
        synchronized (lock) {
            if (config.holograms.containsKey(normalized)) {
                throw new IllegalArgumentException("Hologram already exists: " + normalized);
            }
            requireCapacity();
            config.holograms.put(normalized, validated(source, config));
            save();
        }
    }

    public void rename(String sourceId, String targetId) {
        String source = requireId(sourceId);
        String target = requireId(targetId);
        synchronized (lock) {
            if (!config.holograms.containsKey(source)) {
                throw new IllegalArgumentException("Unknown hologram: " + source);
            }
            if (config.holograms.containsKey(target)) {
                throw new IllegalArgumentException("Hologram already exists: " + target);
            }
            LinkedHashMap<String, HologramDefinition> reordered = new LinkedHashMap<>();
            config.holograms.forEach((id, value) -> reordered.put(id.equals(source) ? target : id, value));
            config.holograms = reordered;
            save();
        }
    }

    public void updateSettings(boolean enabled, double defaultViewDistance, int defaultRefreshIntervalSeconds) {
        synchronized (lock) {
            config.enabled = enabled;
            config.defaultViewDistance = defaultViewDistance;
            config.defaultRefreshIntervalSeconds = defaultRefreshIntervalSeconds;
            save();
        }
    }

    public void mutate(String id, Consumer<HologramDefinition> mutation) {
        String normalized = requireId(id);
        synchronized (lock) {
            HologramDefinition existing = config.holograms.get(normalized);
            if (existing == null) {
                throw new IllegalArgumentException("Unknown hologram: " + normalized);
            }
            HologramDefinition copy = existing.copy();
            mutation.accept(copy);
            config.holograms.put(normalized, validated(copy, config));
            save();
        }
    }

    public void mutateLines(String id, Consumer<List<String>> mutation) {
        mutate(id, definition -> mutation.accept(definition.lines));
    }

    private void requireCapacity() {
        if (config.holograms.size() >= MAX_HOLOGRAMS) {
            throw new IllegalArgumentException("Hologram limit reached.");
        }
    }

    private HologramService.Config load() {
        Path path = pathSupplier.get();
        if (!Files.exists(path)) {
            HologramService.Config defaults = new HologramService.Config();
            normalize(defaults);
            write(defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            HologramService.Config loaded = gson.fromJson(reader, HologramService.Config.class);
            if (loaded == null) {
                loaded = new HologramService.Config();
            }
            normalize(loaded);
            write(loaded);
            return loaded;
        } catch (IOException | JsonParseException | IllegalArgumentException | IllegalStateException failure) {
            if (logger != null) {
                logger.error("Paradigm: failed to load holograms.json; keeping holograms disabled for safety.", failure);
            }
            HologramService.Config safe = new HologramService.Config();
            safe.enabled = false;
            return safe;
        }
    }

    private void save() {
        normalize(config);
        write(config);
    }

    private void write(HologramService.Config value) {
        Path target = pathSupplier.get();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                gson.toJson(value, writer);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException retryWithoutAtomicMove) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to save holograms.json", failure);
        }
    }

    static void normalize(HologramService.Config value) {
        if (!Double.isFinite(value.defaultViewDistance) || value.defaultViewDistance < 1.0D) {
            value.defaultViewDistance = 48.0D;
        }
        value.defaultViewDistance = Math.min(512.0D, value.defaultViewDistance);
        value.defaultRefreshIntervalSeconds = Math.max(1, Math.min(3600, value.defaultRefreshIntervalSeconds));
        value.renderMode = "auto";
        if (value.holograms == null) {
            value.holograms = new LinkedHashMap<>();
        }
        if (value.holograms.size() > MAX_HOLOGRAMS) {
            throw new IllegalArgumentException("holograms.json exceeds the hologram limit.");
        }
        LinkedHashMap<String, HologramDefinition> normalized = new LinkedHashMap<>();
        value.holograms.forEach((id, definition) -> {
            String key = requireId(id);
            if (normalized.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate hologram id: " + key);
            }
            normalized.put(key, validated(definition != null ? definition : new HologramDefinition(), value));
        });
        value.holograms = normalized;
    }

    static HologramDefinition validated(HologramDefinition definition, HologramService.Config config) {
        definition.normalize(config.defaultViewDistance, config.defaultRefreshIntervalSeconds);
        definition.dimension = requireDimension(definition.dimension);
        definition.x = finite(definition.x, "x");
        definition.y = finite(definition.y, "y");
        definition.z = finite(definition.z, "z");
        if (definition.lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("A hologram may contain at most " + MAX_LINES + " lines.");
        }
        for (String line : definition.lines) {
            if (line.length() > MAX_LINE_LENGTH) {
                throw new IllegalArgumentException("A hologram line may contain at most " + MAX_LINE_LENGTH + " characters.");
            }
        }
        return definition;
    }

    static int lineIndex(int oneBasedLine, List<String> lines) {
        if (oneBasedLine < 1 || oneBasedLine > lines.size()) {
            throw new IllegalArgumentException("Line is out of range.");
        }
        return oneBasedLine - 1;
    }

    static String requireText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Line text cannot be blank.");
        }
        if (text.length() > MAX_LINE_LENGTH) {
            throw new IllegalArgumentException("Line text is too long.");
        }
        return text;
    }

    static String requireDimension(String dimension) {
        String value = dimension != null ? dimension.trim().toLowerCase(Locale.ROOT) : "";
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid dimension id.");
        }
        return value;
    }

    static double finite(double value, String name) {
        if (!Double.isFinite(value) || Math.abs(value) > 30_000_000D) {
            throw new IllegalArgumentException("Invalid " + name + " coordinate.");
        }
        return value;
    }

    static String requireId(String id) {
        String normalized = normalizeId(id);
        if (normalized == null) {
            throw new IllegalArgumentException("Hologram id must match [a-z0-9_-]{1,64}.");
        }
        return normalized;
    }

    static String normalizeId(String id) {
        String normalized = id != null ? id.trim().toLowerCase(Locale.ROOT) : "";
        return normalized.matches("[a-z0-9_-]{1,64}") ? normalized : null;
    }
}
