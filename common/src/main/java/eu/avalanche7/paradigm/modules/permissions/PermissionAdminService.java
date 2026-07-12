package eu.avalanche7.paradigm.modules.permissions;

import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPermission;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextType;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionExpiryArgumentParser;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PermissionAdminService {
    private final Services services;
    private final AuditService audit;

    public PermissionAdminService(Services services, AuditService audit) {
        this.services = services;
        this.audit = audit;
    }

    public PermissionMutationResult mutate(DashboardPrincipal actor, PermissionMutationRequest request) {
        return mutate(actor, request, true);
    }

    /** Used by already-authorized in-game command handlers. */
    public PermissionMutationResult mutateTrusted(DashboardPrincipal actor, PermissionMutationRequest request) {
        return mutate(actor, request, false);
    }

    private PermissionMutationResult mutate(DashboardPrincipal actor, PermissionMutationRequest request, boolean enforceDashboardPermission) {
        if (enforceDashboardPermission && !canManage(actor)) {
            audit(actor, AuditActionType.PERMISSION_CHANGE, AuditResult.DENIED, "Permission mutation denied.", Map.of("reason", "permission_denied"));
            return result(false, "permission_denied", "Permission denied.", false);
        }

        PermissionMutationRequest safe = request != null ? request : new PermissionMutationRequest();
        String action = text(safe.action).toLowerCase(java.util.Locale.ROOT);
        String group = text(safe.group);
        String parent = text(safe.parent);
        String permission = normalizePermission(safe.permission);
        String assignmentId = text(safe.assignmentId);
        PermissionScope scope = PermissionScope.parse(safe.scope);
        if (scope == null) {
            return result(false, "invalid_context", "Invalid permission context scope.", false);
        }
        PermissionContextSet contexts;
        try {
            contexts = contexts(scope, safe.contexts);
        } catch (IllegalArgumentException e) {
            return result(false, e.getMessage().startsWith("unsupported") ? "unsupported_context" : "invalid_context", e.getMessage(), false);
        }
        Long expiresAtMs;
        try {
            expiresAtMs = Boolean.TRUE.equals(safe.permanent) ? null : parseExpiry(safe.duration, safe.expiresAtMs);
        } catch (IllegalArgumentException e) {
            return result(false, "invalid_expiry", e.getMessage(), false);
        }

        boolean denied = Boolean.TRUE.equals(safe.denied) || permission.startsWith("-");
        if (permission.startsWith("-")) {
            permission = permission.substring(1);
        }
        boolean dangerous = PermissionDangerAnalyzer.dangerous(permission, group, parent);
        if (dangerous && !Boolean.TRUE.equals(safe.confirmed)) {
            return result(false, "confirmation_required", "Confirmation is required for this permission change.", true);
        }

        if (assignmentId.isBlank() && removalIsAmbiguous(action, group, permission, safe.user, contexts)) {
            return result(false, "assignment_ambiguous", "More than one assignment matches. Remove by assignment id.", false);
        }

        boolean changed;
        try {
            var handler = services.getPermissionsHandler();
            changed = switch (action) {
                case "group_create" -> validGroup(group) && handler.createPermissionGroup(group);
                case "group_delete" -> validGroup(group) && Boolean.TRUE.equals(safe.confirmed) && handler.deletePermissionGroup(group);
                case "group_update" -> validGroup(group) && updateGroupMetadata(handler, group, safe.metadata);
                case "group_permission_add" -> validGroup(group) && validPermission(permission) && handler.addPermissionToGroup(group, permission, denied, contexts, expiresAtMs);
                case "group_permission_remove" -> validGroup(group) && (assignmentId.isBlank()
                        ? validPermission(permission) && handler.removePermissionFromGroup(group, permission, contexts)
                        : handler.removePermissionFromGroupById(group, assignmentId));
                case "group_parent_add" -> validGroup(group) && validGroup(parent) && handler.addPermissionGroupParent(group, parent);
                case "group_parent_remove" -> validGroup(group) && validGroup(parent) && handler.removePermissionGroupParent(group, parent);
                case "user_permission_add" -> {
                    UUID uuid = resolveUuid(safe.user);
                    yield uuid != null && validPermission(permission) && handler.addPermissionToPlayer(uuid, permission, denied, contexts, expiresAtMs);
                }
                case "user_permission_remove" -> {
                    UUID uuid = resolveUuid(safe.user);
                    yield uuid != null && (assignmentId.isBlank()
                            ? validPermission(permission) && handler.removePermissionFromPlayer(uuid, permission, contexts)
                            : handler.removePermissionFromPlayerById(uuid, assignmentId));
                }
                case "user_group_add" -> {
                    UUID uuid = resolveUuid(safe.user);
                    yield uuid != null && validGroup(group) && handler.assignPlayerGroup(uuid, group, contexts, expiresAtMs, actor != null ? actor.name() : "dashboard");
                }
                case "user_group_remove" -> {
                    UUID uuid = resolveUuid(safe.user);
                    yield uuid != null && (assignmentId.isBlank()
                            ? validGroup(group) && handler.revokePlayerGroup(uuid, group, contexts)
                            : handler.revokePlayerGroupById(uuid, assignmentId));
                }
                default -> false;
            };
        } catch (Throwable t) {
            audit(actor, auditType(action), AuditResult.FAILED, "Permission mutation failed.", Map.of("action", action, "error", t.getClass().getSimpleName()));
            return result(false, "validation_failed", "Permission change failed.", dangerous);
        }

        audit(actor, auditType(action), changed ? AuditResult.SUCCESS : AuditResult.FAILED,
                changed ? "Permission mutation applied." : "Permission mutation rejected.",
                Map.of("action", action, "group", group, "permission", permission, "assignmentId", assignmentId, "scope", scope.name().toLowerCase(java.util.Locale.ROOT),
                        "contexts", contexts.canonical(), "expiresAtMs", expiresAtMs != null ? String.valueOf(expiresAtMs) : ""));
        return result(changed, changed ? "ok" : "validation_failed", changed ? "Permission change applied." : "Permission change was rejected.", dangerous);
    }

    private boolean canManage(DashboardPrincipal actor) {
        if (actor == null) {
            return false;
        }
        if (actor.console()) {
            return true;
        }
        // Dashboard sessions carry the authenticated UUID.  Resolve the same
        // cache/fallback path used by DashboardService instead of requiring
        // the actor to still be online at mutation time.
        return services.getPermissionsHandler().hasPermission(actor.uuid(), DashboardPermission.MANAGE, 4);
    }

    private static boolean updateGroupMetadata(eu.avalanche7.paradigm.modules.permissions.PermissionsHandler handler, String group, Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return false;
        boolean changed = false;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String field = text(entry.getKey()).toLowerCase(java.util.Locale.ROOT);
            if (!Set.of("weight", "prefix", "suffix", "description").contains(field)) {
                throw new IllegalArgumentException("Unsupported group metadata field.");
            }
            String value = entry.getValue() != null ? entry.getValue() : "";
            if (value.length() > 512) throw new IllegalArgumentException("Group metadata value is too long.");
            changed |= handler.setPermissionGroupMetadata(group, field, value);
        }
        return changed;
    }

    private boolean removalIsAmbiguous(String action, String group, String permission, String user, PermissionContextSet contexts) {
        var handler = services.getPermissionsHandler();
        return switch (action) {
            case "group_permission_remove" -> handler.countPermissionAssignmentsInGroup(group, permission, contexts) > 1;
            case "user_permission_remove" -> {
                UUID uuid = resolveUuid(user);
                yield uuid != null && handler.countPermissionAssignmentsForPlayer(uuid, permission, contexts) > 1;
            }
            case "user_group_remove" -> {
                UUID uuid = resolveUuid(user);
                yield uuid != null && handler.countPlayerGroupAssignments(uuid, group, contexts) > 1;
            }
            default -> false;
        };
    }

    private UUID resolveUuid(String uuidOrName) {
        String value = text(uuidOrName);
        if (value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (Throwable ignored) {
        }
        try {
            IPlayer player = services.getPlatformAdapter().getPlayerByName(value);
            if (player != null && player.getUUID() != null) {
                return UUID.fromString(player.getUUID());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private PermissionContextSet contexts(PermissionScope scope, Map<String, String> custom) {
        return switch (scope) {
            case GLOBAL -> PermissionContextSet.empty();
            case CURRENT_SERVER -> {
                var identity = services.getStorageService() != null && services.getStorageService().context() != null ? services.getStorageService().context().serverIdentity() : null;
                String serverId = identity != null ? identity.serverId() : null;
                if (serverId == null || serverId.isBlank()) {
                    throw new IllegalArgumentException("current server context unavailable");
                }
                yield PermissionContextSet.server(serverId);
            }
            case CURRENT_NETWORK -> {
                var identity = services.getStorageService() != null && services.getStorageService().context() != null ? services.getStorageService().context().serverIdentity() : null;
                String networkId = identity != null ? identity.networkId() : null;
                if (networkId == null || networkId.isBlank()) {
                    throw new IllegalArgumentException("current network context unavailable");
                }
                yield PermissionContextSet.network(networkId);
            }
            case CUSTOM -> {
                Map<String, String> values = new LinkedHashMap<>();
                if (custom != null) {
                    for (Map.Entry<String, String> entry : custom.entrySet()) {
                        if (PermissionContextType.fromKey(entry.getKey()) == null) {
                            throw new IllegalArgumentException("unsupported context: " + entry.getKey());
                        }
                        values.put(entry.getKey(), entry.getValue());
                    }
                }
                yield PermissionContextSet.of(values);
            }
        };
    }

    private Long parseExpiry(String duration, Long absoluteExpiresAtMs) {
        if (absoluteExpiresAtMs != null) {
            if (absoluteExpiresAtMs <= System.currentTimeMillis()) {
                throw new IllegalArgumentException("Expiry must be in the future.");
            }
            return absoluteExpiresAtMs;
        }
        String raw = text(duration);
        if (raw.isBlank()) {
            return null;
        }
        PermissionExpiryArgumentParser.Result result = PermissionExpiryArgumentParser.parse(raw, false, System.currentTimeMillis());
        if (!result.valid()) {
            throw new IllegalArgumentException(result.message());
        }
        return result.expiresAtMs();
    }

    private void audit(DashboardPrincipal actor, AuditActionType type, AuditResult result, String message, Map<String, String> details) {
        if (audit != null) {
            audit.dashboard(actor, type, result, message, details);
        }
    }

    private static AuditActionType auditType(String action) {
        return action != null && action.startsWith("group_") ? AuditActionType.GROUP_CHANGE : AuditActionType.PERMISSION_CHANGE;
    }

    private static PermissionMutationResult result(boolean ok, String code, String message, boolean confirmationRequired) {
        return new PermissionMutationResult(ok, code, message, confirmationRequired);
    }

    private static String normalizePermission(String permission) {
        return text(permission).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean validPermission(String permission) {
        return permission != null && !permission.isBlank() && permission.length() <= 255 && permission.matches("[a-z0-9_.*:-]+");
    }

    private static boolean validGroup(String group) {
        return group != null && !group.isBlank() && group.length() <= 64 && group.matches("[A-Za-z0-9_.-]+");
    }

    private static String text(String value) {
        return value != null ? value.trim() : "";
    }
}
