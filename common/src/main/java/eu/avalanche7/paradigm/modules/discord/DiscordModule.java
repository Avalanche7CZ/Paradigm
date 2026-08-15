package eu.avalanche7.paradigm.modules.discord;

import java.util.List;
import java.util.concurrent.TimeUnit;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public class DiscordModule implements ParadigmModule {
    private static volatile DiscordModule INSTANCE;

    private Services services;

    public DiscordModule() {
        INSTANCE = this;
    }

    public static DiscordModule current() {
        return INSTANCE;
    }

    @Override
    public String getName() {
        return "Discord";
    }

    @Override
    public boolean isEnabled(Services services) {
        return true;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        services.getDiscordService().subscribeToParadigmEvents();
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
        this.services = services;
        services.getDiscordService().relay().registerPlatformListeners();
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        this.services = services;
        DiscordService discord = services.getDiscordService();
        discord.start();

        try {
            services.getTaskScheduler().schedule(discord::markServerReady, 0L, TimeUnit.MILLISECONDS);
        } catch (RuntimeException unavailable) {
            services.getDebugLogger().debugLog("[Discord] Could not schedule the server-started notification.");
        }
    }

    @Override
    public void onEnable(Services services) {
        this.services = services;
    }

    @Override
    public void onDisable(Services services) {
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        services.getDiscordService().shutdown();
    }

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
    }

    public void reload() {
        eu.avalanche7.paradigm.configs.DiscordConfigHandler.reload();
        if (services != null) {
            services.getDiscordService().reload();
        }
    }

    public ICommandBuilder buildCommandBranch(IPlatformAdapter platform, Services services) {
        ICommandBuilder root = platform.createCommandBuilder()
                .literal("discord")
                .requires(source -> hasPermission(source, services))
                .executes(context -> status(context.getSource(), services));

        return root
                .then(platform.createCommandBuilder().literal("status")
                        .executes(context -> status(context.getSource(), services)))
                .then(platform.createCommandBuilder().literal("reconnect")
                        .executes(context -> reconnect(context.getSource(), services)))
                .then(platform.createCommandBuilder().literal("test")
                        .executes(context -> test(context.getSource(), services, DiscordDestination.CHAT))
                        .then(platform.createCommandBuilder()
                                .argument("destination", ICommandBuilder.ArgumentType.WORD)
                                .executes(context -> test(context.getSource(), services,
                                        DiscordDestination.parse(context.getStringArgument("destination"))))));
    }

    private int status(ICommandSource source, Services services) {
        DiscordConnectionStatus status = services.getDiscordService().status();
        send(source, services, "discord.status.state", "Discord: {state}", "{state}", status.summary());
        send(source, services, "discord.status.relay",
                "Relay: Minecraft to Discord {out}, Discord to Minecraft {in}",
                "{out}", status.minecraftToDiscordEnabled() ? "on" : "off",
                "{in}", status.discordToMinecraftEnabled() ? "on" : "off");
        send(source, services, "discord.status.queue",
                "Queue: {depth} pending, {sent} sent, {dropped} dropped, {failed} failed",
                "{depth}", Integer.toString(status.queueDepth()),
                "{sent}", Long.toString(status.sentCount()),
                "{dropped}", Long.toString(status.droppedCount()),
                "{failed}", Long.toString(status.failedCount()));
        send(source, services, "discord.status.heartbeat", "Heartbeat: {heartbeat}",
                "{heartbeat}", describeHeartbeat(status));

        List<String> warnings = status.warnings();
        if (warnings.isEmpty()) {
            return 1;
        }
        IPlatformAdapter platform = services.getPlatformAdapter();
        for (String warning : warnings) {
            platform.sendFailure(source, platform.createLiteralComponent("§e[Discord] " + warning));
        }
        return 1;
    }

    private static String describeHeartbeat(DiscordConnectionStatus status) {
        if (status.state() != DiscordConnectionState.CONNECTED) {
            return "not connected";
        }
        if (status.heartbeatOutstanding()) {
            return "awaiting acknowledgement";
        }
        if (status.lastHeartbeatAckMs() <= 0L) {
            return "no acknowledgement yet";
        }
        long age = Math.max(0L, System.currentTimeMillis() - status.lastHeartbeatAckMs()) / 1000L;
        return "acknowledged " + age + "s ago";
    }

    private int reconnect(ICommandSource source, Services services) {
        DiscordService discord = services.getDiscordService();
        if (!discord.isEnabled()) {
            send(source, services, "discord.error.disabled",
                    "Discord integration is disabled or incompletely configured.");
            return 0;
        }
        discord.reconnect();
        send(source, services, "discord.reconnect.requested", "Reconnecting to Discord.");
        return 1;
    }

    private int test(ICommandSource source, Services services, DiscordDestination destination) {
        if (destination == null) {
            send(source, services, "discord.test.unknown_destination",
                    "Unknown destination. Use chat, moderation or notifications.");
            return 0;
        }
        DiscordService discord = services.getDiscordService();
        if (!discord.isEnabled()) {
            send(source, services, "discord.error.disabled",
                    "Discord integration is disabled or incompletely configured.");
            return 0;
        }
        if (!discord.sendTest(destination)) {
            send(source, services, "discord.test.failed", "Could not queue the Discord test message.");
            return 0;
        }
        send(source, services, "discord.test.queued", "Test message queued for the {destination} destination.",
                "{destination}", destination.name().toLowerCase(java.util.Locale.ROOT));
        return 1;
    }

    private boolean hasPermission(ICommandSource source, Services services) {
        if (source == null) {
            return false;
        }
        if (source.isConsole()) {
            return true;
        }
        if (source.hasPermissionLevel(2)) {
            return true;
        }
        IPlayer player = source.getPlayer();
        return player != null
                && services.getPermissionsHandler().hasPermission(player, ParadigmPermissions.DISCORD_MANAGE);
    }

    private void send(ICommandSource source, Services services, String key, String fallback, String... placeholders) {
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            raw = raw.replace(placeholders[index], placeholders[index + 1]);
        }
        IPlatformAdapter platform = services.getPlatformAdapter();
        platform.sendSuccess(source, platform.createLiteralComponent("§b[Discord] §f" + raw), false);
    }
}
