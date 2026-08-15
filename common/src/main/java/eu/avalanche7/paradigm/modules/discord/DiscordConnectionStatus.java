package eu.avalanche7.paradigm.modules.discord;

import java.util.List;

public record DiscordConnectionStatus(
        boolean enabled,
        DiscordConnectionState state,
        DiscordInboundCapability inboundCapability,
        String botUsername,
        boolean tokenConfigured,
        boolean chatChannelConfigured,
        boolean moderationChannelConfigured,
        boolean notificationChannelConfigured,
        boolean minecraftToDiscordEnabled,
        boolean discordToMinecraftEnabled,
        long connectedSinceMs,
        long lastHeartbeatAckMs,
        boolean heartbeatOutstanding,
        int queueDepth,
        long sentCount,
        long droppedCount,
        long failedCount,
        String lastError,
        List<String> warnings) {
    public DiscordConnectionStatus {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public DiscordConnectionStatus withWarnings(List<String> newWarnings) {
        return new DiscordConnectionStatus(enabled, state, inboundCapability, botUsername, tokenConfigured,
                chatChannelConfigured, moderationChannelConfigured, notificationChannelConfigured,
                minecraftToDiscordEnabled, discordToMinecraftEnabled, connectedSinceMs, lastHeartbeatAckMs,
                heartbeatOutstanding, queueDepth, sentCount, droppedCount, failedCount, lastError, newWarnings);
    }

    public boolean inboundRelayBroken() {
        return discordToMinecraftEnabled
                && state == DiscordConnectionState.CONNECTED
                && inboundCapability.blocksInboundRelay();
    }

    public String summary() {
        if (!enabled) {
            return "Disabled";
        }
        String base = switch (state) {
            case CONNECTED -> "Connected" + (botUsername != null && !botUsername.isBlank() ? " as " + botUsername : "");
            case CONNECTING -> "Connecting";
            case RECONNECTING -> "Reconnecting";
            case DISCONNECTED -> "Disconnected";
            case FAILED -> "Failed";
            case DISABLED -> "Disabled";
        };
        if (inboundRelayBroken()) {
            return base + " (Discord to Minecraft chat unavailable)";
        }
        return base;
    }
}
