package eu.avalanche7.paradigm.modules.chat;

import eu.avalanche7.paradigm.core.Services;

public final class ChatConsoleLogger {
    private ChatConsoleLogger() {
    }

    public static void log(Services services, String playerName, String message) {
        if (services != null && services.getLogger() != null) {
            services.getLogger().info("<{}> {}", playerName != null ? playerName : "unknown", message != null ? message : "");
        }
    }
}
