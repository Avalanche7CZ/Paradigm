package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.paradigm.configs.AnnouncementsConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

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

    private List<Integer> globalRandomPool = new ArrayList<>();
    private List<Integer> actionbarRandomPool = new ArrayList<>();
    private List<Integer> titleRandomPool = new ArrayList<>();
    private List<Integer> bossbarRandomPool = new ArrayList<>();

    private boolean tasksScheduled = false;
    private ScheduledFuture<?> globalTask = null;
    private ScheduledFuture<?> actionbarTask = null;
    private ScheduledFuture<?> titleTask = null;
    private ScheduledFuture<?> bossbarTask = null;
    private IPlatformAdapter platform;

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
        this.platform = services.getPlatformAdapter();
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
                                                    for (IPlatformAdapter.BossBarColor color : IPlatformAdapter.BossBarColor.values()) {
                                                        builder.suggest(color.name().toLowerCase());
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
        if (platform == null) {
            services.getDebugLogger().debugLog(NAME + ": Cannot schedule announcements, platform adapter is null.");
            return;
        }

        initializeRandomPools(config);

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

    private void initializeRandomPools(AnnouncementsConfigHandler.Config config) {
        globalRandomPool.clear();
        for (int i = 0; i < config.globalMessages.value.size(); i++) {
            globalRandomPool.add(i);
        }
        Collections.shuffle(globalRandomPool, random);

        actionbarRandomPool.clear();
        for (int i = 0; i < config.actionbarMessages.value.size(); i++) {
            actionbarRandomPool.add(i);
        }
        Collections.shuffle(actionbarRandomPool, random);

        titleRandomPool.clear();
        for (int i = 0; i < config.titleMessages.value.size(); i++) {
            titleRandomPool.add(i);
        }
        Collections.shuffle(titleRandomPool, random);

        bossbarRandomPool.clear();
        for (int i = 0; i < config.bossbarMessages.value.size(); i++) {
            bossbarRandomPool.add(i);
        }
        Collections.shuffle(bossbarRandomPool, random);
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

    private int getNextRandomIndex(List<Integer> pool, int messageCount) {
        if (pool.isEmpty()) {
            for (int i = 0; i < messageCount; i++) {
                pool.add(i);
            }
            Collections.shuffle(pool, random);
        }
        return pool.remove(0);
    }

    private void broadcastGlobalMessages(Services services) {
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (platform == null || config.globalMessages.value.isEmpty()) return;

        List<String> messages = config.globalMessages.value;
        String prefix = config.prefix.value != null ? config.prefix.value + "§r" : "";
        String header = config.header.value != null ? config.header.value : "";
        String footer = config.footer.value != null ? config.footer.value : "";

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.value)) {
            messageText = messages.get(globalMessageIndex).replace("{Prefix}", prefix);
            globalMessageIndex = (globalMessageIndex + 1) % messages.size();
        } else {
            int randomIndex = getNextRandomIndex(globalRandomPool, messages.size());
            messageText = messages.get(randomIndex).replace("{Prefix}", prefix);
        }

        Text message = services.getMessageParser().parseMessage(messageText, null);

        if (config.headerAndFooter.value) {
            platform.broadcastSystemMessage(message, header, footer, null);
        } else {
            platform.broadcastChatMessage(message);
        }
        services.getDebugLogger().debugLog(NAME + ": Broadcasted global message: {}", message.getString());
    }

    private void broadcastActionbarMessages(Services services) {
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (platform == null || config.actionbarMessages.value.isEmpty()) return;

        List<String> messages = config.actionbarMessages.value;
        String prefix = config.prefix.value != null ? config.prefix.value + "§r" : "";

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.value)) {
            messageText = messages.get(actionbarMessageIndex).replace("{Prefix}", prefix);
            actionbarMessageIndex = (actionbarMessageIndex + 1) % messages.size();
        } else {
            int randomIndex = getNextRandomIndex(actionbarRandomPool, messages.size());
            messageText = messages.get(randomIndex).replace("{Prefix}", prefix);
        }
        Text message = services.getMessageParser().parseMessage(messageText, null);

        platform.getOnlinePlayers().forEach(player ->
            platform.sendActionBar(player, message)
        );
        services.getDebugLogger().debugLog(NAME + ": Broadcasted actionbar message: {}", message.getString());
    }

    private void broadcastTitleMessages(Services services) {
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (platform == null || config.titleMessages.value.isEmpty()) return;

        List<String> messages = config.titleMessages.value;
        String prefix = config.prefix.value != null ? config.prefix.value + "§r" : "";

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.value)) {
            messageText = messages.get(titleMessageIndex).replace("{Prefix}", prefix);
            titleMessageIndex = (titleMessageIndex + 1) % messages.size();
        } else {
            int randomIndex = getNextRandomIndex(titleRandomPool, messages.size());
            messageText = messages.get(randomIndex).replace("{Prefix}", prefix);
        }

        String[] parts = messageText.split(" \\|\\| ", 2);
        String titleStr = parts[0].trim();
        String subtitleStr = parts.length > 1 ? parts[1].trim() : "";
        Text titleComponent = !titleStr.isEmpty() ? services.getMessageParser().parseMessage(titleStr, null) : Text.empty();
        Text subtitleComponent = !subtitleStr.isEmpty() ? services.getMessageParser().parseMessage(subtitleStr, null) : Text.empty();

        platform.getOnlinePlayers().forEach(player -> {
            platform.clearTitles(player);
            if (!titleComponent.getString().isEmpty() && !subtitleComponent.getString().isEmpty()) {
                platform.sendTitle(player, titleComponent, subtitleComponent);
            } else if (!titleComponent.getString().isEmpty()) {
                platform.sendTitle(player, titleComponent, Text.empty());
            } else if (!subtitleComponent.getString().isEmpty()) {
                platform.sendSubtitle(player, subtitleComponent);
            }
        });
        services.getDebugLogger().debugLog(NAME + ": Broadcasted title message: {}", messageText);
    }

    private void broadcastBossbarMessages(Services services) {
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (platform == null || config.bossbarMessages.value.isEmpty()) return;

        List<String> messages = config.bossbarMessages.value;
        String prefix = config.prefix.value != null ? config.prefix.value + "§r" : "";
        int bossbarTime = config.bossbarTime.value != null ? config.bossbarTime.value : 10;
        String colorStr = config.bossbarColor.value != null ? config.bossbarColor.value.toUpperCase() : "PURPLE";
        IPlatformAdapter.BossBarColor bossbarColor;
        try {
            bossbarColor = IPlatformAdapter.BossBarColor.valueOf(colorStr);
        } catch (IllegalArgumentException e) {
            services.getDebugLogger().debugLog(NAME + ": Invalid bossbar color: {}. Defaulting to PURPLE.", config.bossbarColor.value);
            bossbarColor = IPlatformAdapter.BossBarColor.PURPLE;
        }

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.value)) {
            messageText = messages.get(bossbarMessageIndex).replace("{Prefix}", prefix);
            bossbarMessageIndex = (bossbarMessageIndex + 1) % messages.size();
        } else {
            int randomIndex = getNextRandomIndex(bossbarRandomPool, messages.size());
            messageText = messages.get(randomIndex).replace("{Prefix}", prefix);
        }
        Text message = services.getMessageParser().parseMessage(messageText, null);

        platform.sendBossBar(
                platform.getOnlinePlayers(),
                message,
                bossbarTime,
                bossbarColor,
                1.0f
        );
        services.getDebugLogger().debugLog(NAME + ": Broadcasted bossbar message: {}", message.getString());
    }

    public int broadcastTitleCmd(CommandContext<ServerCommandSource> context, String title, String subtitle, Services services) {
        ServerCommandSource source = context.getSource();
        if (platform == null) {
            source.sendError(Text.literal("Platform not available."));
            return 0;
        }
        Text titleComponent = (title != null && !title.isEmpty()) ? services.getMessageParser().parseMessage(title, null) : Text.empty();
        Text subtitleComponent = (subtitle != null && !subtitle.isEmpty()) ? services.getMessageParser().parseMessage(subtitle, null) : Text.empty();
        platform.getOnlinePlayers().forEach(player -> {
            platform.clearTitles(player);
            if (!titleComponent.getString().isEmpty() && !subtitleComponent.getString().isEmpty()) {
                platform.sendTitle(player, titleComponent, subtitleComponent);
            } else if (!titleComponent.getString().isEmpty()) {
                platform.sendTitle(player, titleComponent, Text.empty());
            } else if (!subtitleComponent.getString().isEmpty()) {
                platform.sendSubtitle(player, subtitleComponent);
            }
        });
        source.sendFeedback(() -> platform.createLiteralComponent("Title broadcasted."), true);
        return 1;
    }

    public int broadcastMessageCmd(CommandContext<ServerCommandSource> context, String type, Services services) {
        String messageStr = StringArgumentType.getString(context, "message");
        ServerCommandSource source = context.getSource();
        if (platform == null) {
            source.sendError(Text.literal("Platform not available."));
            return 0;
        }
        Text broadcastMessage = services.getMessageParser().parseMessage(messageStr, null);
        switch (type) {
            case "broadcast": {
                boolean headerFooter = BoolArgumentType.getBool(context, "header_footer");
                if (headerFooter) {
                    String header = services.getAnnouncementsConfig().header.value;
                    String footer = services.getAnnouncementsConfig().footer.value;
                    platform.broadcastSystemMessage(broadcastMessage, header, footer, null);
                } else {
                    platform.broadcastChatMessage(broadcastMessage);
                }
                break;
            }
            case "actionbar":
                platform.getOnlinePlayers().forEach(player ->
                    platform.sendActionBar(player, broadcastMessage)
                );
                break;
            case "bossbar": {
                String colorStr = StringArgumentType.getString(context, "color");
                int interval = IntegerArgumentType.getInteger(context, "interval");
                IPlatformAdapter.BossBarColor bossBarColor;
                try {
                    bossBarColor = IPlatformAdapter.BossBarColor.valueOf(colorStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    source.sendError(Text.literal("Invalid bossbar color: " + colorStr));
                    return 0;
                }
                platform.sendBossBar(
                    platform.getOnlinePlayers(),
                    broadcastMessage,
                    interval,
                    bossBarColor,
                    1.0f
                );
                break;
            }
            default:
                source.sendError(Text.literal("Invalid message type for command: " + type));
                return 0;
        }
        return 1;
    }
}
