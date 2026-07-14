package eu.avalanche7.paradigm.api;

import java.util.Map;
import java.util.UUID;

/** Server-side formatted message delivery without exposing Minecraft component types. */
public interface MessageService {
    MessageResult sendPlayerMessage(UUID playerUuid, String template, Map<String, String> placeholders);

    MessageResult broadcastMessage(String template, Map<String, String> placeholders);
}
