package eu.avalanche7.paradigm.modules.discord;

import eu.avalanche7.paradigm.configs.DiscordConfigHandler;

public interface DiscordOutbox {
    DiscordConfigHandler.Config config();

    boolean isEnabled();

    String botUserId();

    default boolean isOwnWebhook(String webhookId) {
        return false;
    }

    default void auditConsoleCommand(String actorId, String actorName, String command) {
    }

    boolean send(DiscordMessage message);

    boolean sendNotification(String content, int colorRgb, String title);

    boolean sendServer(String content, int colorRgb, String title);

    boolean sendModeration(String content, int colorRgb, String title, String dedupeKey);
}
