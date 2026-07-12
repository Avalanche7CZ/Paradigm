package eu.avalanche7.paradigm.modules.moderation;

import eu.avalanche7.paradigm.storage.identity.ServerScope;

import java.util.Map;

public record PunishmentRecord(
        String punishmentId,
        PunishmentType type,
        ServerScope scope,
        String networkId,
        String serverId,
        String subjectUuid,
        String subjectName,
        String subjectIpHash,
        String subjectIpAddress,
        String reason,
        String actorUuid,
        String actorName,
        long createdAtMs,
        long startsAtMs,
        Long expiresAtMs,
        Long revokedAtMs,
        String revokedByUuid,
        String revokedByName,
        String revokeReason,
        long updatedAtMs,
        Map<String, String> metadata
) {
    public PunishmentRecord {
        if (!PunishmentIds.isValid(punishmentId)) throw new IllegalArgumentException("Invalid punishment id.");
        if (type == null) throw new IllegalArgumentException("Punishment type is required.");
        scope = scope != null ? scope : ServerScope.GLOBAL;
        createdAtMs = createdAtMs > 0L ? createdAtMs : System.currentTimeMillis();
        startsAtMs = startsAtMs > 0L ? startsAtMs : createdAtMs;
        updatedAtMs = updatedAtMs > 0L ? updatedAtMs : createdAtMs;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public PunishmentStatus status(long nowMs) {
        if (revokedAtMs != null) return PunishmentStatus.REVOKED;
        if (startsAtMs > nowMs) return PunishmentStatus.PENDING;
        if (expiresAtMs != null && expiresAtMs <= nowMs) return PunishmentStatus.EXPIRED;
        return PunishmentStatus.ACTIVE;
    }

    public boolean activeAt(long nowMs) {
        return status(nowMs) == PunishmentStatus.ACTIVE;
    }

    public boolean appliesTo(String currentNetworkId, String currentServerId) {
        if (networkId != null && currentNetworkId != null && !networkId.equalsIgnoreCase(currentNetworkId)) return false;
        return scope != ServerScope.SERVER || (serverId != null && serverId.equalsIgnoreCase(currentServerId));
    }

    public PunishmentRecord revoked(long nowMs, String actorUuid, String actorName, String reason) {
        return new PunishmentRecord(punishmentId, type, scope, networkId, serverId, subjectUuid, subjectName,
                subjectIpHash, subjectIpAddress, this.reason, this.actorUuid, this.actorName, createdAtMs,
                startsAtMs, expiresAtMs, nowMs, actorUuid, actorName, reason, nowMs, metadata);
    }

    public PunishmentRecord withoutSensitiveIp() {
        return new PunishmentRecord(punishmentId, type, scope, networkId, serverId, subjectUuid, subjectName,
                subjectIpHash != null ? IpAddressUtil.maskHash(subjectIpHash) : null, null, reason, actorUuid,
                actorName, createdAtMs, startsAtMs, expiresAtMs, revokedAtMs, revokedByUuid, revokedByName,
                revokeReason, updatedAtMs, metadata);
    }
}
