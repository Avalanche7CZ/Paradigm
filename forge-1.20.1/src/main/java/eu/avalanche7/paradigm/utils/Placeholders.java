package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.MinecraftPlayer;
import net.minecraft.server.level.ServerPlayer;

public class Placeholders {

    public Placeholders() {
    }

    public String replacePlaceholders(String text, IPlayer player) {
        if (text == null) return "";
        if (player == null) {
            return text;
        }
        ServerPlayer mcPlayer = player instanceof MinecraftPlayer ? ((MinecraftPlayer) player).getHandle() : null;
        if (mcPlayer == null) return text;
        String replacedText = text;
        replacedText = replacedText.replace("{player}", mcPlayer.getName().getString());
        replacedText = replacedText.replace("{player_name}", mcPlayer.getName().getString());
        replacedText = replacedText.replace("{player_uuid}", mcPlayer.getUUID().toString());
        replacedText = replacedText.replace("{player_level}", String.valueOf(mcPlayer.experienceLevel));
        replacedText = replacedText.replace("{player_health}", String.format("%.1f", mcPlayer.getHealth()));
        replacedText = replacedText.replace("{max_player_health}", String.format("%.1f", mcPlayer.getMaxHealth()));
        return replacedText;
    }
}
