package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.paradigm.configs.AnnouncementsConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class Announcements implements ParadigmModule {
    private static final String NAME = "Announcements";
    private final Random random = new Random(System.currentTimeMillis());
    private int globalMessageIndex = 0;
    private int actionbarMessageIndex = 0;
    private int titleMessageIndex = 0;
    private int bossbarMessageIndex = 0;
    private int lastGlobalRandomIndex = -1;
    private int lastActionbarRandomIndex = -1;
    private int lastTitleRandomIndex = -1;
    private int lastBossbarRandomIndex = -1;
    private boolean announcementsScheduled = false;
    private ScheduledFuture<?> globalTask = null;
    private ScheduledFuture<?> actionbarTask = null;
    private ScheduledFuture<?> titleTask = null;
    private ScheduledFuture<?> bossbarTask = null;
    private IPlatformAdapter platform;
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        var cfg = services.getAnnouncementsConfig();
        return (cfg != null) && (
            (cfg.globalEnable != null && Boolean.TRUE.equals(cfg.globalEnable.value)) ||
            (cfg.actionbarEnable != null && Boolean.TRUE.equals(cfg.actionbarEnable.value)) ||
            (cfg.titleEnable != null && Boolean.TRUE.equals(cfg.titleEnable.value)) ||
            (cfg.bossbarEnable != null && Boolean.TRUE.equals(cfg.bossbarEnable.value))
        );
    }

    @Override
    public void onLoad(Object event, Services services, Object modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(Object event, Services services) {
        if (isEnabled(services) && !announcementsScheduled) {
            services.getDebugLogger().debugLog(NAME + ": Server starting, scheduling announcements if enabled.");
            scheduleConfiguredAnnouncements();
        }
    }

    @Override
    public void onEnable(Services services) {
        if (platform != null && isEnabled(services) && !announcementsScheduled) {
            services.getDebugLogger().debugLog(NAME + ": Module enabled, scheduling announcements.");
            scheduleConfiguredAnnouncements();
        }
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + ": Module disabled. Cancelling scheduled tasks.");
        cancelAllTasks();
        announcementsScheduled = false;
    }

    @Override
    public void onServerStopping(Object event, Services services) {
        services.getDebugLogger().debugLog(NAME + ": Server stopping.");
        cancelAllTasks();
        announcementsScheduled = false;
    }

    @Override
    public void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, Services services) {
        dispatcher.register(
                CommandManager.literal("paradigm")
                        .requires(source -> source.hasPermissionLevel(PermissionsHandler.BROADCAST_PERMISSION_LEVEL)
                                || (source.isExecutedByPlayer() && services.getPermissionsHandler().hasPermission(source.getPlayer(), PermissionsHandler.BROADCAST_PERMISSION)))
                        .then(CommandManager.literal("broadcast")
                                .then(CommandManager.argument("header_footer", BoolArgumentType.bool())
                                        .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                                .executes(context -> broadcastMessageCmd(context, "broadcast")))))
                        .then(CommandManager.literal("actionbar")
                                .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                        .executes(context -> broadcastMessageCmd(context, "actionbar"))))
                        .then(CommandManager.literal("title")
                                .then(CommandManager.argument("titleAndSubtitle", StringArgumentType.greedyString())
                                        .executes(context -> broadcastTitleCmd(context))))
                        .then(CommandManager.literal("bossbar")
                                .then(CommandManager.argument("interval", IntegerArgumentType.integer(1))
                                        .then(CommandManager.argument("color", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    for (IPlatformAdapter.BossBarColor color : IPlatformAdapter.BossBarColor.values()) {
                                                        builder.suggest(color.name().toLowerCase());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(CommandManager.argument("message", StringArgumentType.greedyString())
                                                        .executes(context -> broadcastMessageCmd(context, "bossbar"))))))
        );
    }

    @Override
    public void registerEventListeners(Object eventBus, Services services) {}

    private void scheduleConfiguredAnnouncements() {
        services.getDebugLogger().debugLog(NAME + ": scheduleConfiguredAnnouncements() called.");
        if (announcementsScheduled) {
            services.getDebugLogger().debugLog(NAME + ": Announcements already scheduled, skipping.");
            return;
        }
        cancelAllTasks();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (platform == null) {
            services.getDebugLogger().debugLog(NAME + ": Cannot schedule announcements, platform adapter is null.");
            return;
        }
        if (config.globalEnable.value) {
            long interval = config.globalInterval.value;
            globalTask = services.getTaskScheduler().scheduleAtFixedRate(this::broadcastGlobalMessages, interval, interval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled global messages with interval: " + interval + " seconds");
        }
        if (config.actionbarEnable.value) {
            long interval = config.actionbarInterval.value;
            actionbarTask = services.getTaskScheduler().scheduleAtFixedRate(this::broadcastActionbarMessages, interval, interval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled actionbar messages with interval: " + interval + " seconds");
        }
        if (config.titleEnable.value) {
            long interval = config.titleInterval.value;
            titleTask = services.getTaskScheduler().scheduleAtFixedRate(this::broadcastTitleMessages, interval, interval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled title messages with interval: " + interval + " seconds");
        }
        if (config.bossbarEnable.value) {
            long interval = config.bossbarInterval.value;
            bossbarTask = services.getTaskScheduler().scheduleAtFixedRate(this::broadcastBossbarMessages, interval, interval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled bossbar messages with interval: " + interval + " seconds");
        }
        announcementsScheduled = true;
    }

    private void cancelAllTasks() {
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
    }

    private String getNextMessage(List<String> messages, String orderMode, String type) {
        String prefix = services.getAnnouncementsConfig().prefix.value != null ? services.getAnnouncementsConfig().prefix.value + "§r" : "";
        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(orderMode)) {
            int index;
            switch (type) {
                case "global" -> {
                    index = globalMessageIndex++;
                    if (globalMessageIndex >= messages.size()) globalMessageIndex = 0;
                }
                case "actionbar" -> {
                    index = actionbarMessageIndex++;
                    if (actionbarMessageIndex >= messages.size()) actionbarMessageIndex = 0;
                }
                case "title" -> {
                    index = titleMessageIndex++;
                    if (titleMessageIndex >= messages.size()) titleMessageIndex = 0;
                }
                case "bossbar" -> {
                    index = bossbarMessageIndex++;
                    if (bossbarMessageIndex >= messages.size()) bossbarMessageIndex = 0;
                }
                default -> index = random.nextInt(messages.size());
            }
            messageText = messages.get(index);
        } else {
            int index;
            int lastIndex;
            switch (type) {
                case "global" -> lastIndex = lastGlobalRandomIndex;
                case "actionbar" -> lastIndex = lastActionbarRandomIndex;
                case "title" -> lastIndex = lastTitleRandomIndex;
                case "bossbar" -> lastIndex = lastBossbarRandomIndex;
                default -> lastIndex = -1;
            }
            if (messages.size() > 1) {
                do {
                    index = random.nextInt(messages.size());
                } while (index == lastIndex);
            } else {
                index = 0;
            }
            switch (type) {
                case "global" -> lastGlobalRandomIndex = index;
                case "actionbar" -> lastActionbarRandomIndex = index;
                case "title" -> lastTitleRandomIndex = index;
                case "bossbar" -> lastBossbarRandomIndex = index;
            }
            messageText = messages.get(index);
        }
        return messageText.replace("{Prefix}", prefix);
    }

    private void broadcastGlobalMessages() {
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        List<String> messages = config.globalMessages.value;
        if (platform == null || messages.isEmpty()) return;
        String orderMode = config.orderMode.value;
        String messageText = getNextMessage(messages, orderMode, "global");
        boolean headerFooter = config.headerAndFooter.value;
        String header = config.header.value != null ? config.header.value : "";
        String footer = config.footer.value != null ? config.footer.value : "";
        platform.getOnlinePlayers().forEach(player -> {
            IPlayer iPlayer = platform.wrapPlayer(player);
            if (headerFooter) {
                IComponent headerComp = services.getMessageParser().parseMessage(header, iPlayer);
                IComponent messageComp = services.getMessageParser().parseMessage(messageText, iPlayer);
                IComponent footerComp = services.getMessageParser().parseMessage(footer, iPlayer);
                platform.sendSystemMessage(player, headerComp.getOriginalText());
                platform.sendSystemMessage(player, messageComp.getOriginalText());
                platform.sendSystemMessage(player, footerComp.getOriginalText());
            } else {
                IComponent messageComp = services.getMessageParser().parseMessage(messageText, iPlayer);
                platform.sendSystemMessage(player, messageComp.getOriginalText());
            }
        });
        services.getDebugLogger().debugLog(NAME + ": Broadcasted global message: " + messageText);
    }

    private void broadcastActionbarMessages() {
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        List<String> messages = config.actionbarMessages.value;
        if (platform == null || messages.isEmpty()) return;
        String orderMode = config.orderMode.value;
        String messageText = getNextMessage(messages, orderMode, "actionbar");
        platform.getOnlinePlayers().forEach(player -> {
            IPlayer iPlayer = platform.wrapPlayer(player);
            IComponent messageComp = services.getMessageParser().parseMessage(messageText, iPlayer);
            platform.sendActionBar(player, messageComp.getOriginalText());
        });
        services.getDebugLogger().debugLog(NAME + ": Broadcasted actionbar message: " + messageText);
    }

    private void broadcastTitleMessages() {
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        List<String> messages = config.titleMessages.value;
        if (platform == null || messages.isEmpty()) return;
        String orderMode = config.orderMode.value;
        String messageText = getNextMessage(messages, orderMode, "title");
        platform.getOnlinePlayers().forEach(player -> {
            IPlayer iPlayer = platform.wrapPlayer(player);
            String[] parts = messageText.split(" \\|\\| ", 2);
            IComponent titleComp = services.getMessageParser().parseMessage(parts[0], iPlayer);
            IComponent subtitleComp = parts.length > 1 ? services.getMessageParser().parseMessage(parts[1], iPlayer) : services.getMessageParser().parseMessage("", iPlayer);
            platform.clearTitles(player);
            platform.sendTitle(player, titleComp.getOriginalText(), subtitleComp.getOriginalText());
        });
        services.getDebugLogger().debugLog(NAME + ": Broadcasted title message: " + messageText);
    }

    private void broadcastBossbarMessages() {
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        List<String> messages = config.bossbarMessages.value;
        if (platform == null || messages.isEmpty()) return;
        String orderMode = config.orderMode.value;
        String messageText = getNextMessage(messages, orderMode, "bossbar");
        int bossbarTime = config.bossbarTime.value != null ? config.bossbarTime.value : 10;
        String colorStr = config.bossbarColor.value != null ? config.bossbarColor.value.toUpperCase() : "PURPLE";
        IPlatformAdapter.BossBarColor tempBossbarColor;
        try {
            tempBossbarColor = IPlatformAdapter.BossBarColor.valueOf(colorStr);
        } catch (IllegalArgumentException e) {
            services.getDebugLogger().debugLog(NAME + ": Invalid bossbar color: " + colorStr + ". Defaulting to PURPLE.");
            tempBossbarColor = IPlatformAdapter.BossBarColor.PURPLE;
        }
        final IPlatformAdapter.BossBarColor bossbarColor = tempBossbarColor;
        platform.getOnlinePlayers().forEach(player -> {
            IPlayer iPlayer = platform.wrapPlayer(player);
            IComponent messageComp = services.getMessageParser().parseMessage(messageText, iPlayer);
            platform.sendBossBar(Collections.singletonList(player), messageComp.getOriginalText(), bossbarTime, bossbarColor, 1.0f);
        });
        services.getDebugLogger().debugLog(NAME + ": Broadcasted bossbar message: " + messageText);
    }

    public int broadcastTitleCmd(CommandContext<ServerCommandSource> context) {
        String titleAndSubtitle = StringArgumentType.getString(context, "titleAndSubtitle");
        // ICommandSource source = platform.wrapCommandSource(context.getSource()); // Fix this if needed
        services.getDebugLogger().debugLog(NAME + ": /paradigm title command executed with message: " + titleAndSubtitle);
        String[] parts = titleAndSubtitle.split(" \\|\\| ", 2);
        platform.getOnlinePlayers().forEach(target -> {
            IPlayer iPlayer = platform.wrapPlayer(target);
            IComponent titleComp = services.getMessageParser().parseMessage(parts[0], iPlayer);
            IComponent subtitleComp = parts.length > 1 ? services.getMessageParser().parseMessage(parts[1], iPlayer) : services.getMessageParser().parseMessage("", iPlayer);
            platform.clearTitles(target);
            platform.sendTitle(target, titleComp.getOriginalText(), subtitleComp.getOriginalText());
        });
        platform.sendSuccess(context.getSource(), platform.createLiteralComponent("Title broadcasted."), true);
        return 1;
    }

    public int broadcastMessageCmd(CommandContext<ServerCommandSource> context, String type) {
        String messageStr = StringArgumentType.getString(context, "message");
        // ICommandSource source = platform.wrapCommandSource(context.getSource()); // Fix this if needed
        services.getDebugLogger().debugLog(NAME + ": /paradigm " + type + " command executed with message: " + messageStr);
        switch (type) {
            case "broadcast" -> {
                boolean headerFooter = BoolArgumentType.getBool(context, "header_footer");
                String header = services.getAnnouncementsConfig().header.value;
                String footer = services.getAnnouncementsConfig().footer.value;
                platform.getOnlinePlayers().forEach(player -> {
                    IPlayer iPlayer = platform.wrapPlayer(player);
                    IComponent messageComp = services.getMessageParser().parseMessage(messageStr, iPlayer);
                    if (headerFooter) {
                        IComponent headerComp = services.getMessageParser().parseMessage(header, iPlayer);
                        IComponent footerComp = services.getMessageParser().parseMessage(footer, iPlayer);
                        platform.sendSystemMessage(player, headerComp.getOriginalText());
                        platform.sendSystemMessage(player, messageComp.getOriginalText());
                        platform.sendSystemMessage(player, footerComp.getOriginalText());
                    } else {
                        platform.sendSystemMessage(player, messageComp.getOriginalText());
                    }
                });
            }
            case "actionbar" -> {
                platform.getOnlinePlayers().forEach(player -> {
                    IPlayer iPlayer = platform.wrapPlayer(player);
                    IComponent messageComp = services.getMessageParser().parseMessage(messageStr, iPlayer);
                    platform.sendActionBar(player, messageComp.getOriginalText());
                });
            }
            case "bossbar" -> {
                String colorStr = StringArgumentType.getString(context, "color");
                int interval = IntegerArgumentType.getInteger(context, "interval");
                IPlatformAdapter.BossBarColor bossBarColor;
                try {
                    bossBarColor = IPlatformAdapter.BossBarColor.valueOf(colorStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    platform.sendFailure(context.getSource(), platform.createLiteralComponent("Invalid bossbar color: " + colorStr));
                    return 0;
                }
                platform.getOnlinePlayers().forEach(player -> {
                    IPlayer iPlayer = platform.wrapPlayer(player);
                    IComponent messageComp = services.getMessageParser().parseMessage(messageStr, iPlayer);
                    platform.sendBossBar(Collections.singletonList(player), messageComp.getOriginalText(), interval, bossBarColor, 1.0f);
                });
            }
            default -> {
                platform.sendFailure(context.getSource(), platform.createLiteralComponent("Invalid message type for command: " + type));
                return 0;
            }
        }
        platform.sendSuccess(context.getSource(), platform.createLiteralComponent(type + " broadcasted."), true);
        return 1;
    }

    public void rescheduleAnnouncements() {
        if (services == null) return;
        services.getDebugLogger().debugLog(NAME + ": Rescheduling announcements on config reload.");
        cancelAllTasks();
        announcementsScheduled = false;
        scheduleConfiguredAnnouncements();
    }
}
