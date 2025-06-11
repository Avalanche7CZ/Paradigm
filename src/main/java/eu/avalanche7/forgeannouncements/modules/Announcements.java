package eu.avalanche7.forgeannouncements.modules;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.avalanche7.forgeannouncements.configs.AnnouncementsConfigHandler;
import eu.avalanche7.forgeannouncements.core.ForgeAnnouncementModule;
import eu.avalanche7.forgeannouncements.core.Services;
import eu.avalanche7.forgeannouncements.utils.PermissionsHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class Announcements implements ForgeAnnouncementModule {

    private static final String NAME = "Announcements";
    private final Random random = new Random();
    private int globalMessageIndex = 0;
    private int actionbarMessageIndex = 0;
    private int titleMessageIndex = 0;
    private int bossbarMessageIndex = 0;


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
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(ServerStartingEvent event, Services services) {
        if (isEnabled(services)) {
            services.getDebugLogger().debugLog(NAME + " module: Server starting, scheduling announcements if enabled.");
            scheduleConfiguredAnnouncements(services);
        }
    }

    @Override
    public void onEnable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module enabled.");
        if (services.getMinecraftServer() != null && isEnabled(services)) {
            scheduleConfiguredAnnouncements(services);
        }
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module disabled. Tasks should be implicitly stopped by TaskScheduler shutdown.");
    }

    @Override
    public void onServerStopping(ServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
    }

    @Override
    public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, Services services) {
        dispatcher.register(
                Commands.literal("forgeannouncements")
                        .requires(source -> source.hasPermission(PermissionsHandler.BROADCAST_PERMISSION_LEVEL))
                        .then(Commands.literal("broadcast")
                                .then(Commands.argument("header_footer", BoolArgumentType.bool())
                                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                                .executes(context -> broadcastMessageCmd(context, "broadcast", services)))))
                        .then(Commands.literal("actionbar")
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(context -> broadcastMessageCmd(context, "actionbar", services))))
                        .then(Commands.literal("title")
                                .then(Commands.argument("titleAndSubtitle", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String titleAndSubtitle = StringArgumentType.getString(context, "titleAndSubtitle");
                                            String[] parts = titleAndSubtitle.split(" \\|\\| ", 2);
                                            String title = parts[0];
                                            String subtitle = parts.length > 1 ? parts[1] : null;
                                            return broadcastTitleCmd(context, title, subtitle, services);
                                        })))
                        .then(Commands.literal("bossbar")
                                .then(Commands.argument("interval", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("color", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    for (BossEvent.BossBarColor color : BossEvent.BossBarColor.values()) {
                                                        builder.suggest(color.getName());
                                                    }
                                                    return builder.buildFuture();
                                                })
                                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                                        .executes(context -> broadcastMessageCmd(context, "bossbar", services))))))
        );
    }

    @Override
    public void registerEventListeners(IEventBus forgeEventBus, Services services) {
    }

    private void scheduleConfiguredAnnouncements(Services services) {
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        MinecraftServer server = services.getMinecraftServer();
        if (server == null) {
            services.getDebugLogger().debugLog(NAME + ": Cannot schedule announcements, server instance is null.");
            return;
        }

        if (config.globalEnable.get()) {
            long globalInterval = config.globalInterval.get();
            services.getTaskScheduler().scheduleAtFixedRate(() -> broadcastGlobalMessages(services), globalInterval, globalInterval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled global messages with interval: {} seconds", globalInterval);
        }

        if (config.actionbarEnable.get()) {
            long actionbarInterval = config.actionbarInterval.get();
            services.getTaskScheduler().scheduleAtFixedRate(() -> broadcastActionbarMessages(services), actionbarInterval, actionbarInterval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled actionbar messages with interval: {} seconds", actionbarInterval);
        }

        if (config.titleEnable.get()) {
            long titleInterval = config.titleInterval.get();
            services.getTaskScheduler().scheduleAtFixedRate(() -> broadcastTitleMessages(services), titleInterval, titleInterval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled title messages with interval: {} seconds", titleInterval);
        }

        if (config.bossbarEnable.get()) {
            long bossbarInterval = config.bossbarInterval.get();
            services.getTaskScheduler().scheduleAtFixedRate(() -> broadcastBossbarMessages(services), bossbarInterval, bossbarInterval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled bossbar messages with interval: {} seconds", bossbarInterval);
        }
    }

    private void broadcastGlobalMessages(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (server == null || config.globalMessages.get().isEmpty()) return;

        List<? extends String> messages = config.globalMessages.get();
        String prefix = config.prefix.get() + "§r";
        String header = config.header.get();
        String footer = config.footer.get();

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.get())) {
            messageText = messages.get(globalMessageIndex).replace("{Prefix}", prefix);
            globalMessageIndex = (globalMessageIndex + 1) % messages.size();
        } else {
            messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
        }

        Component message = services.getMessageParser().parseMessage(messageText, null);

        if (config.headerAndFooter.get()) {
            Component headerComp = services.getMessageParser().parseMessage(header, null);
            Component footerComp = services.getMessageParser().parseMessage(footer, null);
            server.getPlayerList().getPlayers().forEach(player -> {
                player.sendSystemMessage(headerComp);
                player.sendSystemMessage(message);
                player.sendSystemMessage(footerComp);
            });
        } else {
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
        services.getDebugLogger().debugLog(NAME + ": Broadcasted global message: {}", message.getString());
    }

    private void broadcastActionbarMessages(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (server == null || config.actionbarMessages.get().isEmpty()) return;

        List<? extends String> messages = config.actionbarMessages.get();
        String prefix = config.prefix.get() + "§r";

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.get())) {
            messageText = messages.get(actionbarMessageIndex).replace("{Prefix}", prefix);
            actionbarMessageIndex = (actionbarMessageIndex + 1) % messages.size();
        } else {
            messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
        }
        Component message = services.getMessageParser().parseMessage(messageText, null);

        server.getPlayerList().getPlayers().forEach(player -> {
            player.connection.send(new ClientboundSetActionBarTextPacket(message));
        });
        services.getDebugLogger().debugLog(NAME + ": Broadcasted actionbar message: {}", message.getString());
    }

    private void broadcastTitleMessages(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (server == null || config.titleMessages.get().isEmpty()) return;

        List<? extends String> messages = config.titleMessages.get();
        String prefix = config.prefix.get() + "§r";

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.get())) {
            messageText = messages.get(titleMessageIndex).replace("{Prefix}", prefix);
            titleMessageIndex = (titleMessageIndex + 1) % messages.size();
        } else {
            messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
        }

        String[] parts = messageText.split(" \\|\\| ", 2);
        Component titleComponent = services.getMessageParser().parseMessage(parts[0], null);
        Component subtitleComponent = parts.length > 1 ? services.getMessageParser().parseMessage(parts[1], null) : Component.empty();


        server.getPlayerList().getPlayers().forEach(player -> {
            player.connection.send(new ClientboundClearTitlesPacket(false));
            player.connection.send(new ClientboundSetTitleTextPacket(titleComponent));
            if (parts.length > 1 && subtitleComponent != Component.empty()) {
                boolean hasContent = false;
                if (subtitleComponent instanceof MutableComponent mc) {
                    if (!mc.getString().isEmpty() || !mc.getSiblings().isEmpty()) {
                        hasContent = true;
                    }
                } else if (!subtitleComponent.getString().isEmpty()){
                    hasContent = true;
                }
                if(hasContent) {
                    player.connection.send(new ClientboundSetSubtitleTextPacket(subtitleComponent));
                }
            }
        });
        services.getDebugLogger().debugLog(NAME + ": Broadcasted title message: {}", messageText);
    }

    private void broadcastBossbarMessages(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (server == null || config.bossbarMessages.get().isEmpty()) return;

        List<? extends String> messages = config.bossbarMessages.get();
        String prefix = config.prefix.get() + "§r";
        int bossbarTime = config.bossbarTime.get();
        BossEvent.BossBarColor bossbarColor;
        try {
            bossbarColor = BossEvent.BossBarColor.valueOf(config.bossbarColor.get().toUpperCase());
        } catch (IllegalArgumentException e) {
            services.getDebugLogger().debugLog(NAME + ": Invalid bossbar color: {}. Defaulting to PURPLE.", config.bossbarColor.get());
            bossbarColor = BossEvent.BossBarColor.PURPLE;
        }

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.get())) {
            messageText = messages.get(bossbarMessageIndex).replace("{Prefix}", prefix);
            bossbarMessageIndex = (bossbarMessageIndex + 1) % messages.size();
        } else {
            messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
        }
        Component message = services.getMessageParser().parseMessage(messageText, null);

        ServerBossEvent bossEvent = new ServerBossEvent(message, bossbarColor, BossEvent.BossBarOverlay.PROGRESS);
        bossEvent.setProgress(1.0f);

        server.getPlayerList().getPlayers().forEach(bossEvent::addPlayer);
        services.getDebugLogger().debugLog(NAME + ": Broadcasted bossbar message: {}", message.getString());

        services.getTaskScheduler().schedule(() -> {
            MinecraftServer currentServer = services.getMinecraftServer();
            if (currentServer != null) {
                bossEvent.removeAllPlayers();
                bossEvent.setVisible(false);
                services.getDebugLogger().debugLog(NAME + ": Removed bossbar message after {} seconds", bossbarTime);
            }
        }, bossbarTime, TimeUnit.SECONDS);
    }

    public int broadcastTitleCmd(CommandContext<CommandSourceStack> context, String title, String subtitle, Services services) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = services.getMinecraftServer();

        if (server == null) {
            source.sendFailure(Component.literal("Server not available."));
            return 0;
        }

        Component titleComponent = services.getMessageParser().parseMessage(title, null);
        Component subtitleComponent = (subtitle != null && !subtitle.isEmpty()) ? services.getMessageParser().parseMessage(subtitle, null) : Component.empty();

        server.getPlayerList().getPlayers().forEach(player -> {
            player.connection.send(new ClientboundClearTitlesPacket(true));
            player.connection.send(new ClientboundSetTitleTextPacket(titleComponent));
            if (subtitle != null && subtitleComponent != Component.empty()) {
                boolean hasContent = false;
                if (subtitleComponent instanceof MutableComponent mc) {
                    if (!mc.getString().isEmpty() || !mc.getSiblings().isEmpty()) {
                        hasContent = true;
                    }
                } else if (!subtitleComponent.getString().isEmpty()){
                    hasContent = true;
                }
                if(hasContent) {
                    player.connection.send(new ClientboundSetSubtitleTextPacket(subtitleComponent));
                }
            }
        });
        //source.sendSuccess(() -> Component.literal("Title broadcasted."), true);
        return 1;
    }

    public int broadcastMessageCmd(CommandContext<CommandSourceStack> context, String type, Services services) throws CommandSyntaxException {
        String messageStr = StringArgumentType.getString(context, "message");
        CommandSourceStack source = context.getSource();
        MinecraftServer server = services.getMinecraftServer();

        if (server == null) {
            source.sendFailure(Component.literal("Server not available."));
            return 0;
        }

        Component broadcastMessage = services.getMessageParser().parseMessage(messageStr, null);

        switch (type) {
            case "broadcast":
                boolean headerFooter = BoolArgumentType.getBool(context, "header_footer");
                if (headerFooter) {
                    String header = services.getAnnouncementsConfig().header.get();
                    String footer = services.getAnnouncementsConfig().footer.get();
                    Component headerMessage = services.getMessageParser().parseMessage(header, null);
                    Component footerMessage = services.getMessageParser().parseMessage(footer, null);
                    server.getPlayerList().getPlayers().forEach(player -> {
                        player.sendSystemMessage(headerMessage);
                        player.sendSystemMessage(broadcastMessage);
                        player.sendSystemMessage(footerMessage);
                    });
                } else {
                    server.getPlayerList().broadcastSystemMessage(broadcastMessage, false);
                }
                //source.sendSuccess(() -> Component.literal("Global message broadcasted."), true);
                break;
            case "actionbar":
                server.getPlayerList().getPlayers().forEach(player -> {
                    player.connection.send(new ClientboundSetActionBarTextPacket(broadcastMessage));
                });
                //source.sendSuccess(() -> Component.literal("Actionbar message broadcasted."), true);
                break;
            case "bossbar":
                String colorStr = StringArgumentType.getString(context, "color");
                int interval = IntegerArgumentType.getInteger(context, "interval");
                BossEvent.BossBarColor bossBarColor;
                try {
                    bossBarColor = BossEvent.BossBarColor.valueOf(colorStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    source.sendFailure(Component.literal("Invalid bossbar color: " + colorStr));
                    return 0;
                }
                ServerBossEvent bossEvent = new ServerBossEvent(broadcastMessage, bossBarColor, BossEvent.BossBarOverlay.PROGRESS);
                bossEvent.setProgress(1.0f);
                server.getPlayerList().getPlayers().forEach(bossEvent::addPlayer);
                services.getTaskScheduler().schedule(() -> {
                    MinecraftServer currentServer = services.getMinecraftServer();
                    if(currentServer != null) {
                        bossEvent.removeAllPlayers();
                        bossEvent.setVisible(false);
                    }
                }, interval, TimeUnit.SECONDS);
                //source.sendSuccess(() -> Component.literal("Bossbar message broadcasted for " + interval + " seconds."), true);
                break;
            default:
                source.sendFailure(Component.literal("Invalid message type for command: " + type));
                return 0;
        }
        return 1;
    }
}
