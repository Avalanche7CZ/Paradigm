package eu.avalanche7.paradigm.modules.moderation;

import java.util.List;
import java.util.Map;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.model.StoredLocation;

public class ModerationService {
    private final Services services;
    private final AuditService audit;

    public ModerationService(Services services, AuditService audit) {
        this.services = services;
        this.audit = audit;
    }

    public ModerationActionResult apply(DashboardPrincipal actor, ModerationActionRequest request) {
        return apply(actor, request, null);
    }

    public ModerationActionResult apply(DashboardPrincipal actor, ModerationActionRequest request, OnlineTarget online) {
        ModerationActionRequest safe = request != null ? request : new ModerationActionRequest();
        ModerationActionType type = ModerationActionType.parse(safe.action);
        if (type == null) {
            return result(false, "validation_failed", "Unknown moderation action.", false, null);
        }

        boolean confirmRequired = switch (type) {
            case BAN, TEMPBAN, UNBAN, IPBAN, TEMPIPBAN, UNIPBAN, REVOKE, JAIL -> true;
            default -> false;
        };
        if (confirmRequired && !Boolean.TRUE.equals(safe.confirmed)) {
            return result(false, "confirmation_required", "Confirmation is required for this moderation action.", true, null);
        }

        String targetUuid = online != null ? online.uuid() : text(safe.uuid);
        String targetName = online != null ? online.name() : text(safe.player);
        boolean idOnly = type == ModerationActionType.REVOKE || !text(safe.punishmentId).isBlank();
        if (!idOnly && targetName.isBlank() && targetUuid.isBlank() && text(safe.ipAddress).isBlank()) {
            return result(false, "validation_failed", "A player, IP address, or punishment ID is required.", false, null);
        }

        String reason = text(safe.reason).isBlank()
                ? services.getLang().getTranslation("moderation.no_reason")
                : text(safe.reason);
        Long expires = switch (type) {
            case TEMPMUTE, TEMPBAN, TEMPIPBAN, JAIL -> parseExpiry(safe.duration);
            default -> null;
        };

        if ((type == ModerationActionType.TEMPMUTE
                || type == ModerationActionType.TEMPBAN
                || type == ModerationActionType.TEMPIPBAN)
                && expires == null) {
            return result(false, "validation_failed", "Valid duration is required.", false, null);
        }
        if (type == ModerationActionType.JAIL && hasExplicitInvalidDuration(safe.duration, expires)) {
            return result(false, "validation_failed", "Invalid jail duration. Use values like 30m, 2h, 7d, or perm.", false, null);
        }

        ServerScope scope = "server".equalsIgnoreCase(safe.scope) ? ServerScope.SERVER : ServerScope.GLOBAL;
        PunishmentRecord[] created = new PunishmentRecord[1];
        boolean changed;
        try {
            changed = switch (type) {
                case WARN -> create(actor, PunishmentType.WARN, scope, targetUuid, targetName, null, reason, null, created);
                case MUTE -> create(actor, PunishmentType.MUTE, scope, targetUuid, targetName, null, reason, null, created);
                case TEMPMUTE -> create(actor, PunishmentType.MUTE, scope, targetUuid, targetName, null, reason, expires, created);
                case UNMUTE -> revokeMatching(actor, safe.punishmentId, targetUuid, null, PunishmentType.MUTE, reason);
                case BAN -> create(actor, PunishmentType.BAN, scope, targetUuid, targetName, null, reason, null, created);
                case TEMPBAN -> create(actor, PunishmentType.BAN, scope, targetUuid, targetName, null, reason, expires, created);
                case UNBAN -> revokeMatching(actor, safe.punishmentId, targetUuid, null, PunishmentType.BAN, reason);
                case IPBAN -> create(actor, PunishmentType.IP_BAN, scope, targetUuid, targetName,
                        resolveIp(safe, online), reason, null, created);
                case TEMPIPBAN -> create(actor, PunishmentType.IP_BAN, scope, targetUuid, targetName,
                        resolveIp(safe, online), reason, expires, created);
                case UNIPBAN -> revokeMatching(actor, safe.punishmentId, null, safe.ipAddress,
                        PunishmentType.IP_BAN, reason);
                case REVOKE -> services.getPunishmentService().revoke(
                        safe.punishmentId, actorUuid(actor), actorName(actor), reason);
                case JAIL -> createJail(actor, targetUuid, targetName, reason, expires, created);
                case UNJAIL -> unjail(actor, safe.punishmentId, targetUuid, reason);
            };
        } catch (IllegalArgumentException error) {
            audit(actor, type, AuditResult.FAILED, targetUuid, targetName, reason, safe.duration, safe.punishmentId);
            return result(false, "validation_failed", error.getMessage(), false, safe.punishmentId);
        }

        if (changed && online != null && isLoginBlockingAction(type)) {
            enforceCurrentPlayer(online.uuid());
        }
        if (changed && type == ModerationActionType.JAIL && !targetUuid.isBlank()) {
            teleportCurrentPlayerToJail(targetUuid);
        }

        audit(actor, type, changed ? AuditResult.SUCCESS : AuditResult.FAILED,
                targetUuid, targetName, reason, safe.duration,
                created[0] != null ? created[0].punishmentId() : safe.punishmentId);
        return result(
                changed,
                changed ? "ok" : "ambiguous_or_inactive",
                changed ? "Moderation action applied." : "Use an exact active punishment ID.",
                confirmRequired,
                created[0] != null ? created[0].punishmentId() : safe.punishmentId
        );
    }

    private boolean create(
            DashboardPrincipal actor,
            PunishmentType type,
            ServerScope scope,
            String uuid,
            String name,
            String ip,
            String reason,
            Long expires,
            PunishmentRecord[] output
    ) {
        output[0] = services.getPunishmentService().create(
                type,
                scope,
                blankToNull(uuid),
                blankToNull(name),
                blankToNull(ip),
                reason,
                actorUuid(actor),
                actorName(actor),
                expires
        );
        return true;
    }

    private boolean revokeMatching(
            DashboardPrincipal actor,
            String id,
            String uuid,
            String ip,
            PunishmentType type,
            String reason
    ) {
        if (!text(id).isBlank()) {
            PunishmentRecord record = services.getPunishmentService().find(id).orElse(null);
            if (record == null || record.type() != type || !record.activeAt(System.currentTimeMillis())) {
                return false;
            }
            return services.getPunishmentService().revoke(
                    record.punishmentId(), actorUuid(actor), actorName(actor), reason);
        }

        List<PunishmentRecord> matches = services.getPunishmentService()
                .activeFor(blankToNull(uuid), blankToNull(ip))
                .stream()
                .filter(record -> record.type() == type)
                .toList();
        if (matches.size() != 1) {
            return false;
        }
        return services.getPunishmentService().revoke(
                matches.get(0).punishmentId(), actorUuid(actor), actorName(actor), reason);
    }

    private boolean unjail(DashboardPrincipal actor, String id, String uuid, String reason) {
        String targetUuid = text(uuid);
        if (targetUuid.isBlank()) {
            return false;
        }

        PunishmentRecord target;
        if (!text(id).isBlank()) {
            target = services.getPunishmentService().find(id).orElse(null);
            if (target == null
                    || target.type() != PunishmentType.JAIL
                    || !target.activeAt(System.currentTimeMillis())
                    || target.subjectUuid() == null
                    || !targetUuid.equalsIgnoreCase(target.subjectUuid())) {
                return false;
            }
        } else {
            List<PunishmentRecord> matches = services.getPunishmentService()
                    .activeFor(targetUuid, null)
                    .stream()
                    .filter(record -> record.type() == PunishmentType.JAIL)
                    .toList();
            if (matches.size() != 1) {
                return false;
            }
            target = matches.get(0);
        }

        StoredJailState previousState = services.getStorageService().moderation().getJailState(targetUuid).orElse(null);
        if (previousState != null && !services.getStorageService().moderation().clearJailState(targetUuid)) {
            return false;
        }

        boolean revoked = services.getPunishmentService().revoke(
                target.punishmentId(), actorUuid(actor), actorName(actor), reason);
        if (!revoked && previousState != null) {
            services.getStorageService().moderation().setJailState(previousState);
        }
        return revoked;
    }

    private boolean createJail(
            DashboardPrincipal actor,
            String uuid,
            String name,
            String reason,
            Long expiresAtMs,
            PunishmentRecord[] output
    ) {
        String normalizedUuid = text(uuid);
        if (normalizedUuid.isBlank()) {
            throw new IllegalArgumentException("Jail requires a player UUID.");
        }

        StoredLocation jailLocation = services.getStorageService().moderation().getJailLocation().orElse(null);
        if (jailLocation == null) {
            return false;
        }

        PunishmentRecord punishment = services.getPunishmentService().create(
                PunishmentType.JAIL,
                ServerScope.SERVER,
                normalizedUuid,
                blankToNull(name),
                null,
                reason,
                actorUuid(actor),
                actorName(actor),
                expiresAtMs
        );
        output[0] = punishment;

        try {
            services.getStorageService().moderation().setJailState(new StoredJailState(
                    services.getStorageService().context().serverId(),
                    normalizedUuid,
                    name,
                    reason,
                    actorName(actor),
                    jailLocation,
                    System.currentTimeMillis(),
                    expiresAtMs
            ));
            return true;
        } catch (RuntimeException | Error failure) {
            output[0] = null;
            try {
                services.getPunishmentService().revoke(
                        punishment.punishmentId(),
                        actorUuid(actor),
                        actorName(actor),
                        "Jail state persistence failed."
                );
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    public OnlineTarget snapshotPlayer(String value) {
        String query = text(value);
        if (query.isBlank()) {
            return null;
        }
        IPlayer player = services.getPlatformAdapter().getPlayerByUuid(query);
        if (player == null) {
            player = services.getPlatformAdapter().getPlayerByName(query);
        }
        if (player == null) {
            return null;
        }
        return new OnlineTarget(
                text(player.getUUID()),
                text(player.getName()),
                text(services.getPlatformAdapter().getPlayerRemoteAddress(player))
        );
    }

    private String resolveIp(ModerationActionRequest request, OnlineTarget online) {
        String requested = text(request.ipAddress);
        if (!requested.isBlank()) {
            return requested;
        }
        return online != null ? blankToNull(online.remoteAddress()) : null;
    }

    private Long parseExpiry(String value) {
        String normalized = text(value);
        if (normalized.isBlank()
                || "permanent".equalsIgnoreCase(normalized)
                || "perm".equalsIgnoreCase(normalized)) {
            return null;
        }
        long millis = DurationParser.parseToMillis(normalized);
        return millis > 0L ? System.currentTimeMillis() + millis : null;
    }

    private boolean hasExplicitInvalidDuration(String raw, Long parsedExpiry) {
        String value = text(raw);
        if (value.isBlank()
                || "permanent".equalsIgnoreCase(value)
                || "perm".equalsIgnoreCase(value)) {
            return false;
        }
        return parsedExpiry == null;
    }

    private void enforceCurrentPlayer(String uuid) {
        String normalizedUuid = text(uuid);
        if (normalizedUuid.isBlank()) {
            return;
        }
        runOnServerThread(() -> {
            IPlayer current = services.getPlatformAdapter().getPlayerByUuid(normalizedUuid);
            if (current != null) {
                services.getPunishmentService().enforcePlayer(current);
            }
        });
    }

    private void teleportCurrentPlayerToJail(String uuid) {
        String normalizedUuid = text(uuid);
        if (normalizedUuid.isBlank()) {
            return;
        }
        StoredLocation jailLocation = services.getStorageService().moderation().getJailLocation().orElse(null);
        if (jailLocation == null) {
            return;
        }
        PlayerDataStore.StoredLocation destination = new PlayerDataStore.StoredLocation(
                jailLocation.worldId(),
                jailLocation.x(),
                jailLocation.y(),
                jailLocation.z(),
                jailLocation.yaw(),
                jailLocation.pitch()
        );
        runOnServerThread(() -> {
            IPlayer current = services.getPlatformAdapter().getPlayerByUuid(normalizedUuid);
            if (current != null) {
                services.getPlatformAdapter().teleportPlayer(current, destination);
            }
        });
    }

    private void runOnServerThread(Runnable runnable) {
        if (services.getTaskScheduler() != null) {
            services.getTaskScheduler().schedule(runnable, 0L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            runnable.run();
        }
    }

    private static boolean isLoginBlockingAction(ModerationActionType type) {
        return type == ModerationActionType.BAN
                || type == ModerationActionType.TEMPBAN
                || type == ModerationActionType.IPBAN
                || type == ModerationActionType.TEMPIPBAN;
    }

    private void audit(
            DashboardPrincipal actor,
            ModerationActionType action,
            AuditResult result,
            String targetUuid,
            String targetName,
            String reason,
            String duration,
            String punishmentId
    ) {
        if (audit == null) {
            return;
        }
        audit.dashboard(
                actor,
                AuditActionType.MODERATION_ACTION,
                result,
                "Moderation action " + action.name().toLowerCase(java.util.Locale.ROOT) + ".",
                Map.of(
                        "action", action.name(),
                        "punishmentId", safe(punishmentId),
                        "target", safe(targetName),
                        "targetUuid", safe(targetUuid),
                        "reason", safe(reason),
                        "duration", safe(duration)
                )
        );
    }

    private static ModerationActionResult result(
            boolean applied,
            String code,
            String message,
            boolean confirmation,
            String id
    ) {
        return new ModerationActionResult(applied, code, message, confirmation, id);
    }

    private static String actorName(DashboardPrincipal actor) {
        return actor != null && actor.name() != null ? actor.name() : "dashboard";
    }

    private static String actorUuid(DashboardPrincipal actor) {
        return actor != null ? actor.uuid() : null;
    }

    private static String text(String value) {
        return value != null ? value.trim() : "";
    }

    private static String blankToNull(String value) {
        String result = text(value);
        return result.isBlank() ? null : result;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    public record OnlineTarget(String uuid, String name, String remoteAddress) {
    }
}
