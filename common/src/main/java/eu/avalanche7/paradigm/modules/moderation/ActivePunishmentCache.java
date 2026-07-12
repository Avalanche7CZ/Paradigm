package eu.avalanche7.paradigm.modules.moderation;

import eu.avalanche7.paradigm.storage.identity.ServerScope;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ActivePunishmentCache {
    private final Map<String, PunishmentRecord> byId = new ConcurrentHashMap<>();

    public void replace(Collection<PunishmentRecord> records) {
        byId.clear();
        if (records != null) records.forEach(this::put);
    }

    public void put(PunishmentRecord record) {
        if (record != null && record.activeAt(System.currentTimeMillis())) byId.put(record.punishmentId(), record);
    }

    public void remove(String punishmentId) {
        if (punishmentId != null) byId.remove(punishmentId);
    }

    public List<PunishmentRecord> activeFor(String uuid, String ipHash, String networkId, String serverId) {
        long now = System.currentTimeMillis();
        return byId.values().stream().filter(record -> {
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
