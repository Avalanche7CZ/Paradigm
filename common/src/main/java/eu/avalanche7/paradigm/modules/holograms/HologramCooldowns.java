package eu.avalanche7.paradigm.modules.holograms;

import java.util.HashMap;
import java.util.Map;

final class HologramCooldowns {
    private final Map<String, Long> nextAllowed = new HashMap<>();

    boolean tryAcquire(String sourceId, String playerId, int cooldownSeconds, long now) {
        String key = sourceId + '\u0000' + playerId;
        if (nextAllowed.getOrDefault(key, 0L) > now) return false;
        if (cooldownSeconds > 0) nextAllowed.put(key, now + cooldownSeconds * 1000L);
        else nextAllowed.remove(key);
        return true;
    }

    void clear() {
        nextAllowed.clear();
    }
}
