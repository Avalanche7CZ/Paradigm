package eu.avalanche7.paradigm.modules.dashboard;

import com.sun.net.httpserver.HttpExchange;

import eu.avalanche7.paradigm.modules.dashboard.api.AuditApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.AuthApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.ConfigApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.CustomCommandApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.DiscordApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.HologramApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.MenuApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.ModerationApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.OverviewApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.PermissionsApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.PlayerApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.RemoteConfigApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.ServerApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.StaticAssetHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.StorageApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.api.TicketApiHandler;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardSession;
import eu.avalanche7.paradigm.modules.moderation.ModerationActionType;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;

public class DashboardRouter {
    private final DashboardService dashboard;
    private final AuthApiHandler auth;
    private final OverviewApiHandler overview;
    private final PlayerApiHandler players;
    private final ServerApiHandler servers;
    private final StorageApiHandler storage;
    private final DiscordApiHandler discord;
    private final ConfigApiHandler config;
    private final RemoteConfigApiHandler remoteConfig;
    private final CustomCommandApiHandler customCommands;
    private final PermissionsApiHandler permissions;
    private final ModerationApiHandler moderation;
    private final AuditApiHandler audit;
    private final HologramApiHandler holograms;
    private final MenuApiHandler menus;
    private final TicketApiHandler tickets;
    private final StaticAssetHandler staticAssets;

    public DashboardRouter(DashboardService dashboard) {
        this.dashboard = dashboard;
        this.auth = new AuthApiHandler(dashboard);
        this.overview = new OverviewApiHandler(dashboard);
        this.players = new PlayerApiHandler(dashboard);
        this.servers = new ServerApiHandler(dashboard);
        this.storage = new StorageApiHandler(dashboard);
        this.discord = new DiscordApiHandler(dashboard);
        this.config = new ConfigApiHandler(dashboard);
        this.remoteConfig = new RemoteConfigApiHandler(dashboard);
        this.customCommands = new CustomCommandApiHandler(dashboard);
        this.permissions = new PermissionsApiHandler(dashboard);
        this.moderation = new ModerationApiHandler(dashboard);
        this.audit = new AuditApiHandler(dashboard);
        this.holograms = new HologramApiHandler(dashboard);
        this.menus = new MenuApiHandler(dashboard);
        this.tickets = new TicketApiHandler(dashboard);
        this.staticAssets = new StaticAssetHandler(dashboard.config());
    }

    public DashboardResponse route(HttpExchange exchange) {
        DashboardSession session = session(exchange);
        DashboardPrincipal principal = session != null ? session.principal() : (!dashboard.config().requireLogin ? new DashboardPrincipal("local", "Local Admin", true) : null);
        DashboardRequestContext ctx = new DashboardRequestContext(exchange, principal, session);
        String path = ctx.path();
        String method = ctx.method();

        if (path.startsWith("/api/") && DashboardRequestContext.bodyTooLarge(exchange)) {
            return DashboardResponse.apiError(413, "payload_too_large", "Dashboard request body is too large.");
        }

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

                if ("GET".equals(method) && "/api/overview".equals(path)) {
                    DashboardResponse denied = denyPage(ctx, ParadigmPermissions.DASHBOARD_OVERVIEW, "You do not have permission to view the overview.");
                    return denied != null ? denied : overview.get(ctx);
                }
                if ("GET".equals(method) && "/api/players".equals(path)) return players.list(ctx);
                if ("GET".equals(method) && "/api/servers".equals(path)) {
                    DashboardResponse denied = denyPage(ctx, ParadigmPermissions.DASHBOARD_SERVERS, "You do not have permission to view servers.");
                    return denied != null ? denied : servers.list(ctx);
                }

                if (path.startsWith("/api/storage/configuration")) {
                    if ("GET".equals(method)) {
                        if (!dashboard.canViewConfigCategory(ctx.principal(), "storage")) {
                            return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to view storage configuration.");
                        }
                    } else if (mutating(method) && !dashboard.canEditConfigCategory(ctx.principal(), "storage")) {
                        return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to edit storage configuration.");
                    }
                } else if (path.startsWith("/api/storage/")) {
                    if ("GET".equals(method)) {
                        DashboardResponse denied = denyPage(ctx, ParadigmPermissions.DASHBOARD_STORAGE, "You do not have permission to view storage runtime status.");
                        if (denied != null) return denied;
                    } else if (mutating(method) && !dashboard.hasPermission(ctx.principal(), ParadigmPermissions.STORAGE_MANAGE)) {
                        return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to manage storage.");
                    }
                }
                if ("GET".equals(method) && "/api/storage/status".equals(path)) return storage.status(ctx);
                if ("POST".equals(method) && "/api/storage/test".equals(path)) return storage.test(ctx);
                if ("GET".equals(method) && "/api/storage/health".equals(path)) return storage.health(ctx);
                if ("GET".equals(method) && "/api/storage/repair/check".equals(path)) return storage.repairCheck(ctx);
                if ("POST".equals(method) && "/api/storage/migration/dry-run".equals(path)) return storage.migrationDryRun(ctx);
                if ("GET".equals(method) && "/api/storage/configuration".equals(path)) return storage.configuration(ctx);
                if ("POST".equals(method) && "/api/storage/configuration".equals(path)) return storage.saveConfiguration(ctx);
                if ("POST".equals(method) && "/api/storage/configuration/test".equals(path)) return storage.testConfiguration(ctx);

                if (path.startsWith("/api/discord")) {
                    if ("GET".equals(method) && !dashboard.canViewConfigCategory(ctx.principal(), "discord")) {
                        return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to view the Discord integration.");
                    }
                    if ("POST".equals(method) && "/api/discord/token".equals(path)
                            && !dashboard.canEditConfigCategory(ctx.principal(), "discord")) {
                        return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to edit Discord configuration.");
                    }
                    if (mutating(method) && !"/api/discord/token".equals(path)
                            && !dashboard.hasPermission(ctx.principal(), ParadigmPermissions.DISCORD_MANAGE)) {
                        return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to manage the Discord integration.");
                    }
                }
                if ("GET".equals(method) && "/api/discord/status".equals(path)) return discord.status(ctx);
                if ("POST".equals(method) && "/api/discord/token".equals(path)) return discord.saveToken(ctx);
                if ("POST".equals(method) && "/api/discord/test".equals(path)) return discord.test(ctx);
                if ("POST".equals(method) && "/api/discord/reconnect".equals(path)) return discord.reconnect(ctx);

                if ("GET".equals(method) && "/api/config/snapshot".equals(path)) return config.snapshot(ctx);
                if ("POST".equals(method) && "/api/config/patch".equals(path)) return config.patch(ctx);
                if ("POST".equals(method) && "/api/config/apply".equals(path)) return config.apply(ctx);

                if (path.startsWith("/api/remote-config/") && !dashboard.services().getStorageService().isMysqlActive()) {
                    return DashboardResponse.apiError(409, "sql_required", "Remote server management requires shared MySQL storage.");
                }
                if ("GET".equals(method) && "/api/remote-config/snapshot".equals(path)) return remoteConfig.snapshot(ctx);
                if ("POST".equals(method) && "/api/remote-config/patch".equals(path)) return remoteConfig.patch(ctx);
                if ("POST".equals(method) && "/api/remote-config/copy".equals(path)) return remoteConfig.copy(ctx);
                if ("POST".equals(method) && "/api/remote-config/adopt".equals(path)) return remoteConfig.adopt(ctx);

                if ("GET".equals(method) && "/api/audit/recent".equals(path)) {
                    DashboardResponse denied = denyPage(ctx, ParadigmPermissions.DASHBOARD_AUDIT, "You do not have permission to view the audit log.");
                    return denied != null ? denied : audit.recent(ctx);
                }

                {
                    DashboardResponse denied = guardResource(ctx, method, path, "/api/holograms", "holograms",
                            ParadigmPermissions.HOLOGRAM_MANAGE, ParadigmPermissions.DASHBOARD_CONFIG_HOLOGRAMS_VIEW, ParadigmPermissions.HOLOGRAM_MANAGE);
                    if (denied != null) return denied;
                }
                if ("GET".equals(method) && "/api/holograms".equals(path)) return holograms.list(ctx);
                if ("GET".equals(method) && "/api/holograms/item".equals(path)) return holograms.get(ctx);
                if ("POST".equals(method) && path.startsWith("/api/holograms/")) {
                    String action = path.substring("/api/holograms/".length());
                    if (!java.util.Set.of("create", "update", "duplicate", "rename", "delete", "refresh", "settings", "player-location", "temporary-remove", "temporary-update").contains(action)) {
                        return DashboardResponse.apiError(400, "invalid_request", "Unknown hologram operation.");
                    }
                    return holograms.mutate(ctx, action);
                }

                {
                    DashboardResponse denied = guardResource(ctx, method, path, "/api/tickets", "tickets",
                            ParadigmPermissions.TICKET_MANAGE, ParadigmPermissions.DASHBOARD_TICKETS, ParadigmPermissions.TICKET_MANAGE);
                    if (denied != null) return denied;
                }
                if ("GET".equals(method) && "/api/tickets".equals(path)) return tickets.list(ctx);
                if ("GET".equals(method) && "/api/tickets/item".equals(path)) return tickets.get(ctx);
                if ("POST".equals(method) && path.startsWith("/api/tickets/")) {
                    String ticketAction = path.substring("/api/tickets/".length());
                    if (!TicketApiHandler.ACTIONS.contains(ticketAction)) {
                        return DashboardResponse.apiError(400, "invalid_request", "Unknown ticket operation.");
                    }
                    return tickets.mutate(ctx, ticketAction);
                }

                {
                    DashboardResponse denied = guardResource(ctx, method, path, "/api/menus", "menus",
                            ParadigmPermissions.MENU_MANAGE, ParadigmPermissions.DASHBOARD_CONFIG_MENUS_VIEW, ParadigmPermissions.MENU_MANAGE);
                    if (denied != null) return denied;
                }
                if ("GET".equals(method) && "/api/menus".equals(path)) return menus.list(ctx);
                if ("GET".equals(method) && "/api/menus/item".equals(path)) return menus.get(ctx);
                if ("POST".equals(method) && path.startsWith("/api/menus/")) {
                    String action = path.substring("/api/menus/".length());
                    if (!MenuApiHandler.ACTIONS.contains(action)) {
                        return DashboardResponse.apiError(400, "invalid_request", "Unknown menu operation.");
                    }
                    return menus.mutate(ctx, action);
                }

                {
                    DashboardResponse denied = guardResource(ctx, method, path, "/api/custom-commands", "custom commands",
                            ParadigmPermissions.CONFIG_EDIT, ParadigmPermissions.DASHBOARD_CONFIG_CUSTOMCOMMANDS_VIEW, ParadigmPermissions.CONFIG_EDIT);
                    if (denied != null) return denied;
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

                if (path.startsWith("/api/permissions/")) {
                    if ("GET".equals(method)) {
                        DashboardResponse denied = denyPage(ctx, ParadigmPermissions.DASHBOARD_PERMISSIONS,
                                "You do not have permission to view permissions.", ParadigmPermissions.GROUP_MANAGE);
                        if (denied != null) return denied;
                    } else if (mutating(method) && !dashboard.canManagePermissions(ctx.principal())) {
                        return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to manage dashboard permissions.");
                    }
                }
                if ("GET".equals(method) && "/api/permissions/summary".equals(path)) return permissions.summary(ctx);
                if ("GET".equals(method) && "/api/permissions/groups".equals(path)) return permissions.groups(ctx);
                if ("GET".equals(method) && "/api/permissions/group".equals(path)) return permissions.group(ctx);
                if ("GET".equals(method) && "/api/permissions/users".equals(path)) return permissions.users(ctx);
                if ("GET".equals(method) && "/api/permissions/user".equals(path)) return permissions.user(ctx);
                if ("GET".equals(method) && "/api/permissions/nodes".equals(path)) return permissions.nodes(ctx);
                if ("GET".equals(method) && "/api/permissions/tracks".equals(path)) return permissions.tracks(ctx);
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
                if ("POST".equals(method) && path.startsWith("/api/permissions/track/")) {
                    String action = "track_" + path.substring("/api/permissions/track/".length());
                    if (java.util.Set.of("track_create", "track_delete", "track_rename", "track_clone", "track_clear", "track_append", "track_insert", "track_remove", "track_move").contains(action)) return permissions.mutate(ctx, action);
                }
                if ("POST".equals(method) && path.startsWith("/api/permissions/user/track/")) {
                    String action = path.substring("/api/permissions/user/track/".length());
                    if (java.util.Set.of("promote", "demote", "settrack", "cleartrack").contains(action)) return permissions.mutate(ctx, action);
                }

                if (path.startsWith("/api/moderation/")) {
                    DashboardResponse denied = denyPage(ctx, ParadigmPermissions.DASHBOARD_MODERATION, "You do not have permission to view moderation.",
                            ParadigmPermissions.KICK, ParadigmPermissions.BAN, ParadigmPermissions.WARN, ParadigmPermissions.JAIL);
                    if (denied != null) return denied;
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
            if (DashboardRequestContext.causedByPayloadTooLarge(t)) {
                return DashboardResponse.apiError(413, "payload_too_large", "Dashboard request body is too large.");
            }
            if (dashboard.services().getLogger() != null) {
                dashboard.services().getLogger().warn("Paradigm Dashboard: request {} {} failed: {}", method, path, t.toString());
            }
            return DashboardResponse.apiError(500, "internal_error", "Dashboard request failed.");
        }
    }

    /** Returns an error response if the principal cannot view this page, or null if access is allowed. */
    private DashboardResponse denyPage(DashboardRequestContext ctx, PermissionDefinition page, String message, PermissionDefinition... legacy) {
        return dashboard.canAccessPage(ctx.principal(), page, legacy) ? null : DashboardResponse.apiError(403, "permission_denied", message);
    }

    /**
     * Gates a resource whose GET routes need dashboard-page visibility and whose mutating routes
     * need the resource's own feature-management permission (unchanged from the pre-granular
     * behavior - the new page permission never grants mutation by itself).
     */
    private DashboardResponse guardResource(DashboardRequestContext ctx, String method, String path, String prefix, String resourceName,
            PermissionDefinition managePermission, PermissionDefinition viewPage, PermissionDefinition... viewLegacy) {
        if (!path.startsWith(prefix)) {
            return null;
        }
        if ("GET".equals(method)) {
            return denyPage(ctx, viewPage, "You do not have permission to view " + resourceName + ".", viewLegacy);
        }
        if (mutating(method) && !dashboard.hasPermission(ctx.principal(), managePermission)) {
            return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to manage " + resourceName + ".");
        }
        return null;
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

    private boolean canRunModerationAction(DashboardPrincipal principal, String action) {
        return switch (action) {
            case "warn" -> dashboard.hasPermission(principal, ParadigmPermissions.WARN);
            case "mute", "tempmute", "unmute" -> dashboard.hasPermission(principal, ParadigmPermissions.MUTE)
                    || dashboard.hasPermission(principal, ParadigmPermissions.TEMP_MUTE);
            case "ban", "tempban", "unban", "revoke" -> dashboard.hasPermission(principal, ParadigmPermissions.BAN)
                    || dashboard.hasPermission(principal, ParadigmPermissions.TEMP_BAN);
            case "ipban", "tempipban", "unipban" -> dashboard.hasPermission(principal, ParadigmPermissions.IP_BAN);
            case "jail", "unjail" -> dashboard.hasPermission(principal, ParadigmPermissions.JAIL);
            default -> false;
        };
    }
}
