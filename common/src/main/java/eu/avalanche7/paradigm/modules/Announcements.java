package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.configs.AnnouncementsConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandBuilder;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandContext;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

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
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        List<String> bossBarColors = new ArrayList<>();
        for (IPlatformAdapter.BossBarColor c : IPlatformAdapter.BossBarColor.values()) {
            bossBarColors.add(c.name().toLowerCase());
        }

        ICommandBuilder cmd = platform.createCommandBuilder()
                .literal("paradigm")
                .requires(source -> source.hasPermissionLevel(PermissionsHandler.BROADCAST_PERMISSION_LEVEL)
                        || (source.getPlayer() != null && platform.hasPermission(source.getPlayer(), PermissionsHandler.BROADCAST_PERMISSION)))
                .then(platform.createCommandBuilder()
                        .literal("broadcast")
                        .then(platform.createCommandBuilder()
                                .argument("message", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(ctx -> broadcastMessageCmd(ctx, "broadcast"))))
                .then(platform.createCommandBuilder()
                        .literal("actionbar")
                        .then(platform.createCommandBuilder()
                                .argument("message", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(ctx -> broadcastMessageCmd(ctx, "actionbar"))))
                .then(platform.createCommandBuilder()
                        .literal("title")
                        .then(platform.createCommandBuilder()
                                .argument("titleAndSubtitle", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(this::broadcastTitleCmd)))
                .then(platform.createCommandBuilder()
                        .literal("bossbar")
                        .then(platform.createCommandBuilder()
                                .argument("interval", ICommandBuilder.ArgumentType.INTEGER)
                                .then(platform.createCommandBuilder()
                                        .argument("color", ICommandBuilder.ArgumentType.WORD)
                                        .suggests(bossBarColors)
                                        .then(platform.createCommandBuilder()
                                                .argument("message", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                                .executes(ctx -> broadcastMessageCmd(ctx, "bossbar"))))));

        platform.registerCommand(cmd);
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
        return messageText
                .replace("{Prefix}", prefix)
                .replace("{prefix}", prefix);
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
            if (headerFooter) {
                IComponent headerComp = services.getMessageParser().parseMessage(header, player);
                IComponent messageComp = services.getMessageParser().parseMessage(messageText, player);
                IComponent footerComp = services.getMessageParser().parseMessage(footer, player);
                platform.sendSystemMessage(player, headerComp);
                platform.sendSystemMessage(player, messageComp);
                platform.sendSystemMessage(player, footerComp);
            } else {
                IComponent messageComp = services.getMessageParser().parseMessage(messageText, player);
                platform.sendSystemMessage(player, messageComp);
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
            IComponent messageComp = services.getMessageParser().parseMessage(messageText, player);
            platform.sendActionBar(player, messageComp);
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
            String[] parts = messageText.split(" \\|\\| ", 2);
            IComponent titleComp = services.getMessageParser().parseMessage(parts[0], player);
            IComponent subtitleComp = parts.length > 1 ? services.getMessageParser().parseMessage(parts[1], player) : services.getMessageParser().parseMessage("", player);
            platform.clearTitles(player);
            platform.sendTitle(player, titleComp, subtitleComp);
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
            IComponent messageComp = services.getMessageParser().parseMessage(messageText, player);
            platform.sendBossBar(Collections.singletonList(player), messageComp, bossbarTime, bossbarColor, 1.0f);
        });
        services.getDebugLogger().debugLog(NAME + ": Broadcasted bossbar message: " + messageText);
    }

    public int broadcastTitleCmd(ICommandContext context) {
        String titleAndSubtitle = context.getStringArgument("titleAndSubtitle");
        if (titleAndSubtitle == null) titleAndSubtitle = "";

        ICommandSource source = context.getSource();
        services.getDebugLogger().debugLog(NAME + ": /paradigm title command executed with message: " + titleAndSubtitle);

        String[] parts = titleAndSubtitle.split(" \\\\|\\\\| ", 2);
        platform.getOnlinePlayers().forEach(target -> {
            IComponent titleComp = services.getMessageParser().parseMessage(parts.length > 0 ? parts[0] : "", target);
            IComponent subtitleComp = parts.length > 1 ? services.getMessageParser().parseMessage(parts[1], target) : services.getMessageParser().parseMessage("", target);
            platform.clearTitles(target);
            platform.sendTitle(target, titleComp, subtitleComp);
        });

        platform.sendSuccess(source, platform.createLiteralComponent("Title broadcasted."), true);
        return 1;
    }

    public int broadcastMessageCmd(ICommandContext context, String type) {
        String messageStr = context.getStringArgument("message");
        final String msg = messageStr == null ? "" : messageStr;

        ICommandSource source = context.getSource();
        services.getDebugLogger().debugLog(NAME + ": /paradigm " + type + " command executed with message: " + msg);

        switch (type) {
            case "broadcast" -> platform.getOnlinePlayers().forEach(player -> {
                IComponent messageComp = services.getMessageParser().parseMessage(msg, player);
                platform.sendSystemMessage(player, messageComp);
            });
            case "actionbar" -> platform.getOnlinePlayers().forEach(player -> {
                IComponent messageComp = services.getMessageParser().parseMessage(msg, player);
                platform.sendActionBar(player, messageComp);
            });
            case "bossbar" -> {
                String colorStr = context.getStringArgument("color");
                int interval = context.getIntArgument("interval");

                if (colorStr == null) colorStr = "";
                IPlatformAdapter.BossBarColor bossBarColor;
                try {
                    bossBarColor = IPlatformAdapter.BossBarColor.valueOf(colorStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    platform.sendFailure(source, platform.createLiteralComponent("Invalid bossbar color: " + colorStr));
                    return 0;
                }

                platform.getOnlinePlayers().forEach(player -> {
                    IComponent messageComp = services.getMessageParser().parseMessage(msg, player);
                    platform.sendBossBar(Collections.singletonList(player), messageComp, interval, bossBarColor, 1.0f);
                });
            }
            default -> {
                platform.sendFailure(source, platform.createLiteralComponent("Invalid message type for command: " + type));
                return 0;
            }
        }

        platform.sendSuccess(source, platform.createLiteralComponent(type + " broadcasted."), true);
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
