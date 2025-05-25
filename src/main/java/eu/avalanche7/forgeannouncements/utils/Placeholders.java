package eu.avalanche7.forgeannouncements.utils;

import net.minecraft.server.level.ServerPlayer;

public class Placeholders {

    public static String replacePlaceholders(String text, ServerPlayer player) {
        if (player == null) {
            return text;
        }

        String replacedText = text;

        replacedText = replacedText.replace("{player}", player.getName().getString());
        replacedText = replacedText.replace("{player_name}", player.getName().getString());
        replacedText = replacedText.replace("{player_uuid}", player.getUUID().toString());
        replacedText = replacedText.replace("{player_level}", String.valueOf(player.experienceLevel));
        replacedText = replacedText.replace("{player_health}", String.format("%.2f", player.getHealth()));
        replacedText = replacedText.replace("{max_player_health}", String.format("%.2f", player.getMaxHealth()));


        return replacedText;
    }
}
