package eu.avalanche7.forgeannouncements.utils;

import eu.avalanche7.forgeannouncements.configs.MOTDConfigHandler;
import eu.avalanche7.forgeannouncements.configs.MainConfigHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketTitle;
import net.minecraft.util.text.*;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

@Mod.EventBusSubscriber(modid = "forgeannouncements")
public class MOTD {


    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!MainConfigHandler.MOTD_ENABLE) {
            DebugLogger.debugLog("MOTD feature is disabled.");
            return;
        }
            ITextComponent motdMessage = createMOTDMessage((EntityPlayerMP) event.player);
            if (motdMessage == null) {
                System.out.println("MOTD message is null");
                return;
            }
            event.player.sendMessage(motdMessage);
        }


    private static ITextComponent createMOTDMessage(EntityPlayerMP player) {
        String[] lines = MOTDConfigHandler.motdMessage.split("\n");
        TextComponentString motdMessage = new TextComponentString("");
        for (String line : lines) {
            motdMessage.appendSibling(parseColoredText(line, player)).appendText("\n");
        }
        return motdMessage;
    }

    private static ITextComponent parseColoredText(String text, EntityPlayerMP player) {
        TextComponentString component = new TextComponentString("");
        String[] parts = text.split("§");

        if (parts.length > 0) {
            component.appendText(parts[0]);
        }

        for (int i = 1; i < parts.length; i++) {
            if (parts[i].length() > 0) {
                char colorCode = parts[i].charAt(0);
                String textPart = parts[i].substring(1);
                TextFormatting color = TextFormatting.fromColorIndex(Character.digit(colorCode, 16));

                if (color == null) {
                    System.out.println("Invalid color code: " + colorCode);
                    continue;
                }

                Style style = new Style().setColor(color);

                if (textPart.contains("[link=")) {
                    String[] linkParts = textPart.split("\\[link=", 2);
                    if (linkParts.length == 2) {
                        String remainingText = linkParts[1];
                        int endIndex = remainingText.indexOf("]");
                        if (endIndex != -1) {
                            String url = remainingText.substring(0, endIndex);
                            remainingText = remainingText.substring(endIndex + 1).trim();

                            TextComponentString linkText = new TextComponentString(linkParts[0] + url);
                            linkText.setStyle(style.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, formatUrl(url))));
                            component.appendSibling(linkText);
                            textPart = remainingText;
                        }
                    }
                } else if (textPart.contains("[command=")) {
                    String[] commandParts = textPart.split("\\[command=", 2);
                    if (commandParts.length == 2) {
                        String remainingText = commandParts[1];
                        int endIndex = remainingText.indexOf("]");
                        if (endIndex != -1) {
                            String command = remainingText.substring(0, endIndex);
                            remainingText = remainingText.substring(endIndex + 1).trim();
                            if (!command.startsWith("/")) {
                                command = "/" + command; // Ensure command starts with "/"
                            }
                            String initialText = commandParts[0].isEmpty() ? "/" : commandParts[0];

                            TextComponentString commandText = new TextComponentString(initialText + command);
                            commandText.setStyle(style.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
                            component.appendSibling(commandText);
                            if (!remainingText.isEmpty()) {
                                component.appendText(" ");
                            }
                            textPart = remainingText;
                        }
                    }
                } else if (textPart.contains("[hover=")) {
                    String[] hoverParts = textPart.split("\\[hover=", 2);
                    if (hoverParts.length == 2) {
                        String hoverText = hoverParts[1];
                        int endIndex = hoverText.indexOf("]");
                        if (endIndex != -1) {
                            hoverText = hoverText.substring(0, endIndex);
                            String remainingText = hoverParts[1].substring(endIndex + 1);

                            component.appendSibling(new TextComponentString(hoverParts[0])
                                    .setStyle(style.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponentString(hoverText)))));
                            textPart = remainingText;
                        }
                    }
                } else if (textPart.contains("[divider]")) {
                    component.appendSibling(new TextComponentString("--------------------")
                            .setStyle(style.setColor(TextFormatting.GRAY)));
                    textPart = "";

                } else if (textPart.contains("[title=")) {
                    String[] titleParts = textPart.split("\\[title=", 2);
                    if (titleParts.length == 2) {
                        String titleText = titleParts[1];
                        int endIndex = titleText.indexOf("]");
                        if (endIndex != -1) {
                            titleText = titleText.substring(0, endIndex);
                            String remainingText = titleParts[1].substring(endIndex + 1);
                            ITextComponent titleComponent = ITextComponent.Serializer.jsonToComponent("{\"text\":\"" + titleText + "\",\"color\":\"" + color.getFriendlyName().toLowerCase() + "\"}");
                            player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.TITLE, titleComponent));
                            textPart = remainingText;
                        }
                    }
                } else if (textPart.contains("[subtitle=")) {
                    String[] subtitleParts = textPart.split("\\[subtitle=", 2);
                    if (subtitleParts.length == 2) {
                        String subtitleText = subtitleParts[1];
                        int endIndex = subtitleText.indexOf("]");
                        if (endIndex != -1) {
                            subtitleText = subtitleText.substring(0, endIndex);
                            String remainingText = subtitleParts[1].substring(endIndex + 1);
                            ITextComponent subtitleComponent = ITextComponent.Serializer.jsonToComponent("{\"text\":\"" + subtitleText + "\",\"color\":\"" + color.getFriendlyName().toLowerCase() + "\"}");
                            player.connection.sendPacket(new SPacketTitle(SPacketTitle.Type.SUBTITLE, subtitleComponent));
                            textPart = remainingText;
                        }
                    }
                }
                component.appendSibling(new TextComponentString(textPart.trim()).setStyle(style));
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
