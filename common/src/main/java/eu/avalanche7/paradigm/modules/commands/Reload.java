package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.*;
import eu.avalanche7.paradigm.modules.Announcements;
import eu.avalanche7.paradigm.modules.Restart;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Reload implements ParadigmModule {
    private static final Map<ParadigmModule, Boolean> LAST_HELP_STATE = new ConcurrentHashMap<>();

    @Override public String getName() { return "Reload"; }
    @Override public boolean isEnabled(Services services) { return true; }
    @Override public void onLoad(Object e, Services s, Object b) {}
    @Override public void onServerStarting(Object e, Services s) {}
    @Override public void onEnable(Services s) {}
    @Override public void onDisable(Services s) {}
    @Override public void onServerStopping(Object e, Services s) {}
    @Override public void registerEventListeners(Object bus, Services s) {}

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        IPlatformAdapter platform = services.getPlatformAdapter();

        ICommandBuilder reload = platform.createCommandBuilder()
                .literal("reload")
                .requires(src -> {
                    if (src == null) return false;
                    if (src.isConsole()) return true;
                    IPlayer player = src.getPlayer();
                    return player != null && services.getPermissionsHandler().hasPermission(
                            player,
                            PermissionsHandler.RELOAD_PERMISSION,
                            PermissionsHandler.RELOAD_PERMISSION_LEVEL
                    );
                })
                .then(platform.createCommandBuilder()
                        .argument("config", ICommandBuilder.ArgumentType.WORD)
                        .executes(ctx -> {
                            Map<ParadigmModule, Boolean> prevEnabled = new HashMap<>();
                            for (var m : ParadigmAPI.getModules()) {
                                try { prevEnabled.put(m, m.isEnabled(services)); } catch (Throwable ignored) { prevEnabled.put(m, false); }
                            }

                            String cfg = ctx.getStringArgument("config").toLowerCase(Locale.ROOT);
                            String msg;

                            switch (cfg) {
                                case "main" -> { MainConfigHandler.reload(); msg = "Main config reloaded."; }
                                case "announcements" -> { AnnouncementsConfigHandler.reload(); msg = "Announcements config reloaded."; }
                                case "chat" -> { ChatConfigHandler.reload(); msg = "Chat config reloaded."; }
                                case "motd" -> { MOTDConfigHandler.reload(); msg = "MOTD config reloaded."; }
                                case "mention" -> { MentionConfigHandler.reload(); msg = "Mention config reloaded."; }
                                case "restart" -> { RestartConfigHandler.reload(); msg = "Restart config reloaded."; }
                                case "customcommands" -> {
                                    services.getCmConfig().reloadCommands();
                                    services.getPermissionsHandler().refreshCustomCommandPermissions();
                                    msg = "Custom commands config reloaded.";
                                }
                                case "all" -> {
                                    MainConfigHandler.reload();
                                    AnnouncementsConfigHandler.reload();
                                    ChatConfigHandler.reload();
                                    MOTDConfigHandler.reload();
                                    MentionConfigHandler.reload();
                                    RestartConfigHandler.reload();
                                    services.getCmConfig().reloadCommands();
                                    services.getPermissionsHandler().refreshCustomCommandPermissions();
                                    msg = "All configs reloaded.";
                                }
                                default -> {
                                    platform.sendFailure(ctx.getSource(), platform.createLiteralComponent("§cUnknown config: " + cfg));
                                    return 0;
                                }
                            }

                            refreshModuleStates(services, prevEnabled);

                            if ("main".equals(cfg) || "announcements".equals(cfg) || "all".equals(cfg)) {
                                rescheduleAnnouncements();
                            }
                            if ("main".equals(cfg) || "restart".equals(cfg) || "all".equals(cfg)) {
                                rescheduleRestart(services);
                            }

                            platform.sendSuccess(ctx.getSource(), platform.createLiteralComponent("§a" + msg), true);
                            return 1;
                        }));

        ICommandBuilder paradigm = platform.createCommandBuilder()
                .literal("paradigm")
                .then(reload);

        platform.registerCommand(paradigm);
    }

    private void rescheduleAnnouncements() {
        for (var m : ParadigmAPI.getModules()) {
            if (m instanceof Announcements) {
                ((Announcements) m).rescheduleAnnouncements();
            }
        }
    }

    private void rescheduleRestart(Services services) {
        for (var m : ParadigmAPI.getModules()) {
            if (m instanceof Restart) {
                ((Restart) m).rescheduleNextRestart(services);
            }
        }
    }

    public static void refreshModuleStatesForHelp(Services services) {
        refreshModuleStates(services, LAST_HELP_STATE);
    }

    public static void refreshModuleStates(Services services, Map<ParadigmModule, Boolean> prevEnabled) {
        for (var m : ParadigmAPI.getModules()) {
            try {
                boolean before = prevEnabled.getOrDefault(m, m.isEnabled(services));
                boolean after;
                try { after = m.isEnabled(services); } catch (Throwable t) { after = false; }

                if (before && !after) {
                    m.onDisable(services);
                } else if (!before && after) {
                    m.onEnable(services);
                }
                prevEnabled.put(m, after);
            } catch (Throwable t) {
                if (services != null && services.getLogger() != null) {
                    services.getLogger().warn("Failed to refresh module {}: {}", m.getName(), t.toString());
                }
            }
        }
    }
}
