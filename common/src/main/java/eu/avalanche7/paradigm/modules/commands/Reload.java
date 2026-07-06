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
import eu.avalanche7.paradigm.utils.PermissionAPI.PermissionAPI;
import eu.avalanche7.paradigm.utils.PermissionNodeRegistry;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
                                    CommandManager.reloadCustomCommands(services);
                                    msg = "All configs reloaded.";
                                }
                                default -> {
                                    platform.sendFailure(ctx.getSource(), platform.createLiteralComponent("§cUnknown config: " + cfg));
                                    return 0;
                                }
                            }

                            refreshModuleStates(services, prevEnabled);

                            if ("main".equals(cfg) || "announcements".equals(cfg) || "all".equals(cfg)) {
                                rescheduleAnnouncements();
                            }
                            if ("main".equals(cfg) || "restart".equals(cfg) || "all".equals(cfg)) {
                                rescheduleRestart(services);
                            }

                            platform.sendSuccess(ctx.getSource(), platform.createLiteralComponent("§a" + msg), true);
                            return 1;
                        }));

        ICommandBuilder paradigm = platform.createCommandBuilder()
                .literal("paradigm")
                .then(reload)
                .then(buildStorageBranch(platform, services))
                .then(buildCommandToggleBranch(platform, services));

        if (services.getPermissionsHandler().isInternalPermissionsEnabled()) {
            paradigm = paradigm
                    .then(buildPermissionHomeBranch(platform, services))
                    .then(buildPermissionCheckBranch(platform, services))
                    .then(buildGroupBranch(platform, services));
        }

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
        sendStorageLine(source, services, "storage.status.provider", "Storage provider: selected={selected}, active={active}.",
                "{selected}", status.selectedProvider(), "{active}", status.activeProvider());
        sendStorageLine(source, services, "storage.status.identity", "Server identity: networkId={networkId}, serverId={serverId}, serverName={serverName}.",
                "{networkId}", status.serverIdentity().networkId(),
                "{serverId}", status.serverIdentity().serverId(),
                "{serverName}", status.serverIdentity().serverName());
        sendStorageLine(source, services, "storage.status.target", "Target: {target}.", "{target}", status.target());
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

    private ICommandBuilder buildPermissionHomeBranch(IPlatformAdapter platform, Services services) {
        return platform.createCommandBuilder()
                .literal("perms")
                .requires(src -> hasGroupManagePermission(src, services))
                .executes(ctx -> {
                    sendPermissionsHome(ctx.getSource(), services);
                    return 1;
                });
    }

    private ICommandBuilder buildPermissionCheckBranch(IPlatformAdapter platform, Services services) {
        return platform.createCommandBuilder()
                .literal("permission")
                .requires(src -> hasGroupManagePermission(src, services))
                .then(platform.createCommandBuilder().literal("check").then(buildPermissionCheckArgs(platform, services)))
                .then(platform.createCommandBuilder().literal("explain").then(buildPermissionCheckArgs(platform, services)))
                .then(buildPermissionNodesBranch(platform, services));
    }

    private ICommandBuilder buildPermissionCheckArgs(IPlatformAdapter platform, Services services) {
        return platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.WORD)
                .then(platform.createCommandBuilder()
                        .argument("permission", ICommandBuilder.ArgumentType.STRING)
                        .suggests((ctx, input) -> permissionSuggestions(services, input))
                        .executes(ctx -> explainPermission(ctx.getSource(), services, ctx.getStringArgument("player"), ctx.getStringArgument("permission"))));
    }

    private ICommandBuilder buildPermissionNodesBranch(IPlatformAdapter platform, Services services) {
        return platform.createCommandBuilder()
                .literal("nodes")
                .executes(ctx -> {
                    sendPermissionNodeList(ctx.getSource(), services, "");
                    return 1;
                })
                .then(platform.createCommandBuilder()
                        .argument("query", ICommandBuilder.ArgumentType.STRING)
                        .suggests((ctx, input) -> permissionSuggestions(services, input))
                        .executes(ctx -> {
                            sendPermissionNodeList(ctx.getSource(), services, ctx.getStringArgument("query"));
                            return 1;
                        }));
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

    private ICommandBuilder buildGroupBranch(IPlatformAdapter platform, Services services) {
        ICommandBuilder root = platform.createCommandBuilder()
                .literal("group")
                .requires(src -> hasGroupManagePermission(src, services))
                .executes(ctx -> {
                    sendGroupListPanel(ctx.getSource(), services);
                    return 1;
                });

        ICommandBuilder list = platform.createCommandBuilder()
                .literal("list")
                .executes(ctx -> {
                    sendGroupListPanel(ctx.getSource(), services);
                    return 1;
                });

        ICommandBuilder add = platform.createCommandBuilder()
                .literal("add")
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .executes(ctx -> {
                            String name = ctx.getStringArgument("name");
                            if (!services.getPermissionsHandler().createPermissionGroup(name)) {
                                sendGroupMessage(ctx.getSource(), services, "group.manage.add_fail", "Could not create group {group}.", "{group}", name);
                                return 0;
                            }
                            sendGroupMessage(ctx.getSource(), services, "group.manage.add_ok", "Created group {group}.", "{group}", name);
                            return 1;
                        }));

        ICommandBuilder remove = platform.createCommandBuilder()
                .literal("remove")
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .executes(ctx -> {
                            String name = ctx.getStringArgument("name");
                            if (!services.getPermissionsHandler().deletePermissionGroup(name)) {
                                sendGroupMessage(ctx.getSource(), services, "group.manage.remove_fail", "Could not remove group {group}.", "{group}", name);
                                return 0;
                            }
                            sendGroupMessage(ctx.getSource(), services, "group.manage.remove_ok", "Removed group {group}.", "{group}", name);
                            return 1;
                        }));

        ICommandBuilder info = platform.createCommandBuilder()
                .literal("info")
                .then(platform.createCommandBuilder()
                        .argument("name", ICommandBuilder.ArgumentType.WORD)
                        .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .executes(ctx -> {
                            String name = ctx.getStringArgument("name");
                            if (services.getPermissionsHandler().getPermissionGroupInfo(name) == null) {
                                sendGroupMessage(ctx.getSource(), services, "group.manage.not_found", "Group {group} was not found.", "{group}", name);
                                return 0;
                            }
                            sendGroupInfoPanel(ctx.getSource(), services, name);
                            return 1;
                        }));

        ICommandBuilder parentAddCommand = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                .then(platform.createCommandBuilder()
                        .argument("parent", ICommandBuilder.ArgumentType.WORD)
                        .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .executes(ctx -> {
                            String group = ctx.getStringArgument("group");
                            String parentName = ctx.getStringArgument("parent");
                            if (!services.getPermissionsHandler().addPermissionGroupParent(group, parentName)) {
                                sendGroupMessage(ctx.getSource(), services, "group.manage.parent_add_fail", "Could not add parent {parent} to {group}.", "{parent}", parentName, "{group}", group);
                                return 0;
                            }
                            sendGroupMessage(ctx.getSource(), services, "group.manage.parent_add_ok", "Added parent {parent} to {group}.", "{parent}", parentName, "{group}", group);
                            return 1;
                        }));

        ICommandBuilder parentRemoveCommand = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                .then(platform.createCommandBuilder()
                        .argument("parent", ICommandBuilder.ArgumentType.WORD)
                        .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .executes(ctx -> {
                            String group = ctx.getStringArgument("group");
                            String parentName = ctx.getStringArgument("parent");
                            if (!services.getPermissionsHandler().removePermissionGroupParent(group, parentName)) {
                                sendGroupMessage(ctx.getSource(), services, "group.manage.parent_remove_fail", "Could not remove parent {parent} from {group}.", "{parent}", parentName, "{group}", group);
                                return 0;
                            }
                            sendGroupMessage(ctx.getSource(), services, "group.manage.parent_remove_ok", "Removed parent {parent} from {group}.", "{parent}", parentName, "{group}", group);
                            return 1;
                        }));

        ICommandBuilder parent = platform.createCommandBuilder()
                .literal("parent")
                .then(platform.createCommandBuilder().literal("add").then(parentAddCommand))
                .then(platform.createCommandBuilder().literal("remove").then(parentRemoveCommand));

        ICommandBuilder groupPermBase = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                .then(platform.createCommandBuilder()
                        .argument("permission", ICommandBuilder.ArgumentType.STRING)
                        .suggests((ctx, input) -> permissionSuggestions(services, input))
                        .executes(ctx -> addGroupPermission(ctx.getSource(), services, ctx.getStringArgument("group"), ctx.getStringArgument("permission"), false)));

        ICommandBuilder groupDenyBase = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                .then(platform.createCommandBuilder()
                        .argument("permission", ICommandBuilder.ArgumentType.STRING)
                        .suggests((ctx, input) -> permissionSuggestions(services, input))
                        .executes(ctx -> addGroupPermission(ctx.getSource(), services, ctx.getStringArgument("group"), ctx.getStringArgument("permission"), true)));

        ICommandBuilder groupPermRemoveBase = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                .then(platform.createCommandBuilder()
                        .argument("permission", ICommandBuilder.ArgumentType.STRING)
                        .suggests((ctx, input) -> groupPermissionSuggestions(services, ctx.getStringArgument("group"), input))
                        .executes(ctx -> removeGroupPermission(ctx.getSource(), services, ctx.getStringArgument("group"), ctx.getStringArgument("permission"))));

        ICommandBuilder groupPermListBase = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                .executes(ctx -> {
                    sendGroupPermissionListPanel(ctx.getSource(), services, ctx.getStringArgument("group"));
                    return 1;
                });

        ICommandBuilder groupPerm = platform.createCommandBuilder()
                .literal("perm")
                .then(platform.createCommandBuilder().literal("add").then(groupPermBase))
                .then(platform.createCommandBuilder().literal("deny").then(groupDenyBase))
                .then(platform.createCommandBuilder().literal("remove").then(groupPermRemoveBase))
                .then(platform.createCommandBuilder().literal("list").then(groupPermListBase));

        ICommandBuilder setWeight = platform.createCommandBuilder()
                .literal("setweight")
                .then(platform.createCommandBuilder()
                        .argument("group", ICommandBuilder.ArgumentType.WORD)
                        .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .then(platform.createCommandBuilder()
                                .argument("weight", ICommandBuilder.ArgumentType.INTEGER)
                                .executes(ctx -> setGroupMetadata(ctx.getSource(), services, ctx.getStringArgument("group"), "weight", String.valueOf(ctx.getIntArgument("weight"))))));

        ICommandBuilder setPrefix = buildGroupTextMetadataCommand(platform, services, "setprefix", "prefix");
        ICommandBuilder setSuffix = buildGroupTextMetadataCommand(platform, services, "setsuffix", "suffix");
        ICommandBuilder setDescription = buildGroupTextMetadataCommand(platform, services, "setdescription", "description");

        ICommandBuilder userAddDuration = platform.createCommandBuilder()
                .argument("amount", ICommandBuilder.ArgumentType.INTEGER)
                .then(platform.createCommandBuilder()
                        .argument("unit", ICommandBuilder.ArgumentType.WORD)
                        .suggests(List.of("days", "weeks", "months"))
                        .executes(ctx -> assignUserGroup(
                                ctx.getSource(),
                                services,
                                ctx.getStringArgument("player"),
                                ctx.getStringArgument("group"),
                                ctx.getIntArgument("amount"),
                                ctx.getStringArgument("unit")
                        )));

        ICommandBuilder userAddBase = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                .executes(ctx -> assignUserGroup(ctx.getSource(), services, ctx.getStringArgument("player"), ctx.getStringArgument("group"), 0, ""))
                .then(userAddDuration);

        ICommandBuilder userRemoveBase = platform.createCommandBuilder()
                .argument("group", ICommandBuilder.ArgumentType.WORD)
                .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                .executes(ctx -> {
                    UUID uuid = resolvePlayerUuid(services, ctx.getStringArgument("player"));
                    if (uuid == null) {
                        sendGroupMessage(ctx.getSource(), services, "group.manage.user_invalid", "Player must be online name or UUID.");
                        return 0;
                    }
                    String group = ctx.getStringArgument("group");
                    if (!services.getPermissionsHandler().revokePlayerGroup(uuid, group)) {
                        sendGroupMessage(ctx.getSource(), services, "group.manage.user_remove_fail", "Could not remove group {group} from player.", "{group}", group);
                        return 0;
                    }
                    sendGroupMessage(ctx.getSource(), services, "group.manage.user_remove_ok", "Removed group {group} from player.", "{group}", group);
                    return 1;
                });

        ICommandBuilder userPermBase = platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.WORD)
                .then(platform.createCommandBuilder()
                        .argument("permission", ICommandBuilder.ArgumentType.STRING)
                        .suggests((ctx, input) -> permissionSuggestions(services, input))
                        .executes(ctx -> addUserPermission(ctx.getSource(), services, ctx.getStringArgument("player"), ctx.getStringArgument("permission"), false)));

        ICommandBuilder userDenyBase = platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.WORD)
                .then(platform.createCommandBuilder()
                        .argument("permission", ICommandBuilder.ArgumentType.STRING)
                        .suggests((ctx, input) -> permissionSuggestions(services, input))
                        .executes(ctx -> addUserPermission(ctx.getSource(), services, ctx.getStringArgument("player"), ctx.getStringArgument("permission"), true)));

        ICommandBuilder userPermRemoveBase = platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.WORD)
                .then(platform.createCommandBuilder()
                        .argument("permission", ICommandBuilder.ArgumentType.STRING)
                        .suggests((ctx, input) -> userPermissionSuggestions(services, ctx.getStringArgument("player"), input))
                        .executes(ctx -> removeUserPermission(ctx.getSource(), services, ctx.getStringArgument("player"), ctx.getStringArgument("permission"))));

        ICommandBuilder userPermListBase = platform.createCommandBuilder()
                .argument("player", ICommandBuilder.ArgumentType.WORD)
                .executes(ctx -> sendUserInfoPanel(ctx.getSource(), services, ctx.getStringArgument("player")));

        ICommandBuilder userPerm = platform.createCommandBuilder()
                .literal("perm")
                .then(platform.createCommandBuilder().literal("add").then(userPermBase))
                .then(platform.createCommandBuilder().literal("deny").then(userDenyBase))
                .then(platform.createCommandBuilder().literal("remove").then(userPermRemoveBase))
                .then(platform.createCommandBuilder().literal("list").then(userPermListBase));

        ICommandBuilder user = platform.createCommandBuilder()
                .literal("user")
                .then(platform.createCommandBuilder()
                        .literal("add")
                        .then(platform.createCommandBuilder()
                                .argument("player", ICommandBuilder.ArgumentType.WORD)
                                .then(userAddBase)))
                .then(platform.createCommandBuilder()
                        .literal("remove")
                        .then(platform.createCommandBuilder()
                                .argument("player", ICommandBuilder.ArgumentType.WORD)
                                .then(userRemoveBase)))
                .then(platform.createCommandBuilder()
                        .literal("info")
                        .then(platform.createCommandBuilder()
                                .argument("player", ICommandBuilder.ArgumentType.WORD)
                                .executes(ctx -> sendUserInfoPanel(ctx.getSource(), services, ctx.getStringArgument("player")))))
                .then(platform.createCommandBuilder()
                        .literal("list")
                        .then(platform.createCommandBuilder()
                                .argument("player", ICommandBuilder.ArgumentType.WORD)
                                .executes(ctx -> sendUserInfoPanel(ctx.getSource(), services, ctx.getStringArgument("player")))))
                .then(userPerm);

        return root.then(list).then(add).then(remove).then(info).then(parent).then(groupPerm).then(setWeight).then(setPrefix).then(setSuffix).then(setDescription).then(user);
    }

    private ICommandBuilder buildGroupTextMetadataCommand(IPlatformAdapter platform, Services services, String literal, String field) {
        return platform.createCommandBuilder()
                .literal(literal)
                .then(platform.createCommandBuilder()
                        .argument("group", ICommandBuilder.ArgumentType.WORD)
                        .suggests((ctx, input) -> services.getPermissionsHandler().listPermissionGroups())
                        .then(platform.createCommandBuilder()
                                .argument("value", ICommandBuilder.ArgumentType.GREEDY_STRING)
                                .executes(ctx -> setGroupMetadata(ctx.getSource(), services, ctx.getStringArgument("group"), field, ctx.getStringArgument("value")))));
    }

    private int addGroupPermission(ICommandSource source, Services services, String group, String permission, boolean denied) {
        if (!services.getPermissionsHandler().addPermissionToGroup(group, permission, denied)) {
            sendGroupMessage(source, services, "group.manage.perm_add_fail", "Could not add permission {permission} to {group}.", "{permission}", permission, "{group}", group);
            return 0;
        }
        sendGroupMessage(source, services, "group.manage.perm_add_ok", "Added permission {permission} to {group}.", "{permission}", denied ? "-" + stripDeny(permission) : stripDeny(permission), "{group}", group);
        sendGroupPermissionListPanel(source, services, group);
        return 1;
    }

    private int removeGroupPermission(ICommandSource source, Services services, String group, String permission) {
        if (!services.getPermissionsHandler().removePermissionFromGroup(group, permission)) {
            sendGroupMessage(source, services, "group.manage.perm_remove_fail", "Could not remove permission {permission} from {group}.", "{permission}", permission, "{group}", group);
            return 0;
        }
        sendGroupMessage(source, services, "group.manage.perm_remove_ok", "Removed permission {permission} from {group}.", "{permission}", permission, "{group}", group);
        sendGroupPermissionListPanel(source, services, group);
        return 1;
    }

    private int setGroupMetadata(ICommandSource source, Services services, String group, String field, String value) {
        if (!services.getPermissionsHandler().setPermissionGroupMetadata(group, field, value)) {
            sendGroupMessage(source, services, "group.manage.metadata_fail", "Could not update {field} for {group}.", "{field}", field, "{group}", group);
            return 0;
        }
        sendGroupMessage(source, services, "group.manage.metadata_ok", "Updated {field} for {group}.", "{field}", field, "{group}", group);
        sendGroupInfoPanel(source, services, group);
        return 1;
    }

    private int addUserPermission(ICommandSource source, Services services, String playerInput, String permission, boolean denied) {
        UUID uuid = resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            sendGroupMessage(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        if (!services.getPermissionsHandler().addPermissionToPlayer(uuid, permission, denied)) {
            sendGroupMessage(source, services, "group.manage.user_perm_add_fail", "Could not add permission {permission} to player.", "{permission}", permission);
            return 0;
        }
        sendGroupMessage(source, services, "group.manage.user_perm_add_ok", "Added permission {permission} to player.", "{permission}", denied ? "-" + stripDeny(permission) : stripDeny(permission));
        sendUserInfoPanel(source, services, playerInput);
        return 1;
    }

    private int removeUserPermission(ICommandSource source, Services services, String playerInput, String permission) {
        UUID uuid = resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            sendGroupMessage(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        if (!services.getPermissionsHandler().removePermissionFromPlayer(uuid, permission)) {
            sendGroupMessage(source, services, "group.manage.user_perm_remove_fail", "Could not remove permission {permission} from player.", "{permission}", permission);
            return 0;
        }
        sendGroupMessage(source, services, "group.manage.user_perm_remove_ok", "Removed permission {permission} from player.", "{permission}", permission);
        sendUserInfoPanel(source, services, playerInput);
        return 1;
    }

    private int explainPermission(ICommandSource source, Services services, String playerInput, String permission) {
        UUID uuid = resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            sendGroupMessage(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        PermissionAPI.PermissionExplain explain = services.getPermissionsHandler().explainPlayerPermission(uuid, permission);
        if (explain == null) {
            sendGroupMessage(source, services, "group.manage.permission_check_fail", "Could not check permission {permission}.", "{permission}", permission);
            return 0;
        }
        sendPermissionExplainPanel(source, services, playerInput, permission, explain);
        return 1;
    }

    private void sendPermissionsHome(ICommandSource source, Services services) {
        sendUiLine(source, services, header(services, "Permissions"));
        sendUiLine(source, services, row(services)
                .append(button(services, "[groups]", "/paradigm group list", true, "Open group list", "60A5FA"))
                .append(space(services))
                .append(button(services, "[+ group]", "/paradigm group add ", false, "Prepare group creation", "34D399"))
                .append(space(services))
                .append(button(services, "[check]", "/paradigm permission check ", false, "Prepare permission check", "FBBF24"))
                .append(space(services))
                .append(button(services, "[nodes]", "/paradigm permission nodes", true, "List discovered permission nodes", "60A5FA")));
        sendUiLine(source, services, row(services)
                .append(text(services, "Quick: ", "64748B"))
                .append(button(services, "[group]", "/paradigm group info ", false, "Prepare group detail", "60A5FA"))
                .append(space(services))
                .append(button(services, "[user]", "/paradigm group user info ", false, "Prepare user detail", "60A5FA")));
    }

    private void sendGroupListPanel(ICommandSource source, Services services) {
        List<String> groups = services.getPermissionsHandler().listPermissionGroups();
        sendUiLine(source, services, header(services, "Groups (" + groups.size() + ")"));
        if (groups.isEmpty()) {
            sendUiLine(source, services, text(services, "No groups configured.", "FCA5A5"));
        }
        for (String group : groups) {
            IComponent line = row(services)
                    .append(text(services, "- " + group, "E5E7EB"))
                    .append(space(services))
                    .append(button(services, "[open]", "/paradigm group info " + group, true, "Open group details", "60A5FA"))
                    .append(space(services))
                    .append(button(services, "[perms]", "/paradigm group perm list " + group, true, "Open group permissions", "60A5FA"))
                    .append(space(services))
                    .append(button(services, "[parent+]", "/paradigm group parent add " + group + " ", false, "Prepare parent add", "FBBF24"));
            if (!isBuiltInGroup(group)) {
                line.append(space(services))
                        .append(button(services, "[del]", "/paradigm group remove " + group, false, "Prepare group deletion", "F87171"));
            }
            sendUiLine(source, services, line);
        }
        sendUiLine(source, services, row(services)
                .append(button(services, "[+ group]", "/paradigm group add ", false, "Prepare group creation", "34D399"))
                .append(space(services))
                .append(button(services, "[home]", "/paradigm perms", true, "Back to permissions home", "94A3B8")));
    }

    private void sendGroupInfoPanel(ICommandSource source, Services services, String groupName) {
        PermissionAPI.GroupInfo info = services.getPermissionsHandler().getPermissionGroupInfo(groupName);
        if (info == null) {
            sendGroupMessage(source, services, "group.manage.not_found", "Group {group} was not found.", "{group}", groupName);
            return;
        }
        String group = info.name();
        sendUiLine(source, services, header(services, "Group " + group));
        sendUiLine(source, services, row(services)
                .append(text(services, "weight: " + info.weight(), "CBD5E1"))
                .append(space(services))
                .append(button(services, "[edit]", "/paradigm group setweight " + group + " " + info.weight(), false, "Prepare weight edit", "FBBF24")));
        sendUiLine(source, services, row(services)
                .append(text(services, "prefix: " + blankDash(info.prefix()), "CBD5E1"))
                .append(space(services))
                .append(button(services, "[edit]", "/paradigm group setprefix " + group + " " + info.prefix(), false, "Prepare prefix edit", "FBBF24")));
        sendUiLine(source, services, row(services)
                .append(text(services, "suffix: " + blankDash(info.suffix()), "CBD5E1"))
                .append(space(services))
                .append(button(services, "[edit]", "/paradigm group setsuffix " + group + " " + info.suffix(), false, "Prepare suffix edit", "FBBF24")));
        sendUiLine(source, services, row(services)
                .append(text(services, "parents: " + (info.inherits().isEmpty() ? "-" : String.join(", ", info.inherits())), "CBD5E1"))
                .append(space(services))
                .append(button(services, "[+]", "/paradigm group parent add " + group + " ", false, "Prepare parent add", "34D399"))
                .append(space(services))
                .append(button(services, "[-]", "/paradigm group parent remove " + group + " ", false, "Prepare parent removal", "F87171")));
        sendUiLine(source, services, row(services)
                .append(text(services, "perms: " + info.permissions().size(), "CBD5E1"))
                .append(space(services))
                .append(button(services, "[open]", "/paradigm group perm list " + group, true, "Open permissions", "60A5FA"))
                .append(space(services))
                .append(button(services, "[+]", "/paradigm group perm add " + group + " ", false, "Prepare permission add", "34D399"))
                .append(space(services))
                .append(button(services, "[deny]", "/paradigm group perm deny " + group + " ", false, "Prepare permission deny", "F87171")));
        sendUiLine(source, services, row(services)
                .append(button(services, "[desc]", "/paradigm group setdescription " + group + " " + info.description(), false, "Prepare description edit", "FBBF24"))
                .append(space(services))
                .append(button(services, "[back]", "/paradigm group list", true, "Back to groups", "94A3B8")));
    }

    private void sendGroupPermissionListPanel(ICommandSource source, Services services, String groupName) {
        PermissionAPI.GroupInfo info = services.getPermissionsHandler().getPermissionGroupInfo(groupName);
        if (info == null) {
            sendGroupMessage(source, services, "group.manage.not_found", "Group {group} was not found.", "{group}", groupName);
            return;
        }
        String group = info.name();
        sendUiLine(source, services, header(services, "Perms " + group));
        if (info.permissions().isEmpty()) {
            sendUiLine(source, services, text(services, "No direct permissions.", "94A3B8"));
        }
        for (String rule : info.permissions()) {
            boolean denied = rule != null && rule.startsWith("-");
            String node = stripDeny(rule);
            sendUiLine(source, services, row(services)
                    .append(text(services, denied ? "x " : "+ ", denied ? "F87171" : "34D399"))
                    .append(text(services, rule, "E5E7EB"))
                    .append(space(services))
                    .append(button(services, "[remove]", "/paradigm group perm remove " + group + " " + node, true, "Remove this permission rule", "F87171")));
        }
        sendUiLine(source, services, row(services)
                .append(button(services, "[+ add]", "/paradigm group perm add " + group + " ", false, "Prepare allow permission", "34D399"))
                .append(space(services))
                .append(button(services, "[deny]", "/paradigm group perm deny " + group + " ", false, "Prepare deny permission", "F87171"))
                .append(space(services))
                .append(button(services, "[group]", "/paradigm group info " + group, true, "Back to group detail", "94A3B8")));
    }

    private int sendUserInfoPanel(ICommandSource source, Services services, String playerInput) {
        UUID uuid = resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            sendGroupMessage(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        PermissionAPI.UserInfo info = services.getPermissionsHandler().getPlayerPermissionInfo(uuid);
        if (info == null) {
            sendGroupMessage(source, services, "group.manage.user_empty", "No groups assigned.");
            return 1;
        }

        String label = resolvePlayerLabel(services, uuid, playerInput);
        sendUiLine(source, services, header(services, "User " + label));
        PermissionAPI.PermissionMeta meta = info.meta();
        sendUiLine(source, services, text(services, "primary: " + (meta != null ? meta.primaryGroup() : "-"), "CBD5E1"));
        sendUiLine(source, services, row(services)
                .append(text(services, "groups: " + (info.permanentGroups().isEmpty() ? "-" : String.join(", ", info.permanentGroups())), "CBD5E1"))
                .append(space(services))
                .append(button(services, "[+]", "/paradigm group user add " + label + " ", false, "Prepare group assignment", "34D399")));
        for (String group : info.permanentGroups()) {
            sendUiLine(source, services, row(services)
                    .append(text(services, "- " + group, "94A3B8"))
                    .append(space(services))
                    .append(button(services, "[remove]", "/paradigm group user remove " + label + " " + group, true, "Remove this group from user", "F87171")));
        }
        if (info.temporaryGroups().isEmpty()) {
            sendUiLine(source, services, text(services, "temp: -", "94A3B8"));
        } else {
            for (PermissionAPI.TemporaryGroupInfo temp : info.temporaryGroups()) {
                sendUiLine(source, services, text(services, "temp: " + temp.group() + " until " + Instant.ofEpochMilli(temp.expiresAtMs()), "FBBF24"));
            }
        }
        sendUiLine(source, services, text(services, "direct perms:", "CBD5E1"));
        if (info.permissions().isEmpty()) {
            sendUiLine(source, services, text(services, "- none", "94A3B8"));
        }
        for (String rule : info.permissions()) {
            boolean denied = rule != null && rule.startsWith("-");
            String node = stripDeny(rule);
            sendUiLine(source, services, row(services)
                    .append(text(services, denied ? "x " : "+ ", denied ? "F87171" : "34D399"))
                    .append(text(services, rule, "E5E7EB"))
                    .append(space(services))
                    .append(button(services, "[remove]", "/paradigm group user perm remove " + label + " " + node, true, "Remove direct permission", "F87171")));
        }
        sendUiLine(source, services, row(services)
                .append(button(services, "[+ perm]", "/paradigm group user perm add " + label + " ", false, "Prepare direct permission", "34D399"))
                .append(space(services))
                .append(button(services, "[deny]", "/paradigm group user perm deny " + label + " ", false, "Prepare direct deny", "F87171"))
                .append(space(services))
                .append(button(services, "[check]", "/paradigm permission check " + label + " ", false, "Prepare permission check", "FBBF24")));
        return 1;
    }

    private void sendPermissionExplainPanel(ICommandSource source, Services services, String playerInput, String permission, PermissionAPI.PermissionExplain explain) {
        String state = explain.allowed() == null ? "UNDEFINED" : (explain.allowed() ? "ALLOWED" : "DENIED");
        String color = explain.allowed() == null ? "FBBF24" : (explain.allowed() ? "34D399" : "F87171");
        sendUiLine(source, services, header(services, "Check"));
        sendUiLine(source, services, row(services)
                .append(text(services, playerInput + " -> " + permission + ": ", "CBD5E1"))
                .append(text(services, state, color)));
        sendUiLine(source, services, text(services, "match: " + blankDash(explain.sourceType()) + " " + blankDash(explain.sourceName()) + " " + blankDash(explain.rule()), "94A3B8"));
        sendUiLine(source, services, text(services, "groups: " + (explain.groupsChecked().isEmpty() ? "-" : String.join(", ", explain.groupsChecked())), "94A3B8"));
        sendUiLine(source, services, row(services)
                .append(button(services, "[user]", "/paradigm group user info " + playerInput, true, "Open user permissions", "60A5FA"))
                .append(space(services))
                .append(button(services, "[allow]", "/paradigm group user perm add " + playerInput + " " + permission, true, "Add direct allow", "34D399"))
                .append(space(services))
                .append(button(services, "[deny]", "/paradigm group user perm deny " + playerInput + " " + permission, true, "Add direct deny", "F87171")));
    }

    private void sendPermissionNodeList(ICommandSource source, Services services, String query) {
        List<PermissionNodeRegistry.DiscoveredPermission> nodes = services.getPermissionsHandler().listDiscoveredPermissionNodes(query, 20);
        String title = query == null || query.isBlank() ? "Nodes" : "Nodes " + query.trim();
        boolean externalEnabled = services.getPermissionsHandler().isExternalCommandPermissionsEnabled();
        sendUiLine(source, services, header(services, title));
        sendUiLine(source, services, row(services)
                .append(text(services, "external: " + (externalEnabled ? "on" : "off"), externalEnabled ? "34D399" : "F87171"))
                .append(space(services))
                .append(text(services, "mode: " + (services.getPermissionsHandler().isExternalCommandStrictMode() ? "strict" : "deny_only"), "CBD5E1"))
                .append(space(services))
                .append(button(services, "[search]", "/paradigm permission nodes ", false, "Search discovered nodes", "60A5FA")));

        if (nodes.isEmpty()) {
            sendUiLine(source, services, text(services, "No discovered nodes yet. They appear after command registration or Forge/NeoForge PermissionAPI registration.", "FBBF24"));
            return;
        }

        for (PermissionNodeRegistry.DiscoveredPermission node : nodes) {
            String name = node.node != null ? node.node : "";
            String sourceName = permissionSourceLabel(node.source);
            sendUiLine(source, services, row(services)
                    .append(text(services, "- " + name, "E5E7EB"))
                    .append(space(services))
                    .append(text(services, sourceName, "94A3B8"))
                    .append(space(services))
                    .append(button(services, "[allow]", "/paradigm group perm add default " + name, false, "Suggest allow for default group", "34D399"))
                    .append(space(services))
                    .append(button(services, "[deny]", "/paradigm group perm deny default " + name, false, "Suggest deny for default group", "F87171")));
        }

        sendUiLine(source, services, row(services)
                .append(button(services, "[perms]", "/paradigm perms", true, "Back to permissions home", "94A3B8"))
                .append(space(services))
                .append(button(services, "[groups]", "/paradigm group list", true, "Open groups", "60A5FA")));
    }

    private String permissionSourceLabel(String source) {
        if (source == null || source.isBlank()) {
            return "manual";
        }
        return switch (source) {
            case PermissionNodeRegistry.SOURCE_COMMAND_TREE -> "command";
            case PermissionNodeRegistry.SOURCE_COMMAND_ALIAS -> "alias";
            case PermissionNodeRegistry.SOURCE_FORGE_PERMISSION_API -> "Forge API";
            case PermissionNodeRegistry.SOURCE_NEOFORGE_PERMISSION_API -> "NeoForge API";
            case PermissionNodeRegistry.SOURCE_PARADIGM -> "Paradigm";
            default -> source;
        };
    }

    private int assignUserGroup(ICommandSource source, Services services, String playerInput, String group, int amount, String unit) {
        UUID uuid = resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            sendGroupMessage(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }

        if (amount <= 0) {
            if (!services.getPermissionsHandler().assignPlayerGroup(uuid, group)) {
                sendGroupMessage(source, services, "group.manage.user_add_fail", "Could not assign group {group}.", "{group}", group);
                return 0;
            }
            sendGroupMessage(source, services, "group.manage.user_add_ok", "Assigned group {group}.", "{group}", group);
            return 1;
        }

        long expiresAtMs = computeExpiry(amount, unit);
        if (expiresAtMs <= System.currentTimeMillis()) {
            sendGroupMessage(source, services, "group.manage.duration_invalid", "Invalid duration. Use days/weeks/months with amount > 0.");
            return 0;
        }

        String assignedBy = source != null ? source.getSourceName() : "console";
        if (!services.getPermissionsHandler().assignPlayerGroupTemp(uuid, group, expiresAtMs, assignedBy)) {
            sendGroupMessage(source, services, "group.manage.user_add_fail", "Could not assign group {group}.", "{group}", group);
            return 0;
        }

        sendGroupMessage(source, services, "group.manage.user_add_temp_ok", "Assigned group {group} until {until}.",
                "{group}", group,
                "{until}", Instant.ofEpochMilli(expiresAtMs).toString());
        return 1;
    }

    private UUID resolvePlayerUuid(Services services, String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(input.trim());
        } catch (Exception ignored) {
        }

        IPlayer online = services.getPlatformAdapter().getPlayerByName(input.trim());
        if (online == null || online.getUUID() == null || online.getUUID().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(online.getUUID());
        } catch (Exception ignored) {
            return null;
        }
    }

    private long computeExpiry(int amount, String unitRaw) {
        if (amount <= 0 || unitRaw == null || unitRaw.isBlank()) {
            return -1L;
        }
        String unit = unitRaw.trim().toLowerCase(Locale.ROOT);

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime expires = switch (unit) {
            case "day", "days" -> now.plusDays(amount);
            case "week", "weeks" -> now.plusWeeks(amount);
            case "month", "months" -> now.plusMonths(amount);
            default -> null;
        };
        if (expires == null) {
            return -1L;
        }
        return expires.toInstant().toEpochMilli();
    }

    private List<String> permissionSuggestions(Services services, String input) {
        Set<String> nodes = new LinkedHashSet<>();
        for (String node : services.getPermissionsHandler().knownPermissionNodes().keySet()) {
            if (node == null || node.isBlank()) {
                continue;
            }
            if (node.contains("<number>")) {
                nodes.add(node.replace("<number>", "3"));
            } else {
                nodes.add(node);
            }
        }
        nodes.add("paradigm.*");
        nodes.add("*");
        return filterSuggestions(new ArrayList<>(nodes), input);
    }

    private List<String> groupPermissionSuggestions(Services services, String group, String input) {
        PermissionAPI.GroupInfo info = services.getPermissionsHandler().getPermissionGroupInfo(group);
        if (info == null) {
            return permissionSuggestions(services, input);
        }
        List<String> nodes = new ArrayList<>();
        for (String rule : info.permissions()) {
            nodes.add(stripDeny(rule));
        }
        return filterSuggestions(nodes, input);
    }

    private List<String> userPermissionSuggestions(Services services, String playerInput, String input) {
        UUID uuid = resolvePlayerUuid(services, playerInput);
        if (uuid == null) {
            return permissionSuggestions(services, input);
        }
        PermissionAPI.UserInfo info = services.getPermissionsHandler().getPlayerPermissionInfo(uuid);
        if (info == null) {
            return permissionSuggestions(services, input);
        }
        List<String> nodes = new ArrayList<>();
        for (String rule : info.permissions()) {
            nodes.add(stripDeny(rule));
        }
        return filterSuggestions(nodes, input);
    }

    private List<String> filterSuggestions(List<String> values, String input) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        String q = input != null ? input.trim().toLowerCase(Locale.ROOT) : "";
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .filter(value -> q.isEmpty() || value.toLowerCase(Locale.ROOT).startsWith(q))
                .toList();
    }

    private String resolvePlayerLabel(Services services, UUID uuid, String fallback) {
        if (uuid != null) {
            IPlayer online = services.getPlatformAdapter().getPlayerByUuid(uuid.toString());
            if (online != null && online.getName() != null && !online.getName().isBlank()) {
                return online.getName();
            }
            return uuid.toString();
        }
        return fallback != null && !fallback.isBlank() ? fallback.trim() : "-";
    }

    private static String stripDeny(String permission) {
        if (permission == null) {
            return "";
        }
        String trimmed = permission.trim();
        return trimmed.startsWith("-") ? trimmed.substring(1).trim() : trimmed;
    }

    private static String blankDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static boolean isBuiltInGroup(String group) {
        if (group == null) {
            return false;
        }
        String normalized = group.trim().toLowerCase(Locale.ROOT);
        return "default".equals(normalized) || "admin".equals(normalized);
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

    private boolean hasGroupManagePermission(ICommandSource src, Services services) {
        if (src == null) return false;
        if (src.isConsole()) return true;
        if (src.hasPermissionLevel(2)) return true;
        IPlayer player = src.getPlayer();
        return player != null && services.getPermissionsHandler().hasPermission(
                player,
                PermissionsHandler.GROUP_MANAGE_PERMISSION,
                PermissionsHandler.GROUP_MANAGE_PERMISSION_LEVEL
        );
    }

    private void sendGroupMessage(ICommandSource source, Services services, String key, String fallback, String... placeholders) {
        String raw = services.getLang().getTranslation(key);
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }

        IComponent message = services.getMessageParser().parseMessage(
                "<color:#A78BFA><bold>[Group]</bold></color> <color:#E5E7EB>" + raw + "</color>",
                source != null ? source.getPlayer() : null
        );
        services.getPlatformAdapter().sendSuccess(source, message, false);
    }
}
