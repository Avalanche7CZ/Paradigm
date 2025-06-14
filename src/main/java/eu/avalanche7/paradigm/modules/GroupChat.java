package eu.avalanche7.paradigm.modules;

import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerGroupData;
import eu.avalanche7.paradigm.utils.GroupChatManager;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GroupChat implements ParadigmModule {

    private static final String NAME = "GroupChat";
    private GroupChatManager groupChatManager;
    private Services services;

    public GroupChat(GroupChatManager groupChatManager) {
        this.groupChatManager = groupChatManager;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isEnabled(Services services) {
        return true;
    }

    @Override
    public void onLoad(FMLPreInitializationEvent event, Services services) {
        this.services = services;
        if (this.groupChatManager != null) {
            this.groupChatManager.setServices(services);
        }
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
        if (this.groupChatManager != null) {
            this.groupChatManager.clearAllGroupsAndPlayerData();
        }
    }

    @Override
    public void onServerStopping(FMLServerStoppingEvent event, Services services) {
        services.getDebugLogger().debugLog(NAME + " module: Server stopping.");
        onDisable(services);
    }

    @Override
    public ICommand getCommand() {
        return new GroupChatCommand(this.groupChatManager, this.services);
    }

    @Override
    public void registerEventListeners(Services services) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPlayerChat(ServerChatEvent event) {
        if (this.services == null || !isEnabled(this.services) || this.groupChatManager == null) return;

        EntityPlayerMP player = event.getPlayer();
        PlayerGroupData data = groupChatManager.getPlayerData(player);

        if (groupChatManager.isGroupChatToggled(player)) {
            String groupName = data.getCurrentGroup();
            if (groupName != null) {
                event.setCanceled(true);
                groupChatManager.sendMessageToGroup(player, groupName, event.getMessage());
                services.getLogger().info("[GroupChat] [{}] {}: {}", groupName, player.getName(), event.getMessage());
            } else {
                player.sendMessage(services.getLang().translate("group.no_group_to_send_message"));
                groupChatManager.setGroupChatToggled(player, false);
                player.sendMessage(services.getLang().translate("group.chat_disabled"));
            }
        }
    }

    // --- Inner Command Class for 1.12.2 ---

    public static class GroupChatCommand extends CommandBase {
        private final GroupChatManager groupChatManager;
        private final Services services;

        public GroupChatCommand(GroupChatManager groupChatManager, Services services) {
            this.groupChatManager = groupChatManager;
            this.services = services;
        }

        @Override
        public String getName() {
            return "groupchat";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/groupchat <subcommand>";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (!(sender instanceof EntityPlayerMP)) {
                throw new CommandException("This command can only be used by players.");
            }
            EntityPlayerMP player = (EntityPlayerMP) sender;

            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                displayHelp(player, services);
                return;
            }

            String subCommand = args[0].toLowerCase();
            switch (subCommand) {
                case "create":
                    if (args.length < 2) throw new CommandException("/groupchat create <name>");
                    groupChatManager.createGroup(player, args[1]);
                    break;
                case "delete":
                    groupChatManager.deleteGroup(player);
                    break;
                case "invite":
                    if (args.length < 2) throw new CommandException("/groupchat invite <player>");
                    EntityPlayerMP target = getPlayer(server, sender, args[1]);
                    groupChatManager.invitePlayer(player, target);
                    break;
                case "join":
                    if (args.length < 2) throw new CommandException("/groupchat join <name>");
                    groupChatManager.joinGroup(player, args[1]);
                    break;
                case "leave":
                    groupChatManager.leaveGroup(player);
                    break;
                case "list":
                    groupChatManager.listGroups(player);
                    break;
                case "info":
                    String groupName = args.length > 1 ? args[1] : groupChatManager.getPlayerData(player).getCurrentGroup();
                    if (groupName != null) {
                        groupChatManager.groupInfo(player, groupName);
                    } else {
                        player.sendMessage(services.getLang().translate("group.no_group_to_info"));
                    }
                    break;
                case "say":
                    if (args.length < 2) throw new CommandException("/groupchat say <message>");
                    String message = buildString(args, 1);
                    groupChatManager.sendMessageFromCommand(player, message);
                    break;
                case "toggle":
                    groupChatManager.toggleGroupChat(player);
                    break;
                default:
                    throw new CommandException("Unknown subcommand. Use /groupchat help.");
            }
        }

        private void displayHelp(EntityPlayerMP player, Services services) {
            String label = getName();
            player.sendMessage(services.getLang().translate("group.help_title"));
            sendHelpMessage(player, label, "create <name>", services.getLang().translate("group.help_create").getUnformattedText(), services);
            sendHelpMessage(player, label, "delete", services.getLang().translate("group.help_delete").getUnformattedText(), services);
            sendHelpMessage(player, label, "invite <player>", services.getLang().translate("group.help_invite").getUnformattedText(), services);
            sendHelpMessage(player, label, "join <group_name>", services.getLang().translate("group.help_join").getUnformattedText(), services);
            sendHelpMessage(player, label, "leave", services.getLang().translate("group.help_leave").getUnformattedText(), services);
            sendHelpMessage(player, label, "list", services.getLang().translate("group.help_list").getUnformattedText(), services);
            sendHelpMessage(player, label, "info [group_name]", services.getLang().translate("group.help_info").getUnformattedText(), services);
            sendHelpMessage(player, label, "say <message>", services.getLang().translate("group.help_say").getUnformattedText(), services);
            sendHelpMessage(player, label, "toggle", services.getLang().translate("group.help_toggle").getUnformattedText(), services);
        }

        private void sendHelpMessage(EntityPlayerMP player, String label, String command, String description, Services services) {
            ITextComponent hoverText = new TextComponentString(description).setStyle(new net.minecraft.util.text.Style().setColor(TextFormatting.AQUA));
            ITextComponent message = new TextComponentString(" §9> §e/" + label + " " + command);
            message.getStyle()
                    .setColor(TextFormatting.YELLOW)
                    .setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + label + " " + command))
                    .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hoverText));
            player.sendMessage(message);
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
            if (args.length == 1) {
                return getListOfStringsMatchingLastWord(args, "create", "delete", "invite", "join", "leave", "list", "info", "say", "toggle", "help");
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("invite")) {
                    return getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames());
                } else if (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("info")) {
                    return getListOfStringsMatchingLastWord(args, groupChatManager.getAllGroupNames());
                }
            }
            return Collections.emptyList();
        }
    }
}

