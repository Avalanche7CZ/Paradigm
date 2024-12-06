package eu.avalanche7.forgeannouncements.commands;

import eu.avalanche7.forgeannouncements.utils.PermissionsHandler;
import eu.avalanche7.forgeannouncements.utils.TaskScheduler;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.world.BossInfo;
import net.minecraft.world.BossInfoServer;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class AnnouncementsCommand extends CommandBase {
    @Override
    public String getName() {
        return "forgeannouncements";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/forgeannouncements <broadcast|actionbar|title|bossbar> <message> [options]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(new TextComponentString("Usage: " + getUsage(sender)));
            return;
        }

        String type = args[0];
        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        int requiredPermissionLevel;
        switch (type) {
            case "broadcast":
                requiredPermissionLevel = PermissionsHandler.BROADCAST_PERMISSION_LEVEL;
                broadcastMessage(server, sender, message);
                break;
            case "actionbar":
                requiredPermissionLevel = PermissionsHandler.ACTIONBAR_PERMISSION_LEVEL;
                actionbarMessage(server, sender, message);
                break;
            case "title":
                requiredPermissionLevel = PermissionsHandler.TITLE_PERMISSION_LEVEL;
                int separatorIndex = java.util.Arrays.asList(args).indexOf("-");
                String title, subtitle;
                if (separatorIndex == -1 || separatorIndex == 1 || separatorIndex == args.length - 1) {
                    title = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                    subtitle = "";
                } else {
                    title = String.join(" ", java.util.Arrays.copyOfRange(args, 1, separatorIndex));
                    subtitle = String.join(" ", java.util.Arrays.copyOfRange(args, separatorIndex + 1, args.length));
                }
                titleMessage(server, sender, title, subtitle);
                break;
            case "subtitle":
                subtitleMessage(server, sender, message);
                break;
            case "bossbar":
                requiredPermissionLevel = PermissionsHandler.BOSSBAR_PERMISSION_LEVEL;
                if (args.length < 4) {
                    sender.sendMessage(new TextComponentString("Usage: /forgeannouncements bossbar <interval> <color> <message>"));
                    return;
                }
                int interval;
                try {
                    interval = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(new TextComponentString("Interval must be an integer."));
                    return;
                }
                String color = args[2];
                String bossbarMessage = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                bossbarMessage(server, sender, bossbarMessage, color, interval);
                break;
            default:
                sender.sendMessage(new TextComponentString("Invalid message type: " + type));
        }
    }

    private void broadcastMessage(MinecraftServer server, ICommandSender sender, String message) {
        message = processColorCodes(message);
        TextComponentString broadcastMessage = new TextComponentString("");

        String[] words = message.split(" ");
        for (String word : words) {
            if (word.startsWith("http://") || word.startsWith("https://")) {
                Style style = new Style().setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, word));
                broadcastMessage.appendSibling(new TextComponentString(word).setStyle(style));
            } else {
                broadcastMessage.appendSibling(new TextComponentString(word + " "));
            }
        }

        PlayerList playerList = server.getPlayerList();
        for (EntityPlayerMP player : playerList.getPlayers()) {
            player.sendMessage(broadcastMessage);
        }
    }

    private String processColorCodes(String message) {
        return message.replace("&", "§");
    }

    private void actionbarMessage(MinecraftServer server, ICommandSender sender, String message) {
        message = processColorCodes(message);
        ITextComponent actionbarMessage = new TextComponentString(message);

        PlayerList playerList = server.getPlayerList();
        for (EntityPlayerMP player : playerList.getPlayers()) {
            player.sendStatusMessage(actionbarMessage, true);
        }
    }

    private void titleMessage(MinecraftServer server, ICommandSender sender, String title, String subtitle) {
        title = processColorCodes(title);
        subtitle = processColorCodes(subtitle);
        ITextComponent titleMessage = new TextComponentString(title);
        ITextComponent subtitleMessage = new TextComponentString(subtitle);

        PlayerList playerList = server.getPlayerList();
        for (EntityPlayerMP player : playerList.getPlayers()) {
            player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.TITLE, titleMessage));
            player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.SUBTITLE, subtitleMessage));
        }
    }

    private void subtitleMessage(MinecraftServer server, ICommandSender sender, String message) {
        ITextComponent subtitleMessage = ITextComponent.Serializer.jsonToComponent("{\"text\":\"" + message + "\",\"color\":\"gold\"}");

        PlayerList playerList = server.getPlayerList();
        for (EntityPlayerMP player : playerList.getPlayers()) {
            player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.SUBTITLE, subtitleMessage));
        }
    }

    private void bossbarMessage(MinecraftServer server, ICommandSender sender, String message, String color, int interval) {
        message = processColorCodes(message);
        TextComponentString bossbarMessage = new TextComponentString(message);
        BossInfo.Color bossbarColor;
        try {
            bossbarColor = BossInfo.Color.valueOf(color.toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(new TextComponentString("Invalid bossbar color: " + color));
            return;
        }
        BossInfoServer bossInfo = new BossInfoServer(bossbarMessage, bossbarColor, BossInfo.Overlay.PROGRESS);

        PlayerList playerList = server.getPlayerList();
        for (EntityPlayerMP player : playerList.getPlayers()) {
            bossInfo.addPlayer(player);
        }

        TaskScheduler.schedule(() -> {
            for (EntityPlayerMP player : playerList.getPlayers()) {
                bossInfo.removePlayer(player);
            }
        }, interval, TimeUnit.SECONDS);
    }

    public static void registerCommands(FMLServerStartingEvent event) {
        event.registerServerCommand(new AnnouncementsCommand());
    }
}
