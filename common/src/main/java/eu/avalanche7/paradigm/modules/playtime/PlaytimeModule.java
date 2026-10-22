package eu.avalanche7.paradigm.modules.playtime;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.CommandMessages;
import eu.avalanche7.paradigm.modules.commands.shared.StorageCommandSupport;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfileState;
import eu.avalanche7.paradigm.utils.DurationFormatter;

public final class PlaytimeModule implements ParadigmModule {
    private Services services;
    private LegacyPlaytimeCommand importer;

    @Override
    public String getName() {
        return "Playtime";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services == null
                || services.getMainConfig() == null
                || Boolean.TRUE.equals(services.getMainConfig().playtimeEnable.value);
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        this.services = services;
        if (isEnabled(services)) {
            services.getPlaytimeService().start();
        }
    }

    @Override
    public void onEnable(Services services) {
        this.services = services;
        if (isEnabled(services)) {
            services.getPlaytimeService().start();
        }
    }

    @Override
    public void onDisable(Services services) {
        if (services != null) {
            services.getPlaytimeService().stop();
        }
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        if (services != null) {
            services.getPlaytimeService().stop();
        }
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        if (importer == null) importer = new LegacyPlaytimeCommand(services);
        importer.register();
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal("playtime")
                .requires(src -> services.getCommandToggleStore().isEnabled("playtime")
                        && allowed(src, ParadigmPermissions.PLAYTIME))
                .executes(ctx -> self(ctx.getSource()))
                .then(services.getPlatformAdapter().createCommandBuilder().literal("status")
                        .executes(ctx -> {
                            var source = ctx.getSource();
                            CommandMessages.source(services, source, "Playtime", "playtime.recovery.status", "Playtime persistence: {status}.",
                                    "{status}", services.getPlaytimeService().recoveryStatus(source.getPlayer() != null ? source.getPlayer().getUUID() : null));
                            return 1;
                        }))
                .then(services.getPlatformAdapter().createCommandBuilder().literal("network")
                        .executes(ctx -> networkSelf(ctx.getSource()))
                        .then(services.getPlatformAdapter().createCommandBuilder().argument("target", ICommandBuilder.ArgumentType.WORD)
                                .requires(src -> allowed(src, ParadigmPermissions.PLAYTIME_OTHERS))
                                .suggests((ctx, input) -> services.getPlatformAdapter().getVisiblePlayerNames(ctx.getSource().getPlayer()))
                                .executes(ctx -> lookup(ctx.getSource(), ctx.getStringArgument("target"), true))))
                .then(services.getPlatformAdapter().createCommandBuilder().literal("player")
                        .requires(src -> allowed(src, ParadigmPermissions.PLAYTIME_OTHERS))
                        .executes(ctx -> other(ctx.getSource(), "player"))
                        .then(services.getPlatformAdapter().createCommandBuilder().argument("target", ICommandBuilder.ArgumentType.WORD)
                                .requires(src -> allowed(src, ParadigmPermissions.PLAYTIME_OTHERS))
                                .executes(ctx -> other(ctx.getSource(), ctx.getStringArgument("target")))))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("target", ICommandBuilder.ArgumentType.WORD)
                        .requires(src -> allowed(src, ParadigmPermissions.PLAYTIME_OTHERS))
                        .suggests((ctx, input) -> services.getPlatformAdapter().getVisiblePlayerNames(ctx.getSource().getPlayer()))
                        .executes(ctx -> other(ctx.getSource(), ctx.getStringArgument("target"))));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        this.services = services;
        IEventSystem events = moduleEvents(services);
        if (events != null) {
            events.onPlayerJoin(event -> {
                if (event.getPlayer() != null) {
                    services.getPlaytimeService().beginSession(event.getPlayer());
                }
            });
        }
        IEventSystem lifecycle = lifecycleEvents(services);
        if (lifecycle != null) {
            lifecycle.onPlayerLeave(event -> {
                if (event.getPlayer() != null) {
                    services.getPlaytimeService().endSession(event.getPlayer());
                }
            });
        }
    }

    private boolean allowed(ICommandSource source, eu.avalanche7.paradigm.modules.permissions.PermissionDefinition permission) {
        if (source == null) {
            return false;
        }
        IPlayer player = source.getPlayer();
        if (player == null) {
            return source.isConsole() || source.hasPermissionLevel(permission.fallbackLevel());
        }
        return services.getPermissionsHandler().hasPermission(player, permission);
    }

    private int self(ICommandSource source) {
        IPlayer player = source != null ? source.getPlayer() : null;
        if (player == null) {
            CommandMessages.source(services, source, "Playtime", "playtime.player_only",
                    "Use /playtime <player> from the console.");
            return 0;
        }
        if (!services.getPlaytimeService().isPlaytimeLoaded(player.getUUID())) {
            CommandMessages.source(services, source, "Playtime", "playtime.recovery.pending", "Historical playtime is unavailable ({status}). New measurements are tracked; use /playtime status.",
                    "{status}", services.getPlaytimeService().recoveryStatus(player.getUUID()));
            return 0;
        }
        long total = services.getPlaytimeService().onlinePlaytimeMs(player);
        long session = services.getPlaytimeService().sessionMs(player);
        CommandMessages.send(services, player, "Playtime", "playtime.local.self",
                "Playtime on this server: {total} (this session: {session}).",
                "{total}", DurationFormatter.humanize(total),
                "{session}", DurationFormatter.humanize(session));
        return 1;
    }

    private int other(ICommandSource source, String input) {
        String query = input != null ? input.trim() : "";
        if (query.isEmpty()) {
            return 0;
        }

        IPlayer online = services.getPlatformAdapter().getPlayerByName(query);
        if (online == null) {
            online = services.getPlatformAdapter().getPlayerByUuid(query);
        }
        if (online != null && services.getPlaytimeService().isTracking(online.getUUID()) && services.getPlaytimeService().isPlaytimeLoaded(online.getUUID())) {
            CommandMessages.source(services, source, "Playtime", "playtime.local.other",
                    "{player} has played for {total} on this server.",
                    "{player}", online.getName(),
                    "{total}", DurationFormatter.humanize(services.getPlaytimeService().onlinePlaytimeMs(online)));
            return 1;
        }

        return lookup(source, query, false);
    }

    private int networkSelf(ICommandSource source) {
        if (source.getPlayer() == null) {
            CommandMessages.source(services, source, "Playtime", "playtime.network.player_only", "Use /playtime network <player> from the console.");
            return 0;
        }
        return lookup(source, source.getPlayer().getUUID(), true);
    }

    private int lookup(ICommandSource source, String query, boolean network) {
        return StorageCommandSupport.runForSource(services, source, "playtime.lookup",
                () -> services.getPlayerProfileService().findByNameOrUuid(query)
                        .map(profile -> services.getStorageService().players().readPlaytime(profile.uuid())).orElse(null),
                state -> sendLookup(source, query, state, network), "playtime.error_load");
    }

    private void sendLookup(ICommandSource source, String query, StoredPlayerProfileState state, boolean network) {
        if (!allowed(source, ParadigmPermissions.PLAYTIME)) return;
        boolean self = source.getPlayer() != null && (query.equalsIgnoreCase(source.getPlayer().getUUID()) || query.equalsIgnoreCase(source.getPlayer().getName()));
        if (!self && !allowed(source, ParadigmPermissions.PLAYTIME_OTHERS)) return;
        if (state == null) {
            CommandMessages.source(services, source, "Playtime", "playtime.unknown", "No playtime data for {player}.", "{player}", query);
            return;
        }
        var profile = state.profile();
        String name = profile.name() != null && !profile.name().isBlank() ? profile.name() : profile.uuid();
        String server = services.getStorageService().context().serverId();
        long local = state.localPlaytimeMs(server);
        long livePending = 0L;
        IPlayer online = services.getPlatformAdapter().getPlayerByUuid(profile.uuid());
        if (online != null && services.getPlaytimeService().isTracking(profile.uuid()) && services.getPlaytimeService().isPlaytimeLoaded(profile.uuid()))
            livePending = Math.max(0L, services.getPlaytimeService().onlinePlaytimeMs(online) - local);
        long total = Math.addExact(network ? profile.playtimeMs() : local, livePending);
        CommandMessages.source(services, source, "Playtime", network ? "playtime.network.other" : "playtime.local.other",
                network ? "{player} has played for {total} across the network (stored + loaded live time on this server; unattributed legacy: {legacy})."
                        : "{player} has played for {total} on this server.",
                "{player}", name, "{total}", DurationFormatter.humanize(total), "{legacy}", DurationFormatter.humanize(state.unattributedPlaytimeMs()));
    }
}
