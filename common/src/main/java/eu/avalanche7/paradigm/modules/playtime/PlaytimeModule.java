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
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.utils.DurationFormatter;

public final class PlaytimeModule implements ParadigmModule {
    private Services services;

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
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal("playtime")
                .requires(src -> services.getCommandToggleStore().isEnabled("playtime")
                        && allowed(src, ParadigmPermissions.PLAYTIME))
                .executes(ctx -> self(ctx.getSource()))
                .then(services.getPlatformAdapter().createCommandBuilder()
                        .argument("player", ICommandBuilder.ArgumentType.WORD)
                        .requires(src -> allowed(src, ParadigmPermissions.PLAYTIME_OTHERS))
                        .suggests((ctx, input) -> services.getPlatformAdapter().getOnlinePlayerNames())
                        .executes(ctx -> other(ctx.getSource(), ctx.getStringArgument("player"))));
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
        long total = services.getPlaytimeService().onlinePlaytimeMs(player);
        long session = services.getPlaytimeService().sessionMs(player);
        CommandMessages.send(services, player, "Playtime", "playtime.self",
                "Playtime: {total} (this session: {session}).",
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
        if (online != null && services.getPlaytimeService().isTracking(online.getUUID())) {
            CommandMessages.source(services, source, "Playtime", "playtime.other",
                    "{player} has played for {total}.",
                    "{player}", online.getName(),
                    "{total}", DurationFormatter.humanize(services.getPlaytimeService().onlinePlaytimeMs(online)));
            return 1;
        }

        return StorageCommandSupport.runForSource(services, source, "playtime.lookup",
                () -> services.getPlayerProfileService().findByNameOrUuid(query).orElse(null),
                profile -> sendLookup(source, query, profile),
                "playtime.error_load");
    }

    private void sendLookup(ICommandSource source, String query, StoredPlayerProfile profile) {
        if (profile == null) {
            CommandMessages.source(services, source, "Playtime", "playtime.unknown",
                    "No playtime data for {player}.", "{player}", query);
            return;
        }
        String name = profile.name() != null && !profile.name().isBlank() ? profile.name() : profile.uuid();
        CommandMessages.source(services, source, "Playtime", "playtime.other",
                "{player} has played for {total}.",
                "{player}", name,
                "{total}", DurationFormatter.humanize(profile.playtimeMs()));
    }
}
