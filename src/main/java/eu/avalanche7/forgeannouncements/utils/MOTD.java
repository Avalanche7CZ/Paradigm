package eu.avalanche7.forgeannouncements.utils;

import eu.avalanche7.forgeannouncements.configs.MainConfigHandler;
import eu.avalanche7.forgeannouncements.configs.MOTDConfigHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.network.chat.*;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;

import java.util.List;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class MOTD {

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!MainConfigHandler.CONFIG.motdEnable.get()) {
            DebugLogger.debugLog("MOTD feature is disabled.");
            return;
        }

        ServerPlayer player = (ServerPlayer) event.getEntity();
        Component motdMessage = createMOTDMessage(player);
        event.getPlayer().sendMessage(motdMessage, Util.NIL_UUID);
    }

    private static Component createMOTDMessage(ServerPlayer player) {
        List<String> lines = MOTDConfigHandler.getConfig().motdLines;
        TextComponent motdMessage = new TextComponent("");
        for (String line : lines) {
            motdMessage.append(parseTags(line, player)).append("\n");
        }

        return motdMessage;
    }

    private static Component parseTags(String text, ServerPlayer player) {
        MutableComponent component = new TextComponent("");
        String[] parts = text.split("§", -1);

        for (int i = 0; i < parts.length; i++) {
            if (i == 0 && !parts[i].isEmpty()) {
                component.append(ColorUtils.parseMessageWithColor(parts[i]));
            } else if (i > 0 && !parts[i].isEmpty()) {
                char colorCode = parts[i].charAt(0);
                String textPart = parts[i].substring(1);
                Style style = Style.EMPTY.withColor(TextColor.fromLegacyFormat(ChatFormatting.getByCode(colorCode)));

                while (!textPart.isEmpty()) {
                    if (textPart.startsWith("[link=")) {
                        int endIndex = textPart.indexOf("]");
                        if (endIndex != -1) {
                            String url = textPart.substring(6, endIndex);
                            component.append(new TextComponent(url)
                                    .setStyle(style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url)))));
                            component.append(" ");
                            textPart = textPart.substring(endIndex + 1).trim();
                        }
                    } else if (textPart.startsWith("[command=")) {
                        int endIndex = textPart.indexOf("]");
                        if (endIndex != -1) {
                            String command = textPart.substring(9, endIndex);
                            component.append(new TextComponent("/" + command + " ")
                                    .setStyle(style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + command))));
                            textPart = textPart.substring(endIndex + 1).trim();
                        }
                    } else if (textPart.contains("[hover=")) {
                        String[] hoverParts = textPart.split("\\[hover=", 2);
                        if (hoverParts.length == 2) {
                            String hoverText = hoverParts[1];
                            int endIndex = hoverText.indexOf("]");
                            if (endIndex != -1) {
                                hoverText = hoverText.substring(0, endIndex);
                                String remainingText = hoverParts[1].substring(endIndex + 1);

                                component.append(new TextComponent(hoverParts[0])
                                        .setStyle(style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponent(hoverText)))));
                                textPart = remainingText;
                            }
                        }
                    } else if (textPart.startsWith("[divider]")) {
                        component.append(new TextComponent("--------------------")
                                .setStyle(style.withColor(TextColor.fromLegacyFormat(ChatFormatting.GRAY))));
                        textPart = textPart.substring(9).trim();
                    } else if (textPart.startsWith("[title=")) {
                        int endIndex = textPart.indexOf("]");
                        if (endIndex != -1) {
                            String titleText = textPart.substring(7, endIndex);
                            Component titleComponent = ColorUtils.parseMessageWithColor("§" + colorCode + titleText);
                            ClientboundSetTitleTextPacket titlePacket = new ClientboundSetTitleTextPacket(titleComponent);
                            player.connection.send(titlePacket);
                            textPart = textPart.substring(endIndex + 1).trim();
                        }
                    } else if (textPart.startsWith("[subtitle=")) {
                        int endIndex = textPart.indexOf("]");
                        if (endIndex != -1) {
                            String subtitleText = textPart.substring(10, endIndex);
                            Component subtitleComponent = ColorUtils.parseMessageWithColor("§" + colorCode + subtitleText);
                            ClientboundSetSubtitleTextPacket subtitlePacket = new ClientboundSetSubtitleTextPacket(subtitleComponent);
                            player.connection.send(subtitlePacket);
                            textPart = textPart.substring(endIndex + 1).trim();
                        }
                    } else {
                        int nextSpecialTag = textPart.indexOf("[");
                        if (nextSpecialTag == -1) {
                            component.append(ColorUtils.parseMessageWithColor("§" + colorCode + textPart).setStyle(style));
                            textPart = "";
                        } else {
                            component.append(ColorUtils.parseMessageWithColor("§" + colorCode + textPart.substring(0, nextSpecialTag)).setStyle(style));
                            textPart = textPart.substring(nextSpecialTag);
                        }
                    }
                }
            }
        }

        return component;
    }

    private static String formatUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        return url;
    }
}