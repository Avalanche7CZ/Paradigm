package eu.avalanche7.paradigm.modules.afk;

import java.util.Locale;

import eu.avalanche7.paradigm.configs.AfkConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.commands.shared.CommandMessages;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.IEventSystem;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class AfkModule implements ParadigmModule {
    private Services services;

    @Override
    public String getName() {
        return "Afk";
    }

    @Override
    public boolean isEnabled(Services services) {
        return services != null
                && AfkConfigHandler.isInitialized()
                && Boolean.TRUE.equals(AfkConfigHandler.getConfig().enabled.get());
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        this.services = services;
        if (isEnabled(services)) {
            services.getAfkService().start();
        }
    }

    @Override
    public void onEnable(Services services) {
        this.services = services;
        if (isEnabled(services)) {
            services.getAfkService().start();
        }
    }

    @Override
    public void onDisable(Services services) {
        if (services != null) {
            services.getAfkService().stop();
        }
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        if (services != null) {
            services.getAfkService().stop();
        }
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        this.services = services;
        ICommandBuilder cmd = services.getPlatformAdapter().createCommandBuilder()
                .literal("afk")
                .requires(src -> src.getPlayer() != null
                        && services.getCommandToggleStore().isEnabled("afk")
                        && services.getPermissionsHandler().hasPermission(src.getPlayer(), ParadigmPermissions.AFK))
                .executes(ctx -> toggle(ctx.getSource().getPlayer()));
        services.getPlatformAdapter().registerCommand(cmd);
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        this.services = services;
        IEventSystem events = moduleEvents(services);
        if (events != null) {
            events.onPlayerJoin(event -> {
                if (event.getPlayer() != null) {
                    services.getAfkService().beginSession(event.getPlayer());
                }
            });
            events.onPlayerChat(event -> {
                if (!event.isCancelled() && event.getPlayer() != null) {
                    services.getAfkService().markActivity(event.getPlayer());
                }
            });
            events.onPlayerCommand(event -> {
                if (!event.isCancelled() && event.getPlayer() != null && !isAfkCommand(event.getCommand())) {
                    services.getAfkService().markActivity(event.getPlayer());
                }
            });
        }
        IEventSystem lifecycle = lifecycleEvents(services);
        if (lifecycle != null) {
            lifecycle.onPlayerLeave(event -> {
                if (event.getPlayer() != null) {
                    services.getAfkService().endSession(event.getPlayer());
                }
            });
        }
    }

    private int toggle(IPlayer player) {
        if (player == null || services == null) {
            return 0;
        }
        boolean afk = services.getAfkService().toggle(player);
        if (afk) {
            CommandMessages.send(services, player, "AFK", "afk.now_afk", "You are now AFK.");
        } else {
            CommandMessages.send(services, player, "AFK", "afk.no_longer_afk", "You are no longer AFK.");
        }
        return 1;
    }

    static boolean isAfkCommand(String command) {
        if (command == null) {
            return false;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        int separator = normalized.indexOf(' ');
        String root = separator >= 0 ? normalized.substring(0, separator) : normalized;
        int namespace = root.lastIndexOf(':');
        if (namespace >= 0) {
            root = root.substring(namespace + 1);
        }
        return "afk".equals(root);
    }
}
