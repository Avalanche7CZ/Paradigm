package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.avalanche7.paradigm.configs.AnnouncementsConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Announcements implements ParadigmModule {

    private static final String NAME = "Announcements";
    private final Random random = new Random();
    private int globalMessageIndex = 0;
    private int actionbarMessageIndex = 0;
    private int titleMessageIndex = 0;
    private int bossbarMessageIndex = 0;
    private boolean tasksScheduled = false;
    private ScheduledFuture<?> globalTask = null;
    private ScheduledFuture<?> actionbarTask = null;
    private ScheduledFuture<?> titleTask = null;
    private ScheduledFuture<?> bossbarTask = null;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getAnnouncementsConfig().globalEnable.value;
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        if (isEnabled(services)) {
            services.getDebugLogger().debugLog(NAME + " module: Server starting, scheduling announcements if enabled.");
            scheduleConfiguredAnnouncements(services);
        }
    }

    @Override
    public void onEnable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module enabled.");
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module disabled. Cancelling scheduled tasks.");
        cancelAllTasks(services);
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
        cancelAllTasks(services);
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        dispatcher.register(
                CommandManager.literal("paradigm")
                        .requires(source -> source.hasPermissionLevel(PermissionsHandler.BROADCAST_PERMISSION_LEVEL))
                        .then(CommandManager.literal("broadcast")
                                .then(CommandManager.argument("header_footer", BoolArgumentType.bool())
                                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                                .executes(context -> broadcastMessageCmd(context, "broadcast", services)))))
                        .then(CommandManager.literal("actionbar")
                                .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                        .executes(context -> broadcastMessageCmd(context, "actionbar", services))))
                        .then(CommandManager.literal("title")
                                .then(CommandManager.argument("titleAndSubtitle", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String titleAndSubtitle = StringArgumentType.getString(context, "titleAndSubtitle");
                                            String[] parts = titleAndSubtitle.split(" \\|\\| ", 2);
                                            String title = parts[0];
                                            String subtitle = parts.length > 1 ? parts[1] : null;
                                            return broadcastTitleCmd(context, title, subtitle, services);
                                        })))
                        .then(CommandManager.literal("bossbar")
                                .then(CommandManager.argument("interval", IntegerArgumentType.integer(1))
                                        .then(CommandManager.argument("color", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    for (BossBar.Color color : BossBar.Color.values()) {
                                                        builder.suggest(color.getName());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                                        .executes(context -> broadcastMessageCmd(context, "bossbar", services))))))
        );
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {
    }

    private void scheduleConfiguredAnnouncements(Services services) {
        if (tasksScheduled) {
            services.getDebugLogger().debugLog(NAME + ": Tasks already scheduled, skipping.");
            return;
        }

        cancelAllTasks(services);

        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        MinecraftServer server = services.getMinecraftServer();
        if (server == null) {
            services.getDebugLogger().debugLog(NAME + ": Cannot schedule announcements, server instance is null.");
            return;
        }

        if (config.globalEnable.value) {
            long globalInterval = config.globalInterval.value;
            globalTask = services.getTaskScheduler().scheduleAtFixedRate(() -> broadcastGlobalMessages(services), globalInterval, globalInterval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled global messages with interval: {} seconds", globalInterval);
        }

        if (config.actionbarEnable.value) {
            long actionbarInterval = config.actionbarInterval.value;
            actionbarTask = services.getTaskScheduler().scheduleAtFixedRate(() -> broadcastActionbarMessages(services), actionbarInterval, actionbarInterval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled actionbar messages with interval: {} seconds", actionbarInterval);
        }

        if (config.titleEnable.value) {
            long titleInterval = config.titleInterval.value;
            titleTask = services.getTaskScheduler().scheduleAtFixedRate(() -> broadcastTitleMessages(services), titleInterval, titleInterval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled title messages with interval: {} seconds", titleInterval);
        }

        if (config.bossbarEnable.value) {
            long bossbarInterval = config.bossbarInterval.value;
            bossbarTask = services.getTaskScheduler().scheduleAtFixedRate(() -> broadcastBossbarMessages(services), bossbarInterval, bossbarInterval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled bossbar messages with interval: {} seconds", bossbarInterval);
        }
        tasksScheduled = true;
    }

    private void cancelAllTasks(Services services) {
        if (globalTask != null && !globalTask.isCancelled()) {
            globalTask.cancel(false);
            globalTask = null;
            services.getDebugLogger().debugLog(NAME + ": Cancelled global messages task.");
        }
        if (actionbarTask != null && !actionbarTask.isCancelled()) {
            actionbarTask.cancel(false);
            actionbarTask = null;
            services.getDebugLogger().debugLog(NAME + ": Cancelled actionbar messages task.");
        }
        if (titleTask != null && !titleTask.isCancelled()) {
            titleTask.cancel(false);
            titleTask = null;
            services.getDebugLogger().debugLog(NAME + ": Cancelled title messages task.");
        }
        if (bossbarTask != null && !bossbarTask.isCancelled()) {
            bossbarTask.cancel(false);
            bossbarTask = null;
            services.getDebugLogger().debugLog(NAME + ": Cancelled bossbar messages task.");
        }
        tasksScheduled = false;
        if (services != null) {
            services.getDebugLogger().debugLog(NAME + ": All scheduled tasks have been cancelled.");
        }
    }

    private void broadcastGlobalMessages(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (server == null || config.globalMessages.value.isEmpty()) return;

        List<String> messages = config.globalMessages.value;
        String prefix = config.prefix.value != null ? config.prefix.value + "§r" : "";
        String header = config.header.value != null ? config.header.value : "";
        String footer = config.footer.value != null ? config.footer.value : "";

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.value)) {
            messageText = messages.get(globalMessageIndex).replace("{Prefix}", prefix);
            globalMessageIndex = (globalMessageIndex + 1) % messages.size();
        } else {
            messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
        }

        Text message = services.getMessageParser().parseMessage(messageText, null);

        if (config.headerAndFooter.value) {
            Text headerComp = services.getMessageParser().parseMessage(header, null);
            Text footerComp = services.getMessageParser().parseMessage(footer, null);
            server.getPlayerManager().getPlayerList().forEach(player -> {
                player.sendMessage(headerComp);
                player.sendMessage(message);
                player.sendMessage(footerComp);
            });
        } else {
            server.getPlayerManager().broadcast(message, false);
        }
        services.getDebugLogger().debugLog(NAME + ": Broadcasted global message: {}", message.getString());
    }

    private void broadcastActionbarMessages(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (server == null || config.actionbarMessages.value.isEmpty()) return;

        List<String> messages = config.actionbarMessages.value;
        if (messages == null || messages.isEmpty()) return;
        String prefix = config.prefix.value != null ? config.prefix.value + "§r" : "";

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.value)) {
            messageText = messages.get(actionbarMessageIndex).replace("{Prefix}", prefix);
            actionbarMessageIndex = (actionbarMessageIndex + 1) % messages.size();
        } else {
            messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
        }
        Text message = services.getMessageParser().parseMessage(messageText, null);

        server.getPlayerManager().getPlayerList().forEach(player -> {
            player.networkHandler.sendPacket(new OverlayMessageS2CPacket(message));
        });
        services.getDebugLogger().debugLog(NAME + ": Broadcasted actionbar message: {}", message.getString());
    }

    private void broadcastTitleMessages(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (server == null || config.titleMessages.value.isEmpty()) return;

        List<String> messages = config.titleMessages.value;
        if (messages == null || messages.isEmpty()) return;
        String prefix = config.prefix.value != null ? config.prefix.value + "§r" : "";

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.value)) {
            messageText = messages.get(titleMessageIndex).replace("{Prefix}", prefix);
            titleMessageIndex = (titleMessageIndex + 1) % messages.size();
        } else {
            messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
        }

        String[] parts = messageText.split(" \\|\\| ", 2);
        Text titleComponent = services.getMessageParser().parseMessage(parts[0], null);
        Text subtitleComponent = parts.length > 1 ? services.getMessageParser().parseMessage(parts[1], null) : Text.empty();

        server.getPlayerManager().getPlayerList().forEach(player -> {
            player.networkHandler.sendPacket(new ClearTitleS2CPacket(false));
            player.networkHandler.sendPacket(new TitleS2CPacket(titleComponent));
            if (parts.length > 1 && !subtitleComponent.getString().isEmpty()) {
                player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitleComponent));
            }
        });
        services.getDebugLogger().debugLog(NAME + ": Broadcasted title message: {}", messageText);
    }

    private void broadcastBossbarMessages(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (server == null || config.bossbarMessages.value.isEmpty()) return;

        List<String> messages = config.bossbarMessages.value;
        if (messages == null || messages.isEmpty()) return;
        String prefix = config.prefix.value != null ? config.prefix.value + "§r" : "";
        int bossbarTime = config.bossbarTime.value != null ? config.bossbarTime.value : 10;
        BossBar.Color bossbarColor;
        try {
            bossbarColor = config.bossbarColor.value != null ? BossBar.Color.valueOf(config.bossbarColor.value.toUpperCase()) : BossBar.Color.PURPLE;
        } catch (IllegalArgumentException e) {
            services.getDebugLogger().debugLog(NAME + ": Invalid bossbar color: {}. Defaulting to PURPLE.", config.bossbarColor.value);
            bossbarColor = BossBar.Color.PURPLE;
        }

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.value)) {
            messageText = messages.get(bossbarMessageIndex).replace("{Prefix}", prefix);
            bossbarMessageIndex = (bossbarMessageIndex + 1) % messages.size();
        } else {
            messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
        }
        Text message = services.getMessageParser().parseMessage(messageText, null);

        ServerBossBar bossBar = new ServerBossBar(message, bossbarColor, BossBar.Style.PROGRESS);
        bossBar.setPercent(1.0f);

        server.getPlayerManager().getPlayerList().forEach(bossBar::addPlayer);
        services.getDebugLogger().debugLog(NAME + ": Broadcasted bossbar message: {}", message.getString());

        services.getTaskScheduler().schedule(() -> {
            bossBar.clearPlayers();
            bossBar.setVisible(false);
            services.getDebugLogger().debugLog(NAME + ": Removed bossbar message after {} seconds", bossbarTime);
        }, bossbarTime, TimeUnit.SECONDS);
    }

    public int broadcastTitleCmd(CommandContext<ServerCommandSource> context, String title, String subtitle, Services services) throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        MinecraftServer server = services.getMinecraftServer();

        if (server == null) {
            source.sendError(Text.literal("Server not available."));
            return 0;
        }

        Text titleComponent = services.getMessageParser().parseMessage(title, null);
        Text subtitleComponent = (subtitle != null && !subtitle.isEmpty()) ? services.getMessageParser().parseMessage(subtitle, null) : Text.empty();

        server.getPlayerManager().getPlayerList().forEach(player -> {
            player.networkHandler.sendPacket(new ClearTitleS2CPacket(false));
            player.networkHandler.sendPacket(new TitleS2CPacket(titleComponent));
            if (subtitle != null && !subtitleComponent.getString().isEmpty()) {
                player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitleComponent));
            }
        });
        source.sendFeedback(() -> Text.literal("Title broadcasted."), true);
        return 1;
    }

    public int broadcastMessageCmd(CommandContext<ServerCommandSource> context, String type, Services services) throws CommandSyntaxException {
        String messageStr = StringArgumentType.getString(context, "message");
        ServerCommandSource source = context.getSource();
        MinecraftServer server = services.getMinecraftServer();

        if (server == null) {
            source.sendError(Text.literal("Server not available."));
            return 0;
        }

        Text broadcastMessage = services.getMessageParser().parseMessage(messageStr, null);

        switch (type) {
            case "broadcast":
                boolean headerFooter = BoolArgumentType.getBool(context, "header_footer");
                if (headerFooter) {
                    String header = services.getAnnouncementsConfig().header.value;
                    String footer = services.getAnnouncementsConfig().footer.value;
                    Text headerMessage = services.getMessageParser().parseMessage(header, null);
                    Text footerMessage = services.getMessageParser().parseMessage(footer, null);
                    server.getPlayerManager().getPlayerList().forEach(player -> {
                        player.sendMessage(headerMessage);
                        player.sendMessage(broadcastMessage);
                        player.sendMessage(footerMessage);
                    });
                } else {
                    server.getPlayerManager().broadcast(broadcastMessage, false);
                }
                break;
            case "actionbar":
                server.getPlayerManager().getPlayerList().forEach(player -> {
                    player.networkHandler.sendPacket(new OverlayMessageS2CPacket(broadcastMessage));
                });
                break;
            case "bossbar":
                String colorStr = StringArgumentType.getString(context, "color");
                int interval = IntegerArgumentType.getInteger(context, "interval");
                BossBar.Color bossBarColor;
                try {
                    bossBarColor = BossBar.Color.valueOf(colorStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    source.sendError(Text.literal("Invalid bossbar color: " + colorStr));
                    return 0;
                }
                ServerBossBar bossBar = new ServerBossBar(broadcastMessage, bossBarColor, BossBar.Style.PROGRESS);
                bossBar.setPercent(1.0f);
                server.getPlayerManager().getPlayerList().forEach(bossBar::addPlayer);
                services.getTaskScheduler().schedule(bossBar::clearPlayers, interval, TimeUnit.SECONDS);
                break;
            default:
                source.sendError(Text.literal("Invalid message type for command: " + type));
                return 0;
        }
        return 1;
    }
}

