package eu.avalanche7.paradigm.storage.json;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import eu.avalanche7.paradigm.data.ModerationDataStore;
import eu.avalanche7.paradigm.modules.moderation.PunishmentIds;
import eu.avalanche7.paradigm.modules.moderation.PunishmentRecord;
import eu.avalanche7.paradigm.modules.moderation.PunishmentType;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.identity.StorageContext;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredPunishment;
import eu.avalanche7.paradigm.storage.model.StoredWarning;
import eu.avalanche7.paradigm.storage.repository.ModerationRepository;

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
        ensureLegacyLedger();
        return store.mutateState(state -> {
            long effectiveNow = Math.max(revokedAtMs, System.currentTimeMillis());
            for (int i = 0; i < state.punishments.size(); i++) {
                PunishmentRecord record = state.punishments.get(i);
                if (record.punishmentId().equalsIgnoreCase(punishmentId) && record.activeAt(effectiveNow)) {
                    StoredJailState jail = record.subjectUuid() != null ? jailState(state, record.subjectUuid()) : null;
                    state.punishments.set(i, record.revoked(revokedAtMs, actorUuid, actorName, reason));
                    if (record.type() == PunishmentType.JAIL && jail != null && punishmentId.equals(jail.punishmentId())) {
                        state.jails.remove(record.subjectUuid());
                    }
                    return true;
                }
            }
            return false;
        });
    }

    @Override
    public List<PunishmentRecord> replaceJail(PunishmentRecord punishment, StoredJailState expected,
                                              StoredJailState replacement) {
        ensureLegacyLedger();
        if (punishment.type() != PunishmentType.JAIL || punishment.scope() != ServerScope.SERVER
                || !serverId().equals(punishment.serverId()) || !networkId().equals(punishment.networkId())
                || !punishment.subjectUuid().equals(replacement.uuid())
                || !punishment.punishmentId().equals(replacement.punishmentId())) {
            throw new IllegalArgumentException("Jail state and punishment must identify the same server/player.");
        }
        return store.mutateState(state -> {
            if (!punishment.activeAt(System.currentTimeMillis())) throw new IllegalStateException("Jail duration expired before commit.");
            if (!java.util.Objects.equals(expected, jailState(state, replacement.uuid()))) {
                throw new IllegalStateException("Jail state changed during replacement. Try again.");
            }
            long now = System.currentTimeMillis();
            List<PunishmentRecord> superseded = new ArrayList<>();
            for (int i = 0; i < state.punishments.size(); i++) {
                PunishmentRecord old = state.punishments.get(i);
                if (old.type() == PunishmentType.JAIL && old.scope() == ServerScope.SERVER
                        && serverId().equals(old.serverId()) && replacement.uuid().equalsIgnoreCase(old.subjectUuid())
                        && old.activeAt(now)) {
                    superseded.add(old);
                    state.punishments.set(i, old.revoked(now, punishment.actorUuid(), punishment.actorName(),
                            "Replaced by " + punishment.punishmentId()));
                }
            }
            state.punishments.add(punishment);
            ModerationDataStore.JailEntry entry = new ModerationDataStore.JailEntry();
            entry.uuid = replacement.uuid();
            entry.name = replacement.name();
            entry.reason = replacement.reason();
            entry.actor = replacement.actor();
            entry.createdAtMs = replacement.createdAtMs();
            entry.expiresAtMs = replacement.expiresAtMs() != null ? replacement.expiresAtMs() : 0L;
            entry.punishmentId = replacement.punishmentId();
            entry.location = JsonPlayerRepository.toData(replacement.location());
            state.jails.put(entry.uuid, entry);
            return superseded;
        });
    }

    private StoredJailState jailState(ModerationDataStore.State state, String uuid) {
        ModerationDataStore.JailEntry jail = state.jails.get(uuid);
        if (jail == null) return null;
        var location = jail.location != null ? jail.location : state.jailLocation;
        String id = jail.punishmentId;
        if (id == null) {
            id = state.punishments.stream().filter(record -> record.type() == PunishmentType.JAIL
                    && record.scope() == ServerScope.SERVER && serverId().equals(record.serverId())
                    && uuid.equalsIgnoreCase(record.subjectUuid()) && record.revokedAtMs() == null)
                    .sorted(Comparator.comparingInt((PunishmentRecord record) -> record.createdAtMs() == jail.createdAtMs ? 0 : 1)
                            .thenComparing(Comparator.comparingLong(PunishmentRecord::createdAtMs).reversed())
                            .thenComparing(PunishmentRecord::punishmentId))
                    .map(PunishmentRecord::punishmentId).findFirst().orElse(null);
        }
        return new StoredJailState(serverId(), jail.uuid, jail.name, jail.reason, jail.actor,
                location != null ? JsonPlayerRepository.fromData(location) : null,
                jail.createdAtMs, jail.expiresAtMs > 0L ? jail.expiresAtMs : null, id);
    }

    private String escalationSubject(String uuid) {
        return networkId() + ":" + uuid.toLowerCase(Locale.ROOT);
    }

    @Override
    public Map<String, Long> escalationWatermarks(String uuid) {
        return store.readState(state -> Map.copyOf(state.escalationClaims.getOrDefault(escalationSubject(uuid), Map.of())));
    }

    @Override
    public Optional<PunishmentRecord> claimEscalation(PunishmentRecord punishment, Map<String, Long> expected, long consumedAtMs) {
        if (punishment.type() != PunishmentType.BAN || punishment.scope() != ServerScope.GLOBAL
                || !networkId().equals(punishment.networkId()) || expected.isEmpty()
                || expected.values().stream().anyMatch(value -> value < 0L || value >= consumedAtMs)) {
            throw new IllegalArgumentException("Escalation requires advancing rule watermarks and a global ban.");
        }
        return store.mutateState(state -> {
            Map<String, Long> current = state.escalationClaims.computeIfAbsent(escalationSubject(punishment.subjectUuid()), ignored -> new java.util.LinkedHashMap<>());
            if (expected.entrySet().stream().anyMatch(entry -> !entry.getValue().equals(current.getOrDefault(entry.getKey(), 0L)))) return Optional.empty();
            if (!punishment.activeAt(System.currentTimeMillis())) throw new IllegalStateException("Escalation expired before commit.");
            state.punishments.add(punishment);
            expected.keySet().forEach(rule -> current.put(rule, consumedAtMs));
            return Optional.of(punishment);
        });
    }

    @Override
    public boolean clearJailState(StoredJailState expected) {
        return store.mutateState(state -> {
            if (!java.util.Objects.equals(expected, jailState(state, expected.uuid()))) return false;
            state.jails.remove(expected.uuid());
            return true;
        });
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
        if (store == null || jailState == null || jailState.location() == null) return;
        store.mutateState(state -> {
            ModerationDataStore.JailEntry entry = new ModerationDataStore.JailEntry();
            entry.uuid = jailState.uuid();
            entry.name = jailState.name();
            entry.reason = jailState.reason();
            entry.actor = jailState.actor();
            entry.createdAtMs = jailState.createdAtMs();
            entry.expiresAtMs = jailState.expiresAtMs() != null ? jailState.expiresAtMs() : 0L;
            entry.punishmentId = jailState.punishmentId();
            entry.location = JsonPlayerRepository.toData(jailState.location());
            state.jails.put(entry.uuid, entry);
            return null;
        });
    }

    @Override
    public Optional<StoredJailState> getJailState(String uuid) {
        ensureLegacyLedger();
        return store == null ? Optional.empty() : store.readState(state -> Optional.ofNullable(jailState(state, uuid)));
    }

    @Override
    public List<StoredJailState> listJailStates() {
        ensureLegacyLedger();
        return store == null ? List.of() : store.readState(state -> state.jails.keySet().stream()
                .map(uuid -> jailState(state, uuid)).toList());
    }

    @Override
    public boolean clearJailState(String uuid) {
        return store != null && store.mutateState(state -> state.jails.remove(uuid) != null);
    }

    @Override
    public List<StoredJailState> consumeExpiredJails(long nowMs) {
        if (store == null) return List.of();
        return store.mutateState(state -> {
            List<StoredJailState> expired = state.jails.keySet().stream()
                    .map(uuid -> jailState(state, uuid))
                    .filter(jail -> jail.expiresAtMs() != null && jail.expiresAtMs() <= nowMs).toList();
            for (StoredJailState jail : expired) state.jails.remove(jail.uuid());
            return expired;
        });
    }

    private String serverId() {
        return context != null ? context.serverId() : "default";
    }

    private String networkId() {
        return context != null ? context.networkId() : "default";
    }
}
