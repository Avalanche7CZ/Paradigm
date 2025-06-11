package eu.avalanche7.paradigm.utils;

import net.minecraft.server.level.ServerPlayer;

public class Placeholders {

    public Placeholders() {
    }

    public String replacePlaceholders(String text, ServerPlayer player) {
        if (text == null) return "";
        if (player == null) {
            return text.replaceAll("\\{player(?:_name|_uuid|_level|_health|_max_health)?\\}", "");
        }

        String replacedText = text;
        replacedText = replacedText.replace("{player}", player.getName().getString());
        replacedText = replacedText.replace("{player_name}", player.getName().getString());
        replacedText = replacedText.replace("{player_uuid}", player.getUUID().toString());
        replacedText = replacedText.replace("{player_level}", String.valueOf(player.experienceLevel));
        replacedText = replacedText.replace("{player_health}", String.format("%.1f", player.getHealth()));
        replacedText = replacedText.replace("{max_player_health}", String.format("%.1f", player.getMaxHealth()));

        return replacedText;
    }
}
