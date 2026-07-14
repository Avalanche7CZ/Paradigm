package eu.avalanche7.paradigm.modules.tab;

import eu.avalanche7.paradigm.configs.TablistConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
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
    private static volatile Tablist current;
    private final TablistMetadataProvider metadataProvider;
    private final Map<String, PlayerSnapshot> snapshots = new HashMap<>();
    private Services services;
    private ScheduledFuture<?> refreshTask;
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
        services.getPlatformAdapter().getEventSystem().onPlayerLeave(event -> scheduleRefresh(0));
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
        TablistMetadataProvider resolver = metadataProvider != null ? metadataProvider : new TablistMetadataResolver(services);
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

    private void start() {
        if (active || services == null) return;
        active = true;
        int seconds = Math.max(1, Math.min(3600, TablistConfigHandler.getConfig().refreshInterval.value));
        refreshTask = services.getTaskScheduler().scheduleAtFixedRate(this::refreshNow, 0, seconds, TimeUnit.SECONDS);
    }

    private void stop(boolean resetPlayers) {
        active = false;
        if (refreshTask != null) refreshTask.cancel(false);
        refreshTask = null;
        snapshots.clear();
        if (resetPlayers && services != null) {
            for (IPlayer player : services.getPlatformAdapter().getOnlinePlayers()) {
                services.getPlatformAdapter().resetPlayerListState(player);
            }
        }
    }

    private void scheduleRefresh(long delayTicks) {
        if (!active || services == null) return;
        long delayMs = Math.max(0, delayTicks) * 50L;
        services.getTaskScheduler().schedule(this::refreshNow, delayMs, TimeUnit.MILLISECONDS);
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
