package eu.avalanche7.paradigm.modules.moderation;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import eu.avalanche7.paradigm.storage.identity.ServerScope;

public final class ActivePunishmentCache {
    private volatile Map<String, PunishmentRecord> byId = Map.of();

    public synchronized void replace(Collection<PunishmentRecord> records) {
        long now = System.currentTimeMillis();
        Map<String, PunishmentRecord> next = new LinkedHashMap<>();
        if (records != null) {
            for (PunishmentRecord record : records) {
                if (record != null && record.activeAt(now)) {
                    next.put(record.punishmentId(), record);
                }
            }
        }
        byId = Map.copyOf(next);
    }

    public synchronized void put(PunishmentRecord record) {
        if (record == null || !record.activeAt(System.currentTimeMillis())) return;
        Map<String, PunishmentRecord> next = new LinkedHashMap<>(byId);
        next.put(record.punishmentId(), record);
        byId = Map.copyOf(next);
    }

    public synchronized void remove(String punishmentId) {
        if (punishmentId == null || !byId.containsKey(punishmentId)) return;
        Map<String, PunishmentRecord> next = new LinkedHashMap<>(byId);
        next.remove(punishmentId);
        byId = Map.copyOf(next);
    }

    public List<PunishmentRecord> activeFor(String uuid, String ipHash, String networkId, String serverId) {
        long now = System.currentTimeMillis();
        Map<String, PunishmentRecord> snapshot = byId;
        return snapshot.values().stream().filter(record -> {
            if (!record.activeAt(now) || !record.appliesTo(networkId, serverId)) return false;
            boolean uuidMatch = uuid != null && record.subjectUuid() != null && record.subjectUuid().equalsIgnoreCase(uuid);
            boolean ipMatch = ipHash != null && record.subjectIpHash() != null && record.subjectIpHash().equals(ipHash);
            return uuidMatch || ipMatch;
        }).sorted(precedence()).toList();
    }

    public Optional<PunishmentRecord> loginBlock(String uuid, String ipHash, String networkId, String serverId) {
        return activeFor(uuid, ipHash, networkId, serverId).stream()
                .filter(record -> record.type() == PunishmentType.BAN || record.type() == PunishmentType.IP_BAN).findFirst();
    }

    public int size() { return byId.size(); }

    private static Comparator<PunishmentRecord> precedence() {
        return Comparator.comparingInt(ActivePunishmentCache::score).thenComparing(PunishmentRecord::createdAtMs).reversed()
                .thenComparing(PunishmentRecord::punishmentId);
    }

    private static int score(PunishmentRecord record) {
        int score = record.scope() == ServerScope.GLOBAL ? 20 : 0;
        if (record.type() == PunishmentType.IP_BAN) score += 10;
        return score;
    }
}
