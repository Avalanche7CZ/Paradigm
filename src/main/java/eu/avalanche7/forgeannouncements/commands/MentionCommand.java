package eu.avalanche7.forgeannouncements.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import eu.avalanche7.forgeannouncements.utils.Lang;
import eu.avalanche7.forgeannouncements.utils.Mentions;
import eu.avalanche7.forgeannouncements.utils.PermissionsHandler;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class MentionCommand {

    @SubscribeEvent
    public static void onServerStarting(RegisterCommandsEvent event) {
        MentionCommand.register(event.getDispatcher());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mention")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(MentionCommand::executeMention)));
    }

    private static int executeMention(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String message = StringArgumentType.getString(context, "message");
        Level world = source.getLevel();
        List<ServerPlayer> players = world.getServer().getPlayerList().getPlayers();
        boolean isConsole = source.getEntity() == null;
        ServerPlayer sender = isConsole ? null : (ServerPlayer) source.getEntity();

        if (message.contains("@everyone")) {
            if (!isConsole) {
                if (!Mentions.canMentionEveryone(sender)) {
                    sender.sendMessage(Lang.translate("mention.too_frequent_mention_everyone"), Util.NIL_UUID);
                    return 0;
                }

                boolean hasPermission = PermissionsHandler.hasPermission(sender, PermissionsHandler.MENTION_EVERYONE_PERMISSION);
                boolean hasPermissionLevel = sender.hasPermissions(PermissionsHandler.MENTION_EVERYONE_PERMISSION_LEVEL);
                if (!hasPermission && !hasPermissionLevel) {
                    sender.sendMessage(Lang.translate("mention.no_permission_everyone"), Util.NIL_UUID);
                    return 0;
                }
            }
            Mentions.notifyEveryone(players, sender, message, isConsole);
            //source.sendSuccess(new TextComponent("Mentioned everyone successfully."), true);
            return 1;
        }
        boolean mentioned = false;
        for (ServerPlayer player : players) {
            String mention = "@" + player.getName().getString();
            if (message.contains(mention)) {
                if (!isConsole) {
                    if (!Mentions.canMentionPlayer(sender, player)) {
                        sender.sendMessage(Lang.translate("mention.too_frequent_mention_player"), Util.NIL_UUID);
                        return 0;
                    }

                    boolean hasPermission = PermissionsHandler.hasPermission(sender, PermissionsHandler.MENTION_PLAYER_PERMISSION);
                    boolean hasPermissionLevel = sender.hasPermissions(PermissionsHandler.MENTION_PLAYER_PERMISSION_LEVEL);
                    if (!hasPermission && !hasPermissionLevel) {
                        sender.sendMessage(Lang.translate("mention.no_permission_player"), Util.NIL_UUID);
                        return 0;
                    }
                }
                Mentions.notifyPlayer(player, sender, message, isConsole);
                mentioned = true;
            }
        }

        if (mentioned) {
            //source.sendSuccess(new TextComponent("Mentioned player(s) successfully."), true);
        } else {
            source.sendFailure(new TextComponent("No valid mentions found in the message."));
        }

        return 1;
    }
}