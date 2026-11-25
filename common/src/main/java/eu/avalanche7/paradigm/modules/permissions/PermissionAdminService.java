package eu.avalanche7.paradigm.modules.permissions;

import static eu.avalanche7.paradigm.modules.profile.PlayerProfileService.parseCanonicalUuid;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardAuthorization;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextSet;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionContextType;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionExpiryArgumentParser;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageConflictException;
import eu.avalanche7.paradigm.storage.StorageException;
import eu.avalanche7.paradigm.storage.StorageTransactionException;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;
import eu.avalanche7.paradigm.utils.ServerThreadCalls;

public class PermissionAdminService {
    private final Services services;
    private final AuditService audit;

    public PermissionAdminService(Services services, AuditService audit) {
        this.services = services;
        this.audit = audit;
    }

    public PermissionMutationResult mutate(DashboardPrincipal actor, PermissionMutationRequest request) {
        var handler = services.getPermissionsHandler();
        long version = handler.permissionsStateVersion();
        BooleanSupplier authorizationCurrent = checkDashboardAuthority(actor, () -> true);
        if (authorizationCurrent == null) return denied(actor);
        UUID user = targetsUser(action(request)) ? resolveOnlineUser(text(request.user)) : null;
        return persistDashboardMutation(actor, request, user, null, version, authorizationCurrent);
    }

    public PermissionMutationResult mutateTrusted(DashboardPrincipal actor, PermissionMutationRequest request) {
        UUID user = targetsUser(action(request)) ? parseCanonicalUuid(text(request.user)) : null;
        return mutateResolved(actor, request, user, null, true);
    }

    public CompletableFuture<PermissionMutationResult> mutateAsync(DashboardPrincipal actor, PermissionMutationRequest request, Executor executor) {
        return mutateAsync(actor, request, executor, () -> true);
    }

    public CompletableFuture<PermissionMutationResult> mutateAsync(DashboardPrincipal actor, PermissionMutationRequest request,
            Executor executor, BooleanSupplier sessionCurrent) {
        return onServer(() -> sessionCurrent.getAsBoolean() && canManage(actor)).thenComposeAsync(allowed -> {
            if (!allowed) return CompletableFuture.completedFuture(denied(actor));
            String action = action(request);
            String userInput = targetsUser(action) ? text(request.user) : "";
            UUID uuid = parseCanonicalUuid(userInput);
            boolean lookup = uuid == null && !userInput.isEmpty();
            boolean display = "user_display_group".equals(action);
            List<StoredPlayerProfile> matches = lookup ? services.getPlayerProfileService().matchingNames(userInput) : List.of();

            if (actor.console() && !lookup && !display) {
                return CompletableFuture.completedFuture(persistDashboardMutation(actor, request, uuid, null, -1L, sessionCurrent));
            }
            return onServer(() -> {
                long version = services.getPermissionsHandler().permissionsStateVersion();
                BooleanSupplier authorizationCurrent = checkDashboardAuthority(actor, sessionCurrent);
                if (authorizationCurrent == null) return CompletableFuture.completedFuture(denied(actor));
                if (matches.size() > 1) {
                    return CompletableFuture.completedFuture(result(false, "player_ambiguous",
                            "Multiple stored players use that name. Use the player's UUID.", false));
                }
                UUID user = lookup ? resolveMatchedUser(userInput, matches) : uuid;
                PermissionContextSet context = display && user != null
                        ? services.getPermissionsHandler().displayGroupContext(user) : null;
                return CompletableFuture.supplyAsync(
                        () -> persistDashboardMutation(actor, request, user, context, version, authorizationCurrent), executor);
            }).thenCompose(pending -> pending);
        }, executor);
    }

    private PermissionMutationResult persistDashboardMutation(DashboardPrincipal actor, PermissionMutationRequest request,
            UUID user, PermissionContextSet displayContext, long version, BooleanSupplier authorizationCurrent) {
        return services.getPermissionsHandler().writeDashboardMutation(actor.console() ? -1L : version, authorizationCurrent,
                () -> mutateResolved(actor, request, user, displayContext, false), () -> denied(actor));
    }

    private BooleanSupplier checkDashboardAuthority(DashboardPrincipal actor, BooleanSupplier sessionCurrent) {
        var handler = services.getPermissionsHandler();
        boolean internal = handler.isInternalPermissionsEnabled();
        long checkedAt = System.currentTimeMillis();
        if (!sessionCurrent.getAsBoolean() || !canManage(actor)) return null;
        if (actor.console()) return sessionCurrent;

        UUID actorId = parseCanonicalUuid(actor.uuid());
        long expiresAt = internal && actorId != null ? handler.dashboardAuthorizationExpiresAt(actorId, checkedAt) : Long.MAX_VALUE;
        return () -> sessionCurrent.getAsBoolean() && internal == handler.isInternalPermissionsEnabled()
                && System.currentTimeMillis() < expiresAt;
    }

    private <T> CompletableFuture<T> onServer(Supplier<T> task) {
        var platform = services.getPlatformAdapter();
        if (platform == null || platform.isServerThread()) {
            return CompletableFuture.completedFuture(null).thenApply(ignored -> task.get());
        }
        return ServerThreadCalls.supply(services, task);
    }

    private PermissionMutationResult denied(DashboardPrincipal actor) {
        audit(actor, AuditActionType.PERMISSION_CHANGE, AuditResult.DENIED, "Permission mutation denied.", Map.of("reason", "permission_denied"));
        return result(false, "permission_denied", "Permission denied.", false);
    }

    private PermissionMutationResult mutateResolved(DashboardPrincipal actor, PermissionMutationRequest request, UUID user,
                                                     PermissionContextSet displayContext, boolean trusted) {
        PermissionMutationRequest safe = request != null ? request : new PermissionMutationRequest();
        String action = action(safe);
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

        if (action.startsWith("track_") || Set.of("promote", "demote", "settrack", "cleartrack").contains(action)) {
            String trackArgumentError = trackArgumentError(action, safe, expiresAtMs);
            if (trackArgumentError != null) {
                return result(false, trackArgumentError.startsWith("Expiry") ? "invalid_expiry" : "invalid_argument", trackArgumentError, false);
            }
            try { return mutateTrack(actor, safe, action, user, contexts, expiresAtMs); }
            catch (StorageException failure) { return persistenceFailure(actor, action, failure); }
        }

        if ("group_rename".equals(action)) {
            String target = text(safe.target).toLowerCase(java.util.Locale.ROOT);
            if (!validGroup(group) || !validGroup(target)) return result(false, "invalid_group_name", "A valid group name is required.", false);
            if (services.getPermissionsHandler().getPermissionGroupInfo(group) == null) return result(false, "group_not_found", "Group was not found.", false);
            if (services.getPermissionsHandler().getPermissionGroupInfo(target) != null) return result(false, "group_exists", "A group with the target name already exists.", false);
        }

        boolean denied = Boolean.TRUE.equals(safe.denied) || permission.startsWith("-");
        if (permission.startsWith("-")) {
            permission = permission.substring(1);
        }
        boolean dangerous = PermissionDangerAnalyzer.dangerous(permission, group, parent);
        if (dangerous && !Boolean.TRUE.equals(safe.confirmed)) {
            return result(false, "confirmation_required", "Confirmation is required for this permission change.", true);
        }

        if (assignmentId.isBlank() && removalIsAmbiguous(action, group, permission, user, contexts)) {
            return result(false, "assignment_ambiguous", "More than one assignment matches. Remove by assignment id.", false);
        }

        boolean changed;
        var handler = services.getPermissionsHandler();
        // Existing standalone commands accept group names supported by the storage API.
        boolean validDefinitionName = trusted || validGroup(group);
        try {
            changed = switch (action) {
                case "group_create" -> validDefinitionName && handler.createPermissionGroup(group);
                case "group_delete" -> validDefinitionName && Boolean.TRUE.equals(safe.confirmed) && handler.deletePermissionGroup(group);
                case "group_rename" -> validGroup(group) && validGroup(text(safe.target)) && handler.renamePermissionGroup(group, text(safe.target));
                case "group_update" -> validDefinitionName && (trusted || validDashboardMetadataLength(safe.metadata))
                        && handler.setPermissionGroupMetadata(group, safe.metadata);
                case "group_permission_add" -> validGroup(group) && PermissionNodeSyntax.validAssignment(permission) && handler.addPermissionToGroup(group, permission, denied, contexts, expiresAtMs);
                case "group_permission_remove" -> validGroup(group) && (assignmentId.isBlank()
                        ? PermissionNodeSyntax.validAssignment(permission) && handler.removePermissionFromGroup(group, permission, contexts)
                        : handler.removePermissionFromGroupById(group, assignmentId));
                case "group_parent_add" -> validDefinitionName && (trusted || validGroup(parent)) && handler.addPermissionGroupParent(group, parent);
                case "group_parent_remove" -> validDefinitionName && (trusted || validGroup(parent)) && handler.removePermissionGroupParent(group, parent);
                case "user_permission_add" -> user != null && PermissionNodeSyntax.validAssignment(permission)
                        && handler.addPermissionToPlayer(user, permission, denied, contexts, expiresAtMs);
                case "user_permission_remove" -> user != null && (assignmentId.isBlank()
                        ? PermissionNodeSyntax.validAssignment(permission) && handler.removePermissionFromPlayer(user, permission, contexts)
                        : handler.removePermissionFromPlayerById(user, assignmentId));
                case "user_display_group" -> user != null && (displayContext == null
                        ? handler.setDisplayGroup(user, safe.group) : handler.setDisplayGroup(user, safe.group, displayContext));
                case "user_group_add" -> user != null && validGroup(group)
                        && handler.assignPlayerGroup(user, group, contexts, expiresAtMs, actor != null ? actor.name() : "dashboard");
                case "user_group_remove" -> user != null && (assignmentId.isBlank()
                        ? validGroup(group) && handler.revokePlayerGroup(user, group, contexts)
                        : handler.revokePlayerGroupById(user, assignmentId));
                default -> false;
            };
        } catch (StorageException failure) {
            return persistenceFailure(actor, action, failure);
        } catch (Throwable t) {
            audit(actor, auditType(action), AuditResult.FAILED, "Permission mutation failed.", Map.of("action", action, "error", t.getClass().getSimpleName()));
            return result(false, "validation_failed", "Permission change failed.", dangerous);
        }

        boolean pending = changed && targetsUser(action) && !"user_display_group".equals(action) && handler.queuesUserWrites();
        audit(actor, auditType(action), changed ? AuditResult.SUCCESS : AuditResult.FAILED,
                pending ? "Permission mutation accepted into runtime; persistence is pending."
                        : changed ? "Permission mutation applied." : "Permission mutation rejected.",
                Map.of("action", action, "group", group, "permission", permission, "assignmentId", assignmentId, "scope", scope.name().toLowerCase(java.util.Locale.ROOT),
                        "contexts", contexts.canonical(), "expiresAtMs", expiresAtMs != null ? String.valueOf(expiresAtMs) : "",
                        "persistence", pending ? "pending" : changed ? "confirmed" : "rejected"));
        if (changed) {
            switch (action) {
                case "group_delete" -> handler.refreshGroupPermissionEffects(null);
                case "group_rename" -> handler.refreshGroupPermissionEffects(text(safe.target));
                case "group_permission_add", "group_permission_remove", "group_parent_add", "group_parent_remove" ->
                        handler.refreshGroupPermissionEffects(group);
                default -> { }
            }
        }
        if (!changed && !assignmentId.isBlank() && services.getPermissionsHandler().isInternalPermissionsEnabled()
                && Set.of("group_permission_remove", "user_permission_remove", "user_group_remove").contains(action))
            return result(false, "assignment_not_found", "The original assignment is already absent from the host runtime. No removal was applied.", false);
        if (pending) return result(true, "persistence_pending", "Permission change accepted into runtime; persistence is pending.", false);
        return result(changed, changed ? "ok" : "validation_failed", changed ? "Permission change applied." : "Permission change was rejected.", dangerous);
    }

    private boolean canManage(DashboardPrincipal actor) {
        if (actor == null) {
            return false;
        }
        if (actor.console()) {
            return true;
        }

        DashboardAuthorization.PermissionCheck check = permission ->
                services.getPermissionsHandler().hasPermission(actor.uuid(), permission.node(), permission.fallbackLevel());
        return DashboardAuthorization.canManagePermissions(check);
    }

    private static boolean validDashboardMetadataLength(Map<String, String> metadata) {
        return metadata != null && metadata.values().stream().allMatch(value -> value == null || value.length() <= 512);
    }

    private boolean removalIsAmbiguous(String action, String group, String permission, UUID user, PermissionContextSet contexts) {
        var handler = services.getPermissionsHandler();
        return switch (action) {
            case "group_permission_remove" -> handler.countPermissionAssignmentsInGroup(group, permission, contexts) > 1;
            case "user_permission_remove" -> user != null && handler.countPermissionAssignmentsForPlayer(user, permission, contexts) > 1;
            case "user_group_remove" -> user != null && handler.countPlayerGroupAssignments(user, group, contexts) > 1;
            default -> false;
        };
    }

    private UUID resolveMatchedUser(String name, List<StoredPlayerProfile> matches) {
        if (matches.isEmpty()) return resolveOnlineUser(name);
        UUID user = parseCanonicalUuid(matches.get(0).uuid());
        return user != null && services.getPlayerProfileService().matchesOnlineIdentity(name, user) ? user : null;
    }

    private UUID resolveOnlineUser(String input) {
        UUID uuid = parseCanonicalUuid(input);
        var platform = services.getPlatformAdapter();
        if (uuid != null || input.isEmpty() || platform == null) return uuid;
        IPlayer player = platform.getPlayerByName(input);
        return player != null && input.equalsIgnoreCase(player.getName()) ? parseCanonicalUuid(player.getUUID()) : null;
    }

    private static boolean targetsUser(String action) {
        return action.startsWith("user_") || Set.of("promote", "demote", "settrack", "cleartrack").contains(action);
    }

    private static String action(PermissionMutationRequest request) {
        return request == null ? "" : text(request.action).toLowerCase(java.util.Locale.ROOT);
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

    private PermissionMutationResult persistenceFailure(DashboardPrincipal actor, String action, StorageException failure) {
        audit(actor, auditType(action), AuditResult.FAILED, "Permission persistence was not acknowledged.", Map.of("action", action, "error", failure.getClass().getSimpleName()));
        if (failure instanceof StorageConflictException)
            return result(false, "persistence_conflict", failure.getMessage(), false);
        if (failure instanceof StorageTransactionException transaction) {
            if (transaction.outcome() == StorageTransactionException.Outcome.COMMITTED) {
                return result(false, "persistence_committed_recovery", "Storage reported a commit followed by a failure. Reload permissions before retrying.", false);
            }
            return result(false, "persistence_uncertain", "Storage could not confirm the outcome. Inspect and reload before retrying.", false);
        }
        return result(false, "persistence_failed", "Permission persistence failed. Check storage before retrying.", false);
    }

    private PermissionMutationResult mutateTrack(DashboardPrincipal actor, PermissionMutationRequest request, String action,
                                                 UUID user, PermissionContextSet contexts, Long expiresAtMs) {
        String track = text(request.track);
        if (track.isBlank()) track = text(request.group);
        PermissionTrackResult changed;
        if (action.startsWith("track_")) {
            changed = services.getPermissionsHandler().mutatePermissionTrack(action, track, text(request.group), text(request.target), request.position);
        } else {
            String operation = switch (action) {
                case "settrack" -> "set";
                case "cleartrack" -> "clear";
                default -> action;
            };
            changed = services.getPermissionsHandler().movePlayerOnTrack(user, track, contexts, operation,
                    Boolean.TRUE.equals(request.dontAddToFirst), Boolean.TRUE.equals(request.dontRemoveFromFirst),
                    expiresAtMs, expiresAtMs != null || Boolean.TRUE.equals(request.permanent),
                    actor != null ? actor.name() : "dashboard", text(request.group));
        }
        Map<String, String> auditDetails = new LinkedHashMap<>();
        auditDetails.put("action", action);
        auditDetails.put("track", track);
        auditDetails.put("user", text(request.user));
        auditDetails.put("oldPosition", changed.oldPosition() != null ? String.valueOf(changed.oldPosition()) : "");
        auditDetails.put("newPosition", changed.newPosition() != null ? String.valueOf(changed.newPosition()) : "");
        auditDetails.put("contexts", contexts.canonical());
        auditDetails.put("expiresAtMs", expiresAtMs != null ? String.valueOf(expiresAtMs) : "");
        if (!changed.conflictingGroups().isEmpty()) auditDetails.put("conflictingGroups", String.join(",", changed.conflictingGroups()));
        boolean pending = changed.applied() && targetsUser(action) && services.getPermissionsHandler().queuesUserWrites();
        auditDetails.put("persistence", pending ? "pending" : changed.applied() ? "confirmed" : "rejected");
        audit(actor, AuditActionType.GROUP_CHANGE, changed.applied() ? AuditResult.SUCCESS : AuditResult.FAILED,
                pending ? "Track mutation accepted into runtime; persistence is pending."
                        : changed.applied() ? "Track mutation applied." : "Track mutation rejected.", auditDetails);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("track", changed.track());
        details.put("oldPosition", changed.oldPosition());
        details.put("newPosition", changed.newPosition());
        details.put("conflictingGroups", changed.conflictingGroups());
        return new PermissionMutationResult(changed.applied(), pending ? "persistence_pending" : changed.code(), changed.message(), false, details);
    }

    private static String trackArgumentError(String action, PermissionMutationRequest request, Long expiresAtMs) {
        boolean expiryRequested = expiresAtMs != null || Boolean.TRUE.equals(request.permanent);
        if (expiryRequested && !"promote".equals(action) && !"settrack".equals(action)) {
            return "Expiry is only valid when assigning a track rank.";
        }
        if (Boolean.TRUE.equals(request.dontAddToFirst) && !"promote".equals(action)) {
            return "--dont-add-to-first is only valid for promote.";
        }
        if (Boolean.TRUE.equals(request.dontRemoveFromFirst) && !"demote".equals(action)) {
            return "--dont-remove-from-first is only valid for demote.";
        }
        return null;
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

    private static boolean validGroup(String group) {
        return group != null && !group.isBlank() && group.length() <= 64 && group.matches("[A-Za-z0-9_.-]+");
    }

    private static String text(String value) {
        return value != null ? value.trim() : "";
    }
}
