package eu.avalanche7.paradigm.modules.dashboard;

import com.sun.net.httpserver.HttpExchange;
import eu.avalanche7.paradigm.modules.dashboard.api.AuditApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.AuthApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.ConfigApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.CustomCommandApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.ModerationApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.OverviewApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.PermissionsApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.ServerApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.StaticAssetHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.StorageApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.HologramApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardSession;
import eu.avalanche7.paradigm.modules.moderation.ModerationActionType;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPermission;
import eu.avalanche7.paradigm.modules.holograms.HologramService;

public class DashboardRouter {
    private final DashboardService dashboard;
    private final AuthApiHandler auth;
    private final OverviewApiHandler overview;
    private final ServerApiHandler servers;
    private final StorageApiHandler storage;
    private final ConfigApiHandler config;
    private final CustomCommandApiHandler customCommands;
    private final PermissionsApiHandler permissions;
    private final ModerationApiHandler moderation;
    private final AuditApiHandler audit;
    private final HologramApiHandler holograms;
    private final StaticAssetHandler staticAssets;

    public DashboardRouter(DashboardService dashboard) {
        this.dashboard = dashboard;
        this.auth = new AuthApiHandler(dashboard);
        this.overview = new OverviewApiHandler(dashboard);
        this.servers = new ServerApiHandler(dashboard);
        this.storage = new StorageApiHandler(dashboard);
        this.config = new ConfigApiHandler(dashboard);
        this.customCommands = new CustomCommandApiHandler(dashboard);
        this.permissions = new PermissionsApiHandler(dashboard);
        this.moderation = new ModerationApiHandler(dashboard);
        this.audit = new AuditApiHandler(dashboard);
        this.holograms = new HologramApiHandler(dashboard);
        this.staticAssets = new StaticAssetHandler(dashboard.config());
    }

    public DashboardResponse route(HttpExchange exchange) {
        DashboardSession session = session(exchange);
        DashboardPrincipal principal = session != null ? session.principal() : (!dashboard.config().requireLogin ? new DashboardPrincipal("local", "Local Admin", true) : null);
        DashboardRequestContext ctx = new DashboardRequestContext(exchange, principal, session);
        String path = ctx.path();
        String method = ctx.method();

        try {
            if (path.startsWith("/api/")) {
                if ("GET".equals(method) && "/api/auth/status".equals(path)) return auth.status(ctx);
                if ("POST".equals(method) && "/api/auth/login".equals(path)) return auth.login(ctx);

                if (!authenticated(ctx)) {
                    return DashboardResponse.apiError(401, "not_authenticated", "Login required.");
                }
                if (!dashboard.hasDashboardPermission(ctx.principal())) {
                    return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to use the dashboard.");
                }
                if (mutating(method) && !csrfAllowed(ctx)) {
                    return DashboardResponse.apiError(403, "csrf_failed", "Dashboard security token is missing or invalid.");
                }

                if ("POST".equals(method) && "/api/auth/logout".equals(path)) return auth.logout(ctx);
                if ("GET".equals(method) && "/api/overview".equals(path)) return overview.get(ctx);
                if ("GET".equals(method) && "/api/servers".equals(path)) return servers.list(ctx);
                if (path.startsWith("/api/storage/") && mutating(method)
                        && !dashboard.hasPermission(ctx.principal(), PermissionsHandler.STORAGE_MANAGE_PERMISSION, PermissionsHandler.STORAGE_MANAGE_PERMISSION_LEVEL)) {
                    return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to manage storage.");
                }
                if ("GET".equals(method) && "/api/storage/status".equals(path)) return storage.status(ctx);
                if ("POST".equals(method) && "/api/storage/test".equals(path)) return storage.test(ctx);
                if ("GET".equals(method) && "/api/storage/health".equals(path)) return storage.health(ctx);
                if ("GET".equals(method) && "/api/storage/repair/check".equals(path)) return storage.repairCheck(ctx);
                if ("POST".equals(method) && "/api/storage/migration/dry-run".equals(path)) return storage.migrationDryRun(ctx);
                if ("GET".equals(method) && "/api/storage/configuration".equals(path)) return storage.configuration(ctx);
                if ("POST".equals(method) && "/api/storage/configuration".equals(path)) return storage.saveConfiguration(ctx);
                if ("POST".equals(method) && "/api/storage/configuration/test".equals(path)) return storage.testConfiguration(ctx);
                if (path.startsWith("/api/config/") && mutating(method)
                        && !dashboard.hasPermission(ctx.principal(), DashboardPermission.CONFIG_EDIT, 4)) {
                    return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to edit configuration.");
                }
                if ("GET".equals(method) && "/api/config/snapshot".equals(path)) return config.snapshot(ctx);
                if ("POST".equals(method) && "/api/config/patch".equals(path)) return config.patch(ctx);
                if ("POST".equals(method) && "/api/config/apply".equals(path)) return config.apply(ctx);
                if ("GET".equals(method) && "/api/audit/recent".equals(path)) return audit.recent(ctx);

                if (path.startsWith("/api/holograms")
                        && !dashboard.hasPermission(ctx.principal(), HologramService.MANAGE_PERMISSION, HologramService.MANAGE_PERMISSION_LEVEL)) {
                    return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to manage holograms.");
                }
                if ("GET".equals(method) && "/api/holograms".equals(path)) return holograms.list(ctx);
                if ("GET".equals(method) && "/api/holograms/item".equals(path)) return holograms.get(ctx);
                if ("POST".equals(method) && path.startsWith("/api/holograms/")) {
                    String action = path.substring("/api/holograms/".length());
                    if (!java.util.Set.of("create", "update", "duplicate", "rename", "delete", "refresh", "settings", "player-location").contains(action)) {
                        return DashboardResponse.apiError(400, "invalid_request", "Unknown hologram operation.");
                    }
                    return holograms.mutate(ctx, action);
                }

                if (path.startsWith("/api/custom-commands")
                        && !dashboard.hasPermission(ctx.principal(), DashboardPermission.CONFIG_EDIT, 4)) {
                    return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to edit custom commands.");
                }
                if ("GET".equals(method) && "/api/custom-commands".equals(path)) return customCommands.list(ctx);
                if ("GET".equals(method) && "/api/custom-commands/item".equals(path)) return customCommands.get(ctx);
                if ("POST".equals(method) && path.startsWith("/api/custom-commands/")) {
                    String action = path.substring("/api/custom-commands/".length());
                    if (!java.util.Set.of("create", "update", "duplicate", "delete", "reload").contains(action)) {
                        return DashboardResponse.apiError(400, "invalid_request", "Unknown custom command operation.");
                    }
                    return customCommands.mutate(ctx, action);
                }

                if (path.startsWith("/api/permissions/") && !dashboard.hasPermission(ctx.principal(), PermissionsHandler.GROUP_MANAGE_PERMISSION, PermissionsHandler.GROUP_MANAGE_PERMISSION_LEVEL)) {
                    return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to view permissions.");
                }
                if ("GET".equals(method) && "/api/permissions/summary".equals(path)) return permissions.summary(ctx);
                if ("GET".equals(method) && "/api/permissions/groups".equals(path)) return permissions.groups(ctx);
                if ("GET".equals(method) && "/api/permissions/group".equals(path)) return permissions.group(ctx);
                if ("GET".equals(method) && "/api/permissions/users".equals(path)) return permissions.users(ctx);
                if ("GET".equals(method) && "/api/permissions/user".equals(path)) return permissions.user(ctx);
                if ("GET".equals(method) && "/api/permissions/nodes".equals(path)) return permissions.nodes(ctx);
                if ("GET".equals(method) && "/api/permissions/effective".equals(path)) return permissions.effective(ctx);
                if ("POST".equals(method) && "/api/permissions/migrate/luckperms".equals(path)) return permissions.migrateLuckPerms(ctx);
                if ("POST".equals(method) && "/api/permissions/group/create".equals(path)) return permissions.mutate(ctx, "group_create");
                if ("POST".equals(method) && "/api/permissions/group/delete".equals(path)) return permissions.mutate(ctx, "group_delete");
                if ("POST".equals(method) && "/api/permissions/group/update".equals(path)) return permissions.mutate(ctx, "group_update");
                if ("POST".equals(method) && "/api/permissions/group/permission/add".equals(path)) return permissions.mutate(ctx, "group_permission_add");
                if ("POST".equals(method) && "/api/permissions/group/permission/remove".equals(path)) return permissions.mutate(ctx, "group_permission_remove");
                if ("POST".equals(method) && "/api/permissions/group/parent/add".equals(path)) return permissions.mutate(ctx, "group_parent_add");
                if ("POST".equals(method) && "/api/permissions/group/parent/remove".equals(path)) return permissions.mutate(ctx, "group_parent_remove");
                if ("POST".equals(method) && "/api/permissions/user/permission/add".equals(path)) return permissions.mutate(ctx, "user_permission_add");
                if ("POST".equals(method) && "/api/permissions/user/permission/remove".equals(path)) return permissions.mutate(ctx, "user_permission_remove");
                if ("POST".equals(method) && "/api/permissions/user/group/add".equals(path)) return permissions.mutate(ctx, "user_group_add");
                if ("POST".equals(method) && "/api/permissions/user/group/remove".equals(path)) return permissions.mutate(ctx, "user_group_remove");

                if (path.startsWith("/api/moderation/") && !canViewModeration(ctx.principal())) {
                    return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to view moderation.");
                }
                if ("GET".equals(method) && "/api/moderation/recent".equals(path)) return moderation.recent(ctx);
                if ("GET".equals(method) && "/api/moderation/active".equals(path)) return moderation.active(ctx);
                if ("GET".equals(method) && "/api/moderation/player".equals(path)) return moderation.player(ctx);
                if ("GET".equals(method) && "/api/moderation/punishment".equals(path)) return moderation.detail(ctx);
                if ("POST".equals(method) && path.startsWith("/api/moderation/")) {
                    String action = path.substring("/api/moderation/".length()).replace('/', '_');
                    if (ModerationActionType.parse(action) == null) {
                        return DashboardResponse.apiError(400, "invalid_request", "Unknown moderation action.");
                    }
                    if (!canRunModerationAction(ctx.principal(), action)) {
                        return DashboardResponse.apiError(403, "permission_denied", "You do not have permission for this moderation action.");
                    }
                    return moderation.action(ctx, action);
                }

                return DashboardResponse.apiError(404, "not_found", "Unknown API endpoint.");
            }
            return staticAssets.serve(path);
        } catch (Throwable t) {
            if (dashboard.services().getLogger() != null) {
                dashboard.services().getLogger().warn("Paradigm Dashboard: request {} {} failed: {}", method, path, t.toString());
            }
            return DashboardResponse.apiError(500, "internal_error", "Dashboard request failed.");
        }
    }

    private DashboardSession session(HttpExchange exchange) {
        if (!dashboard.config().requireLogin) {
            return null;
        }
        DashboardRequestContext ctx = new DashboardRequestContext(exchange, null);
        return dashboard.auth().validateSession(ctx.cookie(AuthApiHandler.cookieName()));
    }

    private boolean authenticated(DashboardRequestContext ctx) {
        return !dashboard.config().requireLogin || ctx.principal() != null;
    }

    private boolean mutating(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private boolean csrfAllowed(DashboardRequestContext ctx) {
        if (!dashboard.config().requireLogin) {
            return true;
        }
        return dashboard.auth().validateCsrf(ctx.session(), ctx.header("X-Paradigm-CSRF"));
    }

    private boolean canViewModeration(DashboardPrincipal principal) {
        return dashboard.hasPermission(principal, PermissionsHandler.KICK_PERMISSION, PermissionsHandler.KICK_PERMISSION_LEVEL)
                || dashboard.hasPermission(principal, PermissionsHandler.BAN_PERMISSION, PermissionsHandler.BAN_PERMISSION_LEVEL)
                || dashboard.hasPermission(principal, PermissionsHandler.WARN_PERMISSION, PermissionsHandler.WARN_PERMISSION_LEVEL)
                || dashboard.hasPermission(principal, PermissionsHandler.JAIL_PERMISSION, PermissionsHandler.JAIL_PERMISSION_LEVEL);
    }

    private boolean canRunModerationAction(DashboardPrincipal principal, String action) {
        return switch (action) {
            case "warn" -> dashboard.hasPermission(principal, PermissionsHandler.WARN_PERMISSION, PermissionsHandler.WARN_PERMISSION_LEVEL);
            case "mute", "tempmute", "unmute" -> dashboard.hasPermission(principal, PermissionsHandler.MUTE_PERMISSION, PermissionsHandler.MUTE_PERMISSION_LEVEL)
                    || dashboard.hasPermission(principal, PermissionsHandler.TEMPMUTE_PERMISSION, PermissionsHandler.TEMPMUTE_PERMISSION_LEVEL);
            case "ban", "tempban", "unban", "revoke" -> dashboard.hasPermission(principal, PermissionsHandler.BAN_PERMISSION, PermissionsHandler.BAN_PERMISSION_LEVEL)
                    || dashboard.hasPermission(principal, PermissionsHandler.TEMPBAN_PERMISSION, PermissionsHandler.TEMPBAN_PERMISSION_LEVEL);
            case "ipban", "tempipban", "unipban" -> dashboard.hasPermission(principal, PermissionsHandler.IPBAN_PERMISSION, PermissionsHandler.BAN_PERMISSION_LEVEL);
            case "jail", "unjail" -> dashboard.hasPermission(principal, PermissionsHandler.JAIL_PERMISSION, PermissionsHandler.JAIL_PERMISSION_LEVEL);
            default -> false;
        };
    }
}
