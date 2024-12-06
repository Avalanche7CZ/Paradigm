package eu.avalanche7.forgeannouncements.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import eu.avalanche7.forgeannouncements.configs.AnnouncementsConfigHandler;
import eu.avalanche7.forgeannouncements.utils.*;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.TimeUnit;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class AnnouncementsCommand {


    public static int broadcastTitle(CommandContext<CommandSourceStack> context, String title, String subtitle) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        if (!source.hasPermission(2)) {
            source.sendFailure(new TextComponent(Lang.translate("announcement.no_permission").getString()));
            return 0;
        }

        MutableComponent titleComponent = ColorUtils.parseMessageWithColor(title);
        MutableComponent subtitleComponent = subtitle != null ? ColorUtils.parseMessageWithColor(subtitle) : null;

        source.getServer().getPlayerList().getPlayers().forEach(player -> {
            player.connection.send(new ClientboundClearTitlesPacket(false));
            player.connection.send(new ClientboundSetTitleTextPacket(titleComponent));
            if (subtitleComponent != null) {
                player.connection.send(new ClientboundSetSubtitleTextPacket(subtitleComponent));
            }
        });

        return 1;
    }

    public static int broadcastMessage(CommandContext<CommandSourceStack> context, String type) throws CommandSyntaxException {
        String message = StringArgumentType.getString(context, "message");
        CommandSourceStack source = context.getSource();

        if (!source.hasPermission(2)) {
            source.sendFailure(new TextComponent(Lang.translate("announcement.no_permission").getString()));
            return 0;
        }

        MutableComponent broadcastMessage = ColorUtils.parseMessageWithColor(message);

        int requiredPermissionLevel;
        switch (type) {
            case "broadcast":
                requiredPermissionLevel = PermissionsHandler.BROADCAST_PERMISSION_LEVEL;
                boolean headerFooter = BoolArgumentType.getBool(context, "header_footer");
                if (headerFooter) {
                    String header = AnnouncementsConfigHandler.CONFIG.header.get();
                    String footer = AnnouncementsConfigHandler.CONFIG.footer.get();
                    MutableComponent headerMessage = ColorUtils.parseMessageWithColor(header);
                    MutableComponent footerMessage = ColorUtils.parseMessageWithColor(footer);
                    source.getServer().getPlayerList().getPlayers().forEach(player -> {
                        player.sendMessage(headerMessage, Util.NIL_UUID);
                        player.sendMessage(broadcastMessage, Util.NIL_UUID);
                        player.sendMessage(footerMessage, Util.NIL_UUID);
                    });
                } else {
                    source.getServer().getPlayerList().getPlayers().forEach(player -> {
                        player.sendMessage(broadcastMessage, Util.NIL_UUID);
                    });
                }
                break;
            case "actionbar":
                requiredPermissionLevel = PermissionsHandler.ACTIONBAR_PERMISSION_LEVEL;
                source.getServer().getPlayerList().getPlayers().forEach(player -> {
                    player.connection.send(new ClientboundSetActionBarTextPacket(broadcastMessage));
                });
                break;
            case "title":
                requiredPermissionLevel = PermissionsHandler.TITLE_PERMISSION_LEVEL;
                String[] titleParts = message.split(" - ", 2);
                String title = titleParts[0];
                String subtitle = titleParts.length > 1 ? titleParts[1] : "";
                MutableComponent titleComponent = ColorUtils.parseMessageWithColor(title);
                MutableComponent subtitleComponent = ColorUtils.parseMessageWithColor(subtitle);
                source.getServer().getPlayerList().getPlayers().forEach(player -> {
                    player.connection.send(new ClientboundClearTitlesPacket(false));
                    player.connection.send(new ClientboundSetTitleTextPacket(titleComponent));
                    player.connection.send(new ClientboundSetSubtitleTextPacket(subtitleComponent));
                });
                break;
            case "bossbar":
                requiredPermissionLevel = PermissionsHandler.BOSSBAR_PERMISSION_LEVEL;
                String color = StringArgumentType.getString(context, "color");
                int interval = IntegerArgumentType.getInteger(context, "interval");
                BossEvent.BossBarColor bossBarColor;
                try {
                    bossBarColor = BossEvent.BossBarColor.valueOf(color.toUpperCase());
                } catch (IllegalArgumentException e) {
                    source.sendFailure(new TextComponent(Lang.translate("announcement.invalid_color").getString().replace("{color}", color)));
                    return 0;
                }
                ServerBossEvent bossEvent = new ServerBossEvent(broadcastMessage, bossBarColor, BossEvent.BossBarOverlay.PROGRESS);
                source.getServer().getPlayerList().getPlayers().forEach(player -> {
                    bossEvent.addPlayer(player);
                });
                TaskScheduler.schedule(() -> {
                    source.getServer().getPlayerList().getPlayers().forEach(player -> {
                        bossEvent.removePlayer(player);
                    });
                }, interval, TimeUnit.SECONDS);
                break;
            default:
                source.sendFailure(new TextComponent("Invalid message type: " + type));
                return 0;
        }

        if (!source.hasPermission(requiredPermissionLevel)) {
            source.sendFailure(new TextComponent(Lang.translate("announcement.no_permission").getString()));
            return 0;
        }

        return 1;
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("forgeannouncements")
                        .then(Commands.literal("broadcast")
                                .then(Commands.argument("header_footer", BoolArgumentType.bool())
                                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                                .executes(context -> broadcastMessage(context, "broadcast")))))
                        .then(Commands.literal("actionbar")
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(context -> broadcastMessage(context, "actionbar"))))
                        .then(Commands.literal("title")
                                .then(Commands.argument("titleAndSubtitle", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            String titleAndSubtitle = StringArgumentType.getString(context, "titleAndSubtitle");
                                            String[] parts = titleAndSubtitle.split(" - ", 2);
                                            String title = parts[0];
                                            String subtitle = parts.length > 1 ? parts[1] : null;
                                            return broadcastTitle(context, title, subtitle);
                                        })))
                        .then(Commands.literal("bossbar")
                                .then(Commands.argument("interval", IntegerArgumentType.integer())
                                        .then(Commands.argument("color", StringArgumentType.word())
                                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                                        .executes(context -> broadcastMessage(context, "bossbar"))))))
        );
    }
}