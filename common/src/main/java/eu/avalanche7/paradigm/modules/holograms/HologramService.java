package eu.avalanche7.paradigm.modules.holograms;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class HologramService {
    public static final String MANAGE_PERMISSION = ParadigmPermissions.HOLOGRAM_MANAGE.node();
    public static final int MANAGE_PERMISSION_LEVEL = ParadigmPermissions.HOLOGRAM_MANAGE.fallbackLevel();
    public static final int MAX_HOLOGRAMS = HologramStore.MAX_HOLOGRAMS;
    public static final int MAX_LINES = HologramStore.MAX_LINES;
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
    private final HologramStore store;
    private final HologramSourceIndex index = new HologramSourceIndex();
    private final Object lock = new Object();
    private final AtomicBoolean lifecycleQueued = new AtomicBoolean();
    private final Map<String, RuntimeEntity> runtime = new LinkedHashMap<>();
    private final Set<String> dirtySources = new LinkedHashSet<>();
    private final Map<String, Long> nextDue = new LinkedHashMap<>();
    private final HologramCooldowns interactionCooldowns = new HologramCooldowns();

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
        this.store = new HologramStore(this::path, services.getLogger());
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
            store.reload();
            rebuildIndexLocked();
        }
        if (renderer != null) renderer.clearTemplateCache();
        startupCleanupPending = true;
        markAllDirty();
        queueLifecycle();
    }

    public Config snapshot() {
        return store.snapshot();
    }

    public Map<String, HologramDefinition> definitions() {
        return snapshot().holograms;
    }

    public HologramDefinition definition(String id) {
        return store.definition(id);
    }

    public List<TemporaryHologram> temporaryHolograms() {
        return temporary.list();
    }

    public TemporaryHologram createTemporary(HologramDefinition definition, String owner, Long ttlSeconds, Long expiresAt) {
        TemporaryHologram created = temporary.create(definition, owner, ttlSeconds, expiresAt, System.currentTimeMillis());
        rebuildIndex();
        markDirty(HologramSourceIndex.sourceId(true, created.id));
        queueLifecycle();
        return created;
    }

    public TemporaryHologram updateTemporary(String id, HologramDefinition definition, Long expiresAt) {
        TemporaryHologram updated = temporary.update(id, definition, expiresAt);
        rebuildIndex();
        markDirty(HologramSourceIndex.sourceId(true, id));
        queueLifecycle();
        return updated;
    }

    public boolean removeTemporary(String id) {
        boolean removed = temporary.remove(id);
        if (removed) {
            removeSourceRuntime(HologramSourceIndex.sourceId(true, id));
            rebuildIndex();
            queueLifecycle();
        }
        return removed;
    }

    public void create(String id, String dimension, double x, double y, double z) {
        String normalized = HologramStore.requireId(id);
        applyPersistentChange(normalized, false, () -> store.create(normalized, dimension, x, y, z));
    }

    public void put(String id, HologramDefinition definition) {
        String normalized = HologramStore.requireId(id);
        applyPersistentChange(normalized, true, () -> store.put(normalized, definition));
    }

    public void updateSettings(boolean enabled, double defaultViewDistance, int defaultRefreshIntervalSeconds) {
        synchronized (lock) {
            store.updateSettings(enabled, defaultViewDistance, defaultRefreshIntervalSeconds);
            rebuildIndexLocked();
        }
        markAllDirty();
        queueLifecycle();
    }

    public void delete(String id) {
        String normalized = HologramStore.requireId(id);
        removeSourceRuntime(HologramSourceIndex.sourceId(false, normalized));
        synchronized (lock) {
            store.delete(normalized);
            rebuildIndexLocked();
        }
        queueLifecycle();
    }

    public void duplicate(String sourceId, String targetId) {
        String normalized = HologramStore.requireId(targetId);
        applyPersistentChange(normalized, false, () -> store.duplicate(sourceId, normalized));
    }

    public void rename(String sourceId, String targetId) {
        String source = HologramStore.requireId(sourceId);
        String target = HologramStore.requireId(targetId);
        removeSourceRuntime(HologramSourceIndex.sourceId(false, source));
        synchronized (lock) {
            store.rename(source, target);
            rebuildIndexLocked();
        }
        markDirty(HologramSourceIndex.sourceId(false, target));
        queueLifecycle();
    }

    public void addLine(String id, String text) {
        mutateLines(id, lines -> {
            if (lines.size() >= MAX_LINES) throw new IllegalArgumentException("Line limit reached.");
            lines.add(HologramStore.requireText(text));
        });
    }

    public void setLine(String id, int oneBasedLine, String text) {
        mutateLines(id, lines -> lines.set(HologramStore.lineIndex(oneBasedLine, lines), HologramStore.requireText(text)));
    }

    public void removeLine(String id, int oneBasedLine) {
        mutateLines(id, lines -> lines.remove(HologramStore.lineIndex(oneBasedLine, lines)));
    }

    public void reorderLine(String id, int fromOneBased, int toOneBased) {
        mutateLines(id, lines -> {
            int from = HologramStore.lineIndex(fromOneBased, lines);
            if (toOneBased < 1 || toOneBased > lines.size()) throw new IllegalArgumentException("Target line is out of range.");
            String value = lines.remove(from);
            lines.add(toOneBased - 1, value);
        });
    }

    public void move(String id, String dimension, double x, double y, double z) {
        mutate(id, definition -> {
            definition.dimension = HologramStore.requireDimension(dimension);
            definition.x = HologramStore.finite(x, "x");
            definition.y = HologramStore.finite(y, "y");
            definition.z = HologramStore.finite(z, "z");
        });
    }

    public void refresh(String id) {
        if (id == null || id.isBlank()) markAllDirty();
        else {
            String normalized = HologramStore.requireId(id);
            if (definition(normalized) == null) throw new IllegalArgumentException("Unknown hologram: " + normalized);
            markDirty(HologramSourceIndex.sourceId(false, normalized));
        }
        queueLifecycle();
    }

    public Map<String, RuntimeStatus> runtimeStatus() {
        Map<String, RuntimeStatus> statuses = new LinkedHashMap<>();
        synchronized (lock) {
            for (HologramSourceIndex.Source source : index.all()) {
                String sourceId = source.sourceId();
                long count = runtime.values().stream().filter(value -> sourceId.equals(value.sourceId)).count();
                boolean loaded = platform != null && platform.isChunkLoaded(source.location());
                statuses.put(sourceId, new RuntimeStatus(source.persistent() ? source.id() : "temporary:" + source.id(),
                        source.persistent(), loaded, (int) count, nextDue.getOrDefault(sourceId, 0L),
                        dirtySources.contains(sourceId)));
            }
        }
        return statuses;
    }

    private void applyPersistentChange(String normalizedId, boolean clearRuntimeFirst, Runnable storeChange) {
        String sourceId = HologramSourceIndex.sourceId(false, normalizedId);
        if (clearRuntimeFirst) removeSourceRuntime(sourceId);
        synchronized (lock) {
            storeChange.run();
            rebuildIndexLocked();
        }
        markDirty(sourceId);
        queueLifecycle();
    }

    private void mutateLines(String id, Consumer<List<String>> mutation) {
        mutate(id, definition -> mutation.accept(definition.lines));
    }

    private void mutate(String id, Consumer<HologramDefinition> mutation) {
        String normalized = HologramStore.requireId(id);
        applyPersistentChange(normalized, true, () -> store.mutate(normalized, mutation));
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
            removeSourceRuntime(HologramSourceIndex.sourceId(true, expired));
            rebuildIndex();
        }
        probeChunks();
        probeRuntimeEntities();
        processDue(now);
        processDirty(now);
        if (startupCleanupPending) {
            startupCleanupPending = false;
            platform.removeUnknownOwnedLines(index.validOwnershipKeys());
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
            HologramSourceIndex.Source source = index.source(sourceId);
            if (source == null || !source.definition().enabled || !store.globallyEnabled()) {
                removeSourceRuntime(sourceId);
                nextDue.remove(sourceId);
                continue;
            }
            if (!platform.isChunkLoaded(source.location())) continue;
            render(source, now);
        }
        if (!dirtySources.isEmpty()) queueLifecycle();
    }

    private void render(HologramSourceIndex.Source source, long now) {
        HologramDefinition definition = source.definition();
        boolean viewerSpecific = requiresViewerSpecificRendering(definition);
        if (viewerSpecific && !platform.capabilities().viewerSpecificVisibility()) {
            nextDue.put(source.sourceId(), now + 1000L);
            return;
        }
        if (!viewerSpecific && !globalVisibilityAllows(definition)) {
            removeSourceRuntime(source.sourceId());
            nextDue.put(source.sourceId(), now + definition.refreshIntervalSeconds * 1000L);
            return;
        }
        for (int lineIndex = 0; lineIndex < definition.lines.size(); lineIndex++) {
            HologramLine line = HologramLine.of(lineIndex, definition.lines.get(lineIndex));
            if (viewerSpecific) renderForViewers(source, line);
            else renderShared(source, line, null);
        }
        if (definition.interaction.enabled) {
            String key = interactionOwnershipKey(source.id(), definition);
            RuntimeEntity previous = runtime.get(key);
            String runtimeId = renderer.upsertInteraction(source.id(), definition, previous != null ? previous.runtimeId : null);
            if (runtimeId != null) runtime.put(key, new RuntimeEntity(key, source.sourceId(), runtimeId));
        }
        if (hasDynamicContent(definition) || hasDynamicVisibility(definition.visibility) || viewerSpecific) {
            nextDue.put(source.sourceId(), now + definition.refreshIntervalSeconds * 1000L);
        }
        else nextDue.remove(source.sourceId());
    }

    private void renderShared(HologramSourceIndex.Source source, HologramLine line, IPlayer viewer) {
        String key = ownershipKey(source.id(), source.definition(), line.index());
        RuntimeEntity previous = runtime.get(key);
        String runtimeId = renderer.upsert(source.id(), source.definition(), line, previous != null ? previous.runtimeId : null, viewer);
        if (runtimeId != null) runtime.put(key, new RuntimeEntity(key, source.sourceId(), runtimeId));
    }

    private void renderForViewers(HologramSourceIndex.Source source, HologramLine line) {
        String prefix = ownershipKey(source.id(), source.definition(), line.index()) + ":viewer:";
        Set<String> visible = new LinkedHashSet<>();
        for (IPlayer player : services.getPlatformAdapter().getOnlinePlayers()) {
            String key = prefix + player.getUUID();
            if (!conditions.test(source.definition().visibility, source.definition(), player)) {
                removeRuntimeKey(key);
                continue;
            }
            visible.add(key);
            RuntimeEntity previous = runtime.get(key);
            IHologramPlatform.LineRequest request = renderer.viewerRequest(key, source.definition(), line, player);
            String runtimeId = platform.upsertViewerLine(request, player, previous != null ? previous.runtimeId : null);
            if (runtimeId != null) runtime.put(key, new RuntimeEntity(key, source.sourceId(), runtimeId));
        }
        runtime.values().stream()
                .filter(value -> source.sourceId().equals(value.sourceId) && value.key.startsWith(prefix) && !visible.contains(value.key))
                .toList()
                .forEach(value -> removeRuntimeKey(value.key));
    }

    private void probeChunks() {
        List<HologramSourceIndex.ChunkKey> chunks = index.chunks();
        if (chunks.isEmpty()) return;
        int count = Math.min(MAX_CHUNK_PROBES_PER_PASS, chunks.size());
        for (int offset = 0; offset < count; offset++) {
            HologramSourceIndex.ChunkKey key = chunks.get(Math.floorMod(chunkProbeOffset++, chunks.size()));
            boolean loaded = platform.isChunkLoaded(key.location());
            for (String sourceId : index.sourcesInChunk(key)) {
                boolean hasRuntime = runtime.values().stream().anyMatch(value -> sourceId.equals(value.sourceId));
                if (loaded && !hasRuntime) dirtySources.add(sourceId);
                if (!loaded && hasRuntime) removeSourceRuntime(sourceId);
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
        String sourceId = index.sourceForInteraction(ownershipKey);
        HologramSourceIndex.Source source = sourceId != null ? index.source(sourceId) : null;
        if (source == null || !source.definition().interaction.enabled) return;
        if (!conditions.test(source.definition().visibility, source.definition(), player)
                || !conditions.test(source.definition().interaction.conditions, source.definition(), player)) return;
        long now = System.currentTimeMillis();
        if (!interactionCooldowns.tryAcquire(sourceId, player.getUUID(), source.definition().interaction.cooldownSeconds, now)) return;
        actions.execute(player, attack ? source.definition().interaction.onAttack : source.definition().interaction.onInteract);
    }

    private void rebuildIndex() {
        synchronized (lock) {
            rebuildIndexLocked();
        }
    }

    private void rebuildIndexLocked() {
        index.rebuild(store.snapshot(), temporary.list());
        nextDue.keySet().removeIf(id -> !index.contains(id));
    }

    private void markAllDirty() {
        synchronized (lock) {
            dirtySources.addAll(index.sourceIds());
        }
    }

    private void markDirty(String sourceId) {
        synchronized (lock) {
            if (index.contains(sourceId)) dirtySources.add(sourceId);
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

    private Path path() {
        return services.getPlatformAdapter().getConfig().resolveConfigPath(FILE_NAME);
    }

    public static String ownershipKey(String id, HologramDefinition definition, int lineIndex) {
        return "line:" + id + ":" + lineIndex;
    }

    public static String interactionOwnershipKey(String id, HologramDefinition definition) {
        return "interaction:" + id;
    }

    public static String normalizeId(String id) {
        return HologramStore.normalizeId(id);
    }

    public record RuntimeStatus(String id, boolean persistent, boolean chunkLoaded, int renderedEntities, long nextDueAt, boolean dirty) {
    }

    private record RuntimeEntity(String key, String sourceId, String runtimeId) {
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
