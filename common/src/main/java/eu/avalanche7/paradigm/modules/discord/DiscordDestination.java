package eu.avalanche7.paradigm.modules.discord;

import java.util.Locale;

import eu.avalanche7.paradigm.configs.DiscordConfigHandler;

public enum DiscordDestination {
    CHAT,
    MODERATION,
    NOTIFICATIONS,
    SERVER,
    DEATHS,
    ADVANCEMENTS,
    COMMAND_LOG,
    CONSOLE;

    public static DiscordDestination parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "chat" -> CHAT;
            case "moderation", "staff" -> MODERATION;
            case "notifications", "notification" -> NOTIFICATIONS;
            case "server" -> SERVER;
            case "deaths", "death" -> DEATHS;
            case "advancements", "advancement" -> ADVANCEMENTS;
            case "commandlog", "command_log", "commands" -> COMMAND_LOG;
            case "console" -> CONSOLE;
            default -> null;
        };
    }

    public String channelId(DiscordConfigHandler.Config config) {
        if (config == null) {
            return "";
        }
        if (this == COMMAND_LOG) {
            return trim(config.commandLogChannelId.get());
        }
        if (this == CONSOLE) {
            return trim(config.consoleChannelId.get());
        }
        String chat = trim(config.chatChannelId.get());
        if (this == CHAT) {
            return chat;
        }
        if (this == MODERATION) {
            return firstNonBlank(trim(config.moderationChannelId.get()), chat);
        }
        String notifications = firstNonBlank(trim(config.notificationChannelId.get()), chat);
        return switch (this) {
            case NOTIFICATIONS -> notifications;
            case SERVER -> firstNonBlank(trim(config.serverChannelId.get()), notifications);
            case DEATHS -> firstNonBlank(trim(config.deathsChannelId.get()), notifications);
            case ADVANCEMENTS -> firstNonBlank(trim(config.advancementsChannelId.get()), notifications);
            default -> notifications;
        };
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static String trim(String value) {
        return value != null ? value.trim() : "";
    }
}
