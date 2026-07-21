package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.*;
import eu.avalanche7.paradigm.modules.Announcements;
import eu.avalanche7.paradigm.modules.CommandManager;
import eu.avalanche7.paradigm.modules.Restart;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.migration.StorageMigrationOptions;
import eu.avalanche7.paradigm.utils.CommandToggleStore;
import eu.avalanche7.paradigm.modules.commands.permissions.PermissionCommands;
import eu.avalanche7.paradigm.modules.dashboard.LocalDashboardModule;
import eu.avalanche7.paradigm.modules.holograms.Holograms;
import eu.avalanche7.paradigm.modules.permissions.PermissionsHandler;
import eu.avalanche7.paradigm.modules.commands.moderation.PunishmentCommands;
import eu.avalanche7.paradigm.modules.tab.Tablist;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class Reload implements ParadigmModule {
    private static final Map<ParadigmModule, Boolean> LAST_HELP_STATE = new ConcurrentHashMap<>();

    @Override public String getName() { return "Reload"; }
    @Override public boolean isEnabled(Services services) { return true; }
    @Override public void onLoad(Object e, Services s, Object b) {}
    @Override public void onServerStarting(Object e, Services s) {}
    @Override public void onEnable(Services s) {}
    @Override public void onDisable(Services s) {}
    @Override public void onServerStopping(Object e, Services s) {}
    @Override public void registerEventListeners(Object bus, Services s) {}

    @Override
    public void registerCommands(Object dispatcher, Object registryAccess, Services services) {
        IPlatformAdapter platform = services.getPlatformAdapter();

        ICommandBuilder reload = platform.createCommandBuilder()
                .literal("reload")
                .requires(src -> hasReloadPermission(src, services))
                .then(platform.createCommandBuilder()
                        .argument("config", ICommandBuilder.ArgumentType.WORD)
                        .executes(ctx -> {
                            Map<ParadigmModule, Boolean> prevEnabled = new HashMap<>();
                            for (var m : ParadigmAPI.getModules()) {
                                try { prevEnabled.put(m, m.isEnabled(services)); } catch (Throwable ignored) { prevEnabled.put(m, false); }
                            }

                            String cfg = ctx.getStringArgument("config").toLowerCase(Locale.ROOT);
                            String msg;

                            switch (cfg) {
                                case "main" -> { MainConfigHandler.reload(); msg = "Main config reloaded."; }
                                case "announcements" -> { AnnouncementsConfigHandler.reload(); msg = "Announcements config reloaded."; }
                                case "chat" -> { ChatConfigHandler.reload(); msg = "Chat config reloaded."; }
                                case "motd" -> { MOTDConfigHandler.reload(); msg = "MOTD config reloaded."; }
                                case "mention" -> { MentionConfigHandler.reload(); msg = "Mention config reloaded."; }
                                case "restart" -> { RestartConfigHandler.reload(); msg = "Restart config reloaded."; }
                                case "moderation" -> { ModerationConfigHandler.reload(); services.getPunishmentService().refreshAsync(); msg = "Moderation config reloaded."; }
                                case "tablist" -> { TablistConfigHandler.reload(); msg = "Tablist config reloaded."; }
                                case "customcommands" -> {
                                    CommandManager.reloadCustomCommands(services);
                                    msg = "Custom commands config reloaded.";
                                }
                                case "all" -> {
                                    MainConfigHandler.reload();
                                    AnnouncementsConfigHandler.reload();
                                    ChatConfigHandler.reload();
                                    MOTDConfigHandler.reload();
                                    MentionConfigHandler.reload();
                                    RestartConfigHandler.reload();
                                    ModerationConfigHandler.reload();
                                    TablistConfigHandler.reload();
                                    CommandManager.reloadCustomCommands(services);
                                    msg = "All configs reloaded.";
                                }
                                default -> {
                                    platform.sendFailure(ctx.getSource(), platform.createLiteralComponent("§cUnknown config: " + cfg));
                                    return 0;
                                }
                            }

                            refreshModuleStates(services, prevEnabled);
                            if ("main".equals(cfg) || "all".equals(cfg)) {
                                services.getPermissionsHandler().refreshInternalPermissions();
                            }

                            if ("main".equals(cfg) || "announcements".equals(cfg) || "all".equals(cfg)) {
                                rescheduleAnnouncements();
                            }
                            if ("main".equals(cfg) || "restart".equals(cfg) || "all".equals(cfg)) {
                                rescheduleRestart(services);
                            }
                            if ("tablist".equals(cfg) || "all".equals(cfg)) {
                                Tablist tablist = Tablist.current();
                                if (tablist != null) tablist.reload();
                            }

                            platform.sendSuccess(ctx.getSource(), platform.createLiteralComponent("§a" + msg), true);
                            return 1;
                        }));

        ICommandBuilder paradigm = platform.createCommandBuilder()
                .literal("paradigm")
                .then(reload)
                .then(buildStorageBranch(platform, services))
                .then(buildCommandToggleBranch(platform, services));

        LocalDashboardModule dashboard = LocalDashboardModule.current();
        if (dashboard != null) {
            paradigm = paradigm
                    .then(dashboard.buildCommandBranch(platform, services))
                    .then(dashboard.buildAuditCommandBranch(platform, services));
        }

        Tablist tablist = Tablist.current();
        if (tablist != null) {
            paradigm = paradigm.then(tablist.buildCommandBranch(platform, services));
        }

        Holograms holograms = Holograms.current();
        if (holograms != null) {
            paradigm = paradigm.then(holograms.buildCommandBranch());
        }

        if (services.getPermissionsHandler().isInternalPermissionsEnabled()) {
            paradigm = PermissionCommands.register(paradigm, platform, services);
        }
        paradigm = PunishmentCommands.register(paradigm, platform, services);

        platform.registerCommand(paradigm);
    }

    private void rescheduleAnnouncements() {
        for (var m : ParadigmAPI.getModules()) {
            if (m instanceof Announcements) {
                ((Announcements) m).rescheduleAnnouncements();
            }
        }
    }

    private void rescheduleRestart(Services services) {
        for (var m : ParadigmAPI.getModules()) {
            if (m instanceof Restart) {
                ((Restart) m).rescheduleNextRestart(services);
            }
        }
    }

    public static void refreshModuleStatesForHelp(Services services) {
        refreshModuleStates(services, LAST_HELP_STATE);
    }

    public static void refreshModuleStates(Services services, Map<ParadigmModule, Boolean> prevEnabled) {
        for (var m : ParadigmAPI.getModules()) {
            try {
                boolean before = prevEnabled.getOrDefault(m, m.isEnabled(services));
                boolean after;
                try { after = m.isEnabled(services); } catch (Throwable t) { after = false; }

                if (before && !after) {
                    m.onDisable(services);
                } else if (!before && after) {
                    m.onEnable(services);
                }
                prevEnabled.put(m, after);
            } catch (Throwable t) {
                if (services != null && services.getLogger() != null) {
                    services.getLogger().warn("Failed to refresh module {}: {}", m.getName(), t.toString());
                }
            }
        }
    }

    private ICommandBuilder buildCommandToggleBranch(IPlatformAdapter platform, Services services) {
        CommandToggleStore toggles = services.getCommandToggleStore();

        ICommandBuilder root = platform.createCommandBuilder()
                .literal("command")
                .requires(src -> hasTogglePermission(src, services))
                .executes(ctx -> {
                    sendCommandTogglePanel(ctx.getSource(), services, "");
                    return 1;
                });

        ICommandBuilder list = platform.createCommandBuilder()
                .literal("list")
                .executes(ctx -> {
                    sendCommandTogglePanel(ctx.getSource(), services, "");
                    return 1;
                });

        ICommandBuilder search = platform.createCommandBuilder()
                .literal("search")
                .then(platform.createCommandBuilder()
                        .argument("query", ICommandBuilder.ArgumentType.WORD)
                        .executes(ctx -> {
                            sendCommandTogglePanel(ctx.getSource(), services, ctx.getStringArgument("query"));
                            return 1;
                        }));

        ICommandBuilder status = platform.createCommandBuilder()
                .literal("status")
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((ctx, input) -> toggles.knownCommandIds())
                        .executes(ctx -> {
                            String query = ctx.getStringArgument("name");
                            String canonical = toggles.resolveCanonical(query);
                            if (canonical == null) {
                                sendToggleMessage(ctx.getSource(), services, "command_toggle.unknown", "Unknown command key: {command}", "{command}", query);
                                return 0;
                            }
                            boolean enabled = toggles.isEnabled(canonical);
                            sendToggleMessage(ctx.getSource(), services, "command_toggle.status", "{command} is currently {state}.",
                                    "{command}", canonical,
                                    "{state}", enabled ? "enabled" : "disabled");
                            return 1;
                        }));

        ICommandBuilder enable = platform.createCommandBuilder()
                .literal("enable")
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((ctx, input) -> toggles.knownCommandIds())
                        .executes(ctx -> setCommandState(ctx.getSource(), services, ctx.getStringArgument("name"), true)));

        ICommandBuilder disable = platform.createCommandBuilder()
                .literal("disable")
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((ctx, input) -> toggles.knownCommandIds())
                        .executes(ctx -> setCommandState(ctx.getSource(), services, ctx.getStringArgument("name"), false)));

        ICommandBuilder reload = platform.createCommandBuilder()
                .literal("reload")
                .executes(ctx -> {
                    toggles.reload();
                    platform.refreshAllPlayerCommandTrees();
                    sendToggleMessage(ctx.getSource(), services, "command_toggle.reloaded", "Command toggles reloaded from commands.json.");
                    sendCommandTogglePanel(ctx.getSource(), services, "");
                    return 1;
                });

        return root.then(list).then(search).then(status).then(enable).then(disable).then(reload);
    }

    private int setCommandState(ICommandSource source, Services services, String commandName, boolean enabled) {
        CommandToggleStore.ToggleResult result = services.getCommandToggleStore().setEnabled(commandName, enabled);
        if (!result.ok()) {
            if ("protected".equals(result.reason())) {
                sendToggleMessage(source, services, "command_toggle.protected", "Command {command} is protected and cannot be disabled.", "{command}", result.canonicalId());
                return 0;
            }
            sendToggleMessage(source, services, "command_toggle.unknown", "Unknown command key: {command}", "{command}", commandName);
            return 0;
        }

        sendToggleMessage(source, services,
                enabled ? "command_toggle.enabled" : "command_toggle.disabled",
                enabled ? "Enabled command {command}." : "Disabled command {command}.",
                "{command}", result.canonicalId());
        services.getPlatformAdapter().refreshAllPlayerCommandTrees();
        sendCommandTogglePanel(source, services, "");
        return 1;
    }

    private void sendToggleList(ICommandSource source, Services services) {
        sendCommandTogglePanel(source, services, "");
    }

    private void sendCommandTogglePanel(ICommandSource source, Services services, String query) {
        Map<String, Boolean> states = services.getCommandToggleStore().listStates();
        String normalizedQuery = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
        int enabled = 0;
        int disabled = 0;
        for (Map.Entry<String, Boolean> entry : states.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                enabled++;
            } else {
                disabled++;
            }
        }

        sendUiLine(source, services, header(services, "Commands"));
        sendUiLine(source, services, row(services)
                .append(text(services, "enabled: " + enabled, "34D399"))
                .append(space(services))
                .append(text(services, "disabled: " + disabled, "F87171"))
                .append(space(services))
                .append(button(services, "[reload]", "/paradigm command reload", true, "Reload commands.json", "FBBF24"))
                .append(space(services))
                .append(button(services, "[search]", "/paradigm command search ", false, "Prepare command search", "60A5FA")));

        int shown = 0;
        for (Map.Entry<String, Boolean> entry : states.entrySet()) {
            String command = entry.getKey();
            if (!normalizedQuery.isEmpty() && !command.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                continue;
            }
            shown++;
            boolean isEnabled = Boolean.TRUE.equals(entry.getValue());
            boolean locked = services.getCommandToggleStore().isProtected(command);
            IComponent line = row(services)
                    .append(text(services, "- " + command, "E5E7EB"))
                    .append(space(services))
                    .append(text(services, isEnabled ? "on" : "off", isEnabled ? "34D399" : "F87171"))
                    .append(space(services));
            if (locked) {
                line.append(text(services, "[locked]", "94A3B8"));
            } else if (isEnabled) {
                line.append(button(services, "[disable]", "/paradigm command disable " + command, true, "Disable this command", "F87171"));
            } else {
                line.append(button(services, "[enable]", "/paradigm command enable " + command, true, "Enable this command", "34D399"));
            }
            line.append(space(services))
                    .append(button(services, "[status]", "/paradigm command status " + command, true, "Show command status", "60A5FA"));
            sendUiLine(source, services, line);
        }

        if (shown == 0) {
            sendUiLine(source, services, text(services, "No commands matched.", "FBBF24"));
        }
    }

    private boolean hasReloadPermission(ICommandSource src, Services services) {
        if (src == null) return false;
        if (src.isConsole()) return true;
        if (src.hasPermissionLevel(2)) return true;
        IPlayer player = src.getPlayer();
        return player != null && services.getPermissionsHandler().hasPermission(
                player,
                PermissionsHandler.RELOAD_PERMISSION,
                PermissionsHandler.RELOAD_PERMISSION_LEVEL
        );
    }

    private boolean hasTogglePermission(ICommandSource src, Services services) {
        if (src == null) return false;
        if (src.isConsole()) return true;
        if (src.hasPermissionLevel(2)) return true;
        IPlayer player = src.getPlayer();
        return player != null && services.getPermissionsHandler().hasPermission(
                player,
                PermissionsHandler.COMMAND_TOGGLE_PERMISSION,
                PermissionsHandler.COMMAND_TOGGLE_PERMISSION_LEVEL
        );
    }

    private boolean hasStoragePermission(ICommandSource src, Services services) {
        if (src == null) return false;
        if (src.isConsole()) return true;
        if (src.hasPermissionLevel(2)) return true;
        IPlayer player = src.getPlayer();
        return player != null && services.getPermissionsHandler().hasPermission(
                player,
                PermissionsHandler.STORAGE_MANAGE_PERMISSION,
                PermissionsHandler.STORAGE_MANAGE_PERMISSION_LEVEL
        );
    }

    private ICommandBuilder buildStorageBranch(IPlatformAdapter platform, Services services) {
        return platform.createCommandBuilder()
                .literal("storage")
                .requires(src -> hasStoragePermission(src, services))
                .executes(ctx -> {
                    sendStorageStatus(ctx.getSource(), services);
                    return 1;
                })
                .then(platform.createCommandBuilder()
                        .literal("status")
                        .executes(ctx -> {
                            sendStorageStatus(ctx.getSource(), services);
                            return 1;
                        }))
                .then(platform.createCommandBuilder()
                        .literal("test")
                        .executes(ctx -> {
                            runStorageTest(ctx.getSource(), services);
                            return 1;
                        }))
                .then(platform.createCommandBuilder()
                        .literal("migrate")
                        .then(platform.createCommandBuilder()
                                .literal("json")
                                .then(buildStorageMigrationTarget(platform, services, "json", "sql")))
                        .then(platform.createCommandBuilder()
                                .literal("sql")
                                .then(buildStorageMigrationTarget(platform, services, "sql", "json"))));
    }

    private ICommandBuilder buildStorageMigrationTarget(IPlatformAdapter platform, Services services, String from, String to) {
        ICommandBuilder target = platform.createCommandBuilder()
                .literal(to)
                .executes(ctx -> {
                    runStorageMigration(ctx.getSource(), services, from, to, StorageMigrationOptions.defaults());
                    return 1;
                });

        target.then(buildStorageMigrationPolicy(platform, services, from, to, "overwrite", false));
        target.then(buildStorageMigrationPolicy(platform, services, from, to, "skip", false));
        target.then(buildStorageMigrationPolicy(platform, services, from, to, "fail", false));
        target.then(platform.createCommandBuilder()
                .literal("dry-run")
                .executes(ctx -> {
                    runStorageMigration(ctx.getSource(), services, from, to,
                            new StorageMigrationOptions(true, StorageMigrationOptions.ConflictPolicy.OVERWRITE, false));
                    return 1;
                })
                .then(buildStorageMigrationPolicy(platform, services, from, to, "overwrite", true))
                .then(buildStorageMigrationPolicy(platform, services, from, to, "skip", true))
                .then(buildStorageMigrationPolicy(platform, services, from, to, "fail", true)));
        return target;
    }

    private ICommandBuilder buildStorageMigrationPolicy(IPlatformAdapter platform, Services services, String from, String to, String policy, boolean dryRun) {
        return platform.createCommandBuilder()
                .literal(policy)
                .executes(ctx -> {
                    StorageMigrationOptions.ConflictPolicy conflictPolicy = StorageMigrationOptions.ConflictPolicy.parse(policy);
                    runStorageMigration(ctx.getSource(), services, from, to,
                            new StorageMigrationOptions(dryRun, conflictPolicy, !dryRun));
                    return 1;
                });
    }

    private void sendStorageStatus(ICommandSource source, Services services) {
        StorageService storage = services.getStorageService();
        if (storage == null) {
            sendStorageLine(source, services, "storage.error.provider_unavailable", "Storage service is unavailable.");
            return;
        }
        StorageService.StorageStatus status = storage.status();
        sendStorageLine(source, services, "storage.status.provider", "Data provider: configured={selected}, active={active}.",
                "{selected}", status.configuredDataProvider(), "{active}", status.activeDataProvider());
        sendStorageLine(source, services, "storage.status.identity", "Server identity: networkId={networkId}, serverId={serverId}, serverName={serverName}.",
                "{networkId}", status.serverIdentity().networkId(),
                "{serverId}", status.serverIdentity().serverId(),
                "{serverName}", status.serverIdentity().serverName());
        sendStorageLine(source, services, "storage.status.target", "Data target: {target}.", "{target}", status.target());
        sendStorageLine(source, services, "storage.status.data_location", "Data location: {path}.", "{path}", status.dataLocation());
        sendStorageLine(source, services, "storage.status.dependency_mode", "Dependency mode: {mode}.", "{mode}", runtimeLibraryMode(services, status.dependencyMode()));
        sendStorageLine(source, services, "storage.libs.cache.path", "Runtime library cache: {path}.", "{path}", status.runtimeLibraryCachePath());
        sendStorageLine(source, services, "storage.status.sqlite_driver", "SQLite driver: {state}.", "{state}", libraryStateText(services, status.sqliteDriverState()));
        sendStorageLine(source, services, "storage.status.mysql_driver", "MySQL/MariaDB driver: {state}.", "{state}", libraryStateText(services, status.mysqlDriverState()));
        sendStorageLine(source, services, "storage.status.migration_version", "Migration version: {version}.", "{version}", String.valueOf(status.migrationVersion()));
        sendStorageLine(source, services, "storage.status.repositories", "Repositories: {state}.",
                "{state}", storageState(services, status.repositoriesAvailable()));
        if (!"json".equalsIgnoreCase(status.activeProvider())) {
            sendStorageLine(source, services, "storage.status.sql_registered", "SQL server registered: {state}.",
                    "{state}", storageState(services, status.serverRegistered()));
        }
        if (status.fallbackActive()) {
            sendStorageLine(source, services, "storage.status.fallback", "Fallback: active, reason={reason}.", "{reason}", status.fallbackReason());
        }
        if (status.fallbackActive()) {
            sendStorageLine(source, services, "storage.status.fallback_warning_active",
                    "Configured data provider is {configured}, but active data provider is JSON fallback. New runtime data is being written to fallback JSON files.",
                    "{configured}", status.configuredDataProvider());
        } else if (status.fallbackDataPresent() && !"json".equalsIgnoreCase(status.activeProvider())) {
            sendStorageLine(source, services, "storage.status.fallback_warning_previous",
                    "Previous JSON fallback data may exist. Run an explicit migration/sync before assuming SQL contains every fallback write.");
        }
        if ("json".equalsIgnoreCase(status.configuredDataProvider()) && "json".equalsIgnoreCase(status.activeProvider())) {
            sendStorageLine(source, services, "storage.status.migration_recommendation_json",
                    "SQLite is recommended for new installs. Existing JSON installs are preserved; run a dry-run migration before switching.");
        } else if (status.fallbackActive()) {
            sendStorageLine(source, services, "storage.status.migration_recommendation_fallback",
                    "Fix the configured SQL/SQLite provider, then run an explicit migration dry-run if fallback JSON received new data.");
        }
        if (status.lastTestResult() != null) {
            sendStorageLine(source, services, "storage.status.last_test", "Last test: {state} - {message}.",
                    "{state}", storageResult(services, status.lastTestResult().success()),
                    "{message}", status.lastTestResult().message());
        }
    }

    private void runStorageTest(ICommandSource source, Services services) {
        StorageService storage = services.getStorageService();
        if (storage == null) {
            sendStorageLine(source, services, "storage.error.provider_unavailable", "Storage service is unavailable.");
            return;
        }
        sendStorageLine(source, services, "storage.test.started", "Storage test started for provider {provider}.", "{provider}", storage.status().activeProvider());
        storage.testAsync().whenComplete((result, throwable) -> services.getTaskScheduler().schedule(() -> {
            if (throwable != null) {
                sendStorageLine(source, services, "storage.test.failed", "Storage test failed: {message}.", "{message}", throwable.getMessage());
                return;
            }
            sendStorageLine(source, services, "storage.test.provider", "Storage test provider: {provider}.", "{provider}", result.provider());
            sendStorageLine(source, services, "storage.test.config_valid", "Config valid: {state}.", "{state}", storageState(services, result.configValid()));
            sendStorageLine(source, services, "storage.test.result", "Connection/result: {state}.", "{state}", storageResult(services, result.success()));
            sendStorageLine(source, services, "storage.status.sqlite_driver", "SQLite driver: {state}.", "{state}", libraryStateText(services, result.sqliteDriverState()));
            sendStorageLine(source, services, "storage.status.mysql_driver", "MySQL/MariaDB driver: {state}.", "{state}", libraryStateText(services, result.mysqlDriverState()));
            sendStorageLine(source, services, "storage.test.migration_version", "Migration version: {version}.", "{version}", String.valueOf(result.migrationVersion()));
            sendStorageLine(source, services, "storage.test.message", "{message}", "{message}", result.message());
        }, 0L, TimeUnit.MILLISECONDS));
    }

    private void runStorageMigration(ICommandSource source, Services services, String from, String to, StorageMigrationOptions options) {
        StorageService storage = services.getStorageService();
        if (storage == null) {
            sendStorageLine(source, services, "storage.error.provider_unavailable", "Storage service is unavailable.");
            return;
        }
        StorageMigrationOptions effectiveOptions = options != null ? options : StorageMigrationOptions.defaults();
        sendStorageLine(source, services, "storage.migrate.started", "Storage migration started: {from} -> {to}.", "{from}", from, "{to}", to);
        sendStorageLine(source, services, "storage.migrate.options", "Mode={mode}, conflict={conflict}.",
                "{mode}", effectiveOptions.dryRun() ? "dry-run" : "write",
                "{conflict}", effectiveOptions.conflictPolicy().configValue());
        storage.migrateAsync(from, to, effectiveOptions).whenComplete((summary, throwable) -> services.getTaskScheduler().schedule(() -> {
            if (throwable != null) {
                sendStorageLine(source, services, "storage.migrate.failed", "Storage migration failed: {message}.", "{message}", throwable.getMessage());
                return;
            }
            sendStorageLine(source, services, "storage.migrate.source_target", "Migration source={source}, target={target}.",
                    "{source}", summary.sourceProvider(), "{target}", summary.targetProvider());
            sendStorageLine(source, services, "storage.migrate.scope", "Scope: networkId={networkId}, serverId={serverId}.",
                    "{networkId}", summary.networkId(), "{serverId}", summary.serverId());
            sendStorageLine(source, services, "storage.migrate.mode", "Mode={mode}, conflict={conflict}.",
                    "{mode}", summary.dryRun() ? "dry-run" : "write",
                    "{conflict}", summary.conflictPolicy());
            if (summary.jsonBackupPath() != null && !summary.jsonBackupPath().isBlank()) {
                sendStorageLine(source, services, "storage.migrate.backup", "JSON backup: {path}.", "{path}", summary.jsonBackupPath());
            }
            sendStorageLine(source, services, "storage.migrate.count_players", "Players migrated: {count}.", "{count}", String.valueOf(summary.players()));
            sendStorageLine(source, services, "storage.migrate.count_homes", "Homes migrated: {count}.", "{count}", String.valueOf(summary.homes()));
            sendStorageLine(source, services, "storage.migrate.count_warps", "Warps migrated: {count}.", "{count}", String.valueOf(summary.warps()));
            sendStorageLine(source, services, "storage.migrate.count_moderation", "Moderation records migrated: {count}.", "{count}", String.valueOf(summary.moderationRecords()));
            sendStorageLine(source, services, "storage.migrate.count_permissions", "Permission groups/users migrated: {groups}/{users}.",
                    "{groups}", String.valueOf(summary.permissionGroups()), "{users}", String.valueOf(summary.permissionUsers()));
            sendStorageLine(source, services, "storage.migrate.count_admin", "Admin states migrated: {count}.", "{count}", String.valueOf(summary.adminStates()));
            sendStorageLine(source, services, "storage.migrate.conflicts", "Conflicts: {count}.", "{count}", String.valueOf(summary.conflicts()));
            sendStorageLine(source, services, "storage.migrate.failures_skipped", "Failures/skipped: {failures}/{skipped}.",
                    "{failures}", String.valueOf(summary.failures()), "{skipped}", String.valueOf(summary.skipped()));
            sendStorageLine(source, services, "storage.migrate.final_status", "Final status: {state}.", "{state}", storageResult(services, summary.success()));
            summary.messages().stream().limit(5).forEach(message -> sendStorageLine(source, services, "storage.migrate.message", "{message}", "{message}", message));
            if (summary.messages().size() > 5) {
                sendStorageLine(source, services, "storage.migrate.additional_messages", "Additional migration messages: {count}.", "{count}", String.valueOf(summary.messages().size() - 5));
            }
        }, 0L, TimeUnit.MILLISECONDS));
    }

    private void sendStorageLine(ICommandSource source, Services services, String key, String fallback, String... placeholders) {
        String message = rawText(services, key, fallback, placeholders);
        IComponent component = services.getPlatformAdapter().createLiteralComponent("§b[Storage] §f" + message);
        services.getPlatformAdapter().sendSuccess(source, component, false);
    }

    private String storageState(Services services, boolean state) {
        return rawText(services, state ? "storage.common.available" : "storage.common.unavailable", state ? "available" : "unavailable");
    }

    private String storageResult(Services services, boolean state) {
        return rawText(services, state ? "storage.common.success" : "storage.common.failed", state ? "success" : "failed");
    }

    private String libraryStateText(Services services, String state) {
        String normalized = state != null && !state.isBlank() ? state : "missing";
        return rawText(services, "storage.libs.status." + normalized, normalized);
    }

    private String runtimeLibraryMode(Services services, String mode) {
        if ("runtime-download".equals(mode)) {
            return rawText(services, "storage.libs.mode.runtime_download", "runtime-download");
        }
        return mode != null && !mode.isBlank() ? mode : "-";
    }

    private String rawText(Services services, String key, String fallback, String... placeholders) {
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        return raw;
    }

    private void sendToggleMessage(ICommandSource source, Services services, String key, String fallback, String... placeholders) {
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }

        IComponent message = services.getMessageParser().parseMessage(
                "<color:#60A5FA><bold>[Command]</bold></color> <color:#E5E7EB>" + raw + "</color>",
                source != null ? source.getPlayer() : null
        );
        services.getPlatformAdapter().sendSuccess(source, message, false);
    }

    private void sendUiLine(ICommandSource source, Services services, IComponent component) {
        services.getPlatformAdapter().sendSuccess(source, component, false);
    }

    private IComponent header(Services services, String title) {
        return services.getPlatformAdapter().createEmptyComponent()
                .append(text(services, "---- ", "475569"))
                .append(text(services, "[P] ", "A78BFA").withFormatting("bold"))
                .append(text(services, title, "F8FAFC").withFormatting("bold"));
    }

    private IComponent row(Services services) {
        return services.getPlatformAdapter().createEmptyComponent();
    }

    private IComponent space(Services services) {
        return services.getPlatformAdapter().createComponentFromLiteral(" ");
    }

    private IComponent text(Services services, String value, String color) {
        return services.getPlatformAdapter().createComponentFromLiteral(value != null ? value : "").withColorHex(color);
    }

    private IComponent button(Services services, String label, String command, boolean run, String hover) {
        return button(services, label, command, run, hover, run ? "60A5FA" : "FBBF24");
    }

    private IComponent button(Services services, String label, String command, boolean run, String hover, String color) {
        IComponent component = services.getPlatformAdapter()
                .createComponentFromLiteral(label)
                .withColorHex(color)
                .withFormatting("bold")
                .onHoverText(hover != null ? hover : command);
        return run ? component.onClickRunCommand(command) : component.onClickSuggestCommand(command);
    }

}
