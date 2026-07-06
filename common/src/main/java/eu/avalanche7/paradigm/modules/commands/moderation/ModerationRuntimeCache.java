package eu.avalanche7.paradigm.modules.commands.moderation;

import eu.avalanche7.paradigm.storage.model.StoredPunishment;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ModerationRuntimeCache {
    private static final Map<String, StoredPunishment> ACTIVE_MUTES = new ConcurrentHashMap<>();

    private ModerationRuntimeCache() {
    }

    static void replaceMutes(Collection<StoredPunishment> punishments) {
        ACTIVE_MUTES.clear();
        if (punishments == null) {
            return;
        }
        for (StoredPunishment punishment : punishments) {
            putMute(punishment);
        }
    }

    static void putMute(StoredPunishment punishment) {
        if (!isActiveMute(punishment) || punishment.uuid() == null || punishment.uuid().isBlank()) {
            return;
        }
        ACTIVE_MUTES.put(punishment.uuid().toLowerCase(Locale.ROOT), punishment);
    }

    static void removeMute(String uuid) {
        if (uuid != null) {
            ACTIVE_MUTES.remove(uuid.toLowerCase(Locale.ROOT));
        }
    }

    static StoredPunishment getMute(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return null;
        }
        String key = uuid.toLowerCase(Locale.ROOT);
        StoredPunishment punishment = ACTIVE_MUTES.get(key);
        if (punishment == null) {
            return null;
        }
        Long expiresAt = punishment.expiresAtMs();
        if (expiresAt != null && expiresAt > 0L && expiresAt <= System.currentTimeMillis()) {
            ACTIVE_MUTES.remove(key);
            return null;
        }
        return punishment;
    }

    private static boolean isActiveMute(StoredPunishment punishment) {
        if (punishment == null || !punishment.active() || punishment.type() == null) {
            return false;
        }
        String type = punishment.type().toLowerCase(Locale.ROOT);
        return "mute".equals(type) || "tempmute".equals(type);
    }
}
