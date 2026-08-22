package eu.avalanche7.paradigm.modules.dashboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonObject;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.AfkConfigHandler;
import eu.avalanche7.paradigm.configs.AnnouncementsConfigHandler;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.configs.DiscordConfigHandler;
import eu.avalanche7.paradigm.configs.MOTDConfigHandler;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import eu.avalanche7.paradigm.configs.RestartConfigHandler;
import eu.avalanche7.paradigm.configs.schema.ConfigField;
import eu.avalanche7.paradigm.configs.schema.ConfigPatchOperation;
import eu.avalanche7.paradigm.configs.schema.ConfigPatchService;
import eu.avalanche7.paradigm.configs.schema.ConfigSchemaRegistry;
import eu.avalanche7.paradigm.configs.schema.ConfigSnapshot;
import eu.avalanche7.paradigm.configs.schema.ConfigValidationResult;
import eu.avalanche7.paradigm.configs.schema.RemoteConfigEligibility;
import eu.avalanche7.paradigm.configs.schema.RemoteConfigField;
import eu.avalanche7.paradigm.configs.schema.RemoteConfigSnapshot;
import eu.avalanche7.paradigm.configs.schema.RemoteConfigValidationService;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.Announcements;
import eu.avalanche7.paradigm.modules.Restart;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.audit.AuditService;
import eu.avalanche7.paradigm.modules.commands.Reload;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardAuthService;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardAuthorization;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.modules.dashboard.customcommands.CustomCommandAdminService;
import eu.avalanche7.paradigm.modules.dashboard.heartbeat.DashboardHeartbeatService;
import eu.avalanche7.paradigm.modules.moderation.ModerationActionRequest;
import eu.avalanche7.paradigm.modules.moderation.ModerationService;
import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionAdminService;
import eu.avalanche7.paradigm.modules.permissions.PermissionAssignmentId;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;
import eu.avalanche7.paradigm.modules.permissions.PermissionMutationRequest;
import eu.avalanche7.paradigm.modules.permissions.PermissionNodeRegistry;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.StorageConfig;
import eu.avalanche7.paradigm.storage.StorageProviderType;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigAppliedState;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigEntry;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigUpsertResult;
import eu.avalanche7.paradigm.storage.managedconfig.ServerInstanceInfo;
import eu.avalanche7.paradigm.storage.migration.StorageMigrationOptions;
import eu.avalanche7.paradigm.storage.model.StoredJailState;
import eu.avalanche7.paradigm.storage.model.StoredPermissionNode;
import eu.avalanche7.paradigm.storage.model.StoredUserPermissionData;
import eu.avalanche7.paradigm.utils.ServerThreadCalls;

public class DashboardService implements AutoCloseable {
    public static final class SchemaIncompatibleException extends IllegalStateException {
        public SchemaIncompatibleException(String message) {
            super(message);
        }
    }

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
                        Map<ParadigmModule, Boolean> moduleStates = Reload.snapshotModuleStates(services);
                        MainConfigHandler.reload();
                        Reload.refreshModuleStates(services, moduleStates);
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
                    case "commands" -> Reload.refreshAllCommandStates(services);
                    case "discord" -> {
                        DiscordConfigHandler.reload();
                        services.getDiscordService().reload();
                    }
                    case "afk" -> {
                        AfkConfigHandler.reload();
                        services.getAfkService().restart();
                    }
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

    public boolean requiresNetworkManage(String scope, String targetServerId) {
        if ("NETWORK".equalsIgnoreCase(scope) || "GLOBAL".equalsIgnoreCase(scope)) {
            return true;
        }
        StorageService storage = services.getStorageService();
        var identity = storage != null ? storage.context().serverIdentity() : null;
        String self = identity != null ? identity.serverId() : "default";
        return targetServerId == null || targetServerId.isBlank() || !targetServerId.equalsIgnoreCase(self);
    }

    public boolean hasDashboardPermission(DashboardPrincipal principal) {
        if (principal == null) {
            return false;
        }
        if (principal.console()) {
            return true;
        }
        return DashboardAuthorization.canAccessDashboard(check(principal));
    }

    public boolean hasPermission(DashboardPrincipal principal, PermissionDefinition permission) {
        return permission != null && hasPermission(principal, permission.node(), permission.fallbackLevel());
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

    public boolean canAccessPage(DashboardPrincipal principal, PermissionDefinition pagePermission, PermissionDefinition... legacyAlternatives) {
        if (principal == null) {
            return false;
        }
        if (principal.console()) {
            return true;
        }
        return DashboardAuthorization.canAccessPage(check(principal), pagePermission, legacyAlternatives);
    }

    public boolean canManagePermissions(DashboardPrincipal principal) {
        if (principal == null) {
            return false;
        }
        if (principal.console()) {
            return true;
        }
        return DashboardAuthorization.canManagePermissions(check(principal));
    }

    public boolean canViewConfigCategory(DashboardPrincipal principal, String category) {
        if (principal == null) {
            return false;
        }
        if (principal.console()) {
            return true;
        }
        return DashboardAuthorization.canViewConfigCategory(check(principal), category);
    }

    public boolean canEditConfigCategories(DashboardPrincipal principal, Set<String> categories) {
        if (principal == null) {
            return false;
        }
        if (principal.console()) {
            return true;
        }
        return DashboardAuthorization.canEditAllCategories(check(principal), categories);
    }

    public boolean canEditConfigCategory(DashboardPrincipal principal, String category) {
        if (principal == null) {
            return false;
        }
        if (principal.console()) {
            return true;
        }
        return DashboardAuthorization.canEditConfigCategory(check(principal), category);
    }

    public DashboardAuthorization.Capabilities capabilities(DashboardPrincipal principal) {
        if (principal != null && principal.console()) {
            return DashboardAuthorization.computeCapabilities(permission -> true);
        }
        return DashboardAuthorization.computeCapabilities(check(principal));
    }

    private DashboardAuthorization.PermissionCheck check(DashboardPrincipal principal) {
        return permission -> hasPermission(principal, permission);
    }

    public CompletableFuture<Object> overviewAsync() {
        return ServerThreadCalls.supply(services, this::captureOverviewRuntime)
                .thenApplyAsync(runtime -> {
                    StorageService.StorageStatus storage = services.getStorageService().status();
                    List<String> warnings = new ArrayList<>();
                    if (config.remoteAccessRequested()) {
                        warnings.add("Dashboard remote access is enabled or bound outside localhost.");
                    }
                    if (storage.fallbackActive()) {
                        warnings.add("Storage fallback is active: " + storage.fallbackReason());
                    }
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("version", ParadigmAPI.getModVersion());
                    data.put("minecraftVersion", runtime.minecraftVersion());
                    data.put("loader", runtime.loader());
                    data.put("onlinePlayers", runtime.onlinePlayers());
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
                    data.put("modules", Map.of("total", runtime.modules(), "enabled", runtime.enabledModules()));
                    data.put("recentActivity", auditService.recent(6));
                    data.put("warnings", warnings);
                    return (Object) data;
                }, executor);
    }

    public CompletableFuture<Object> serversAsync() {
        return ServerThreadCalls.supply(services, () -> heartbeatService.captureLocal(config, running()))
                .handle((local, failure) -> local)
                .thenApplyAsync(local -> (Object) Map.of(
                        "servers", heartbeatService.list(local),
                        "networkActive", services.getStorageService().isMysqlActive()), executor);
    }

    public boolean isManagedLocally(String category) {
        return services.getManagedConfigSyncService().isManaged(category);
    }

    public ConfigValidationResult upsertSelfManagedField(String category, String key, Object value) {
        StorageService storage = services.getStorageService();
        ConfigValidationResult result = new ConfigValidationResult();
        if (storage == null || !storage.isMysqlActive()) {
            result.reject(key, "Managed config storage is unavailable.");
            return result;
        }
        var identity = storage.context().serverIdentity();
        String networkId = identity != null ? identity.networkId() : "default";
        String selfServerId = identity != null ? identity.serverId() : "default";

        ConfigField field = schemaRegistry().snapshot().fields().stream()
                .filter(f -> f.key().equals(key)).findFirst().orElse(null);
        if (field == null || !field.category().equals(category) || !RemoteConfigEligibility.isRemoteEligible(field)) {
            result.reject(key, "Field is not centrally manageable.");
            return result;
        }
        Object validated;
        try {
            validated = RemoteConfigValidationService.validateFieldValue(field, value);
        } catch (IllegalArgumentException invalid) {
            result.reject(key, invalid.getMessage());
            return result;
        }

        String fingerprint = schemaRegistry().structuralFingerprint();
        ManagedConfigEntry current = storage.managedConfig().get(networkId, ServerScope.SERVER, selfServerId, category).orElse(null);
        Map<String, Object> merged = new LinkedHashMap<>(current != null ? current.data() : Map.of());
        merged.put(key, validated);
        long expected = current != null ? current.revision() : 0L;
        ManagedConfigUpsertResult upsertResult = storage.managedConfig().upsert(networkId, ServerScope.SERVER, selfServerId, category,
                merged, fingerprint, expected, null, null, selfServerId);
        if (upsertResult.ok()) {
            result.accept(key);
            return result;
        }
        result.reject(key, "Managed config changed concurrently; reload and try again.");
        return result;
    }

    public CompletableFuture<Object> remoteConfigSnapshotAsync(DashboardPrincipal principal, String serverId, String categoriesCsv) {
        return CompletableFuture.supplyAsync(() -> buildRemoteSnapshot(principal, serverId, categoriesCsv), executor);
    }

    private RemoteConfigSnapshot buildRemoteSnapshot(DashboardPrincipal principal, String rawServerId, String categoriesCsv) {
        StorageService storage = services.getStorageService();
        if (storage == null || !storage.isMysqlActive()) {
            throw new IllegalStateException("Remote server management requires shared MySQL storage.");
        }
        String serverId = safeText(rawServerId);
        if (serverId.isBlank()) throw new IllegalArgumentException("serverId is required.");
        var identity = storage.context().serverIdentity();
        String networkId = identity != null ? identity.networkId() : "default";

        ServerInstanceInfo instance = storage.servers().getServerInstance(serverId).orElse(null);
        long now = System.currentTimeMillis();
        boolean online = instance != null && instance.lastSeenMs() > 0 && now - instance.lastSeenMs() <= 90_000L;
        long lastSeenMs = instance != null ? instance.lastSeenMs() : 0L;

        ConfigSchemaRegistry registry = schemaRegistry();
        String hostFingerprint = registry.structuralFingerprint();
        String targetFingerprint = instance != null ? instance.schemaFingerprint() : null;
        boolean schemaCompatible = targetFingerprint != null && !targetFingerprint.isBlank() && targetFingerprint.equals(hostFingerprint);

        ConfigSnapshot snapshot = registry.snapshot();
        Set<String> requestedCategoryFilter = parseCategories(categoriesCsv);
        final Set<String> categoryFilter;
        if (!hasPermission(principal, ParadigmPermissions.NETWORK_MANAGE) && !hasPermission(principal, ParadigmPermissions.DASHBOARD_MANAGE)) {
            Set<String> viewable = snapshot.fields().stream()
                    .map(ConfigField::category)
                    .distinct()
                    .filter(RemoteConfigEligibility::isManagedCategory)
                    .filter(category -> canViewConfigCategory(principal, category))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            categoryFilter = requestedCategoryFilter == null ? viewable
                    : requestedCategoryFilter.stream().filter(viewable::contains)
                            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        } else {
            categoryFilter = requestedCategoryFilter;
        }

        Map<String, ManagedConfigEntry> globalBySection = new HashMap<>();
        Map<String, ManagedConfigEntry> serverBySection = new HashMap<>();
        for (ManagedConfigEntry entry : storage.managedConfig().listForNetwork(networkId)) {
            if (entry.scope() == ServerScope.GLOBAL) {
                globalBySection.put(entry.section(), entry);
            } else if (serverId.equals(entry.serverId())) {
                serverBySection.put(entry.section(), entry);
            }
        }
        Map<String, ManagedConfigAppliedState> appliedBySection = new HashMap<>();
        for (ManagedConfigAppliedState state : storage.managedConfig().listApplied(networkId, serverId)) {
            appliedBySection.put(state.section(), state);
        }

        List<RemoteConfigField> fields = new ArrayList<>();
        for (ConfigField field : snapshot.fields()) {
            if (!RemoteConfigEligibility.isManagedCategory(field.category())) continue;
            if (categoryFilter != null && !categoryFilter.contains(field.category())) continue;
            ManagedConfigEntry serverEntry = serverBySection.get(field.category());
            ManagedConfigEntry globalEntry = globalBySection.get(field.category());
            ManagedConfigAppliedState applied = appliedBySection.get(field.category());
            boolean hasServerValue = serverEntry != null && serverEntry.data().containsKey(field.key());
            boolean hasNetworkValue = globalEntry != null && globalEntry.data().containsKey(field.key());
            boolean hasBaselineValue = applied != null && applied.baseline() != null
                    && applied.baseline().containsKey(field.key());
            Object serverValue = hasServerValue ? serverEntry.data().get(field.key()) : null;
            Object networkValue = hasNetworkValue ? globalEntry.data().get(field.key()) : null;
            Object baselineValue = hasBaselineValue ? applied.baseline().get(field.key()) : null;
            if (serverEntry != null && serverEntry.data().containsKey(field.key())) {
                fields.add(RemoteConfigField.of(field, serverValue, true, "server",
                        networkValue, hasNetworkValue, serverValue, true, baselineValue, hasBaselineValue));
            } else if (globalEntry != null && globalEntry.data().containsKey(field.key())) {
                fields.add(RemoteConfigField.of(field, networkValue, true, "network",
                        networkValue, true, serverValue, false, baselineValue, hasBaselineValue));
            } else if (applied != null && applied.baseline() != null && applied.baseline().containsKey(field.key())) {
                fields.add(RemoteConfigField.of(field, baselineValue, true, "unmanaged",
                        networkValue, false, serverValue, false, baselineValue, true));
            } else {
                fields.add(RemoteConfigField.of(field, null, false, "unmanaged",
                        null, false, null, false, null, false));
            }
        }

        List<eu.avalanche7.paradigm.configs.schema.ConfigCategory> categories = snapshot.categories().stream()
                .filter(c -> RemoteConfigEligibility.isManagedCategory(c.id()))
                .filter(c -> categoryFilter == null || categoryFilter.contains(c.id()))
                .toList();

        Set<String> allEligibleCategories = snapshot.fields().stream()
                .map(ConfigField::category)
                .filter(RemoteConfigEligibility::isManagedCategory)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        List<RemoteConfigSnapshot.SectionStatus> sections = new ArrayList<>();
        for (String category : allEligibleCategories) {
            if (categoryFilter != null && !categoryFilter.contains(category)) continue;
            ManagedConfigEntry g = globalBySection.get(category);
            ManagedConfigEntry s = serverBySection.get(category);
            ManagedConfigAppliedState applied = appliedBySection.get(category);
            sections.add(new RemoteConfigSnapshot.SectionStatus(
                    category, g != null || s != null,
                    g != null ? g.revision() : 0L,
                    s != null ? s.revision() : 0L,
                    applied != null ? applied.appliedGlobalRevision() : 0L,
                    applied != null ? applied.appliedServerRevision() : 0L,
                    applied != null ? applied.appliedAtMs() : 0L,
                    applied != null ? applied.lastError() : ""
            ));
        }

        return new RemoteConfigSnapshot(serverId, networkId, online, lastSeenMs, schemaCompatible, categories, fields, sections);
    }

    public CompletableFuture<RemoteConfigPatchOutcome> remoteConfigPatchAsync(DashboardPrincipal actor, RemoteConfigPatchRequest request) {
        return CompletableFuture.supplyAsync(() -> applyRemotePatch(actor, request), executor);
    }

    public record RemoteConfigPatchOutcome(ConfigValidationResult result, long revision, boolean saved) {
    }

    private RemoteConfigPatchOutcome applyRemotePatch(DashboardPrincipal actor, RemoteConfigPatchRequest request) {
        StorageService storage = services.getStorageService();
        if (storage == null || !storage.isMysqlActive()) {
            throw new IllegalStateException("Remote server management requires shared MySQL storage.");
        }
        if (request == null) throw new IllegalArgumentException("Request body is required.");
        ServerScope scope = "NETWORK".equalsIgnoreCase(request.scope) ? ServerScope.GLOBAL : ServerScope.SERVER;
        String targetServerId = storageId(request.serverId, "serverId");
        String section = storageId(request.section, "section");
        if (!RemoteConfigEligibility.isManagedCategory(section)) {
            throw new IllegalArgumentException("Section cannot be centrally managed.");
        }

        var identity = storage.context().serverIdentity();
        String networkId = identity != null ? identity.networkId() : "default";
        String selfServerId = identity != null ? identity.serverId() : "default";

        String hostFingerprint = schemaRegistry().structuralFingerprint();
        String fingerprint = hostFingerprint;
        if (scope == ServerScope.SERVER) {
            ServerInstanceInfo instance = storage.servers().getServerInstance(targetServerId).orElse(null);
            String targetFingerprint = instance != null ? instance.schemaFingerprint() : null;
            if (targetFingerprint == null || targetFingerprint.isBlank() || !targetFingerprint.equals(hostFingerprint)) {
                ConfigValidationResult result = new ConfigValidationResult();
                result.reject("<schema>", "Target server schema is unknown or incompatible; remote editing is disabled until versions match.");
                return new RemoteConfigPatchOutcome(result, 0L, false);
            }
            fingerprint = targetFingerprint;
        }

        RemoteConfigValidationService validation = new RemoteConfigValidationService(schemaRegistry());
        var outcome = validation.validate(section, request.operations);
        ConfigValidationResult result = new ConfigValidationResult();
        for (var rejection : outcome.rejected()) {
            result.reject(rejection.key(), rejection.reason());
        }
        if (!outcome.ok()) {
            return new RemoteConfigPatchOutcome(result, 0L, false);
        }

        String effectiveServerId = scope == ServerScope.GLOBAL ? "" : targetServerId;
        ManagedConfigEntry current = storage.managedConfig().get(networkId, scope, effectiveServerId, section).orElse(null);
        long expected = current != null ? current.revision() : 0L;
        if (request.expectedRevision != expected) {
            result.reject("<revision>", "Managed config changed while the dashboard was open. Reload and try again.");
            return new RemoteConfigPatchOutcome(result, expected, false);
        }

        Map<String, Object> merged = new LinkedHashMap<>(current != null ? current.data() : Map.of());
        for (var op : outcome.accepted()) {
            merged.put(op.key(), op.value());
        }

        ManagedConfigUpsertResult upsertResult = storage.managedConfig().upsert(networkId, scope, effectiveServerId, section, merged,
                fingerprint, request.expectedRevision, actor != null ? actor.uuid() : null, actor != null ? actor.name() : null, selfServerId);
        if (!upsertResult.ok()) {
            result.reject("<revision>", "Managed config changed while the dashboard was open. Reload and try again.");
            return new RemoteConfigPatchOutcome(result, upsertResult.revision(), false);
        }
        for (var op : outcome.accepted()) {
            result.accept(op.key());
        }
        result.newRevision(String.valueOf(upsertResult.revision()));

        if (scope == ServerScope.GLOBAL || targetServerId.equals(selfServerId)) {
            services.getManagedConfigSyncService().triggerImmediateSync();
        }
        return new RemoteConfigPatchOutcome(result, upsertResult.revision(), true);
    }

    public CompletableFuture<Object> remoteConfigCopyAsync(DashboardPrincipal actor, RemoteConfigCopyRequest request) {
        return CompletableFuture.supplyAsync(() -> copyRemoteSection(actor, request), executor);
    }

    private Object copyRemoteSection(DashboardPrincipal actor, RemoteConfigCopyRequest request) {
        StorageService storage = services.getStorageService();
        if (storage == null || !storage.isMysqlActive()) {
            throw new IllegalStateException("Remote server management requires shared MySQL storage.");
        }
        if (request == null) throw new IllegalArgumentException("Request body is required.");
        String fromServerId = storageId(request.fromServerId, "fromServerId");
        String toServerId = storageId(request.toServerId, "toServerId");
        String section = storageId(request.section, "section");
        if (!RemoteConfigEligibility.isManagedCategory(section)) {
            throw new IllegalArgumentException("Section cannot be centrally managed.");
        }

        var identity = storage.context().serverIdentity();
        String networkId = identity != null ? identity.networkId() : "default";
        String selfServerId = identity != null ? identity.serverId() : "default";

        ManagedConfigEntry source = storage.managedConfig().get(networkId, ServerScope.SERVER, fromServerId, section).orElse(null);
        if (source == null) {
            throw new IllegalArgumentException("Source server has no SERVER-scope override for this section.");
        }
        ManagedConfigEntry target = storage.managedConfig().get(networkId, ServerScope.SERVER, toServerId, section).orElse(null);
        long expected = target != null ? target.revision() : 0L;

        ServerInstanceInfo targetInstance = storage.servers().getServerInstance(toServerId).orElse(null);
        String hostFingerprint = schemaRegistry().structuralFingerprint();
        String targetFingerprint = targetInstance != null ? targetInstance.schemaFingerprint() : null;
        if (targetFingerprint == null || targetFingerprint.isBlank() || !targetFingerprint.equals(hostFingerprint)) {
            throw new SchemaIncompatibleException("Target server schema is unknown or incompatible.");
        }

        List<ConfigPatchOperation> sourceOperations = source.data().entrySet().stream()
                .map(entry -> new ConfigPatchOperation(entry.getKey(), entry.getValue())).toList();
        var validated = new RemoteConfigValidationService(schemaRegistry()).validate(section, sourceOperations);
        if (!validated.ok()) {
            String reasons = validated.rejected().stream()
                    .map(error -> error.key() + ": " + error.reason())
                    .collect(java.util.stream.Collectors.joining("; "));
            throw new IllegalArgumentException("Source data is not valid against the current schema: " + reasons);
        }
        Map<String, Object> validatedData = new LinkedHashMap<>();
        validated.accepted().forEach(op -> validatedData.put(op.key(), op.value()));

        ManagedConfigUpsertResult result = storage.managedConfig().upsert(networkId, ServerScope.SERVER, toServerId, section, validatedData,
                targetFingerprint, expected, actor != null ? actor.uuid() : null, actor != null ? actor.name() : null, selfServerId);
        if (result.ok() && toServerId.equals(selfServerId)) {
            services.getManagedConfigSyncService().triggerImmediateSync();
        }
        return Map.of("ok", result.ok(), "revision", result.revision(), "reason", result.conflictReason() != null ? result.conflictReason() : "");
    }

    public CompletableFuture<Object> adoptSectionAsync(DashboardPrincipal actor, RemoteConfigAdoptRequest request) {
        return CompletableFuture.supplyAsync(() -> adoptSection(actor, request), executor);
    }

    private Object adoptSection(DashboardPrincipal actor, RemoteConfigAdoptRequest request) {
        StorageService storage = services.getStorageService();
        if (storage == null || !storage.isMysqlActive()) {
            throw new IllegalStateException("Remote server management requires shared MySQL storage.");
        }
        if (request == null) throw new IllegalArgumentException("Request body is required.");
        String section = storageId(request.section, "section");
        if (!RemoteConfigEligibility.isManagedCategory(section)) {
            throw new IllegalArgumentException("Section cannot be centrally managed.");
        }
        ServerScope scope = "NETWORK".equalsIgnoreCase(request.scope) ? ServerScope.GLOBAL : ServerScope.SERVER;
        var identity = storage.context().serverIdentity();
        String networkId = identity != null ? identity.networkId() : "default";
        String selfServerId = identity != null ? identity.serverId() : "default";
        String targetServerId = storageId(request.serverId, "serverId");

        String fingerprint = schemaRegistry().structuralFingerprint();

        if (scope == ServerScope.GLOBAL) {
            Map<String, Object> values = currentEligibleValues(section);
            ManagedConfigUpsertResult result = storage.managedConfig().upsert(networkId, ServerScope.GLOBAL, "", section, values,
                    fingerprint, 0L, actor != null ? actor.uuid() : null, actor != null ? actor.name() : null, selfServerId);
            if (result.ok()) services.getManagedConfigSyncService().triggerImmediateSync();
            return Map.of("ok", result.ok(), "revision", result.revision(), "reason", result.conflictReason() != null ? result.conflictReason() : "");
        }

        if (targetServerId.equals(selfServerId)) {
            ManagedConfigUpsertResult result = storage.managedConfig().upsert(networkId, ServerScope.SERVER, selfServerId, section, Map.of(),
                    fingerprint, 0L, actor != null ? actor.uuid() : null, actor != null ? actor.name() : null, selfServerId);
            if (result.ok()) {
                storage.managedConfig().upsertApplied(networkId, selfServerId, section, 0L, result.revision(), "", currentEligibleValues(section));
                services.getManagedConfigSyncService().triggerImmediateSync();
            }
            return Map.of("ok", result.ok(), "revision", result.revision(), "reason", result.conflictReason() != null ? result.conflictReason() : "");
        }

        storage.managedConfig().createAdoptionRequest(networkId, targetServerId, section,
                actor != null ? actor.uuid() : null, actor != null ? actor.name() : null);
        return Map.of("ok", true, "pending", true);
    }

    private Map<String, Object> currentEligibleValues(String section) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (ConfigField field : schemaRegistry().snapshot().fields()) {
            if (field.category().equals(section) && RemoteConfigEligibility.isRemoteEligible(field)) {
                values.put(field.key(), field.value() != null ? field.value().value() : null);
            }
        }
        return values;
    }

    private static Set<String> parseCategories(String csv) {
        if (csv == null || csv.isBlank()) return null;
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out.isEmpty() ? null : out;
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
            int trackCount = permissions.listPermissionTracks().size();
            int nodeCount = permissions.knownPermissionNodes().size();
            data.put("internalEnabled", permissions.isInternalPermissionsEnabled());
            data.put("externalCommandPermissions", permissions.isExternalCommandPermissionsEnabled());
            data.put("externalMode", permissions.isExternalCommandStrictMode() ? "strict" : "deny_only");
            data.put("groups", groupCount);
            data.put("users", userCount);
            data.put("tracks", trackCount);
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
        return onlinePlayersAsync().thenApplyAsync(onlinePlayers -> {
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
            for (OnlinePlayerSnapshot online : onlinePlayers) {
                mergeUser(discovered, online.uuid(), online.name(), System.currentTimeMillis(), true);
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
            return (Object) Map.of("users", page(rows, current, size), "total", rows.size(), "page", current, "pageSize", size);
        }, executor);
    }

    public CompletableFuture<Object> permissionUserAsync(String uuidOrName) {
        return resolveUuidAsync(uuidOrName).thenApplyAsync(uuid -> {
            if (uuid == null) {
                return (Object) Map.of("user", null);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("uuid", uuid.toString());
            data.put("info", services.getPermissionsHandler().getPlayerPermissionInfo(uuid));
            return (Object) Map.of("user", data);
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
        return resolveUuidAsync(uuidOrName).thenApplyAsync(uuid -> {
            if (uuid == null) return (Object) Map.of("entries", List.of(), "total", 0, "page", 1, "pageSize", clampPageSize(pageSize));
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
            return (Object) Map.of("entries", page(entries, current, size), "total", entries.size(), "page", current, "pageSize", size);
        }, executor);
    }

    public CompletableFuture<Object> permissionMutationAsync(DashboardPrincipal actor, PermissionMutationRequest mutation) {
        return CompletableFuture.supplyAsync(() -> permissionAdminService.mutate(actor, mutation), executor);
    }

    public CompletableFuture<Object> permissionTracksAsync() {
        return CompletableFuture.supplyAsync(() -> Map.of("tracks", services.getPermissionsHandler().listPermissionTracks()), executor);
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
        String q = safeText(uuidOrName);
        return resolveOnlinePlayerAsync(q).thenApplyAsync(online -> {
            var profile = services.getStorageService().players().listProfiles().stream()
                    .filter(item -> q.equalsIgnoreCase(item.uuid()) || q.equalsIgnoreCase(item.name())).findFirst().orElse(null);
            String uuid = profile != null ? profile.uuid() : online != null ? online.uuid() : validUuid(q) ? q : "";
            String name = profile != null ? profile.name() : online != null ? online.name() : q;
            List<Map<String, Object>> punishments = services.getPunishmentService().history(uuid, 1, 100).stream().map(this::punishmentDto).toList();
            return (Object) Map.of("player", Map.of("uuid", uuid, "name", name), "punishments", punishments, "warnings", List.of());
        }, executor);
    }

    public CompletableFuture<Object> moderationPunishmentAsync(String punishmentId) {
        return CompletableFuture.supplyAsync(() -> services.getPunishmentService().find(punishmentId)
                .map(this::punishmentDto).orElse(null), executor);
    }

    public CompletableFuture<Object> moderationActionAsync(DashboardPrincipal actor, ModerationActionRequest action) {
        String target = action != null ? action.player : null;
        return ServerThreadCalls.supply(services, () -> moderationService.snapshotPlayer(target))
                .exceptionally(failure -> null)
                .thenApplyAsync(online -> moderationService.apply(actor, action, online), executor)
                .thenApply(result -> (Object) result);
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
        if (!safeText(actor).isBlank()) base = auditService.actorAsync(actor, limitForAudit());
        else if (!safeText(type).isBlank()) base = auditService.typeAsync(type, limitForAudit());
        else base = CompletableFuture.completedFuture(auditService.recent(limitForAudit()));
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

    private static int limitForAudit() {
        return 500;
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

    private OverviewRuntime captureOverviewRuntime() {
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
        String minecraftVersion;
        String loader;
        int onlinePlayers;
        try {
            minecraftVersion = services.getPlatformAdapter().getMinecraftVersion();
        } catch (Throwable ignored) {
            minecraftVersion = "";
        }
        try {
            loader = normalizeLoaderName(services.getPlatformAdapter().getLoaderName());
        } catch (Throwable ignored) {
            loader = "Unavailable";
        }
        try {
            onlinePlayers = services.getPlatformAdapter().getOnlinePlayers().size();
        } catch (Throwable ignored) {
            onlinePlayers = 0;
        }
        return new OverviewRuntime(minecraftVersion, loader, onlinePlayers, modules, enabledModules);
    }

    private CompletableFuture<List<OnlinePlayerSnapshot>> onlinePlayersAsync() {
        return ServerThreadCalls.supply(services, () -> {
            List<OnlinePlayerSnapshot> players = new ArrayList<>();
            for (IPlayer player : services.getPlatformAdapter().getOnlinePlayers()) {
                if (player != null && player.getUUID() != null && !player.getUUID().isBlank()) {
                    players.add(new OnlinePlayerSnapshot(player.getUUID(), player.getName()));
                }
            }
            return List.copyOf(players);
        }).exceptionally(failure -> List.of());
    }

    private CompletableFuture<OnlinePlayerSnapshot> resolveOnlinePlayerAsync(String query) {
        String value = safeText(query);
        if (value.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return ServerThreadCalls.supply(services, () -> {
            IPlayer player = services.getPlatformAdapter().getPlayerByUuid(value);
            if (player == null) {
                player = services.getPlatformAdapter().getPlayerByName(value);
            }
            return player != null ? new OnlinePlayerSnapshot(player.getUUID(), player.getName()) : null;
        }).exceptionally(failure -> null);
    }

    private CompletableFuture<UUID> resolveUuidAsync(String uuidOrName) {
        String value = safeText(uuidOrName);
        if (value.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return CompletableFuture.completedFuture(UUID.fromString(value));
        } catch (Throwable ignored) {
        }
        return ServerThreadCalls.supply(services, () -> {
            IPlayer player = services.getPlatformAdapter().getPlayerByName(value);
            if (player == null || player.getUUID() == null) {
                return null;
            }
            try {
                return UUID.fromString(player.getUUID());
            } catch (Throwable ignored) {
                return null;
            }
        }).exceptionally(failure -> null);
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

    private static String safeText(String value) {
        return value != null ? value.trim() : "";
    }

    private record OverviewRuntime(String minecraftVersion, String loader, int onlinePlayers, int modules, int enabledModules) {
    }

    private record OnlinePlayerSnapshot(String uuid, String name) {
    }

    @Override
    public void close() {
        stop();
        executor.shutdownNow();
    }
}
