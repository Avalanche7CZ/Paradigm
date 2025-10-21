package eu.avalanche7.paradigm.modules.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import eu.avalanche7.paradigm.Paradigm;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.Announcements;
import eu.avalanche7.paradigm.modules.Restart;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Reload implements ParadigmModule {
    @Override public String getName() { return "Reload"; }
    @Override public boolean isEnabled(Services services) { return true; }
    @Override public void onLoad(Object e, Services s, Object b) {}
    @Override public void onServerStarting(Object e, Services s) {}
    @Override public void onEnable(Services s) {}
    @Override public void onDisable(Services s) {}
    @Override public void onServerStopping(Object e, Services s) {}
    @Override public void registerEventListeners(Object bus, Services s) {}

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        SuggestionProvider<ServerCommandSource> configSuggestions = (ctx, builder) -> {
            List<String> options = List.of("main", "announcements", "chat", "motd", "mention", "restart", "customcommands", "all");
            options.forEach(builder::suggest);
            return builder.buildFuture();
        };
        dispatcher.register(CommandManager.literal("paradigm")
                .then(CommandManager.literal("reload")
                        .requires(src -> src.hasPermissionLevel(2))
                        .then(CommandManager.argument("config", StringArgumentType.word()).suggests(configSuggestions)
                                .executes(ctx -> {
                                    Map<ParadigmModule, Boolean> prevEnabled = new HashMap<>();
                                    for (var m : Paradigm.getModules()) {
                                        try { prevEnabled.put(m, m.isEnabled(services)); } catch (Throwable ignored) { prevEnabled.put(m, false); }
                                    }

                                    String cfg = StringArgumentType.getString(ctx, "config").toLowerCase(Locale.ROOT);
                                    String msg;
                                    boolean ok = true;
                                    switch (cfg) {
                                        case "main" -> { MainConfigHandler.load(); msg = "Main config reloaded."; }
                                        case "announcements" -> { AnnouncementsConfigHandler.load(); rescheduleAnnouncements(); msg = "Announcements config reloaded and rescheduled."; }
                                        case "chat" -> { ChatConfigHandler.load(); msg = "Chat config reloaded."; }
                                        case "motd" -> { MainConfigHandler.load(); MOTDConfigHandler.loadConfig(); msg = "MOTD and main config reloaded."; }
                                        case "mention" -> { MentionConfigHandler.load(); msg = "Mention config reloaded."; }
                                        case "restart" -> { RestartConfigHandler.load(); rescheduleRestart(services); msg = "Restart config reloaded and rescheduled."; }
                                        case "customcommands" -> {
                                            services.getCmConfig().reloadCommands();
                                            services.getPermissionsHandler().refreshCustomCommandPermissions();
                                            msg = "Custom commands config reloaded. §e§lNote: Command changes require a server restart to take effect!";
                                        }
                                        case "all" -> {
                                            MainConfigHandler.load();
                                            AnnouncementsConfigHandler.load();
                                            ChatConfigHandler.load();
                                            MOTDConfigHandler.loadConfig();
                                            MentionConfigHandler.load();
                                            RestartConfigHandler.load();
                                            services.getCmConfig().reloadCommands();
                                            services.getPermissionsHandler().refreshCustomCommandPermissions();
                                            rescheduleAnnouncements();
                                            rescheduleRestart(services);
                                            msg = "All configs reloaded; schedules refreshed. §e§lNote: Custom command changes require server restart!";
                                        }
                                        default -> { ok = false; msg = "Unknown config: " + cfg; }
                                    }
                                    if (ok) {
                                        refreshModuleStates(services, prevEnabled);
                                        ctx.getSource().sendFeedback(() -> Text.literal("§a" + msg), false);
                                    }
                                    else ctx.getSource().sendError(Text.literal(msg));
                                    return ok ? 1 : 0;
                                }))
                )
        );
    }

    private void rescheduleAnnouncements() {
        for (var m : Paradigm.getModules()) {
            if (m instanceof Announcements) {
                ((Announcements) m).rescheduleAnnouncements();
            }
        }
    }

    private void rescheduleRestart(Services services) {
        for (var m : Paradigm.getModules()) {
            if (m instanceof Restart) {
                ((Restart) m).rescheduleNextRestart(services);
            }
        }
    }

    private void refreshModuleStates(Services services, Map<ParadigmModule, Boolean> prevEnabled) {
        for (var m : Paradigm.getModules()) {
            try {
                boolean before = prevEnabled.getOrDefault(m, false);
                boolean after;
                try { after = m.isEnabled(services); } catch (Throwable t) { after = false; }

                if (before && !after) {
                    m.onDisable(services);
                } else if (!before && after) {
                    m.onEnable(services);
                }
            } catch (Throwable t) {
                if (services != null && services.getLogger() != null) {
                    services.getLogger().warn("Failed to refresh module {}: {}", m.getName(), t.toString());
                }
            }
        }
    }
}
