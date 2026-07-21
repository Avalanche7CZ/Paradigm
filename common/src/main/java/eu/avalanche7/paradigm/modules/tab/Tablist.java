package eu.avalanche7.paradigm.modules.tab;

import eu.avalanche7.paradigm.configs.TablistConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class Tablist implements ParadigmModule {
    private static final long WORLD_WATCH_INTERVAL_SECONDS = 1L;

    private static volatile Tablist current;
    private final TablistMetadataProvider metadataProvider;
    private final Map<String, PlayerSnapshot> snapshots = new HashMap<>();
    private Services services;
    private ScheduledFuture<?> refreshTask;
    private ScheduledFuture<?> worldWatchTask;
    private volatile ScheduledFuture<?> pendingImmediateRefresh;
    private TablistMetadataProvider resolver;
    private boolean active;

    public Tablist() {
        this.metadataProvider = null;
    }

    Tablist(TablistMetadataProvider metadataProvider) {
        this.metadataProvider = metadataProvider;
    }

    @Override
    public String getName() {
        return "Tablist";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services != null && Boolean.TRUE.equals(TablistConfigHandler.getConfig().enabled.value);
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        current = this;
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        if (isEnabled(services)) start();
    }

    @Override
    public void onEnable(Services services) {
        this.services = services;
        if (isEnabled(services)) start();
    }

    @Override
    public void onDisable(Services services) {
        stop(true);
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        stop(false);
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        if (services == null || services.getPlatformAdapter().getEventSystem() == null) return;
        services.getPlatformAdapter().getEventSystem().onPlayerJoin(event -> scheduleRefresh(1));
        services.getPlatformAdapter().getEventSystem().onPlayerLeave(event -> {
            IPlayer player = event.getPlayer();
            String uuid = player != null ? player.getUUID() : null;
            if (uuid != null) {
                snapshots.remove(uuid);
                if (resolver instanceof TablistMetadataResolver r) r.invalidate(uuid);
            }
            scheduleRefresh(0);
        });
    }

    public void reload() {
        TablistConfigHandler.reload();
        if (isEnabled(services)) {
            stop(false);
            start();
        } else {
            stop(true);
        }
    }

    public static Tablist current() {
        return current;
    }

    public void refreshNow() {
        if (!active || services == null) return;
        List<IPlayer> players = services.getPlatformAdapter().getOnlinePlayers();
        List<TablistEntry> entries = new ArrayList<>(players.size());
        for (IPlayer player : players) {
            entries.add(new TablistEntry(player, safe(player.getUUID()), safe(player.getName()), safe(player.getWorldId()),
                    Math.max(0, services.getPlatformAdapter().getPlayerPing(player)), resolver.resolve(player)));
        }

        TablistConfigHandler.Config config = TablistConfigHandler.getConfig();
        List<TablistEntry> sorted = TablistSorter.sort(entries, config.sorting.value);
        Map<String, Integer> orders = new HashMap<>();
        for (int index = 0; index < sorted.size(); index++) orders.put(sorted.get(index).uuid(), index);

        Set<String> online = new HashSet<>();
        for (TablistEntry entry : entries) {
            online.add(entry.uuid());
            apply(entry, config.resolve(entry.worldId()), orders.getOrDefault(entry.uuid(), 0), entries.size());
        }
        snapshots.keySet().removeIf(uuid -> !online.contains(uuid));
    }

    boolean isRefreshScheduled() {
        return refreshTask != null && !refreshTask.isCancelled();
    }

    public boolean isActive() {
        return active;
    }

    public boolean isImmediateRefreshPending() {
        ScheduledFuture<?> pending = pendingImmediateRefresh;
        return pending != null && !pending.isDone() && !pending.isCancelled();
    }

    public int onlineViewerCount() {
        return services != null ? services.getPlatformAdapter().getOnlinePlayers().size() : 0;
    }

    boolean hasSnapshot(String uuid) {
        return snapshots.containsKey(uuid);
    }

    private void start() {
        if (active || services == null) return;
        active = true;
        resolver = metadataProvider != null ? metadataProvider : new TablistMetadataResolver(services);
        int seconds = Math.max(1, Math.min(3600, TablistConfigHandler.getConfig().refreshInterval.value));
        refreshTask = services.getTaskScheduler().scheduleAtFixedRate(this::refreshNow, 0, seconds, TimeUnit.SECONDS);
        worldWatchTask = services.getTaskScheduler().scheduleAtFixedRate(this::watchWorlds,
                WORLD_WATCH_INTERVAL_SECONDS, WORLD_WATCH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stop(boolean resetPlayers) {
        active = false;
        if (refreshTask != null) refreshTask.cancel(false);
        refreshTask = null;
        if (worldWatchTask != null) worldWatchTask.cancel(false);
        worldWatchTask = null;
        resolver = null;
        snapshots.clear();
        if (resetPlayers && services != null) {
            for (IPlayer player : services.getPlatformAdapter().getOnlinePlayers()) {
                services.getPlatformAdapter().resetPlayerListState(player);
            }
        }
    }

    private void watchWorlds() {
        if (!active || services == null) return;
        for (IPlayer player : services.getPlatformAdapter().getOnlinePlayers()) {
            String uuid = safe(player.getUUID());
            if (uuid.isEmpty()) continue;
            PlayerSnapshot previous = snapshots.get(uuid);
            String currentWorld = safe(player.getWorldId());
            if (previous == null || !previous.world().equals(currentWorld)) {
                refreshNow();
                return;
            }
        }
    }

    private void scheduleRefresh(long delayTicks) {
        if (!active || services == null) return;
        ScheduledFuture<?> existing = pendingImmediateRefresh;
        if (existing != null && !existing.isDone() && !existing.isCancelled()) return;
        long delayMs = Math.max(0, delayTicks) * 50L;
        pendingImmediateRefresh = services.getTaskScheduler().schedule(this::refreshNow, delayMs, TimeUnit.MILLISECONDS);
    }

    public ICommandBuilder buildCommandBranch(IPlatformAdapter platform, Services services) {
        ICommandBuilder root = platform.createCommandBuilder()
                .literal("tablist")
                .requires(src -> hasPermission(src, services))
                .executes(ctx -> status(ctx.getSource(), services));

        return root
                .then(platform.createCommandBuilder().literal("status").executes(ctx -> status(ctx.getSource(), services)))
                .then(platform.createCommandBuilder().literal("refresh").executes(ctx -> refresh(ctx.getSource(), services)))
                .then(platform.createCommandBuilder().literal("reload").executes(ctx -> reloadCommand(ctx.getSource(), services)));
    }

    private int status(ICommandSource source, Services services) {
        TablistConfigHandler.Config config = TablistConfigHandler.getConfig();
        send(source, services, "tablist.status.enabled", "Enabled: {state}", "{state}", String.valueOf(active));
        send(source, services, "tablist.status.viewers", "Online viewers: {count}", "{count}", String.valueOf(onlineViewerCount()));
        send(source, services, "tablist.status.overrides", "Configured world overrides: {count}",
                "{count}", String.valueOf(config.perWorldOverrides != null ? config.perWorldOverrides.size() : 0));
        send(source, services, "tablist.status.refresh_interval", "Refresh interval: {seconds}s", "{seconds}", String.valueOf(config.refreshInterval.value));
        send(source, services, "tablist.status.pending_refresh", "Immediate refresh pending: {state}", "{state}", String.valueOf(isImmediateRefreshPending()));
        return 1;
    }

    private int refresh(ICommandSource source, Services services) {
        if (!active) {
            send(source, services, "tablist.error.disabled", "Tablist module is not active.");
            return 0;
        }
        refreshNow();
        send(source, services, "tablist.refresh.done", "Tablist refreshed for all online players.");
        return 1;
    }

    private int reloadCommand(ICommandSource source, Services services) {
        reload();
        send(source, services, "tablist.reload.done", "Tablist config reloaded.");
        return 1;
    }

    private boolean hasPermission(ICommandSource source, Services services) {
        if (source == null) return false;
        if (source.isConsole()) return true;
        if (source.hasPermissionLevel(2)) return true;
        IPlayer player = source.getPlayer();
        return player != null && services.getPermissionsHandler().hasPermission(
                player, PermissionsHandler.TABLIST_MANAGE_PERMISSION, PermissionsHandler.TABLIST_MANAGE_PERMISSION_LEVEL);
    }

    private void send(ICommandSource source, Services services, String key, String fallback, String... placeholders) {
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        services.getPlatformAdapter().sendSuccess(source, services.getPlatformAdapter().createLiteralComponent("§b[Tablist] §f" + raw), false);
    }

    private void apply(TablistEntry entry, TablistConfigHandler.Resolved config, int order, int onlineCount) {
        ServerIdentity identity = services.getStorageService().context() != null
                ? services.getStorageService().context().serverIdentity() : null;
        int maxPlayers = services.getPlatformAdapter().getMaxPlayers();
        String header = TablistPlaceholderResolver.expand(String.join("\n", config.header()), entry, onlineCount, maxPlayers, identity, false);
        String footer = TablistPlaceholderResolver.expand(String.join("\n", config.footer()), entry, onlineCount, maxPlayers, identity, false);
        String name = TablistPlaceholderResolver.expand(config.playerFormat(), entry, onlineCount, maxPlayers, identity, config.showPing());
        if (config.showPing() && !config.playerFormat().contains("{ping}")) {
            name += " <color:gray>" + entry.ping() + "ms</color>";
        }

        PlayerSnapshot previous = snapshots.get(entry.uuid());
        if (previous == null || !previous.header().equals(header) || !previous.footer().equals(footer)) {
            IComponent parsedHeader = services.getMessageParser().parseMessage(header, entry.player());
            IComponent parsedFooter = services.getMessageParser().parseMessage(footer, entry.player());
            services.getPlatformAdapter().setPlayerListHeaderFooter(entry.player(), parsedHeader, parsedFooter);
        }
        if (previous == null || !previous.displayName().equals(name)) {
            services.getPlatformAdapter().setPlayerListDisplayName(entry.player(),
                    services.getMessageParser().parseMessage(name, entry.player()));
        }
        if (previous == null || previous.order() != order) {
            services.getPlatformAdapter().setPlayerListOrder(entry.player(), order);
        }
        snapshots.put(entry.uuid(), new PlayerSnapshot(header, footer, name, order, entry.worldId(), entry.ping(), entry.metadata()));
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private record PlayerSnapshot(String header, String footer, String displayName, int order, String world,
                                  int ping, TablistMetadata metadata) {
    }
}
