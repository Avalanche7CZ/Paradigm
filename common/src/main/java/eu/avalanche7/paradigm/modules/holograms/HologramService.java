package eu.avalanche7.paradigm.modules.holograms;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HologramService {
    public static final String MANAGE_PERMISSION = "paradigm.hologram.manage";
    public static final int MANAGE_PERMISSION_LEVEL = 4;
    public static final int MAX_HOLOGRAMS = 500;
    public static final int MAX_LINES = 100;
    private static final String FILE_NAME = "paradigm/holograms.json";
    private static final int MAX_DIRTY_PER_PASS = 64;
    private static final int MAX_CHUNK_PROBES_PER_PASS = 96;
    private static final int MAX_ENTITY_PROBES_PER_PASS = 128;

    private final Services services;
    private final IHologramPlatform platform;
    private final HologramRenderer renderer;
    private final HologramConditionEvaluator conditions;
    private final HologramActionExecutor actions;
    private final TemporaryHologramService temporary = new TemporaryHologramService();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Object lock = new Object();
    private final AtomicBoolean lifecycleQueued = new AtomicBoolean();
    private final Map<String, RuntimeEntity> runtime = new LinkedHashMap<>();
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Map<ChunkKey, LinkedHashSet<String>> chunkIndex = new LinkedHashMap<>();
    private final Set<String> dirtySources = new LinkedHashSet<>();
    private final Map<String, Long> nextDue = new LinkedHashMap<>();
    private final Map<String, String> interactionSources = new HashMap<>();
    private final HologramCooldowns interactionCooldowns = new HologramCooldowns();

    private Config config;
    private HologramUpdateScheduler scheduler;
    private boolean active;
    private int chunkProbeOffset;
    private int entityProbeOffset;
    private boolean startupCleanupPending;

    public HologramService(Services services) {
        this.services = Objects.requireNonNull(services, "services");
        this.platform = services.getPlatformAdapter().getHologramPlatform();
        this.renderer = platform != null ? new HologramRenderer(services, platform) : null;
        this.conditions = platform != null ? new HologramConditionEvaluator(services, platform) : null;
        this.actions = new HologramActionExecutor(services);
        this.config = loadConfig();
        rebuildIndex();
    }

    public boolean supported() {
        return platform != null;
    }

    public IHologramPlatform.Capabilities capabilities() {
        return platform != null ? platform.capabilities() : IHologramPlatform.Capabilities.legacy();
    }

    public TemporaryHologramService temporary() {
        return temporary;
    }

    public void start() {
        if (!supported() || active) return;
        active = true;
        platform.setInteractionHandler(this::handleInteraction);
        scheduler = new HologramUpdateScheduler(services.getTaskScheduler(), this::queueLifecycle);
        scheduler.start();
        startupCleanupPending = true;
        markAllDirty();
        queueLifecycle();
    }

    public void stop() {
        active = false;
        if (scheduler != null) scheduler.stop();
        scheduler = null;
        if (platform != null) platform.setInteractionHandler(null);
        for (RuntimeEntity entry : new ArrayList<>(runtime.values())) removeRuntime(entry);
        runtime.clear();
        nextDue.clear();
        dirtySources.clear();
        interactionCooldowns.clear();
        temporary.clear();
        if (platform != null) platform.removeUnknownOwnedLines(Set.of());
    }

    public void reload() {
        synchronized (lock) {
            config = loadConfig();
            rebuildIndexLocked();
        }
        if (renderer != null) renderer.clearTemplateCache();
        startupCleanupPending = true;
        markAllDirty();
        queueLifecycle();
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

    public List<TemporaryHologram> temporaryHolograms() {
        return temporary.list();
    }

    public TemporaryHologram createTemporary(HologramDefinition definition, String owner, Long ttlSeconds, Long expiresAt) {
        TemporaryHologram created = temporary.create(definition, owner, ttlSeconds, expiresAt, System.currentTimeMillis());
        rebuildIndex();
        markDirty(sourceId(true, created.id));
        queueLifecycle();
        return created;
    }

    public TemporaryHologram updateTemporary(String id, HologramDefinition definition, Long expiresAt) {
        TemporaryHologram updated = temporary.update(id, definition, expiresAt);
        rebuildIndex();
        markDirty(sourceId(true, id));
        queueLifecycle();
        return updated;
    }

    public boolean removeTemporary(String id) {
        boolean removed = temporary.remove(id);
        if (removed) {
            removeSourceRuntime(sourceId(true, id));
            rebuildIndex();
            queueLifecycle();
        }
        return removed;
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
            config.holograms.put(normalized, validated(definition, config));
            saveLocked();
            rebuildIndexLocked();
        }
        markDirty(sourceId(false, normalized));
        queueLifecycle();
    }

    public void put(String id, HologramDefinition definition) {
        String normalized = requireId(id);
        if (definition == null) throw new IllegalArgumentException("Hologram definition is required.");
        removeSourceRuntime(sourceId(false, normalized));
        synchronized (lock) {
            if (!config.holograms.containsKey(normalized) && config.holograms.size() >= MAX_HOLOGRAMS) throw new IllegalArgumentException("Hologram limit reached.");
            config.holograms.put(normalized, validated(definition.copy(), config));
            saveLocked();
            rebuildIndexLocked();
        }
        markDirty(sourceId(false, normalized));
        queueLifecycle();
    }

    public void updateSettings(boolean enabled, double defaultViewDistance, int defaultRefreshIntervalSeconds) {
        synchronized (lock) {
            config.enabled = enabled;
            config.defaultViewDistance = defaultViewDistance;
            config.defaultRefreshIntervalSeconds = defaultRefreshIntervalSeconds;
            saveLocked();
            rebuildIndexLocked();
        }
        markAllDirty();
        queueLifecycle();
    }

    public void delete(String id) {
        String normalized = requireId(id);
        removeSourceRuntime(sourceId(false, normalized));
        synchronized (lock) {
            if (config.holograms.remove(normalized) == null) throw new IllegalArgumentException("Unknown hologram: " + normalized);
            saveLocked();
            rebuildIndexLocked();
        }
        queueLifecycle();
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
            rebuildIndexLocked();
        }
        markDirty(sourceId(false, normalized));
        queueLifecycle();
    }

    public void rename(String sourceId, String targetId) {
        String source = requireId(sourceId);
        String target = requireId(targetId);
        removeSourceRuntime(sourceId(false, source));
        synchronized (lock) {
            HologramDefinition definition = config.holograms.get(source);
            if (definition == null) throw new IllegalArgumentException("Unknown hologram: " + source);
            if (config.holograms.containsKey(target)) throw new IllegalArgumentException("Hologram already exists: " + target);
            LinkedHashMap<String, HologramDefinition> reordered = new LinkedHashMap<>();
            config.holograms.forEach((id, value) -> reordered.put(id.equals(source) ? target : id, value));
            config.holograms = reordered;
            saveLocked();
            rebuildIndexLocked();
        }
        markDirty(sourceId(false, target));
        queueLifecycle();
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
        if (id == null || id.isBlank()) markAllDirty();
        else {
            String normalized = requireId(id);
            if (definition(normalized) == null) throw new IllegalArgumentException("Unknown hologram: " + normalized);
            markDirty(sourceId(false, normalized));
        }
        queueLifecycle();
    }

    public Map<String, RuntimeStatus> runtimeStatus() {
        Map<String, RuntimeStatus> statuses = new LinkedHashMap<>();
        synchronized (lock) {
            sources.forEach((sourceId, source) -> {
                long count = runtime.values().stream().filter(value -> sourceId.equals(value.sourceId)).count();
                boolean loaded = platform != null && platform.isChunkLoaded(source.location());
                statuses.put(sourceId, new RuntimeStatus(source.persistent ? source.id : "temporary:" + source.id,
                        source.persistent, loaded, (int) count, nextDue.getOrDefault(sourceId, 0L), dirtySources.contains(sourceId)));
            });
        }
        return statuses;
    }

    private void mutateLines(String id, java.util.function.Consumer<List<String>> mutation) {
        mutate(id, definition -> mutation.accept(definition.lines));
    }

    private void mutate(String id, java.util.function.Consumer<HologramDefinition> mutation) {
        String normalized = requireId(id);
        removeSourceRuntime(sourceId(false, normalized));
        synchronized (lock) {
            HologramDefinition existing = config.holograms.get(normalized);
            if (existing == null) throw new IllegalArgumentException("Unknown hologram: " + normalized);
            HologramDefinition copy = existing.copy();
            mutation.accept(copy);
            config.holograms.put(normalized, validated(copy, config));
            saveLocked();
            rebuildIndexLocked();
        }
        markDirty(sourceId(false, normalized));
        queueLifecycle();
    }

    private void queueLifecycle() {
        if (!active || !supported() || !lifecycleQueued.compareAndSet(false, true)) return;
        services.getPlatformAdapter().executeOnServerThread(() -> {
            lifecycleQueued.set(false);
            lifecycle();
        });
    }

    private void lifecycle() {
        if (!active || !supported()) return;
        long now = System.currentTimeMillis();
        for (String expired : temporary.expire(now)) {
            removeSourceRuntime(sourceId(true, expired));
            rebuildIndex();
        }
        probeChunks();
        probeRuntimeEntities();
        processDue(now);
        processDirty(now);
        if (startupCleanupPending) {
            startupCleanupPending = false;
            platform.removeUnknownOwnedLines(validOwnershipKeys());
        }
    }

    private void processDue(long now) {
        nextDue.entrySet().stream().filter(entry -> entry.getValue() <= now)
                .sorted(Map.Entry.comparingByValue()).limit(MAX_DIRTY_PER_PASS)
                .map(Map.Entry::getKey).toList().forEach(dirtySources::add);
    }

    private void processDirty(long now) {
        List<String> selected = dirtySources.stream().limit(MAX_DIRTY_PER_PASS).toList();
        for (String sourceId : selected) {
            dirtySources.remove(sourceId);
            Source source = sources.get(sourceId);
            if (source == null || !source.definition.enabled || !isGloballyEnabled()) {
                removeSourceRuntime(sourceId);
                nextDue.remove(sourceId);
                continue;
            }
            if (!platform.isChunkLoaded(source.location())) continue;
            render(source, now);
        }
        if (!dirtySources.isEmpty()) queueLifecycle();
    }

    private void render(Source source, long now) {
        boolean viewerSpecific = requiresViewerSpecificRendering(source.definition);
        if (viewerSpecific && !platform.capabilities().viewerSpecificVisibility()) {
            nextDue.put(source.sourceId, now + 1000L);
            return;
        }
        if (!viewerSpecific && !globalVisibilityAllows(source.definition)) {
            removeSourceRuntime(source.sourceId);
            nextDue.put(source.sourceId, now + source.definition.refreshIntervalSeconds * 1000L);
            return;
        }
        for (int index = 0; index < source.definition.lines.size(); index++) {
            HologramLine line = HologramLine.of(index, source.definition.lines.get(index));
            if (viewerSpecific) renderForViewers(source, line);
            else renderShared(source, line, null);
        }
        if (source.definition.interaction.enabled) {
            String key = interactionOwnershipKey(source.id, source.definition);
            RuntimeEntity previous = runtime.get(key);
            String runtimeId = renderer.upsertInteraction(source.id, source.definition, previous != null ? previous.runtimeId : null);
            if (runtimeId != null) runtime.put(key, new RuntimeEntity(key, source.sourceId, runtimeId));
        }
        if (hasDynamicContent(source.definition) || hasDynamicVisibility(source.definition.visibility) || viewerSpecific) {
            nextDue.put(source.sourceId, now + source.definition.refreshIntervalSeconds * 1000L);
        }
        else nextDue.remove(source.sourceId);
    }

    private void renderShared(Source source, HologramLine line, IPlayer viewer) {
        String key = ownershipKey(source.id, source.definition, line.index());
        RuntimeEntity previous = runtime.get(key);
        String runtimeId = renderer.upsert(source.id, source.definition, line, previous != null ? previous.runtimeId : null, viewer);
        if (runtimeId != null) runtime.put(key, new RuntimeEntity(key, source.sourceId, runtimeId));
    }

    private void renderForViewers(Source source, HologramLine line) {
        String prefix = ownershipKey(source.id, source.definition, line.index()) + ":viewer:";
        Set<String> visible = new LinkedHashSet<>();
        for (IPlayer player : services.getPlatformAdapter().getOnlinePlayers()) {
            String key = prefix + player.getUUID();
            if (!conditions.test(source.definition.visibility, source.definition, player)) {
                removeRuntimeKey(key);
                continue;
            }
            visible.add(key);
            RuntimeEntity previous = runtime.get(key);
            IHologramPlatform.LineRequest request = renderer.viewerRequest(key, source.definition, line, player);
            String runtimeId = platform.upsertViewerLine(request, player, previous != null ? previous.runtimeId : null);
            if (runtimeId != null) runtime.put(key, new RuntimeEntity(key, source.sourceId, runtimeId));
        }
        runtime.values().stream()
                .filter(value -> source.sourceId.equals(value.sourceId) && value.key.startsWith(prefix) && !visible.contains(value.key))
                .toList()
                .forEach(value -> removeRuntimeKey(value.key));
    }

    private void probeChunks() {
        List<ChunkKey> chunks = new ArrayList<>(chunkIndex.keySet());
        if (chunks.isEmpty()) return;
        int count = Math.min(MAX_CHUNK_PROBES_PER_PASS, chunks.size());
        for (int offset = 0; offset < count; offset++) {
            ChunkKey key = chunks.get(Math.floorMod(chunkProbeOffset++, chunks.size()));
            boolean loaded = platform.isChunkLoaded(key.location());
            for (String sourceId : chunkIndex.getOrDefault(key, new LinkedHashSet<>())) {
                boolean hasRuntime = runtime.values().stream().anyMatch(value -> sourceId.equals(value.sourceId));
                if (loaded && !hasRuntime) dirtySources.add(sourceId);
                if (!loaded && hasRuntime) runtime.entrySet().removeIf(entry -> sourceId.equals(entry.getValue().sourceId));
            }
        }
    }

    private void probeRuntimeEntities() {
        List<RuntimeEntity> entities = new ArrayList<>(runtime.values());
        if (entities.isEmpty()) return;
        int count = Math.min(MAX_ENTITY_PROBES_PER_PASS, entities.size());
        for (int offset = 0; offset < count; offset++) {
            RuntimeEntity entity = entities.get(Math.floorMod(entityProbeOffset++, entities.size()));
            if (!platform.isEntityLoaded(entity.runtimeId)) {
                runtime.remove(entity.key);
                dirtySources.add(entity.sourceId);
            }
        }
    }

    private void handleInteraction(String ownershipKey, IPlayer player, boolean attack) {
        if (!active || ownershipKey == null || player == null) return;
        String sourceId = interactionSources.get(ownershipKey);
        Source source = sourceId != null ? sources.get(sourceId) : null;
        if (source == null || !source.definition.interaction.enabled) return;
        if (!conditions.test(source.definition.visibility, source.definition, player)
                || !conditions.test(source.definition.interaction.conditions, source.definition, player)) return;
        long now = System.currentTimeMillis();
        if (!interactionCooldowns.tryAcquire(sourceId, player.getUUID(), source.definition.interaction.cooldownSeconds, now)) return;
        actions.execute(player, attack ? source.definition.interaction.onAttack : source.definition.interaction.onInteract);
    }

    private void rebuildIndex() {
        synchronized (lock) {
            rebuildIndexLocked();
        }
    }

    private void rebuildIndexLocked() {
        sources.clear();
        chunkIndex.clear();
        interactionSources.clear();
        if (config.enabled) config.holograms.forEach((id, definition) -> addSource(new Source(sourceId(false, id), id, true, definition.copy())));
        for (TemporaryHologram value : temporary.list()) addSource(new Source(sourceId(true, value.id), value.id, false, value.definition));
        nextDue.keySet().removeIf(id -> !sources.containsKey(id));
    }

    private void addSource(Source source) {
        sources.put(source.sourceId, source);
        chunkIndex.computeIfAbsent(ChunkKey.of(source.location()), ignored -> new LinkedHashSet<>()).add(source.sourceId);
        if (source.definition.interaction.enabled) interactionSources.put(interactionOwnershipKey(source.id, source.definition), source.sourceId);
    }

    private void markAllDirty() {
        synchronized (lock) {
            dirtySources.addAll(sources.keySet());
        }
    }

    private void markDirty(String sourceId) {
        synchronized (lock) {
            if (sources.containsKey(sourceId)) dirtySources.add(sourceId);
        }
    }

    private void removeSourceRuntime(String sourceId) {
        List<RuntimeEntity> entries = runtime.values().stream().filter(value -> sourceId.equals(value.sourceId)).toList();
        for (RuntimeEntity entry : entries) {
            runtime.remove(entry.key);
            removeRuntime(entry);
        }
        nextDue.remove(sourceId);
        dirtySources.remove(sourceId);
    }

    private void removeRuntimeKey(String key) {
        RuntimeEntity entry = runtime.remove(key);
        if (entry != null) removeRuntime(entry);
    }

    private void removeRuntime(RuntimeEntity entry) {
        if (entry.key.startsWith("interaction:")) platform.removeInteraction(entry.runtimeId);
        else renderer.remove(entry.runtimeId);
    }

    private boolean isGloballyEnabled() {
        synchronized (lock) {
            return config.enabled;
        }
    }

    private boolean requiresViewerSpecificRendering(HologramDefinition definition) {
        return hasViewerSpecificVisibility(definition.visibility) || definition.lines.stream().anyMatch(this::requiresViewerContext);
    }

    private boolean globalVisibilityAllows(HologramDefinition definition) {
        return HologramConditionEvaluator.evaluate(definition.visibility, definition, new HologramConditionEvaluator.Context() {
            @Override public boolean hasPermission(String node) { return false; }
            @Override public boolean hasGroup(String group) { return false; }
            @Override public boolean isOperator() { return false; }
            @Override public String world() { return definition.dimension; }
            @Override public Double x() { return definition.x; }
            @Override public Double y() { return definition.y; }
            @Override public Double z() { return definition.z; }
            @Override public IHologramPlatform.WorldState worldState(String dimension) { return platform.worldState(dimension); }
        });
    }

    private boolean hasViewerSpecificVisibility(HologramConditionGroup group) {
        if (group == null || group.conditions == null) return false;
        for (HologramCondition condition : group.conditions) {
            if (condition == null) continue;
            if ("permission".equals(condition.type) || "group".equals(condition.type)
                    || "operator".equals(condition.type) || "distance".equals(condition.type)) return true;
            if (("all".equals(condition.type) || "any".equals(condition.type)) && hasViewerSpecificVisibility(condition)) return true;
        }
        return false;
    }

    private boolean hasViewerSpecificVisibility(HologramCondition condition) {
        if (condition.conditions == null) return false;
        for (HologramCondition child : condition.conditions) {
            if (child == null) continue;
            if ("permission".equals(child.type) || "group".equals(child.type)
                    || "operator".equals(child.type) || "distance".equals(child.type)) return true;
            if (("all".equals(child.type) || "any".equals(child.type)) && hasViewerSpecificVisibility(child)) return true;
        }
        return false;
    }

    private boolean hasDynamicVisibility(HologramConditionGroup group) {
        if (group == null || group.conditions == null) return false;
        for (HologramCondition condition : group.conditions) {
            if (condition == null) continue;
            if ("time".equals(condition.type) || "weather".equals(condition.type)) return true;
            if (("all".equals(condition.type) || "any".equals(condition.type)) && hasDynamicVisibility(condition)) return true;
        }
        return false;
    }

    private boolean hasDynamicVisibility(HologramCondition condition) {
        if (condition.conditions == null) return false;
        for (HologramCondition child : condition.conditions) {
            if (child == null) continue;
            if ("time".equals(child.type) || "weather".equals(child.type)) return true;
            if (("all".equals(child.type) || "any".equals(child.type)) && hasDynamicVisibility(child)) return true;
        }
        return false;
    }

    private boolean requiresViewerContext(String line) {
        String value = line != null ? line : "";
        return value.contains("{player") || value.contains("{prefix}") || value.contains("{suffix}") || value.contains("{group}");
    }

    private boolean hasDynamicContent(HologramDefinition definition) {
        return definition.lines.stream().map(value -> HologramLine.of(0, value)).anyMatch(HologramLine::dynamic);
    }

    private Set<String> validOwnershipKeys() {
        Set<String> keys = new LinkedHashSet<>();
        sources.values().forEach(source -> {
            if (!source.definition.enabled) return;
            for (int index = 0; index < source.definition.lines.size(); index++) keys.add(ownershipKey(source.id, source.definition, index));
            if (source.definition.interaction.enabled) keys.add(interactionOwnershipKey(source.id, source.definition));
        });
        return keys;
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
        if (definition.lines.size() > MAX_LINES) throw new IllegalArgumentException("A hologram may contain at most " + MAX_LINES + " lines.");
        for (String line : definition.lines) {
            if (line.length() > 4096) throw new IllegalArgumentException("A hologram line may contain at most 4096 characters.");
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
        return "line:" + id + ":" + lineIndex;
    }

    public static String interactionOwnershipKey(String id, HologramDefinition definition) {
        return "interaction:" + id;
    }

    private static String sourceId(boolean temporary, String id) {
        return (temporary ? "temporary:" : "persistent:") + id;
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

    public record RuntimeStatus(String id, boolean persistent, boolean chunkLoaded, int renderedEntities, long nextDueAt, boolean dirty) {
    }

    private record RuntimeEntity(String key, String sourceId, String runtimeId) {
    }

    private record Source(String sourceId, String id, boolean persistent, HologramDefinition definition) {
        IHologramPlatform.Location location() {
            return new IHologramPlatform.Location(definition.dimension, definition.x, definition.y, definition.z);
        }
    }

    private record ChunkKey(String dimension, int x, int z) {
        static ChunkKey of(IHologramPlatform.Location location) {
            return new ChunkKey(location.dimension(), ((int) Math.floor(location.x())) >> 4, ((int) Math.floor(location.z())) >> 4);
        }

        IHologramPlatform.Location location() {
            return new IHologramPlatform.Location(dimension, x * 16.0D, 0.0D, z * 16.0D);
        }
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
            holograms.forEach((id, definition) -> copy.holograms.put(id, definition.copy()));
            return copy;
        }
    }
}
