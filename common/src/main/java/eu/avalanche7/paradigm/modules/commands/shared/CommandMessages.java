package eu.avalanche7.paradigm.modules.commands.shared;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class CommandMessages {
    private CommandMessages() {
    }

    public static void send(Services services, IPlayer player, String header, String key, String fallback, String... placeholders) {
        if (services == null || player == null) {
            return;
        }
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        String decorated = "<color:#22D3EE><bold>[" + header + "]</bold></color> <color:#E5E7EB>" + raw + "</color>";
        try {
            services.getPlatformAdapter().sendSystemMessage(player, services.getMessageParser().parseMessage(decorated, player));
        } catch (Throwable t) {
            if (services.getDebugLogger() != null) {
                services.getDebugLogger().debugLog("Failed to send command message to player: " + t);
            }
        }
    }

    public static void source(Services services, ICommandSource source, String header, String key, String fallback, String... placeholders) {
        if (source == null) {
            return;
        }
        IPlayer player = source.getPlayer();
        if (player != null) {
            send(services, player, header, key, fallback, placeholders);
            return;
        }
        if (services != null && services.getLogger() != null) {
            String raw = fallback;
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                raw = raw.replace(placeholders[i], placeholders[i + 1]);
            }
            services.getLogger().info("[Paradigm {}] {}", header, raw);
        }
    }
}
