package eu.avalanche7.forgeannouncements.utils;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.level.ServerPlayer;

public class MessageText {

    public static void sendInteractiveMessage(ServerPlayer player, String label, String command, String description, ChatFormatting commandColor) {
        MutableComponent arrow = new TextComponent(" > ").withStyle(ChatFormatting.BLUE);
        MutableComponent message = new TextComponent("/" + label + " " + command).withStyle(commandColor);

        message.setStyle(message.getStyle()
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new TextComponent(description)))
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/" + label + " " + command)));

        arrow.append(message);
        player.sendMessage(arrow, player.getUUID());
    }
}
