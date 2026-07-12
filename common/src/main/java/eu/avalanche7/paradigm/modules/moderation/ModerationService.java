package eu.avalanche7.paradigm.modules.moderation;

import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.data.PlayerDataStore;
import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.model.StoredJailState;

import java.util.Map;

public class ModerationService {
    private final Services services;
    private final AuditService audit;

    public ModerationService(Services services, AuditService audit) {
        this.services = services;
        this.audit = audit;
    }

    public ModerationActionResult apply(DashboardPrincipal actor, ModerationActionRequest request) {
        ModerationActionRequest safe = request != null ? request : new ModerationActionRequest();
        ModerationActionType type = ModerationActionType.parse(safe.action);
        if (type == null) return result(false, "validation_failed", "Unknown moderation action.", false, null);
        boolean confirmRequired = switch (type) {
            case BAN, TEMPBAN, UNBAN, IPBAN, TEMPIPBAN, UNIPBAN, REVOKE, JAIL -> true;
            default -> false;
        };
        if (confirmRequired && !Boolean.TRUE.equals(safe.confirmed)) {
            return result(false, "confirmation_required", "Confirmation is required for this moderation action.", true, null);
        }

        IPlayer online = resolvePlayer(safe.player);
        String targetUuid = online != null ? online.getUUID() : text(safe.uuid);
        String targetName = online != null ? online.getName() : text(safe.player);
        boolean idOnly = type == ModerationActionType.REVOKE || safe.punishmentId != null && !safe.punishmentId.isBlank();
        if (!idOnly && targetName.isBlank() && targetUuid.isBlank() && text(safe.ipAddress).isBlank()) {
            return result(false, "validation_failed", "A player, IP address, or punishment ID is required.", false, null);
        }
        String reason = text(safe.reason).isBlank() ? services.getLang().getTranslation("moderation.no_reason") : text(safe.reason);
        Long expires = switch (type) {
            case TEMPMUTE, TEMPBAN, TEMPIPBAN, JAIL -> parseExpiry(safe.duration);
            default -> null;
        };
        if ((type == ModerationActionType.TEMPMUTE || type == ModerationActionType.TEMPBAN || type == ModerationActionType.TEMPIPBAN) && expires == null) {
            return result(false, "validation_failed", "Valid duration is required.", false, null);
        }

        ServerScope scope = "server".equalsIgnoreCase(safe.scope) ? ServerScope.SERVER : ServerScope.GLOBAL;
        PunishmentRecord[] created = new PunishmentRecord[1];
        boolean changed;
        try {
            changed = switch (type) {
                case WARN -> create(actor, PunishmentType.WARN, scope, targetUuid, targetName, null, reason, null, created);
                case MUTE -> create(actor, PunishmentType.MUTE, scope, targetUuid, targetName, null, reason, null, created);
                case TEMPMUTE -> create(actor, PunishmentType.MUTE, scope, targetUuid, targetName, null, reason, expires, created);
                case UNMUTE -> revokeExactOrSingle(actor, safe.punishmentId, targetUuid, null, PunishmentType.MUTE, reason);
                case BAN -> create(actor, PunishmentType.BAN, scope, targetUuid, targetName, null, reason, null, created);
                case TEMPBAN -> create(actor, PunishmentType.BAN, scope, targetUuid, targetName, null, reason, expires, created);
                case UNBAN -> revokeExactOrSingle(actor, safe.punishmentId, targetUuid, null, PunishmentType.BAN, reason);
                case IPBAN -> create(actor, PunishmentType.IP_BAN, scope, targetUuid, targetName, resolveIp(safe, online), reason, null, created);
                case TEMPIPBAN -> create(actor, PunishmentType.IP_BAN, scope, targetUuid, targetName, resolveIp(safe, online), reason, expires, created);
                case UNIPBAN -> revokeExactOrSingle(actor, safe.punishmentId, null, safe.ipAddress, PunishmentType.IP_BAN, reason);
                case REVOKE -> services.getPunishmentService().revoke(safe.punishmentId, actorUuid(actor), actorName(actor), reason);
                case JAIL -> createJail(actor, online, targetUuid, targetName, reason, expires, created);
                case UNJAIL -> revokeExactOrSingle(actor, safe.punishmentId, targetUuid, null, PunishmentType.JAIL, reason)
                        && services.getStorageService().moderation().clearJailState(targetUuid);
            };
        } catch (IllegalArgumentException error) {
            audit(actor, type, AuditResult.FAILED, targetUuid, targetName, reason, safe.duration, safe.punishmentId);
            return result(false, "validation_failed", error.getMessage(), false, safe.punishmentId);
        }

        if (changed && online != null && (type == ModerationActionType.BAN || type == ModerationActionType.TEMPBAN
                || type == ModerationActionType.IPBAN || type == ModerationActionType.TEMPIPBAN)) {
            runOnServerThread(() -> services.getPunishmentService().enforcePlayer(online));
        }
        audit(actor, type, changed ? AuditResult.SUCCESS : AuditResult.FAILED, targetUuid, targetName, reason, safe.duration,
                created[0] != null ? created[0].punishmentId() : safe.punishmentId);
        return result(changed, changed ? "ok" : "ambiguous_or_inactive", changed ? "Moderation action applied." : "Use an exact active punishment ID.",
                confirmRequired, created[0] != null ? created[0].punishmentId() : safe.punishmentId);
    }

    private boolean create(DashboardPrincipal actor, PunishmentType type, ServerScope scope, String uuid, String name,
                           String ip, String reason, Long expires, PunishmentRecord[] output) {
        output[0] = services.getPunishmentService().create(type, scope, blankToNull(uuid), blankToNull(name), blankToNull(ip), reason,
                actorUuid(actor), actorName(actor), expires);
        return true;
    }

    private boolean revokeExactOrSingle(DashboardPrincipal actor, String id, String uuid, String ip, PunishmentType type, String reason) {
        if (id != null && !id.isBlank()) return services.getPunishmentService().revoke(id, actorUuid(actor), actorName(actor), reason);
        var matches = services.getPunishmentService().activeFor(blankToNull(uuid), blankToNull(ip)).stream().filter(record -> record.type() == type).toList();
        return matches.size() == 1 && services.getPunishmentService().revoke(matches.get(0).punishmentId(), actorUuid(actor), actorName(actor), reason);
    }

    private boolean jail(DashboardPrincipal actor, IPlayer online, String uuid, String name, String reason, Long expiresAtMs) {
        var jailLocation = services.getStorageService().moderation().getJailLocation().orElse(null);
        if (jailLocation == null) return false;
        services.getStorageService().moderation().setJailState(new StoredJailState(services.getStorageService().context().serverId(), uuid,
                name, reason, actorName(actor), jailLocation, System.currentTimeMillis(), expiresAtMs));
        if (online != null) {
            PlayerDataStore.StoredLocation location = new PlayerDataStore.StoredLocation(jailLocation.worldId(), jailLocation.x(), jailLocation.y(), jailLocation.z(), jailLocation.yaw(), jailLocation.pitch());
            runOnServerThread(() -> services.getPlatformAdapter().teleportPlayer(online, location));
        }
        return true;
    }

    private boolean createJail(DashboardPrincipal actor, IPlayer online, String uuid, String name, String reason,
                               Long expiresAtMs, PunishmentRecord[] output) {
        if (services.getStorageService().moderation().getJailLocation().isEmpty()) return false;
        if (!jail(actor, online, uuid, name, reason, expiresAtMs)) return false;
        return create(actor, PunishmentType.JAIL, ServerScope.SERVER, uuid, name, null, reason, expiresAtMs, output);
    }

    private IPlayer resolvePlayer(String value) {
        if (text(value).isBlank()) return null;
        IPlayer player = services.getPlatformAdapter().getPlayerByUuid(value);
        return player != null ? player : services.getPlatformAdapter().getPlayerByName(value);
    }

    private String resolveIp(ModerationActionRequest request, IPlayer online) {
        if (request.ipAddress != null && !request.ipAddress.isBlank()) return request.ipAddress;
        return online != null ? services.getPlatformAdapter().getPlayerRemoteAddress(online) : null;
    }

    private Long parseExpiry(String value) {
        if (text(value).isBlank() || "permanent".equalsIgnoreCase(value) || "perm".equalsIgnoreCase(value)) return null;
        long millis = DurationParser.parseToMillis(value);
        return millis > 0L ? System.currentTimeMillis() + millis : null;
    }

    private void runOnServerThread(Runnable runnable) {
        if (services.getTaskScheduler() != null) services.getTaskScheduler().schedule(runnable, 0L, java.util.concurrent.TimeUnit.MILLISECONDS);
        else runnable.run();
    }

    private void audit(DashboardPrincipal actor, ModerationActionType action, AuditResult result, String targetUuid,
                       String targetName, String reason, String duration, String punishmentId) {
        if (audit == null) return;
        audit.dashboard(actor, AuditActionType.MODERATION_ACTION, result, "Moderation action " + action.name().toLowerCase(java.util.Locale.ROOT) + ".",
                Map.of("action", action.name(), "punishmentId", safe(punishmentId), "target", safe(targetName), "targetUuid", safe(targetUuid),
                        "reason", safe(reason), "duration", safe(duration)));
    }

    private static ModerationActionResult result(boolean applied, String code, String message, boolean confirmation, String id) {
        return new ModerationActionResult(applied, code, message, confirmation, id);
    }

    private static String actorName(DashboardPrincipal actor) { return actor != null && actor.name() != null ? actor.name() : "dashboard"; }
    private static String actorUuid(DashboardPrincipal actor) { return actor != null ? actor.uuid() : null; }
    private static String text(String value) { return value != null ? value.trim() : ""; }
    private static String blankToNull(String value) { String result = text(value); return result.isBlank() ? null : result; }
    private static String safe(String value) { return value != null ? value : ""; }
}
