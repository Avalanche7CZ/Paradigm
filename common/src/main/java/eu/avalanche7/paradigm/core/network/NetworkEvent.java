package eu.avalanche7.paradigm.core.network;

import java.util.Map;

public record NetworkEvent(
        String channel,
        String eventId,
        String networkId,
        String originServerId,
        String type,
        long createdAtMs,
        Map<String, String> payload
) {
    public NetworkEvent {
        payload = payload != null ? Map.copyOf(payload) : Map.of();
    }

    public String payload(String key) {
        return payload.get(key);
    }
}
