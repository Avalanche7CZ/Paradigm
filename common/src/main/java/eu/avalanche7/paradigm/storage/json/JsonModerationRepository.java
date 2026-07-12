package eu.avalanche7.paradigm.storage.json;

import eu.avalanche7.paradigm.data.ModerationDataStore;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPunishment;
import eu.avalanche7.paradigm.storage.model.StoredWarning;
import eu.avalanche7.paradigm.storage.repository.ModerationRepository;
import eu.avalanche7.paradigm.modules.moderation.PunishmentIds;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.Comparator;

public class JsonModerationRepository implements ModerationRepository {
    private final ModerationDataStore store;
    private final StorageContext context;

    public JsonModerationRepository(ModerationDataStore store, StorageContext context) {
        this.store = store;
        this.context = context;
    }

    @Override
    public PunishmentRecord addPunishmentRecord(PunishmentRecord punishment) {
        ensureLegacyLedger();
        return store != null ? store.addPunishmentRecord(punishment) : null;
    }

    @Override
    public Optional<PunishmentRecord> findPunishmentRecord(String punishmentId) {
        ensureLegacyLedger();
        return store == null ? Optional.empty() : store.punishmentRecords().stream()
                .filter(record -> record != null && record.punishmentId().equalsIgnoreCase(punishmentId)).findFirst();
    }

    @Override
    public boolean revokePunishmentRecord(String punishmentId, long revokedAtMs, String actorUuid, String actorName, String reason) {
        Optional<PunishmentRecord> current = findPunishmentRecord(punishmentId);
        return current.isPresent() && current.get().revokedAtMs() == null
                && store.replacePunishmentRecord(current.get().revoked(revokedAtMs, actorUuid, actorName, reason));
    }

    @Override
    public List<PunishmentRecord> listPunishmentRecords(String subjectUuid, int offset, int limit) {
        ensureLegacyLedger();
        if (store == null) return List.of();
        String uuid = subjectUuid != null ? subjectUuid.trim().toLowerCase(Locale.ROOT) : null;
        return store.punishmentRecords().stream()
                .filter(record -> record != null && (uuid == null || (record.subjectUuid() != null && record.subjectUuid().equalsIgnoreCase(uuid))))
                .sorted(Comparator.comparingLong(PunishmentRecord::createdAtMs).reversed())
                .skip(Math.max(0, offset)).limit(Math.max(1, Math.min(limit, 500))).toList();
    }

    @Override
    public List<PunishmentRecord> listActivePunishmentRecords(long updatedAfterMs) {
        ensureLegacyLedger();
        long now = System.currentTimeMillis();
        return store == null ? List.of() : store.punishmentRecords().stream()
                .filter(record -> record != null && record.updatedAtMs() > updatedAfterMs && record.activeAt(now)
                        && record.appliesTo(networkId(), serverId())).toList();
    }

    private void ensureLegacyLedger() {
        if (store == null || !store.punishmentRecords().isEmpty()) return;
        for (ModerationDataStore.MuteEntry entry : store.activeMutes()) addLegacy("mute", ServerScope.SERVER, entry.uuid, entry.name, entry.reason, entry.actor, entry.createdAtMs, entry.expiresAtMs > 0L ? entry.expiresAtMs : null);
        for (ModerationDataStore.TempBanEntry entry : store.activeTempBans()) addLegacy("tempban", ServerScope.GLOBAL, null, entry.name, entry.reason, entry.actor, entry.createdAtMs, entry.expiresAtMs > 0L ? entry.expiresAtMs : null);
        for (ModerationDataStore.BanEntry entry : store.activeBans()) addLegacy("ban", ServerScope.GLOBAL, null, entry.name, entry.reason, entry.actor, entry.createdAtMs, null);
        for (ModerationDataStore.WarnEntry entry : store.warnings()) addLegacy("warn", ServerScope.GLOBAL, entry.uuid, entry.name, entry.reason, entry.actor, entry.createdAtMs, null);
        for (ModerationDataStore.JailEntry entry : store.activeJails()) addLegacy("jail", ServerScope.SERVER, entry.uuid, entry.name, entry.reason, entry.actor, entry.createdAtMs, entry.expiresAtMs > 0L ? entry.expiresAtMs : null);
    }

    private void addLegacy(String type, ServerScope scope, String uuid, String name, String reason, String actor, long createdAt, Long expiresAt) {
        long created = createdAt > 0L ? createdAt : 1L;
        String source = type + '|' + scope + '|' + uuid + '|' + name + '|' + created;
        store.addPunishmentRecord(new PunishmentRecord(PunishmentIds.legacy(source), PunishmentType.fromLegacy(type), scope,
                networkId(), scope == ServerScope.SERVER ? serverId() : null, uuid, name, null, null, reason,
                null, actor, created, created, expiresAt, null, null, null, null, created, Map.of("legacy", "json")));
    }

    @Override
    public long addPunishment(StoredPunishment punishment) {
        if (store == null || punishment == null) return 0L;
        String type = punishment.type() != null ? punishment.type().toLowerCase(java.util.Locale.ROOT) : "";
        if ("mute".equals(type) || "tempmute".equals(type)) {
            store.setMute(punishment.uuid(), punishment.name(), punishment.expiresAtMs() != null ? punishment.expiresAtMs() : 0L, punishment.reason(), punishment.actor());
        } else if ("tempban".equals(type)) {
            store.setTempBan(punishment.name(), punishment.expiresAtMs() != null ? punishment.expiresAtMs() : 0L, punishment.reason(), punishment.actor());
        } else if ("ban".equals(type)) {
            store.setBan(punishment.name(), punishment.reason(), punishment.actor());
        }
        return System.currentTimeMillis();
    }

    @Override
    public boolean deactivatePunishment(long id) {
        return false;
    }

    @Override
    public boolean deactivateActivePunishments(String type, String uuid, String name) {
        if (store == null || type == null) return false;
        String normalizedType = type.trim().toLowerCase(Locale.ROOT);
        if (("mute".equals(normalizedType) || "tempmute".equals(normalizedType)) && uuid != null) {
            return store.clearMute(uuid);
        }
        if ("tempban".equals(normalizedType) && name != null) {
            return store.clearTempBan(name);
        }
        if ("ban".equals(normalizedType) && name != null) {
            return store.clearBan(name);
        }
        return false;
    }

    @Override
    public List<StoredPunishment> listPunishments() {
        if (store == null) return List.of();
        List<StoredPunishment> result = new ArrayList<>();
        for (ModerationDataStore.MuteEntry mute : store.activeMutes()) {
            result.add(new StoredPunishment(0L, mute.expiresAtMs > 0L ? "tempmute" : "mute", ServerScope.SERVER, serverId(), mute.uuid, mute.name, mute.reason, mute.actor, mute.createdAtMs, mute.expiresAtMs > 0L ? mute.expiresAtMs : null, true));
        }
        for (ModerationDataStore.TempBanEntry ban : store.activeTempBans()) {
            result.add(new StoredPunishment(0L, "tempban", ServerScope.GLOBAL, null, null, ban.name, ban.reason, ban.actor, ban.createdAtMs, ban.expiresAtMs > 0L ? ban.expiresAtMs : null, true));
        }
        for (ModerationDataStore.BanEntry ban : store.activeBans()) {
            result.add(new StoredPunishment(0L, "ban", ServerScope.GLOBAL, null, null, ban.name, ban.reason, ban.actor, ban.createdAtMs, null, true));
        }
        return result;
    }

    @Override
    public List<StoredPunishment> activePunishments(String uuid, ServerScope scope) {
        if (store == null) return List.of();
        List<StoredPunishment> result = new ArrayList<>();
        ModerationDataStore.MuteEntry mute = store.getMute(uuid);
        if (mute != null) {
            result.add(new StoredPunishment(0L, "mute", ServerScope.SERVER, serverId(), mute.uuid, mute.name, mute.reason, mute.actor, mute.createdAtMs, mute.expiresAtMs > 0L ? mute.expiresAtMs : null, true));
        }
        return result;
    }

    @Override
    public List<StoredPunishment> consumeExpiredPunishments(long nowMs) {
        if (store == null) return List.of();
        List<StoredPunishment> result = new ArrayList<>();
        for (ModerationDataStore.TempBanEntry entry : store.consumeExpiredTempBans(nowMs)) {
            result.add(new StoredPunishment(0L, "tempban", ServerScope.SERVER, serverId(), null, entry.name, entry.reason, entry.actor, entry.createdAtMs, entry.expiresAtMs, false));
        }
        return result;
    }

    @Override
    public long addWarning(StoredWarning warning) {
        if (store != null && warning != null) {
            store.addWarning(warning.uuid(), warning.name(), warning.reason(), warning.actor());
        }
        return System.currentTimeMillis();
    }

    @Override
    public List<StoredWarning> listWarnings() {
        if (store == null) return List.of();
        List<StoredWarning> result = new ArrayList<>();
        for (ModerationDataStore.WarnEntry warning : store.warnings()) {
            result.add(new StoredWarning(0L, warning.uuid, warning.name, warning.reason, warning.actor, warning.createdAtMs));
        }
        return result;
    }

    @Override
    public List<StoredWarning> listWarnings(String uuid) {
        if (store == null) return List.of();
        List<StoredWarning> result = new ArrayList<>();
        for (ModerationDataStore.WarnEntry warning : store.warningsFor(uuid)) {
            result.add(new StoredWarning(0L, warning.uuid, warning.name, warning.reason, warning.actor, warning.createdAtMs));
        }
        return result;
    }

    @Override
    public void setJailLocation(StoredLocation location) {
        if (store != null && location != null) {
            store.setJailLocation(JsonPlayerRepository.toData(location));
        }
    }

    @Override
    public Optional<StoredLocation> getJailLocation() {
        var location = store != null ? store.getJailLocation() : null;
        return location != null ? Optional.of(JsonPlayerRepository.fromData(location)) : Optional.empty();
    }

    @Override
    public void setJailState(StoredJailState jailState) {
        if (store != null && jailState != null && jailState.location() != null) {
            store.setJail(jailState.uuid(), jailState.name(), jailState.expiresAtMs() != null ? jailState.expiresAtMs() : 0L, jailState.reason(), jailState.actor());
            store.setJailLocation(JsonPlayerRepository.toData(jailState.location()));
        }
    }

    @Override
    public Optional<StoredJailState> getJailState(String uuid) {
        ModerationDataStore.JailEntry jail = store != null ? store.getJail(uuid) : null;
        if (jail == null) return Optional.empty();
        var location = store.getJailLocation();
        return Optional.of(new StoredJailState(serverId(), jail.uuid, jail.name, jail.reason, jail.actor, location != null ? JsonPlayerRepository.fromData(location) : null, jail.createdAtMs, jail.expiresAtMs > 0L ? jail.expiresAtMs : null));
    }

    @Override
    public List<StoredJailState> listJailStates() {
        if (store == null) return List.of();
        List<StoredJailState> result = new ArrayList<>();
        var location = store.getJailLocation();
        for (ModerationDataStore.JailEntry jail : store.activeJails()) {
            result.add(new StoredJailState(serverId(), jail.uuid, jail.name, jail.reason, jail.actor, location != null ? JsonPlayerRepository.fromData(location) : null, jail.createdAtMs, jail.expiresAtMs > 0L ? jail.expiresAtMs : null));
        }
        return result;
    }

    @Override
    public boolean clearJailState(String uuid) {
        return store != null && store.clearJail(uuid);
    }

    @Override
    public List<StoredJailState> consumeExpiredJails(long nowMs) {
        if (store == null) return List.of();
        List<StoredJailState> result = new ArrayList<>();
        for (ModerationDataStore.JailEntry jail : store.consumeExpiredJails(nowMs)) {
            result.add(new StoredJailState(serverId(), jail.uuid, jail.name, jail.reason, jail.actor, null, jail.createdAtMs, jail.expiresAtMs));
        }
        return result;
    }

    private String serverId() {
        return context != null ? context.serverId() : "default";
    }

    private String networkId() {
        return context != null ? context.networkId() : "default";
    }
}
