package eu.avalanche7.paradigm.modules.holograms;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class HologramService {
    public static final String MANAGE_PERMISSION = "paradigm.hologram.manage";
    public static final int MANAGE_PERMISSION_LEVEL = 4;
    private static final String FILE_NAME = "paradigm/holograms.json";
    private static final int MAX_HOLOGRAMS = 500;
    private static final int MAX_LINES = 100;

    private final Services services;
    private final IHologramPlatform platform;
    private final HologramRenderer renderer;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Object lock = new Object();
    private final Map<String, String> runtimeIds = new LinkedHashMap<>();
    private final Map<String, Long> lastRefreshMs = new LinkedHashMap<>();
    private Map<String, HologramDefinition> renderedDefinitions = new LinkedHashMap<>();
    private Config config;
    private HologramUpdateScheduler scheduler;
    private boolean active;
    private int cleanupCountdown;

    public HologramService(Services services) {
        this.services = Objects.requireNonNull(services, "services");
        this.platform = services.getPlatformAdapter().getHologramPlatform();
        this.renderer = platform != null ? new HologramRenderer(services, platform) : null;
        this.config = loadConfig();
    }

    public boolean supported() {
        return platform != null;
    }

    public void start() {
        if (!supported() || active) return;
        active = true;
        scheduler = new HologramUpdateScheduler(services.getTaskScheduler(), this::tick);
        scheduler.start();
        reconcile(true);
    }

    public void stop() {
        active = false;
        if (scheduler != null) scheduler.stop();
        scheduler = null;
        for (String runtimeId : new ArrayList<>(runtimeIds.values())) renderer.remove(runtimeId);
        runtimeIds.clear();
        lastRefreshMs.clear();
        renderedDefinitions.clear();
        if (platform != null) platform.removeUnknownOwnedLines(Set.of());
    }

    public void reload() {
        synchronized (lock) {
            config = loadConfig();
        }
        if (renderer != null) renderer.clearTemplateCache();
        scheduleReconcile(true);
    }

    public Config snapshot() {
        synchronized (lock) {
            return config.copy();
        }
    }

    public Map<String, HologramDefinition> definitions() {
        return snapshot().holograms;
    }

    public HologramDefinition definition(String id) {
        String normalized = normalizeId(id);
        if (normalized == null) return null;
        synchronized (lock) {
            HologramDefinition definition = config.holograms.get(normalized);
            return definition != null ? definition.copy() : null;
        }
    }

    public void create(String id, String dimension, double x, double y, double z) {
        String normalized = requireId(id);
        synchronized (lock) {
            if (config.holograms.containsKey(normalized)) throw new IllegalArgumentException("Hologram already exists: " + normalized);
            if (config.holograms.size() >= MAX_HOLOGRAMS) throw new IllegalArgumentException("Hologram limit reached.");
            HologramDefinition definition = new HologramDefinition();
            definition.dimension = requireDimension(dimension);
            definition.x = finite(x, "x");
            definition.y = finite(y, "y");
            definition.z = finite(z, "z");
            definition.viewDistance = config.defaultViewDistance;
            definition.refreshIntervalSeconds = config.defaultRefreshIntervalSeconds;
            definition.lines.add("<color:white><bold>New hologram</bold></color>");
            definition.normalize(config.defaultViewDistance, config.defaultRefreshIntervalSeconds);
            config.holograms.put(normalized, definition);
            saveLocked();
        }
        scheduleReconcile(true);
    }

    public void put(String id, HologramDefinition definition) {
        String normalized = requireId(id);
        if (definition == null) throw new IllegalArgumentException("Hologram definition is required.");
        synchronized (lock) {
            if (!config.holograms.containsKey(normalized) && config.holograms.size() >= MAX_HOLOGRAMS) {
                throw new IllegalArgumentException("Hologram limit reached.");
            }
            HologramDefinition copy = validated(definition.copy(), config);
            config.holograms.put(normalized, copy);
            saveLocked();
        }
        scheduleReconcile(true);
    }

    public void updateSettings(boolean enabled, double defaultViewDistance, int defaultRefreshIntervalSeconds) {
        synchronized (lock) {
            config.enabled = enabled;
            config.defaultViewDistance = defaultViewDistance;
            config.defaultRefreshIntervalSeconds = defaultRefreshIntervalSeconds;
            saveLocked();
        }
        scheduleReconcile(true);
    }

    public void delete(String id) {
        String normalized = requireId(id);
        synchronized (lock) {
            if (config.holograms.remove(normalized) == null) throw new IllegalArgumentException("Unknown hologram: " + normalized);
            saveLocked();
        }
        scheduleReconcile(true);
    }

    public void duplicate(String sourceId, String targetId) {
        HologramDefinition source = definition(sourceId);
        if (source == null) throw new IllegalArgumentException("Unknown hologram: " + sourceId);
        String normalized = requireId(targetId);
        synchronized (lock) {
            if (config.holograms.containsKey(normalized)) throw new IllegalArgumentException("Hologram already exists: " + normalized);
            if (config.holograms.size() >= MAX_HOLOGRAMS) throw new IllegalArgumentException("Hologram limit reached.");
            config.holograms.put(normalized, validated(source, config));
            saveLocked();
        }
        scheduleReconcile(true);
    }

    public void rename(String sourceId, String targetId) {
        String source = requireId(sourceId);
        String target = requireId(targetId);
        synchronized (lock) {
            HologramDefinition definition = config.holograms.get(source);
            if (definition == null) throw new IllegalArgumentException("Unknown hologram: " + source);
            if (config.holograms.containsKey(target)) throw new IllegalArgumentException("Hologram already exists: " + target);
            LinkedHashMap<String, HologramDefinition> reordered = new LinkedHashMap<>();
            config.holograms.forEach((id, value) -> reordered.put(id.equals(source) ? target : id, value));
            config.holograms = reordered;
            saveLocked();
        }
        scheduleReconcile(true);
    }

    public void addLine(String id, String text) {
        mutateLines(id, lines -> {
            if (lines.size() >= MAX_LINES) throw new IllegalArgumentException("Line limit reached.");
            lines.add(requireText(text));
        });
    }

    public void setLine(String id, int oneBasedLine, String text) {
        mutateLines(id, lines -> lines.set(lineIndex(oneBasedLine, lines), requireText(text)));
    }

    public void removeLine(String id, int oneBasedLine) {
        mutateLines(id, lines -> lines.remove(lineIndex(oneBasedLine, lines)));
    }

    public void reorderLine(String id, int fromOneBased, int toOneBased) {
        mutateLines(id, lines -> {
            int from = lineIndex(fromOneBased, lines);
            if (toOneBased < 1 || toOneBased > lines.size()) throw new IllegalArgumentException("Target line is out of range.");
            String value = lines.remove(from);
            lines.add(toOneBased - 1, value);
        });
    }

    public void move(String id, String dimension, double x, double y, double z) {
        mutate(id, definition -> {
            definition.dimension = requireDimension(dimension);
            definition.x = finite(x, "x");
            definition.y = finite(y, "y");
            definition.z = finite(z, "z");
        });
    }

    public void refresh(String id) {
        if (id == null || id.isBlank()) {
            lastRefreshMs.clear();
        } else {
            String normalized = requireId(id);
            if (definition(normalized) == null) throw new IllegalArgumentException("Unknown hologram: " + normalized);
            lastRefreshMs.remove(normalized);
        }
        scheduleReconcile(true);
    }

    private void mutateLines(String id, java.util.function.Consumer<List<String>> mutation) {
        mutate(id, definition -> mutation.accept(definition.lines));
    }

    private void mutate(String id, java.util.function.Consumer<HologramDefinition> mutation) {
        String normalized = requireId(id);
        synchronized (lock) {
            HologramDefinition existing = config.holograms.get(normalized);
            if (existing == null) throw new IllegalArgumentException("Unknown hologram: " + normalized);
            HologramDefinition copy = existing.copy();
            mutation.accept(copy);
            config.holograms.put(normalized, validated(copy, config));
            saveLocked();
        }
        scheduleReconcile(true);
    }

    private void tick() {
        if (active) reconcile(false);
    }

    private void scheduleReconcile(boolean force) {
        if (!active || !supported()) return;
        services.getPlatformAdapter().executeOnServerThread(() -> reconcile(force));
    }

    private void reconcile(boolean force) {
        if (!active || !supported()) return;
        Config desired = snapshot();
        Map<String, HologramDefinition> next = desired.enabled ? desired.holograms : Map.of();

        for (Map.Entry<String, HologramDefinition> old : new ArrayList<>(renderedDefinitions.entrySet())) {
            HologramDefinition replacement = next.get(old.getKey());
            if (replacement == null || !same(old.getValue(), replacement)) removeRuntimeFor(old.getKey());
        }

        long now = System.currentTimeMillis();
        for (Map.Entry<String, HologramDefinition> entry : next.entrySet()) {
            String id = entry.getKey();
            HologramDefinition definition = entry.getValue();
            if (!definition.enabled) {
                removeRuntimeFor(id);
                continue;
            }
            boolean due = force || now - lastRefreshMs.getOrDefault(id, 0L) >= definition.refreshIntervalSeconds * 1000L;
            for (int index = 0; index < definition.lines.size(); index++) {
                HologramLine line = HologramLine.of(index, definition.lines.get(index));
                String key = ownershipKey(id, definition, index);
                String runtimeId = runtimeIds.get(key);
                if (runtimeId != null && !renderer.loaded(runtimeId)) {
                    runtimeIds.remove(key);
                    runtimeId = null;
                }
                if (runtimeId == null || (line.dynamic() && due) || force) {
                    String updated = renderer.upsert(id, definition, line, runtimeId);
                    if (updated != null) runtimeIds.put(key, updated);
                }
            }
            if (due) lastRefreshMs.put(id, now);
        }

        renderedDefinitions = deepCopy(next);
        Set<String> validKeys = validOwnershipKeys(next);
        runtimeIds.keySet().removeIf(key -> !validKeys.contains(key));
        if (force || cleanupCountdown-- <= 0) {
            platform.removeUnknownOwnedLines(validKeys);
            cleanupCountdown = 30;
        }
    }

    private void removeRuntimeFor(String id) {
        String prefix = id + ":";
        List<String> keys = runtimeIds.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
        for (String key : keys) renderer.remove(runtimeIds.remove(key));
        lastRefreshMs.remove(id);
    }

    private Config loadConfig() {
        Path path = path();
        if (!Files.exists(path)) {
            Config defaults = new Config();
            normalize(defaults);
            write(defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Config loaded = gson.fromJson(reader, Config.class);
            if (loaded == null) loaded = new Config();
            normalize(loaded);
            write(loaded);
            return loaded;
        } catch (Exception e) {
            services.getLogger().error("Paradigm: failed to load holograms.json; keeping holograms disabled for safety.", e);
            Config safe = new Config();
            safe.enabled = false;
            return safe;
        }
    }

    private void normalize(Config value) {
        if (!Double.isFinite(value.defaultViewDistance) || value.defaultViewDistance < 1.0D) value.defaultViewDistance = 48.0D;
        value.defaultViewDistance = Math.min(512.0D, value.defaultViewDistance);
        value.defaultRefreshIntervalSeconds = Math.max(1, Math.min(3600, value.defaultRefreshIntervalSeconds));
        value.renderMode = "auto";
        if (value.holograms == null) value.holograms = new LinkedHashMap<>();
        if (value.holograms.size() > MAX_HOLOGRAMS) throw new IllegalArgumentException("holograms.json exceeds the hologram limit.");
        LinkedHashMap<String, HologramDefinition> normalized = new LinkedHashMap<>();
        value.holograms.forEach((id, definition) -> {
            String key = requireId(id);
            if (normalized.containsKey(key)) throw new IllegalArgumentException("Duplicate hologram id: " + key);
            normalized.put(key, validated(definition != null ? definition : new HologramDefinition(), value));
        });
        value.holograms = normalized;
    }

    private static HologramDefinition validated(HologramDefinition definition, Config config) {
        definition.normalize(config.defaultViewDistance, config.defaultRefreshIntervalSeconds);
        definition.dimension = requireDimension(definition.dimension);
        definition.x = finite(definition.x, "x");
        definition.y = finite(definition.y, "y");
        definition.z = finite(definition.z, "z");
        if (definition.lines.size() > MAX_LINES) {
            throw new IllegalArgumentException(
                    "A hologram may contain at most " + MAX_LINES + " lines.");
        }
        for (String line : definition.lines) {
            if (line.length() > 4096) {
                throw new IllegalArgumentException(
                        "A hologram line may contain at most 4096 characters.");
            }
        }
        return definition;
    }

    private void saveLocked() {
        normalize(config);
        write(config);
    }

    private void write(Config value) {
        Path target = path();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                gson.toJson(value, writer);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save holograms.json", e);
        }
    }

    private Path path() {
        return services.getPlatformAdapter().getConfig().resolveConfigPath(FILE_NAME);
    }

    public static String ownershipKey(String id, HologramDefinition definition, int lineIndex) {
        int revision = Objects.hash(definition.dimension, definition.x, definition.y, definition.z,
                definition.lineSpacing, lineIndex, definition.lines.get(lineIndex));
        return id + ":" + lineIndex + ":" + Integer.toUnsignedString(revision, 36);
    }

    private static Set<String> validOwnershipKeys(Map<String, HologramDefinition> definitions) {
        Set<String> keys = new LinkedHashSet<>();
        definitions.forEach((id, definition) -> {
            if (!definition.enabled) return;
            for (int index = 0; index < definition.lines.size(); index++) keys.add(ownershipKey(id, definition, index));
        });
        return keys;
    }

    private static Map<String, HologramDefinition> deepCopy(Map<String, HologramDefinition> input) {
        Map<String, HologramDefinition> copy = new LinkedHashMap<>();
        input.forEach((id, definition) -> copy.put(id, definition.copy()));
        return copy;
    }

    private static boolean same(HologramDefinition a, HologramDefinition b) {
        return a.enabled == b.enabled && Objects.equals(a.dimension, b.dimension)
                && Double.compare(a.x, b.x) == 0 && Double.compare(a.y, b.y) == 0 && Double.compare(a.z, b.z) == 0
                && Objects.equals(a.viewDistance, b.viewDistance)
                && Objects.equals(a.refreshIntervalSeconds, b.refreshIntervalSeconds)
                && Double.compare(a.lineSpacing, b.lineSpacing) == 0 && Objects.equals(a.lines, b.lines);
    }

    private static int lineIndex(int oneBasedLine, List<String> lines) {
        if (oneBasedLine < 1 || oneBasedLine > lines.size()) throw new IllegalArgumentException("Line is out of range.");
        return oneBasedLine - 1;
    }

    private static String requireText(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Line text cannot be blank.");
        if (text.length() > 4096) throw new IllegalArgumentException("Line text is too long.");
        return text;
    }

    private static String requireDimension(String dimension) {
        String value = dimension != null ? dimension.trim().toLowerCase(Locale.ROOT) : "";
        if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid dimension id.");
        return value;
    }

    private static double finite(double value, String name) {
        if (!Double.isFinite(value) || Math.abs(value) > 30_000_000D) throw new IllegalArgumentException("Invalid " + name + " coordinate.");
        return value;
    }

    private static String requireId(String id) {
        String normalized = normalizeId(id);
        if (normalized == null) throw new IllegalArgumentException("Hologram id must match [a-z0-9_-]{1,64}.");
        return normalized;
    }

    public static String normalizeId(String id) {
        String normalized = id != null ? id.trim().toLowerCase(Locale.ROOT) : "";
        return normalized.matches("[a-z0-9_-]{1,64}") ? normalized : null;
    }

    public static final class Config {
        public boolean enabled = true;
        public double defaultViewDistance = 48.0D;
        public int defaultRefreshIntervalSeconds = 5;
        public String renderMode = "auto";
        public Map<String, HologramDefinition> holograms = new LinkedHashMap<>();

        public Config copy() {
            Config copy = new Config();
            copy.enabled = enabled;
            copy.defaultViewDistance = defaultViewDistance;
            copy.defaultRefreshIntervalSeconds = defaultRefreshIntervalSeconds;
            copy.renderMode = "auto";
            copy.holograms = deepCopy(holograms);
            return copy;
        }
    }
}
