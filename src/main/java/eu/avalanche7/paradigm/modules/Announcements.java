package eu.avalanche7.paradigm.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.avalanche7.paradigm.configs.AnnouncementsConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.IPlatformAdapter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import com.mojang.brigadier.arguments.IntegerArgumentType;

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
    private IPlatformAdapter platform;
    private Services services;

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
        if (isEnabled(services)) {
            services.getDebugLogger().debugLog("{}: Server is starting, scheduling announcements.", NAME);
            scheduleConfiguredAnnouncements();
        }
    }

    @Override
    public void onEnable(Services services) {
        if (platform.getMinecraftServer() != null && isEnabled(services)) {
            services.getDebugLogger().debugLog("{}: Module enabled, scheduling announcements.", NAME);
            scheduleConfiguredAnnouncements();
        }
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog("{}: Module disabled. Scheduled announcement tasks will be terminated.", NAME);
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        services.getDebugLogger().debugLog("{}: Registering commands.", NAME);
        dispatcher.register(
                Commands.literal("paradigm")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("broadcast")
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(this::broadcastMessageCmd)))
                        .then(Commands.literal("actionbar")
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(this::broadcastMessageCmd)))
                        .then(Commands.literal("title")
                                .then(Commands.argument("titleAndSubtitle", StringArgumentType.greedyString())
                                        .executes(this::broadcastTitleCmd)))
                        .then(Commands.literal("bossbar")
                                .then(Commands.argument("interval", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("color", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    for (IPlatformAdapter.BossBarColor color : IPlatformAdapter.BossBarColor.values()) {
                                                        builder.suggest(color.name());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                                        .executes(this::broadcastMessageCmd)))))
        );
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {}

    private void scheduleConfiguredAnnouncements() {
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (platform.getMinecraftServer() == null) {
            services.getDebugLogger().debugLog("{}: Server not available, skipping announcement scheduling.", NAME);
            return;
        }
        services.getDebugLogger().debugLog("{}: Scheduling announcements based on config.", NAME);

        if (config.globalEnable.get()) {
            long interval = config.globalInterval.get();
            services.getDebugLogger().debugLog("{}: Scheduling global announcements every {} seconds.", NAME, interval);
            services.getTaskScheduler().scheduleAtFixedRate(this::broadcastGlobalMessages, interval, interval, TimeUnit.SECONDS);
        }
        if (config.actionbarEnable.get()) {
            long interval = config.actionbarInterval.get();
            services.getDebugLogger().debugLog("{}: Scheduling actionbar announcements every {} seconds.", NAME, interval);
            services.getTaskScheduler().scheduleAtFixedRate(this::broadcastActionbarMessages, interval, interval, TimeUnit.SECONDS);
        }
        if (config.titleEnable.get()) {
            long interval = config.titleInterval.get();
            services.getDebugLogger().debugLog("{}: Scheduling title announcements every {} seconds.", NAME, interval);
            services.getTaskScheduler().scheduleAtFixedRate(this::broadcastTitleMessages, interval, interval, TimeUnit.SECONDS);
        }
        if (config.bossbarEnable.get()) {
            long interval = config.bossbarInterval.get();
            services.getDebugLogger().debugLog("{}: Scheduling bossbar announcements every {} seconds.", NAME, interval);
            services.getTaskScheduler().scheduleAtFixedRate(this::broadcastBossbarMessages, interval, interval, TimeUnit.SECONDS);
        }
    }

    private void broadcastGlobalMessages() {
        services.getDebugLogger().debugLog("{}: Firing global announcement task.", NAME);
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (config.globalMessages.get().isEmpty()) {
            services.getDebugLogger().debugLog("{}: No global messages configured, skipping.", NAME);
            return;
        }

        String messageText = getNextMessage(config.globalMessages.get(), config.orderMode.get(), "global");

        platform.getOnlinePlayers().forEach(player -> {
            Component message = services.getMessageParser().parseMessage(messageText, player);
            if (config.headerAndFooter.get()) {
                platform.broadcastSystemMessage(message, config.header.get(), config.footer.get(), player);
            } else {
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

        platform.getOnlinePlayers().forEach(player -> {
            Component message = services.getMessageParser().parseMessage(messageText, player);
            platform.sendActionBar(player, message);
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

        platform.getOnlinePlayers().forEach(player -> {
            String[] parts = messageText.split(" \\|\\| ", 2);
            Component title = services.getMessageParser().parseMessage(parts[0], player);
            Component subtitle = parts.length > 1 ? services.getMessageParser().parseMessage(parts[1], player) : Component.empty();
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
        int duration = config.bossbarTime.get();
        IPlatformAdapter.BossBarColor color = IPlatformAdapter.BossBarColor.valueOf(config.bossbarColor.get().toUpperCase());

        platform.getOnlinePlayers().forEach(player -> {
            Component message = services.getMessageParser().parseMessage(messageText, player);
            platform.sendBossBar(Collections.singletonList(player), message, duration, color, 1.0f);
        });
    }

    private String getNextMessage(List<? extends String> messages, String orderMode, String type) {
        String prefix = services.getAnnouncementsConfig().prefix.get() + "§r";
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
            int index = random.nextInt(messages.size());
            messageText = messages.get(index);
            services.getDebugLogger().debugLog("{}: Picked random message for type '{}' at index {}: \"{}\"", NAME, type, index, messageText);
        }
        return messageText.replace("{Prefix}", prefix);
    }

    private int broadcastTitleCmd(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String titleAndSubtitle = StringArgumentType.getString(context, "titleAndSubtitle");
        services.getDebugLogger().debugLog("{}: /paradigm title command executed by {} with message: \"{}\"",
                NAME, context.getSource().getDisplayName().getString(), titleAndSubtitle);

        String[] parts = titleAndSubtitle.split(" \\|\\| ", 2);

        platform.getOnlinePlayers().forEach(target -> {
            Component title = services.getMessageParser().parseMessage(parts[0], target);
            Component subtitle = parts.length > 1 ? services.getMessageParser().parseMessage(parts[1], target) : Component.empty();
            platform.clearTitles(target);
            platform.sendTitle(target, title, subtitle);
        });

        platform.sendSuccess(context.getSource(), Component.literal("Title broadcasted."), true);
        return 1;
    }

    private int broadcastMessageCmd(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String messageStr = StringArgumentType.getString(context, "message");
        String commandName = context.getNodes().get(1).getNode().getName();

        services.getDebugLogger().debugLog("{}: /paradigm {} command executed by {} with message: \"{}\"",
                NAME, commandName, context.getSource().getDisplayName().getString(), messageStr);

        switch (commandName) {
            case "broadcast" -> platform.getOnlinePlayers().forEach(player -> {
                Component message = services.getMessageParser().parseMessage(messageStr, player);
                platform.sendSystemMessage(player, message);
            });
            case "actionbar" -> platform.getOnlinePlayers().forEach(player -> {
                Component message = services.getMessageParser().parseMessage(messageStr, player);
                platform.sendActionBar(player, message);
            });
            case "bossbar" -> {
                String colorStr = StringArgumentType.getString(context, "color");
                int interval = IntegerArgumentType.getInteger(context, "interval");
                IPlatformAdapter.BossBarColor color = IPlatformAdapter.BossBarColor.valueOf(colorStr.toUpperCase());
                services.getDebugLogger().debugLog("{}: Bossbar broadcast details: color={}, interval={}", NAME, colorStr, interval);
                platform.getOnlinePlayers().forEach(player -> {
                    Component message = services.getMessageParser().parseMessage(messageStr, player);
                    platform.sendBossBar(Collections.singletonList(player), message, interval, color, 1.0f);
                });
            }
        }
        platform.sendSuccess(context.getSource(), Component.literal(commandName + " broadcasted."), true);
        return 1;
    }
}