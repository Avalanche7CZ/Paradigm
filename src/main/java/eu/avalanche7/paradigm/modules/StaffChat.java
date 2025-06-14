package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.utils.PermissionsHandler;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StaffChat implements ParadigmModule {

    private static final String NAME = "StaffChat";
    private final Map<UUID, Boolean> staffChatEnabledMap = new HashMap<>();
    private final Map<UUID, BossInfoServer> bossBarsMap = new HashMap<>();
    private Services services;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return services.getChatConfig().enableStaffChat.value;
    }

    @Override
    public void onLoad(FMLPreInitializationEvent event, Services services) {
        this.services = services;
        services.getDebugLogger().debugLog(NAME + " module loaded.");
    }

    @Override
    public void onServerStarting(FMLServerStartingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server starting.");
    }

    @Override
    public void onEnable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module enabled.");
    }

    @Override
    public void onDisable(Services services) {
        services.getDebugLogger().debugLog(NAME + " module disabled.");
        MinecraftServer server = services.getMinecraftServer();
        if (server != null) {
            staffChatEnabledMap.forEach((uuid, isEnabled) -> {
                if (isEnabled) {
                    EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(uuid);
                    if (player != null) {
                        removeBossBar(player);
                    }
                }
            });
        }
        staffChatEnabledMap.clear();
        bossBarsMap.clear();
    }

    @Override
    public void onServerStopping(FMLServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
        onDisable(services);
    }

    @Override
    public ICommand getCommand() {
        return new StaffChatCommand(this.services);
    }

    @Override
    public void registerEventListeners(Services services) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (this.services == null || !isEnabled(this.services)) return;

        EntityPlayerMP player = event.getPlayer();
        if (staffChatEnabledMap.getOrDefault(player.getUniqueID(), false)) {
            MinecraftServer server = services.getMinecraftServer();
            if (server == null) {
                services.getLogger().warn("StaffChatModule: Server instance is null during ServerChatEvent for " + player.getName());
                return;
            }
            sendStaffChatMessage(player, event.getMessage(), server, this.services);
            event.setCanceled(true);
        }
    }

    private void toggleStaffChat(EntityPlayerMP player, Services services) {
        boolean isCurrentlyEnabled = staffChatEnabledMap.getOrDefault(player.getUniqueID(), false);
        staffChatEnabledMap.put(player.getUniqueID(), !isCurrentlyEnabled);
        player.sendMessage(new TextComponentString("Staff chat " + (!isCurrentlyEnabled ? "§aenabled" : "§cdisabled")));

        if (!isCurrentlyEnabled) {
            showBossBar(player, services);
        } else {
            removeBossBar(player);
        }
        services.getDebugLogger().debugLog("Player " + player.getName() + " toggled staff chat to " + !isCurrentlyEnabled);
    }

    private void sendStaffChatMessage(EntityPlayerMP sender, String message, MinecraftServer server, Services services) {
        ChatConfigHandler.Config chatConfig = services.getChatConfig();
        String format = chatConfig.staffChatFormat.value;
        String rawFormattedMessage = String.format(format, sender.getName(), message);
        ITextComponent chatComponent = services.getMessageParser().parseMessage(rawFormattedMessage, sender);

        server.getPlayerList().getPlayers().forEach(onlinePlayer -> {
            if (services.getPermissionsHandler().hasPermission(onlinePlayer, PermissionsHandler.STAFF_CHAT_PERMISSION) || onlinePlayer.canUseCommand(2, "")) {
                onlinePlayer.sendMessage(chatComponent);
            }
        });
        services.getLogger().info("(StaffChat) {}: {}", sender.getName(), message);
    }

    private void showBossBar(EntityPlayerMP player, Services services) {
        if (services.getChatConfig().enableStaffBossBar.value) {
            removeBossBar(player);
            ITextComponent title = services.getMessageParser().parseMessage("§cStaff Chat Mode §aEnabled", player);
            BossInfoServer bossBar = new BossInfoServer(title, BossInfo.Color.RED, BossInfo.Overlay.PROGRESS);
            bossBar.addPlayer(player);
            bossBarsMap.put(player.getUniqueID(), bossBar);
        }
    }

    private void removeBossBar(EntityPlayerMP player) {
        BossInfoServer bossBar = bossBarsMap.remove(player.getUniqueID());
        if (bossBar != null) {
            bossBar.removePlayer(player);
        }
    }

    // --- Inner Command Class for 1.12.2 ---

    public class StaffChatCommand extends CommandBase {
        private final Services services;

        public StaffChatCommand(Services services) {
            this.services = services;
        }

        @Override
        public String getName() {
            return "sc";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/sc [message] or /sc toggle";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0; // Handled by permission node check
        }

        @Override
        public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
            if (!(sender instanceof EntityPlayerMP)) return false;
            return services.getPermissionsHandler().hasPermission((EntityPlayerMP) sender, PermissionsHandler.STAFF_CHAT_PERMISSION) || sender.canUseCommand(2, "");
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            EntityPlayerMP player = getCommandSenderAsPlayer(sender);

            if (args.length == 0) {
                toggleStaffChat(player, services);
                return;
            }

            if (args[0].equalsIgnoreCase("toggle")) {
                toggleStaffChat(player, services);
            } else {
                String message = String.join(" ", args);
                sendStaffChatMessage(player, message, server, services);
            }
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
            if (args.length == 1) {
                return getListOfStringsMatchingLastWord(args, "toggle");
            }
            return Collections.emptyList();
        }
    }
}

