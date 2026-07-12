package eu.avalanche7.paradigm.modules.dashboard;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.configs.schema.ConfigPatchService;
import eu.avalanche7.paradigm.configs.schema.ConfigSchemaRegistry;
import eu.avalanche7.paradigm.configs.AnnouncementsConfigHandler;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.configs.MOTDConfigHandler;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import eu.avalanche7.paradigm.configs.RestartConfigHandler;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardAuthService;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPermission;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.dashboard.heartbeat.DashboardHeartbeatService;
import eu.avalanche7.paradigm.modules.moderation.ModerationActionRequest;
import eu.avalanche7.paradigm.modules.moderation.ModerationService;
import eu.avalanche7.paradigm.modules.permissions.PermissionAdminService;
import eu.avalanche7.paradigm.modules.Announcements;
import eu.avalanche7.paradigm.modules.Restart;
import eu.avalanche7.paradigm.modules.commands.Reload;
import eu.avalanche7.paradigm.modules.permissions.PermissionMutationRequest;
import eu.avalanche7.paradigm.modules.permissions.PermissionAssignmentId;
import eu.avalanche7.paradigm.modules.dashboard.customcommands.CustomCommandAdminService;
import com.google.gson.JsonObject;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.migration.StorageMigrationOptions;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;
import eu.avalanche7.paradigm.storage.model.StoredWarning;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageProviderType;
import eu.avalanche7.paradigm.modules.permissions.PermissionNodeRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

public class DashboardService implements AutoCloseable {
    private final Services services;
    private final DashboardAuthService authService = new DashboardAuthService();
    private final AuditService auditService;
    private final DashboardHeartbeatService heartbeatService;
    private final PermissionAdminService permissionAdminService;
    private final ModerationService moderationService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "Paradigm-Dashboard");
        thread.setDaemon(true);
        return thread;
    });
    private volatile DashboardConfig config;
    private volatile DashboardHttpServer httpServer;

    public DashboardService(Services services, DashboardConfig config) {
        this.services = services;
        this.config = config;
        this.auditService = services.getAuditService();
        this.heartbeatService = new DashboardHeartbeatService(services);
        this.permissionAdminService = services.getPermissionAdminService();
        this.moderationService = new ModerationService(services, auditService);
    }

    public synchronized boolean start() {
        if (httpServer != null && httpServer.running()) {
            return true;
        }
        httpServer = new DashboardHttpServer(this, config);
        boolean started = httpServer.start();
        if (started && config.remoteAccessRequested() && services.getLogger() != null) {
            services.getLogger().warn("Paradigm Dashboard: remote access is enabled or bound to all interfaces. Use VPN/reverse proxy and keep login enabled.");
        }
        return started;
    }

    public synchronized void stop() {
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
    }

    public synchronized void reload(DashboardConfig newConfig) {
        boolean wasRunning = running();
        stop();
        this.config = newConfig;
        if (wasRunning || newConfig.enabled) {
            start();
        }
    }

    public boolean running() {
        DashboardHttpServer server = httpServer;
        return server != null && server.running();
    }

    public String baseUrl() {
        return config.localBaseUrl();
    }

    public DashboardConfig config() {
        return config;
    }

    public Services services() {
        return services;
    }

    public DashboardAuthService auth() {
        return authService;
    }

    public AuditService audit() {
        return auditService;
    }

    public ExecutorService executor() {
        return executor;
    }

    public ConfigSchemaRegistry schemaRegistry() {
        return new ConfigSchemaRegistry(services);
    }

    public ConfigPatchService patchService() {
        return new ConfigPatchService(services, schemaRegistry());
    }

    public CompletableFuture<Object> applyConfigAsync(String rawPage) {
        String page = safeText(rawPage).toLowerCase(java.util.Locale.ROOT);
        CompletableFuture<Object> result = new CompletableFuture<>();
        Runnable apply = () -> {
            try {
                if ("dashboard".equals(page)) {
                    result.complete(Map.of("page", page, "message", "Dashboard reload scheduled. Reconnect using /paradigm dashboard open if the bind address changed."));
                    CompletableFuture.delayedExecutor(750L, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> {
                        DashboardConfig reloaded = DashboardConfig.load(services.getPlatformAdapter().getConfig(), services.getLogger());
                        reload(reloaded);
                    });
                    return;
                }
                switch (page) {
                    case "general", "teleports" -> {
                        MainConfigHandler.reload();
                        Reload.refreshModuleStatesForHelp(services);
                    }
                    case "chat" -> {
                        ChatConfigHandler.reload();
                        MentionConfigHandler.reload();
                    }
                    case "announcements" -> {
                        AnnouncementsConfigHandler.reload();
                        for (ParadigmModule module : ParadigmAPI.getModules()) if (module instanceof Announcements announcements) announcements.rescheduleAnnouncements();
                    }
                    case "restart" -> {
                        RestartConfigHandler.reload();
                        for (ParadigmModule module : ParadigmAPI.getModules()) if (module instanceof Restart restart) restart.rescheduleNextRestart(services);
                    }
                    case "motd" -> MOTDConfigHandler.reload();
                    case "commands" -> services.getPlatformAdapter().refreshAllPlayerCommandTrees();
                    default -> throw new IllegalArgumentException("This page does not support a live module reload.");
                }
                result.complete(Map.of("page", page, "message", "Reload applied for " + page + "."));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        };
        if (services.getTaskScheduler() != null) services.getTaskScheduler().schedule(apply, 0L, java.util.concurrent.TimeUnit.MILLISECONDS);
        else apply.run();
        return result;
    }

    public boolean hasDashboardPermission(DashboardPrincipal principal) {
        if (principal == null) {
            return false;
        }
        if (principal.console()) {
            return true;
        }
        return services.getPermissionsHandler().hasPermission(principal.uuid(), DashboardPermission.MANAGE, 4);
    }

    public boolean hasPermission(DashboardPrincipal principal, String permission, int fallbackLevel) {
        if (principal == null) {
            return false;
        }
        if (principal.console()) {
            return true;
        }
        return services.getPermissionsHandler().hasPermission(principal.uuid(), permission, fallbackLevel);
    }

    public CompletableFuture<Object> overviewAsync() {
        return CompletableFuture.supplyAsync(() -> {
            StorageService.StorageStatus storage = services.getStorageService().status();
            int enabledModules = 0;
            int modules = 0;
            for (ParadigmModule module : ParadigmAPI.getModules()) {
                modules++;
                try {
                    if (module.isEnabled(services)) {
                        enabledModules++;
                    }
                } catch (Throwable ignored) {
                }
            }
            List<String> warnings = new ArrayList<>();
            if (config.remoteAccessRequested()) {
                warnings.add("Dashboard remote access is enabled or bound outside localhost.");
            }
            if (storage.fallbackActive()) {
                warnings.add("Storage fallback is active: " + storage.fallbackReason());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("version", ParadigmAPI.getModVersion());
            data.put("minecraftVersion", services.getPlatformAdapter().getMinecraftVersion());
            data.put("loader", loaderName());
            data.put("onlinePlayers", safeOnlinePlayerCount());
            data.put("uptimeMs", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
            data.put("serverName", storage.serverIdentity().serverName());
            data.put("serverId", storage.serverIdentity().serverId());
            data.put("networkId", storage.serverIdentity().networkId());
            data.put("activeProvider", storage.activeDataProvider());
            data.put("dashboardUrl", baseUrl());
            data.put("dashboardRunning", running());
            data.put("sessions", authService.activeSessionCount());
            data.put("loginTokens", authService.activeLoginTokenCount());
            data.put("security", Map.of(
                    "localOnly", !config.remoteAccessRequested(),
                    "csrfEnabled", true,
                    "requireLogin", config.requireLogin,
                    "rateLimitPerMinute", config.rateLimitPerMinute,
                    "publicBaseUrl", config.publicBaseUrl != null ? config.publicBaseUrl : ""
            ));
            data.put("modules", Map.of("total", modules, "enabled", enabledModules));
            data.put("recentActivity", auditService.recent(6));
            data.put("warnings", warnings);
            return data;
        }, executor);
    }

    public CompletableFuture<Object> serversAsync() {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> result = heartbeatService.list(config, running());
            return Map.of("servers", result, "sqlBacked", services.getStorageService().isSqlActive());
        }, executor);
    }

    public CompletableFuture<Object> storageStatusAsync() {
        return CompletableFuture.supplyAsync(() -> {
            StorageService.StorageStatus status = services.getStorageService().status();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("selectedProvider", status.selectedProvider());
            data.put("activeProvider", status.activeProvider());
            data.put("configuredDataProvider", status.configuredDataProvider());
            data.put("activeDataProvider", status.activeDataProvider());
            data.put("displayName", status.displayName());
            data.put("target", status.target());
            data.put("dataLocation", status.dataLocation());
            data.put("migrationVersion", status.migrationVersion());
            data.put("repositoriesAvailable", status.repositoriesAvailable());
            data.put("serverRegistered", status.serverRegistered());
            data.put("fallbackActive", status.fallbackActive());
            data.put("fallbackReason", status.fallbackReason());
            data.put("fallbackDataPresent", status.fallbackDataPresent());
            data.put("fallbackWarning", status.fallbackWarning());
            data.put("migrationRecommendation", status.migrationRecommendation());
            data.put("lastTestResult", status.lastTestResult());
            data.put("dependencyMode", status.dependencyMode());
            data.put("runtimeLibraryCachePath", status.runtimeLibraryCachePath());
            data.put("sqliteDriverState", status.sqliteDriverState());
            data.put("mysqlDriverState", status.mysqlDriverState());
            data.put("serverIdentity", status.serverIdentity());
            data.put("sql", maskedSql());
            return data;
        }, executor);
    }

    public CompletableFuture<Object> storageTestAsync() {
        return services.getStorageService().testAsync().thenApply(result -> result);
    }

    public CompletableFuture<Object> storageConfigurationAsync() {
        return CompletableFuture.supplyAsync(() -> storageConfigurationView(services.getStorageService().config()), executor);
    }

    public CompletableFuture<Object> saveStorageConfigurationAsync(StorageConfigurationRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            StorageConfig config = storageConfiguration(request);
            config.persist(services.getPlatformAdapter().getConfig(), services.getLogger());
            return Map.of("configuration", storageConfigurationView(config), "restartRequired", true,
                    "message", "Storage configuration saved. Restart the server to activate it; no migration was performed.");
        }, executor);
    }

    public CompletableFuture<Object> testStorageConfigurationAsync(StorageConfigurationRequest request) {
        StorageConfig candidate = storageConfiguration(request);
        return services.getStorageService().testConfigurationAsync(candidate).thenApply(result -> result);
    }

    public CompletableFuture<Object> storageMigrationDryRunAsync(DashboardPrincipal actor, String source, String target, String policy) {
        StorageMigrationOptions.ConflictPolicy conflictPolicy = StorageMigrationOptions.ConflictPolicy.parse(policy);
        if (conflictPolicy == null) {
            throw new IllegalArgumentException("Invalid migration conflict policy.");
        }
        StorageMigrationOptions options = new StorageMigrationOptions(true, conflictPolicy, false);
        return services.getStorageService().migrateAsync(source, target, options).thenApply(summary -> {
            auditService.dashboard(actor, AuditActionType.STORAGE_MIGRATION_DRY_RUN, AuditResult.SUCCESS, "Storage migration dry-run completed.",
                    Map.of("source", safeText(source), "target", safeText(target), "policy", conflictPolicy.configValue()));
            return summary;
        });
    }

    public CompletableFuture<Object> permissionsSummaryAsync() {
        return CompletableFuture.supplyAsync(() -> {
            var permissions = services.getPermissionsHandler();
            Map<String, Object> data = new LinkedHashMap<>();
            int groupCount = safeList(() -> services.getStorageService().permissions().listGroups()).size();
            int userCount = safeList(() -> services.getStorageService().permissions().listUsers()).size();
            int nodeCount = permissions.knownPermissionNodes().size();
            data.put("internalEnabled", permissions.isInternalPermissionsEnabled());
            data.put("externalCommandPermissions", permissions.isExternalCommandPermissionsEnabled());
            data.put("externalMode", permissions.isExternalCommandStrictMode() ? "strict" : "deny_only");
            data.put("groups", groupCount);
            data.put("users", userCount);
            data.put("nodes", nodeCount);
            var identity = services.getStorageService().context().serverIdentity();
            data.put("serverId", identity != null ? identity.serverId() : "");
            data.put("networkId", identity != null ? identity.networkId() : "");
            return data;
        }, executor);
    }

    public CompletableFuture<Object> permissionGroupsAsync() {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var group : safeList(() -> services.getStorageService().permissions().listGroups())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", group.name());
                row.put("description", group.description());
                row.put("prefix", group.prefix());
                row.put("suffix", group.suffix());
                row.put("parents", group.parents());
                row.put("weight", group.weight());
                row.put("permissionCount", group.permissions().size());
                List<Map<String, Object>> assignments = new ArrayList<>();
                for (StoredPermissionNode node : group.permissions()) {
                    assignments.add(permissionAssignment("group permission", group.name(), group.name(), node));
                }
                row.put("assignments", assignments);
                rows.add(row);
            }
            return Map.of("groups", rows);
        }, executor);
    }

    public CompletableFuture<Object> permissionGroupAsync(String groupName) {
        return CompletableFuture.supplyAsync(() -> {
            var group = services.getStorageService().permissions().getGroup(safeText(groupName)).orElse(null);
            if (group == null) {
                return Map.of("group", null);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", group.name());
            row.put("description", group.description());
            row.put("prefix", group.prefix());
            row.put("suffix", group.suffix());
            row.put("weight", group.weight());
            row.put("parents", group.parents());
            row.put("permissions", group.permissions());
            return Map.of("group", row);
        }, executor);
    }

    public CompletableFuture<Object> permissionUsersAsync(String query, int page, int pageSize) {
        return CompletableFuture.supplyAsync(() -> {
            String q = query != null ? query.trim().toLowerCase(java.util.Locale.ROOT) : "";
            Map<String, Map<String, Object>> discovered = new LinkedHashMap<>();
            for (var profile : safeList(() -> services.getStorageService().players().listProfiles())) {
                mergeUser(discovered, profile.uuid(), profile.name(), profile.lastSeenMs(), false);
            }
            if (services.getPlayerDataStore() != null) {
                for (var profile : services.getPlayerDataStore().listPlayerEntries()) {
                    mergeUser(discovered, profile.getUuid(), profile.getName(), profile.getLastSeenMs(), false);
                }
            }
            for (IPlayer online : safeList(() -> services.getPlatformAdapter().getOnlinePlayers())) {
                mergeUser(discovered, online.getUUID(), online.getName(), System.currentTimeMillis(), true);
            }
            for (var user : safeList(() -> services.getStorageService().permissions().listUsers())) {
                Map<String, Object> row = mergeUser(discovered, user.uuid(), user.name(), 0L, false);
                row.put("groups", user.groups().size());
                row.put("permissions", user.permissions().size());
                List<Map<String, Object>> assignments = new ArrayList<>();
                for (StoredPermissionNode node : user.permissions()) {
                    assignments.add(permissionAssignment("user permission", user.name() != null ? user.name() : user.uuid(), user.uuid(), node));
                }
                for (StoredUserPermissionData.GroupAssignment group : user.groups()) {
                    Map<String, Object> assignment = new LinkedHashMap<>();
                    assignment.put("kind", "user group");
                    assignment.put("owner", user.name() != null ? user.name() : user.uuid());
                    assignment.put("target", user.uuid());
                    assignment.put("id", PermissionAssignmentId.ensure(group.assignmentId(), "user_group", user.uuid(), group.groupName(), false,
                            group.contextSet(), group.expiresAtMs(), group.assignedBy() + "@" + group.assignedAtMs()));
                    assignment.put("node", group.groupName());
                    assignment.put("denied", false);
                    assignment.put("contexts", group.contextSet().asMap());
                    assignment.put("expiresAtMs", group.expiresAtMs());
                    assignments.add(assignment);
                }
                row.put("assignments", assignments);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> row : discovered.values()) {
                String uuid = safeText((String) row.get("uuid"));
                String name = safeText((String) row.get("name"));
                if (!q.isBlank()
                        && !uuid.toLowerCase(java.util.Locale.ROOT).contains(q)
                        && !name.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                    continue;
                }
                rows.add(row);
            }
            rows.sort(java.util.Comparator.comparing(row -> safeText((String) row.get("name")), String.CASE_INSENSITIVE_ORDER));
            int size = clampPageSize(pageSize);
            int current = Math.max(1, page);
            return Map.of("users", page(rows, current, size), "total", rows.size(), "page", current, "pageSize", size);
        }, executor);
    }

    public CompletableFuture<Object> permissionUserAsync(String uuidOrName) {
        return CompletableFuture.supplyAsync(() -> {
            UUID uuid = resolveUuid(uuidOrName);
            if (uuid == null) {
                return Map.of("user", null);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("uuid", uuid.toString());
            data.put("info", services.getPermissionsHandler().getPlayerPermissionInfo(uuid));
            return Map.of("user", data);
        }, executor);
    }

    public CompletableFuture<Object> permissionNodesAsync(String query, int page, int pageSize) {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (PermissionNodeRegistry.DiscoveredPermission node : services.getPermissionsHandler().listDiscoveredPermissionNodes(query, 2000)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("node", node.node);
                row.put("source", node.source);
                row.put("description", node.description);
                row.put("defaultLevel", node.defaultLevel);
                rows.add(row);
            }
            if (rows.isEmpty()) {
                for (Map.Entry<String, String> entry : services.getPermissionsHandler().knownPermissionNodes().entrySet()) {
                    String q = query != null ? query.trim().toLowerCase(java.util.Locale.ROOT) : "";
                    if (!q.isBlank() && !entry.getKey().toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("node", entry.getKey());
                    row.put("source", "paradigm");
                    row.put("description", entry.getValue());
                    row.put("defaultLevel", -1);
                    rows.add(row);
                }
            }
            rows.sort(java.util.Comparator.comparing(row -> safeText((String) row.get("node")), String.CASE_INSENSITIVE_ORDER));
            int size = clampPageSize(pageSize);
            int current = Math.max(1, page);
            return Map.of("nodes", page(rows, current, size), "total", rows.size(), "page", current, "pageSize", size);
        }, executor);
    }

    public CompletableFuture<Object> permissionEffectiveAsync(String uuidOrName, String query, int page, int pageSize) {
        return CompletableFuture.supplyAsync(() -> {
            UUID uuid = resolveUuid(uuidOrName);
            if (uuid == null) return Map.of("entries", List.of(), "total", 0, "page", 1, "pageSize", clampPageSize(pageSize));
            String q = safeText(query).toLowerCase(java.util.Locale.ROOT);
            Set<String> nodes = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            nodes.addAll(services.getPermissionsHandler().knownPermissionNodes().keySet());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (String node : nodes) {
                if (!q.isBlank() && !node.toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
                var explain = services.getPermissionsHandler().explainPlayerPermission(uuid, node);
                if (explain == null || explain.allowed() == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("node", node);
                row.put("allowed", explain.allowed());
                row.put("sourceType", explain.sourceType());
                row.put("sourceName", explain.sourceName());
                row.put("rule", explain.rule());
                row.put("groups", explain.groupsChecked());
                entries.add(row);
            }
            int size = clampPageSize(pageSize);
            int current = Math.max(1, page);
            return Map.of("entries", page(entries, current, size), "total", entries.size(), "page", current, "pageSize", size);
        }, executor);
    }

    public CompletableFuture<Object> permissionMutationAsync(DashboardPrincipal actor, PermissionMutationRequest mutation) {
        return CompletableFuture.supplyAsync(() -> permissionAdminService.mutate(actor, mutation), executor);
    }

    private static Map<String, Object> permissionAssignment(String kind, String owner, String target, StoredPermissionNode node) {
        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("kind", kind);
        assignment.put("owner", owner);
        assignment.put("target", target);
        assignment.put("id", PermissionAssignmentId.ensure(node.assignmentId(), kind.replace(' ', '_'), target, node.permission(), node.denied(),
                node.contextSet(), node.expiresAtMs(), ""));
        assignment.put("node", node.permission());
        assignment.put("denied", node.denied());
        assignment.put("contexts", node.contextSet().asMap());
        assignment.put("expiresAtMs", node.expiresAtMs());
        return assignment;
    }

    public CompletableFuture<Object> moderationRecentAsync() {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> punishments = services.getPunishmentService().history(null, 1, 80).stream().map(this::punishmentDto).toList();
            return Map.of("punishments", punishments, "warnings", List.of());
        }, executor);
    }

    public CompletableFuture<Object> moderationActiveAsync() {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> active = services.getStorageService().moderation().listActivePunishmentRecords(0L).stream().limit(100).map(this::punishmentDto).toList();
            List<StoredJailState> jails = safeList(() -> services.getStorageService().moderation().listJailStates()).stream().limit(100).toList();
            return Map.of("punishments", active, "jails", jails);
        }, executor);
    }

    public CompletableFuture<Object> moderationPlayerAsync(String uuidOrName) {
        return CompletableFuture.supplyAsync(() -> {
            String q = uuidOrName != null ? uuidOrName.trim() : "";
            var profile = services.getStorageService().players().listProfiles().stream()
                    .filter(item -> q.equalsIgnoreCase(item.uuid()) || q.equalsIgnoreCase(item.name())).findFirst().orElse(null);
            IPlayer online = services.getPlatformAdapter().getPlayerByName(q);
            if (online == null) online = services.getPlatformAdapter().getPlayerByUuid(q);
            String uuid = profile != null ? profile.uuid() : online != null ? online.getUUID() : validUuid(q) ? q : "";
            String name = profile != null ? profile.name() : online != null ? online.getName() : q;
            List<Map<String, Object>> punishments = services.getPunishmentService().history(uuid, 1, 100).stream().map(this::punishmentDto).toList();
            return Map.of("player", Map.of("uuid", uuid, "name", name), "punishments", punishments, "warnings", List.of());
        }, executor);
    }

    public CompletableFuture<Object> moderationPunishmentAsync(String punishmentId) {
        return CompletableFuture.supplyAsync(() -> services.getPunishmentService().find(punishmentId)
                .map(this::punishmentDto).orElse(null), executor);
    }

    public CompletableFuture<Object> moderationActionAsync(DashboardPrincipal actor, ModerationActionRequest action) {
        return CompletableFuture.supplyAsync(() -> moderationService.apply(actor, action), executor);
    }

    private Map<String, Object> punishmentDto(eu.avalanche7.paradigm.modules.moderation.PunishmentRecord source) {
        var record = source.withoutSensitiveIp();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("punishmentId", record.punishmentId());
        result.put("type", record.type().name());
        result.put("status", record.status(System.currentTimeMillis()).name());
        result.put("active", record.activeAt(System.currentTimeMillis()));
        result.put("scope", record.scope().name());
        result.put("networkId", record.networkId());
        result.put("serverId", record.serverId());
        result.put("uuid", record.subjectUuid());
        result.put("name", record.subjectName());
        result.put("ipSubject", record.subjectIpHash());
        result.put("reason", record.reason());
        result.put("actorUuid", record.actorUuid());
        result.put("actorName", record.actorName());
        result.put("createdAtMs", record.createdAtMs());
        result.put("startsAtMs", record.startsAtMs());
        result.put("expiresAtMs", record.expiresAtMs());
        result.put("revokedAtMs", record.revokedAtMs());
        result.put("revokedByUuid", record.revokedByUuid());
        result.put("revokedByName", record.revokedByName());
        result.put("revokeReason", record.revokeReason());
        return result;
    }

    private static boolean validUuid(String value) {
        try { java.util.UUID.fromString(value); return true; }
        catch (Exception ignored) { return false; }
    }

    public CompletableFuture<Object> auditRecentAsync(String actor, String type, int limit) {
        if (actor != null && !actor.isBlank()) return auditService.actorAsync(actor, limit).thenApply(entries -> Map.of("entries", entries));
        if (type != null && !type.isBlank()) return auditService.typeAsync(type, limit).thenApply(entries -> Map.of("entries", entries));
        return auditService.recentAsync(limit).thenApply(entries -> Map.of("entries", entries));
    }

    public CompletableFuture<Object> auditQueryAsync(String actor, String type, String result, String source, String target,
                                                     Long fromMs, Long toMs, int page, int pageSize) {
        CompletableFuture<List<eu.avalanche7.paradigm.modules.audit.AuditEntry>> base;
        if (!safeText(actor).isBlank()) base = auditService.actorAsync(actor, 500);
        else if (!safeText(type).isBlank()) base = auditService.typeAsync(type, 500);
        else base = CompletableFuture.completedFuture(auditService.recent(500));
        return base.thenApply(entries -> {
            String expectedResult = safeText(result).toUpperCase(java.util.Locale.ROOT);
            String expectedSource = safeText(source).toUpperCase(java.util.Locale.ROOT);
            String expectedTarget = safeText(target).toLowerCase(java.util.Locale.ROOT);
            List<eu.avalanche7.paradigm.modules.audit.AuditEntry> filtered = entries.stream().filter(entry -> {
                if (!expectedResult.isBlank() && (entry.result() == null || !entry.result().name().equals(expectedResult))) return false;
                if (!expectedSource.isBlank() && (entry.source() == null || !entry.source().name().equals(expectedSource))) return false;
                if (!expectedTarget.isBlank()) {
                    String haystack = (safeText(entry.targetName()) + " " + safeText(entry.targetUuid())).toLowerCase(java.util.Locale.ROOT);
                    if (!haystack.contains(expectedTarget)) return false;
                }
                if (fromMs != null && entry.timestampMs() < fromMs) return false;
                return toMs == null || entry.timestampMs() <= toMs;
            }).toList();
            int size = clampPageSize(pageSize);
            int current = Math.max(1, page);
            return Map.of("entries", page(filtered, current, size), "total", filtered.size(), "page", current, "pageSize", size);
        });
    }

    public CompletableFuture<List<CustomCommandAdminService.CommandView>> customCommandsAsync(String query) {
        return CompletableFuture.supplyAsync(() -> services.getCustomCommandAdminService().list(query), executor);
    }

    public CompletableFuture<CustomCommandAdminService.CommandView> customCommandAsync(String name) {
        return CompletableFuture.supplyAsync(() -> services.getCustomCommandAdminService().get(name), executor);
    }

    public CompletableFuture<CustomCommandAdminService.MutationResult> customCommandMutationAsync(
            String action, String originalName, String name, JsonObject command) {
        return CompletableFuture.supplyAsync(() -> switch (safeText(action)) {
            case "create" -> services.getCustomCommandAdminService().create(command);
            case "update" -> services.getCustomCommandAdminService().update(originalName, command);
            case "duplicate" -> services.getCustomCommandAdminService().duplicate(originalName, name);
            case "delete" -> services.getCustomCommandAdminService().delete(originalName);
            case "reload" -> services.getCustomCommandAdminService().reload();
            default -> throw new IllegalArgumentException("Unknown custom command operation.");
        }, executor);
    }

    private int safeOnlinePlayerCount() {
        try {
            return services.getPlatformAdapter().getOnlinePlayers().size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private String loaderName() {
        return normalizeLoaderName(services.getPlatformAdapter().getLoaderName());
    }

    static String normalizeLoaderName(String name) {
        return name == null || name.isBlank() ? "Unavailable" : name;
    }

    private Map<String, Object> maskedSql() {
        var storage = services.getStorageService().config();
        Map<String, Object> sql = new LinkedHashMap<>();
        sql.put("dialect", storage.sql != null ? storage.sql.dialect : "");
        sql.put("host", storage.sql != null ? storage.sql.host : "");
        sql.put("port", storage.sql != null ? storage.sql.port : 0);
        sql.put("database", storage.sql != null ? storage.sql.database : "");
        sql.put("username", storage.sql != null ? storage.sql.username : "");
        sql.put("passwordSet", storage.resolvedPassword() != null && !storage.resolvedPassword().isBlank());
        return sql;
    }

    private StorageConfig storageConfiguration(StorageConfigurationRequest request) {
        if (request == null) throw new IllegalArgumentException("Storage configuration is required.");
        StorageConfig current = services.getStorageService().config();
        StorageConfig next = new StorageConfig();
        StorageProviderType provider = StorageProviderType.parse(request.provider);
        if (provider == null) throw new IllegalArgumentException("Storage provider must be json, sqlite, or mysql.");
        next.provider = provider.configValue();
        next.fallbackToJsonOnSqlFailure = request.fallbackToJsonOnSqlFailure != null ? request.fallbackToJsonOnSqlFailure : current.fallbackToJsonOnSqlFailure;
        next.networkId = storageId(request.networkId, "networkId");
        next.serverId = storageId(request.serverId, "serverId");
        next.serverName = bounded(request.serverName, "serverName", 80);
        String sqlitePath = safeText(request.sqlitePath);
        if (sqlitePath.isBlank()) sqlitePath = "config/paradigm/data/paradigm.db";
        java.nio.file.Path path = java.nio.file.Path.of(sqlitePath);
        if (path.isAbsolute() || sqlitePath.contains("..")) throw new IllegalArgumentException("SQLite path must be a relative path without '..'.");
        next.sqlite.path = sqlitePath;
        next.sql.host = bounded(request.sqlHost, "SQL host", 255);
        next.sql.port = request.sqlPort != null && request.sqlPort > 0 && request.sqlPort <= 65535 ? request.sqlPort : 3306;
        next.sql.database = storageId(request.sqlDatabase, "SQL database");
        next.sql.username = bounded(request.sqlUsername, "SQL username", 128);
        next.sql.password = !safeText(request.sqlPassword).isBlank() ? request.sqlPassword : current.sql.password;
        next.sql.passwordEnv = safeText(request.sqlPasswordEnv);
        next.sql.poolSize = request.sqlPoolSize != null ? Math.max(1, Math.min(request.sqlPoolSize, 50)) : 5;
        next.sql.ssl = Boolean.TRUE.equals(request.sqlSsl);
        next.runtimeLibraries = current.runtimeLibraries;
        return next;
    }

    private Map<String, Object> storageConfigurationView(StorageConfig config) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("provider", config.provider);
        view.put("fallbackToJsonOnSqlFailure", config.fallbackToJsonOnSqlFailure);
        view.put("networkId", config.networkId);
        view.put("serverId", config.serverId);
        view.put("serverName", config.serverName);
        view.put("sqlitePath", config.sqlite != null ? config.sqlite.path : "");
        view.put("sqlHost", config.sql != null ? config.sql.host : "");
        view.put("sqlPort", config.sql != null ? config.sql.port : 3306);
        view.put("sqlDatabase", config.sql != null ? config.sql.database : "");
        view.put("sqlUsername", config.sql != null ? config.sql.username : "");
        view.put("sqlPasswordSet", config.resolvedPassword() != null && !config.resolvedPassword().isBlank());
        view.put("sqlPasswordEnv", config.sql != null ? config.sql.passwordEnv : "");
        view.put("sqlPoolSize", config.sql != null ? config.sql.poolSize : 5);
        view.put("sqlSsl", config.sql != null && config.sql.ssl);
        return view;
    }

    private static String storageId(String value, String field) {
        String normalized = safeText(value);
        if (!normalized.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException(field + " is invalid.");
        return normalized;
    }

    private static String bounded(String value, String field, int max) {
        String normalized = safeText(value);
        if (normalized.isBlank() || normalized.length() > max || normalized.contains("\n") || normalized.contains("\r")) {
            throw new IllegalArgumentException(field + " is invalid.");
        }
        return normalized;
    }

    private static Map<String, Object> mergeUser(Map<String, Map<String, Object>> users, String rawUuid, String rawName, long lastSeenMs, boolean online) {
        String uuid = safeText(rawUuid).toLowerCase(java.util.Locale.ROOT);
        if (uuid.isBlank()) return new LinkedHashMap<>();
        Map<String, Object> row = users.computeIfAbsent(uuid, ignored -> {
            Map<String, Object> created = new LinkedHashMap<>();
            created.put("uuid", rawUuid);
            created.put("name", safeText(rawName));
            created.put("online", false);
            created.put("lastSeenMs", 0L);
            created.put("groups", 0);
            created.put("permissions", 0);
            created.put("assignments", List.of());
            return created;
        });
        if (!safeText(rawName).isBlank()) row.put("name", rawName);
        row.put("online", Boolean.TRUE.equals(row.get("online")) || online);
        long existing = row.get("lastSeenMs") instanceof Number number ? number.longValue() : 0L;
        row.put("lastSeenMs", Math.max(existing, lastSeenMs));
        return row;
    }

    private static int clampPageSize(int pageSize) {
        return Math.max(10, Math.min(pageSize > 0 ? pageSize : 25, 100));
    }

    private static <T> List<T> page(List<T> values, int page, int pageSize) {
        if (values == null || values.isEmpty()) return List.of();
        int from = Math.min(values.size(), Math.max(0, page - 1) * pageSize);
        int to = Math.min(values.size(), from + pageSize);
        return List.copyOf(values.subList(from, to));
    }

    private static <T> List<T> safeList(java.util.function.Supplier<List<T>> supplier) {
        try {
            List<T> value = supplier.get();
            return value != null ? value : List.of();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static <T> List<T> limitedNewest(List<T> input, int limit) {
        if (input == null || input.isEmpty()) return List.of();
        int max = Math.max(1, limit);
        List<T> copy = new ArrayList<>(input);
        java.util.Collections.reverse(copy);
        return copy.size() > max ? List.copyOf(copy.subList(0, max)) : List.copyOf(copy);
    }

    private UUID resolveUuid(String uuidOrName) {
        String value = safeText(uuidOrName);
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

    private static String safeText(String value) {
        return value != null ? value.trim() : "";
    }

    @Override
    public void close() {
        stop();
        executor.shutdownNow();
    }
}
