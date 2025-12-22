package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.avalanche7.paradigm.configs.AnnouncementsConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IPlatformAdapter;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class Announcements implements ParadigmModule {

    private static final String NAME = "Announcements";
    private final Random random = new Random();
    private int globalMessageIndex = 0;
    private int actionbarMessageIndex = 0;
    private int titleMessageIndex = 0;
    private int bossbarMessageIndex = 0;
    private int lastGlobalRandomIndex = -1;
    private int lastActionbarRandomIndex = -1;
    private int lastTitleRandomIndex = -1;
    private int lastBossbarRandomIndex = -1;
    private boolean announcementsScheduled = false;
    private IPlatformAdapter platform;
    private Services services;
    private java.util.concurrent.ScheduledFuture<?> globalTask;
    private java.util.concurrent.ScheduledFuture<?> actionbarTask;
    private java.util.concurrent.ScheduledFuture<?> titleTask;
    private java.util.concurrent.ScheduledFuture<?> bossbarTask;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().announcementsEnable.get();
    }

    @Override
    public void onLoad(FMLCommonSetupEvent event, Services services, IEventBus modEventBus) {
        this.services = services;
        this.platform = services.getPlatformAdapter();
        services.getDebugLogger().debugLog("{} module loaded.", NAME);
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {
        if (isEnabled(services) && !announcementsScheduled) {
            services.getDebugLogger().debugLog("{}: Server is starting, scheduling announcements.", NAME);
            scheduleConfiguredAnnouncements();
        }
    }

    @Override
    public void onEnable(Services services) {
        if (platform.getMinecraftServer() != null && isEnabled(services) && !announcementsScheduled) {
            services.getDebugLogger().debugLog("{}: Module enabled, scheduling announcements.", NAME);
            scheduleConfiguredAnnouncements();
        }
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog("{}: Module disabled. Scheduled announcement tasks will be terminated.", NAME);
        announcementsScheduled = false;
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
    }

    @Override
    public void registerCommands(CommandDispatcher<?> dispatcher, Services services) {
        services.getDebugLogger().debugLog("{}: Registering commands.", NAME);
        CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcherCS = (CommandDispatcher<net.minecraft.commands.CommandSourceStack>) dispatcher;
        dispatcherCS.register(
                LiteralArgumentBuilder.<net.minecraft.commands.CommandSourceStack>literal("paradigm")
                        .requires(source -> source.hasPermission(2))
                        .then(LiteralArgumentBuilder.<net.minecraft.commands.CommandSourceStack>literal("broadcast")
                                .then(RequiredArgumentBuilder.<net.minecraft.commands.CommandSourceStack, String>argument("message", StringArgumentType.greedyString())
                                        .executes(this::broadcastMessageCmd)))
                        .then(LiteralArgumentBuilder.<net.minecraft.commands.CommandSourceStack>literal("actionbar")
                                .then(RequiredArgumentBuilder.<net.minecraft.commands.CommandSourceStack, String>argument("message", StringArgumentType.greedyString())
                                        .executes(this::broadcastMessageCmd)))
                        .then(LiteralArgumentBuilder.<net.minecraft.commands.CommandSourceStack>literal("title")
                                .then(RequiredArgumentBuilder.<net.minecraft.commands.CommandSourceStack, String>argument("titleAndSubtitle", StringArgumentType.greedyString())
                                        .executes(this::broadcastTitleCmd)))
                        .then(LiteralArgumentBuilder.<net.minecraft.commands.CommandSourceStack>literal("bossbar")
                                .then(RequiredArgumentBuilder.<net.minecraft.commands.CommandSourceStack, Integer>argument("interval", IntegerArgumentType.integer(1))
                                        .then(RequiredArgumentBuilder.<net.minecraft.commands.CommandSourceStack, String>argument("color", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    for (IPlatformAdapter.BossBarColor color : IPlatformAdapter.BossBarColor.values()) {
                                                        builder.suggest(color.name());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(RequiredArgumentBuilder.<net.minecraft.commands.CommandSourceStack, String>argument("message", StringArgumentType.greedyString())
                                                        .executes(this::broadcastMessageCmd))))));
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {}

    public void rescheduleAnnouncements() {
        services.getDebugLogger().debugLog("{}: rescheduleAnnouncements() called.", NAME);
        // Cancel existing tasks if running
        if (globalTask != null) { globalTask.cancel(false); globalTask = null; }
        if (actionbarTask != null) { actionbarTask.cancel(false); actionbarTask = null; }
        if (titleTask != null) { titleTask.cancel(false); titleTask = null; }
        if (bossbarTask != null) { bossbarTask.cancel(false); bossbarTask = null; }
        announcementsScheduled = false;
        scheduleConfiguredAnnouncements();
    }

    private void scheduleConfiguredAnnouncements() {
        services.getDebugLogger().debugLog("{}: scheduleConfiguredAnnouncements() called. Config: globalInterval={}, actionbarInterval={}, titleInterval={}, bossbarInterval={}", NAME,
            services.getAnnouncementsConfig().globalInterval.get(),
            services.getAnnouncementsConfig().actionbarInterval.get(),
            services.getAnnouncementsConfig().titleInterval.get(),
            services.getAnnouncementsConfig().bossbarInterval.get());
        if (announcementsScheduled) {
            services.getDebugLogger().debugLog("{}: Announcements already scheduled, skipping duplicate scheduling.", NAME);
            return;
        }
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (platform.getMinecraftServer() == null) {
            services.getDebugLogger().debugLog("{}: Server not available, skipping announcement scheduling.", NAME);
            return;
        }
        services.getDebugLogger().debugLog("{}: Scheduling announcements based on config.", NAME);
        if (config.globalEnable.get()) {
            long interval = config.globalInterval.get();
            services.getDebugLogger().debugLog("{}: Scheduling global announcements every {} seconds.", NAME, interval);
            globalTask = services.getTaskScheduler().scheduleAtFixedRate(this::broadcastGlobalMessages, interval, interval, TimeUnit.SECONDS);
        }
        if (config.actionbarEnable.get()) {
            long interval = config.actionbarInterval.get();
            services.getDebugLogger().debugLog("{}: Scheduling actionbar announcements every {} seconds.", NAME, interval);
            actionbarTask = services.getTaskScheduler().scheduleAtFixedRate(this::broadcastActionbarMessages, interval, interval, TimeUnit.SECONDS);
        }
        if (config.titleEnable.get()) {
            long interval = config.titleInterval.get();
            services.getDebugLogger().debugLog("{}: Scheduling title announcements every {} seconds.", NAME, interval);
            titleTask = services.getTaskScheduler().scheduleAtFixedRate(this::broadcastTitleMessages, interval, interval, TimeUnit.SECONDS);
        }
        if (config.bossbarEnable.get()) {
            long interval = config.bossbarInterval.get();
            services.getDebugLogger().debugLog("{}: Scheduling bossbar announcements every {} seconds.", NAME, interval);
            bossbarTask = services.getTaskScheduler().scheduleAtFixedRate(this::broadcastBossbarMessages, interval, interval, TimeUnit.SECONDS);
        }
        announcementsScheduled = true;
        services.getDebugLogger().debugLog("{}: All announcements successfully scheduled.", NAME);
    }

    private void broadcastGlobalMessages() {
        services.getDebugLogger().debugLog("{}: Firing global announcement task.", NAME);
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (config.globalMessages.get().isEmpty()) {
            services.getDebugLogger().debugLog("{}: No global messages configured, skipping.", NAME);
            return;
        }

        String messageText = getNextMessage(config.globalMessages.get(), config.orderMode.get(), "global");
        String processedMessage = replacePrefixPlaceholder(messageText);

        platform.getOnlinePlayers().forEach(player -> {
            if (config.headerAndFooter.get()) {
                IComponent header = services.getMessageParser().parseMessage(config.header.get(), player);
                IComponent message = services.getMessageParser().parseMessage(processedMessage, player);
                IComponent footer = services.getMessageParser().parseMessage(config.footer.get(), player);
                platform.sendSystemMessage(player, header);
                platform.sendSystemMessage(player, message);
                platform.sendSystemMessage(player, footer);
            } else {
                IComponent message = services.getMessageParser().parseMessage(processedMessage, player);
                platform.sendSystemMessage(player, message);
            }
        });
    }

    private void broadcastActionbarMessages() {
        services.getDebugLogger().debugLog("{}: Firing actionbar announcement task.", NAME);
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (config.actionbarMessages.get().isEmpty()) {
            services.getDebugLogger().debugLog("{}: No actionbar messages configured, skipping.", NAME);
            return;
        }

        String messageText = getNextMessage(config.actionbarMessages.get(), config.orderMode.get(), "actionbar");
        String processedMessage = replacePrefixPlaceholder(messageText);

        platform.getOnlinePlayers().forEach(player -> {
            platform.sendActionBar(player, processedMessage);
        });
    }

    private void broadcastTitleMessages() {
        services.getDebugLogger().debugLog("{}: Firing title announcement task.", NAME);
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (config.titleMessages.get().isEmpty()) {
            services.getDebugLogger().debugLog("{}: No title messages configured, skipping.", NAME);
            return;
        }

        String messageText = getNextMessage(config.titleMessages.get(), config.orderMode.get(), "title");
        String processedMessage = replacePrefixPlaceholder(messageText);

        platform.getOnlinePlayers().forEach(player -> {
            String[] parts = processedMessage.split(" \\|\\| ", 2);
            IComponent title = services.getMessageParser().parseMessage(parts[0], player);
            IComponent subtitle = parts.length > 1 ? services.getMessageParser().parseMessage(parts[1], player) : platform.createLiteralComponent("");
            platform.clearTitles(player);
            platform.sendTitle(player, title, subtitle);
        });
    }

    private void broadcastBossbarMessages() {
        services.getDebugLogger().debugLog("{}: Firing bossbar announcement task.", NAME);
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (config.bossbarMessages.get().isEmpty()) {
            services.getDebugLogger().debugLog("{}: No bossbar messages configured, skipping.", NAME);
            return;
        }

        String messageText = getNextMessage(config.bossbarMessages.get(), config.orderMode.get(), "bossbar");
        String processedMessage = replacePrefixPlaceholder(messageText);
        int duration = config.bossbarTime.get();
        IPlatformAdapter.BossBarColor color = IPlatformAdapter.BossBarColor.valueOf(config.bossbarColor.get().toUpperCase());

        platform.getOnlinePlayers().forEach(player -> {
            platform.sendBossBar(Collections.singletonList(player), processedMessage, duration, color, 1.0f);
        });
    }

    /**
     * Replaces {Prefix} placeholder with actual prefix
     */
    private String replacePrefixPlaceholder(String message) {
        if (!message.contains("{Prefix}")) {
            return message;
        }

        String prefix = services.getAnnouncementsConfig().prefix.get();
        return message.replace("{Prefix}", prefix);
    }

    private String getNextMessage(List<? extends String> messages, String orderMode, String type) {
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
            services.getDebugLogger().debugLog("{}: Picked sequential message for type '{}' at index {}: \"{}\"", NAME, type, index, messageText);
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
            services.getDebugLogger().debugLog("{}: Picked random message for type '{}' at index {}: \"{}\"", NAME, type, index, messageText);
        }
        return messageText;
    }

    private int broadcastTitleCmd(CommandContext<net.minecraft.commands.CommandSourceStack> context) throws CommandSyntaxException {
        String titleAndSubtitle = StringArgumentType.getString(context, "titleAndSubtitle");
        ICommandSource source = platform.wrapCommandSource(context.getSource());
        services.getDebugLogger().debugLog("{}: /paradigm title command executed by {} with message: \"{}\"",
                NAME, source.getSourceName(), titleAndSubtitle);

        String[] parts = titleAndSubtitle.split(" \\|\\| ", 2);

        platform.getOnlinePlayers().forEach(target -> {
            IComponent title = services.getMessageParser().parseMessage(parts[0], target);
            IComponent subtitle = parts.length > 1 ? services.getMessageParser().parseMessage(parts[1], target) : platform.createLiteralComponent("");
            platform.clearTitles(target);
            platform.sendTitle(target, title, subtitle);
        });

        platform.sendSuccess(source, platform.createLiteralComponent("Title broadcasted."), !source.isConsole());
        return 1;
    }

    private int broadcastMessageCmd(CommandContext<net.minecraft.commands.CommandSourceStack> context) throws CommandSyntaxException {
        String messageStr = StringArgumentType.getString(context, "message");
        String commandName = context.getNodes().get(1).getNode().getName();
        ICommandSource source = platform.wrapCommandSource(context.getSource());
        services.getDebugLogger().debugLog("{}: /paradigm {} command executed by {} with message: \"{}\"",
                NAME, commandName, source.getSourceName(), messageStr);

        switch (commandName) {
            case "broadcast" -> platform.getOnlinePlayers().forEach(player -> {
                IComponent message = services.getMessageParser().parseMessage(messageStr, player);
                platform.sendSystemMessage(player, message);
            });
            case "actionbar" -> platform.getOnlinePlayers().forEach(player -> {
                IComponent message = services.getMessageParser().parseMessage(messageStr, player);
                platform.sendActionBar(player, message);
            });
            case "bossbar" -> {
                String colorStr = StringArgumentType.getString(context, "color");
                int interval = IntegerArgumentType.getInteger(context, "interval");
                IPlatformAdapter.BossBarColor color = IPlatformAdapter.BossBarColor.valueOf(colorStr.toUpperCase());
                services.getDebugLogger().debugLog("{}: Bossbar broadcast details: color={}, interval={}", NAME, colorStr, interval);
                platform.getOnlinePlayers().forEach(player -> {
                    IComponent message = services.getMessageParser().parseMessage(messageStr, player);
                    platform.sendBossBar(Collections.singletonList(player), message, interval, color, 1.0f);
                });
            }
        }
        platform.sendSuccess(source, platform.createLiteralComponent(commandName + " broadcasted."), !source.isConsole());
        return 1;
    }
}
