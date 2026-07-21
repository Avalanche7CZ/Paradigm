package eu.avalanche7.paradigm.modules.holograms;

import java.util.Locale;

public final class HologramAction {
    public String type = "message";
    public String text = "";
    public String subtitle = "";
    public String sound = "minecraft:entity.experience_orb.pickup";
    public String soundCategory = "master";
    public float volume = 1.0F;
    public float pitch = 1.0F;
    public String command = "";

    public HologramAction copy() {
        HologramAction copy = new HologramAction();
        copy.type = type;
        copy.text = text;
        copy.subtitle = subtitle;
        copy.sound = sound;
        copy.soundCategory = soundCategory;
        copy.volume = volume;
        copy.pitch = pitch;
        copy.command = command;
        return copy;
    }

    public void normalize() {
        type = normalizeType(type);
        text = text != null ? text : "";
        subtitle = subtitle != null ? subtitle : "";
        sound = sound != null ? sound.trim().toLowerCase(Locale.ROOT) : "";
        soundCategory = soundCategory != null ? soundCategory.trim().toLowerCase(Locale.ROOT) : "master";
        command = command != null ? command.trim() : "";
        if (!Float.isFinite(volume)) volume = 1.0F;
        if (!Float.isFinite(pitch)) pitch = 1.0F;
        volume = Math.max(0.0F, Math.min(10.0F, volume));
        pitch = Math.max(0.0F, Math.min(4.0F, pitch));
        if (("message".equals(type) || "actionbar".equals(type) || "title".equals(type)) && text.isBlank()) {
            throw new IllegalArgumentException("Hologram " + type + " actions require text.");
        }
        if ("sound".equals(type) && sound.isBlank()) throw new IllegalArgumentException("Hologram sound actions require a sound id.");
        if (("player_command".equals(type) || "console_command".equals(type)) && command.isBlank()) {
            throw new IllegalArgumentException("Hologram command actions require a command.");
        }
    }

    public static String normalizeType(String value) {
        String normalized = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "message", "actionbar", "title", "sound", "player_command", "console_command" -> normalized;
            default -> throw new IllegalArgumentException("Unknown hologram action type: " + value);
        };
    }
}
