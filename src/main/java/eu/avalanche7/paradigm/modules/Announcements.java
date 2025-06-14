package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.configs.AnnouncementsConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

import javax.annotation.Nullable;
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
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getMainConfig().announcementsEnable.value;
    }

    @Override
    public void onLoad(FMLPreInitializationEvent event, Services services) {
        this.services = services;
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(FMLServerStartingEvent event, Services services) {
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
    public void onServerStopping(FMLServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
    }

    @Override
    public ICommand getCommand() {
        return new AnnouncementsCommand(this.services);
    }

    @Override
    public void registerEventListeners(Services services) {
    }

    private void scheduleConfiguredAnnouncements(Services services) {
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        MinecraftServer server = services.getMinecraftServer();
        if (server == null) {
            services.getDebugLogger().debugLog(NAME + ": Cannot schedule announcements, server instance is null.");
            return;
        }

        if (config.globalEnable.value) {
            long globalInterval = config.globalInterval.value;
            services.getTaskScheduler().scheduleAtFixedRate(() -> broadcastGlobalMessages(services), globalInterval, globalInterval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled global messages with interval: " + globalInterval + " seconds");
        }

        if (config.actionbarEnable.value) {
            long actionbarInterval = config.actionbarInterval.value;
            services.getTaskScheduler().scheduleAtFixedRate(() -> broadcastActionbarMessages(services), actionbarInterval, actionbarInterval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled actionbar messages with interval: " + actionbarInterval + " seconds");
        }

        if (config.titleEnable.value) {
            long titleInterval = config.titleInterval.value;
            services.getTaskScheduler().scheduleAtFixedRate(() -> broadcastTitleMessages(services), titleInterval, titleInterval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled title messages with interval: " + titleInterval + " seconds");
        }

        if (config.bossbarEnable.value) {
            long bossbarInterval = config.bossbarInterval.value;
            services.getTaskScheduler().scheduleAtFixedRate(() -> broadcastBossbarMessages(services), bossbarInterval, bossbarInterval, TimeUnit.SECONDS);
            services.getDebugLogger().debugLog(NAME + ": Scheduled bossbar messages with interval: " + bossbarInterval + " seconds");
        }
    }

    private void broadcastGlobalMessages(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (server == null || config.globalMessages.value.isEmpty()) return;

        List<String> messages = config.globalMessages.value;
        String prefix = config.prefix.value + "§r";
        String header = config.header.value;
        String footer = config.footer.value;

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.value)) {
            messageText = messages.get(globalMessageIndex).replace("{Prefix}", prefix);
            globalMessageIndex = (globalMessageIndex + 1) % messages.size();
        } else {
            messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
        }

        ITextComponent message = services.getMessageParser().parseMessage(messageText, null);

        if (config.headerAndFooter.value) {
            ITextComponent headerComp = services.getMessageParser().parseMessage(header, null);
            ITextComponent footerComp = services.getMessageParser().parseMessage(footer, null);
            server.getPlayerList().getPlayers().forEach(player -> {
                player.sendMessage(headerComp);
                player.sendMessage(message);
                player.sendMessage(footerComp);
            });
        } else {
            server.getPlayerList().sendMessage(message);
        }
        services.getDebugLogger().debugLog(NAME + ": Broadcasted global message: " + message.getUnformattedText());
    }

    private void broadcastActionbarMessages(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (server == null || config.actionbarMessages.value.isEmpty()) return;

        List<String> messages = config.actionbarMessages.value;
        String prefix = config.prefix.value + "§r";

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.value)) {
            messageText = messages.get(actionbarMessageIndex).replace("{Prefix}", prefix);
            actionbarMessageIndex = (actionbarMessageIndex + 1) % messages.size();
        } else {
            messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
        }
        ITextComponent message = services.getMessageParser().parseMessage(messageText, null);

        SPacketTitle packet = new SPacketTitle(SPacketTitle.Type.ACTIONBAR, message);
        server.getPlayerList().sendPacketToAllPlayers(packet);
        services.getDebugLogger().debugLog(NAME + ": Broadcasted actionbar message: " + message.getUnformattedText());
    }

    private void broadcastTitleMessages(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (server == null || config.titleMessages.value.isEmpty()) return;

        List<String> messages = config.titleMessages.value;
        String prefix = config.prefix.value + "§r";

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.value)) {
            messageText = messages.get(titleMessageIndex).replace("{Prefix}", prefix);
            titleMessageIndex = (titleMessageIndex + 1) % messages.size();
        } else {
            messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
        }

        String[] parts = messageText.split(" \\|\\| ", 2);
        ITextComponent titleComponent = services.getMessageParser().parseMessage(parts[0], null);
        ITextComponent subtitleComponent = parts.length > 1 ? services.getMessageParser().parseMessage(parts[1], null) : new TextComponentString("");

        SPacketTitle titlePacket = new SPacketTitle(SPacketTitle.Type.TITLE, titleComponent);
        SPacketTitle subtitlePacket = new SPacketTitle(SPacketTitle.Type.SUBTITLE, subtitleComponent);
        SPacketTitle clearPacket = new SPacketTitle(SPacketTitle.Type.RESET, null);

        server.getPlayerList().getPlayers().forEach(player -> {
            player.connection.sendPacket(clearPacket);
            player.connection.sendPacket(titlePacket);
            if (parts.length > 1 && !subtitleComponent.getUnformattedText().isEmpty()) {
                player.connection.sendPacket(subtitlePacket);
            }
        });
        services.getDebugLogger().debugLog(NAME + ": Broadcasted title message: " + messageText);
    }

    private void broadcastBossbarMessages(Services services) {
        MinecraftServer server = services.getMinecraftServer();
        AnnouncementsConfigHandler.Config config = services.getAnnouncementsConfig();
        if (server == null || config.bossbarMessages.value.isEmpty()) return;

        List<String> messages = config.bossbarMessages.value;
        String prefix = config.prefix.value + "§r";
        int bossbarTime = config.bossbarTime.value;
        BossInfo.Color bossbarColor;
        try {
            bossbarColor = BossInfo.Color.valueOf(config.bossbarColor.value.toUpperCase());
        } catch (IllegalArgumentException e) {
            services.getDebugLogger().debugLog(NAME + ": Invalid bossbar color: " + config.bossbarColor.value + ". Defaulting to PURPLE.");
            bossbarColor = BossInfo.Color.PURPLE;
        }

        String messageText;
        if ("SEQUENTIAL".equalsIgnoreCase(config.orderMode.value)) {
            messageText = messages.get(bossbarMessageIndex).replace("{Prefix}", prefix);
            bossbarMessageIndex = (bossbarMessageIndex + 1) % messages.size();
        } else {
            messageText = messages.get(random.nextInt(messages.size())).replace("{Prefix}", prefix);
        }
        ITextComponent message = services.getMessageParser().parseMessage(messageText, null);

        final BossInfoServer bossInfo = new BossInfoServer(message, bossbarColor, BossInfo.Overlay.PROGRESS);
        bossInfo.setPercent(1.0f);

        server.getPlayerList().getPlayers().forEach(bossInfo::addPlayer);
        services.getDebugLogger().debugLog(NAME + ": Broadcasted bossbar message: " + message.getUnformattedText());

        services.getTaskScheduler().schedule(() -> {
            // Correctly remove all players from the boss bar
            new java.util.ArrayList<>(bossInfo.getPlayers()).forEach(bossInfo::removePlayer);
        }, bossbarTime, TimeUnit.SECONDS);
    }

    public static class AnnouncementsCommand extends CommandBase {
        private final Services services;

        public AnnouncementsCommand(Services services) {
            this.services = services;
        }

        @Override
        public String getName() {
            return "paradigm";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/paradigm <broadcast|actionbar|title|bossbar> <message> [options]";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 2;
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (args.length < 2) {
                throw new CommandException("Usage: " + getUsage(sender));
            }

            String type = args[0].toLowerCase();
            switch (type) {
                case "broadcast":
                    handleBroadcast(server, sender, args);
                    break;
                case "actionbar":
                    handleActionbar(server, sender, args);
                    break;
                case "title":
                    handleTitle(server, sender, args);
                    break;
                case "bossbar":
                    handleBossbar(server, sender, args);
                    break;
                default:
                    throw new CommandException("Invalid message type: " + type);
            }
        }

        private void handleBroadcast(MinecraftServer server, ICommandSender sender, String[] args) {
            String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            ITextComponent broadcastMessage = services.getMessageParser().parseMessage(message, null);
            server.getPlayerList().sendMessage(broadcastMessage);
            notifyCommandListener(sender, this, "Broadcasted global message.");
        }

        private void handleActionbar(MinecraftServer server, ICommandSender sender, String[] args) {
            String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            ITextComponent actionbarMessage = services.getMessageParser().parseMessage(message, null);
            SPacketTitle packet = new SPacketTitle(SPacketTitle.Type.ACTIONBAR, actionbarMessage);
            server.getPlayerList().sendPacketToAllPlayers(packet);
            notifyCommandListener(sender, this, "Broadcasted actionbar message.");
        }

        private void handleTitle(MinecraftServer server, ICommandSender sender, String[] args) {
            String combined = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            String[] parts = combined.split(" \\|\\| ", 2);
            ITextComponent title = services.getMessageParser().parseMessage(parts[0], null);
            ITextComponent subtitle = parts.length > 1 ? services.getMessageParser().parseMessage(parts[1], null) : new TextComponentString("");

            SPacketTitle titlePacket = new SPacketTitle(SPacketTitle.Type.TITLE, title);
            SPacketTitle subtitlePacket = new SPacketTitle(SPacketTitle.Type.SUBTITLE, subtitle);
            SPacketTitle clearPacket = new SPacketTitle(SPacketTitle.Type.RESET, null);

            for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
                player.connection.sendPacket(clearPacket);
                player.connection.sendPacket(titlePacket);
                if (parts.length > 1) {
                    player.connection.sendPacket(subtitlePacket);
                }
            }
            notifyCommandListener(sender, this, "Broadcasted title.");
        }

        private void handleBossbar(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (args.length < 4) {
                throw new CommandException("Usage: /paradigm bossbar <interval> <color> <message>");
            }
            int interval = parseInt(args[1], 1);
            BossInfo.Color color;
            try {
                color = BossInfo.Color.valueOf(args[2].toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new CommandException("Invalid bossbar color: " + args[2]);
            }
            String message = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
            ITextComponent bossbarMessage = services.getMessageParser().parseMessage(message, null);

            final BossInfoServer bossInfo = new BossInfoServer(bossbarMessage, color, BossInfo.Overlay.PROGRESS);
            server.getPlayerList().getPlayers().forEach(bossInfo::addPlayer);

            services.getTaskScheduler().schedule(() -> {
                new java.util.ArrayList<>(bossInfo.getPlayers()).forEach(bossInfo::removePlayer);
            }, interval, TimeUnit.SECONDS);
            notifyCommandListener(sender, this, "Broadcasted bossbar message for " + interval + " seconds.");
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
            if (args.length == 1) {
                return getListOfStringsMatchingLastWord(args, "broadcast", "actionbar", "title", "bossbar");
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("bossbar")) {
                return getListOfStringsMatchingLastWord(args, "PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE");
            }
            return Collections.emptyList();
        }
    }
}
