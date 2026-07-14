package eu.avalanche7.paradigm.configs.schema;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.AnnouncementsConfigHandler;
import eu.avalanche7.paradigm.configs.ChatConfigHandler;
import eu.avalanche7.paradigm.configs.ConfigEntry;
import eu.avalanche7.paradigm.configs.CooldownConfigHandler;
import eu.avalanche7.paradigm.configs.MOTDConfigHandler;
import eu.avalanche7.paradigm.configs.MainConfigHandler;
import eu.avalanche7.paradigm.configs.MentionConfigHandler;
import eu.avalanche7.paradigm.configs.RestartConfigHandler;
import eu.avalanche7.paradigm.configs.ModerationConfigHandler;
import eu.avalanche7.paradigm.configs.TablistConfigHandler;
import com.google.gson.Gson;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.DashboardConfig;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPermission;
import eu.avalanche7.paradigm.storage.StorageProviderType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ConfigSchemaRegistry {
    private final Services services;

    public ConfigSchemaRegistry(Services services) {
        this.services = services;
    }

    public ConfigSnapshot snapshot() {
        List<ConfigCategory> categories = List.of(
                new ConfigCategory("modules", "Modules", "High-level Paradigm module toggles from main.json."),
                new ConfigCategory("command_groups", "Command Groups", "High-level command module toggles from main.json."),
                new ConfigCategory("commands", "Commands", "Persisted command enable/disable states from commands.json."),
                new ConfigCategory("cooldowns", "Cooldowns", "Command cooldown and warmup timings from cooldowns.json."),
                new ConfigCategory("teleports", "Teleports", "Teleport command family toggles and safe spawn status."),
                new ConfigCategory("chat", "Chat", "Chat, message, group chat, and mention settings."),
                new ConfigCategory("announcements", "Announcements", "Announcement channels, intervals, messages, and display settings."),
                new ConfigCategory("restart", "Restart", "Scheduled restart timing and warning messages."),
                new ConfigCategory("motd", "MOTD", "Join MOTD and server-list MOTD text/icon settings."),
                new ConfigCategory("tablist", "Tablist", "Header, footer, player formatting, sorting, and world overrides."),
                new ConfigCategory("moderation", "Moderation", "Punishment cache and formatted ban-screen settings."),
                new ConfigCategory("storage", "Storage", "Read-only data provider status and masked storage settings."),
                new ConfigCategory("dashboard", "Dashboard", "Local dashboard settings from dashboard.json."),
                new ConfigCategory("admin_utilities", "Admin Utilities", "Admin and moderation utility command toggles from main.json."),
                new ConfigCategory("custom_commands", "Custom Commands", "Read-only custom command summary from config/paradigm/commands.")
        );

        List<ConfigField> fields = new ArrayList<>();
        addMainFields(fields);
        addChatFields(fields);
        addMentionFields(fields);
        addAnnouncementFields(fields);
        addRestartFields(fields);
        addMotdFields(fields);
        addTablistFields(fields);
        addModerationFields(fields);
        addCommandFields(fields);
        addCooldownFields(fields);
        addStorageFields(fields);
        addDashboardFields(fields);
        addCustomCommandFields(fields);
        fields.sort(Comparator.comparing(ConfigField::category).thenComparing(ConfigField::key));

        ConfigSnapshot draft = new ConfigSnapshot("", System.currentTimeMillis(), categories, fields);
        return new ConfigSnapshot(ConfigRevisionService.revision(draft), draft.createdAtMs(), categories, fields);
    }

    private void addMainFields(List<ConfigField> fields) {
        MainConfigHandler.Config current = MainConfigHandler.getConfig();
        MainConfigHandler.Config defaults = new MainConfigHandler.Config();
        Map<String, ConfigEntry<?>> defaultEntries = configEntries(MainConfigHandler.Config.class, defaults);
        for (Map.Entry<String, ConfigEntry<?>> entry : configEntries(MainConfigHandler.Config.class, current).entrySet()) {
            String name = entry.getKey();
            if (isUnsafeMainField(name)) {
                continue;
            }
            String category = categoryForMain(name, entry.getValue());
            ConfigField field = entryField("main." + name, category, name, entry.getValue(), defaultEntries.get(name),
                    "main.json", ConfigReloadBehavior.RELOAD_REQUIRED, riskForMain(name), true);
            if (field != null) {
                fields.add(field);
            }
        }
        fields.add(readOnly("main.telemetryServerId", "modules", "Telemetry Server ID", "set/unset",
                "Telemetry server id is treated as internal state and is not editable here.", "main.json"));
    }

    private void addChatFields(List<ConfigField> fields) {
        ChatConfigHandler.Config current = ChatConfigHandler.getConfig();
        ChatConfigHandler.Config defaults = new ChatConfigHandler.Config();
        addConfigEntries(fields, "chat", "chat", ChatConfigHandler.Config.class, current, defaults, "chat.json", ConfigReloadBehavior.RELOAD_REQUIRED);
    }

    private void addMentionFields(List<ConfigField> fields) {
        MentionConfigHandler.Config current = MentionConfigHandler.getConfig();
        MentionConfigHandler.Config defaults = new MentionConfigHandler.Config();
        addConfigEntries(fields, "mentions", "chat", MentionConfigHandler.Config.class, current, defaults, "mentions.json", ConfigReloadBehavior.RELOAD_REQUIRED);
    }

    private void addAnnouncementFields(List<ConfigField> fields) {
        AnnouncementsConfigHandler.Config current = AnnouncementsConfigHandler.getConfig();
        AnnouncementsConfigHandler.Config defaults = new AnnouncementsConfigHandler.Config();
        addConfigEntries(fields, "announcements", "announcements", AnnouncementsConfigHandler.Config.class, current, defaults,
                "announcements.json", ConfigReloadBehavior.RELOAD_REQUIRED);
    }

    private void addRestartFields(List<ConfigField> fields) {
        RestartConfigHandler.Config current = RestartConfigHandler.getConfig();
        RestartConfigHandler.Config defaults = new RestartConfigHandler.Config();
        addConfigEntries(fields, "restart", "restart", RestartConfigHandler.Config.class, current, defaults,
                "restarts.json", ConfigReloadBehavior.RELOAD_REQUIRED);
        fields.removeIf(field -> "restart.restartInterval".equals(field.key()));
        fields.add(simpleField("restart.restartInterval", "restart", "Fixed Restart Interval",
                current.restartInterval.description, ConfigFieldType.DOUBLE,
                current.restartInterval.value, defaults.restartInterval.value,
                "restarts.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.SAFE,
                0.01, 8760.0, 0.25, List.of(), null, true, true, false,
                "hours", true, false));
        fields.add(simpleField("restart.timerBroadcast", "restart", "Timer Broadcast Thresholds",
                current.timerBroadcast.description, ConfigFieldType.STRING_LIST,
                restartThresholds(current.timerBroadcast.value), restartThresholds(defaults.timerBroadcast.value),
                "restarts.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.SAFE,
                null, null, null, List.of(), ConfigFieldType.INTEGER, true, false, true,
                "seconds", true, false));
        fields.add(simpleField("restart.preRestartCommands", "restart", "Pre Restart Commands",
                "One command per row using: seconds before | command. Commands remain constrained to the restart configuration.",
                ConfigFieldType.STRING_LIST, restartCommands(current.preRestartCommands.value), restartCommands(defaults.preRestartCommands.value),
                "restarts.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.ADVANCED,
                null, null, null, List.of(), null, true, false, true, "", false, false));
    }

    private void addMotdFields(List<ConfigField> fields) {
        MOTDConfigHandler.Config current = MOTDConfigHandler.getConfig();
        MOTDConfigHandler.Config defaults = new MOTDConfigHandler.Config();
        fields.add(stringListField("motd.motdLines", "motd", "Join MOTD Lines",
                "Message sent to players after joining. Empty lines are preserved.", current.motdLines, defaults.motdLines,
                "motd.json", true, ConfigReloadBehavior.RELOAD_REQUIRED));
        fields.add(entryField("motd.iconEnabled", "motd", "iconEnabled", current.iconEnabled, defaults.iconEnabled,
                "motd.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.SAFE, true));
        fields.add(entryField("motd.serverlistMotdEnabled", "motd", "serverlistMotdEnabled", current.serverlistMotdEnabled, defaults.serverlistMotdEnabled,
                "motd.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.SAFE, true));

        MOTDConfigHandler.ServerListMOTD currentFirst = firstMotd(current);
        MOTDConfigHandler.ServerListMOTD defaultFirst = firstMotd(defaults);
        fields.add(simpleField("motd.serverlist.0.icon", "motd", "Server-list Icon",
                "Icon name from config/paradigm/icons without .png. Use random where supported.", ConfigFieldType.STRING,
                currentFirst.icon, defaultFirst.icon, "motd.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.SAFE,
                null, null, null, List.of(), null, true, false, true, "", true, false));
        fields.add(simpleField("motd.serverlist.0.line1", "motd", "Server-list Line 1",
                "First line shown in the multiplayer server list.", ConfigFieldType.STRING,
                currentFirst.line1, defaultFirst.line1, "motd.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.SAFE,
                null, null, null, List.of(), null, true, false, true, "", false, false));
        fields.add(simpleField("motd.serverlist.0.line2", "motd", "Server-list Line 2",
                "Second line shown in the multiplayer server list.", ConfigFieldType.STRING,
                currentFirst.line2, defaultFirst.line2, "motd.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.SAFE,
                null, null, null, List.of(), null, true, false, true, "", false, false));
        if (currentFirst.playerCount != null) {
            MOTDConfigHandler.PlayerCountDisplay defaultPlayerCount = defaultFirst.playerCount != null ? defaultFirst.playerCount : new MOTDConfigHandler.PlayerCountDisplay();
            fields.add(simpleField("motd.serverlist.0.playerCount.hoverText", "motd", "Player Count Hover",
                    "Hover text shown over the server-list player count.", ConfigFieldType.STRING,
                    currentFirst.playerCount.hoverText, defaultPlayerCount.hoverText, "motd.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.SAFE,
                    null, null, null, List.of(), null, true, false, true, "", false, false));
            fields.add(simpleField("motd.serverlist.0.playerCount.maxPlayers", "motd", "Displayed Max Players",
                    "Optional max player count displayed in the server list.", ConfigFieldType.INTEGER,
                    currentFirst.playerCount.maxPlayers, defaultPlayerCount.maxPlayers, "motd.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.SAFE,
                    0.0, 1_000_000.0, 1.0, List.of(), null, true, false, true, "", true, false));
            fields.add(simpleField("motd.serverlist.0.playerCount.showActualCount", "motd", "Show Actual Player Count",
                    "Show the real online player count in the server list.", ConfigFieldType.BOOLEAN,
                    currentFirst.playerCount.showActualCount, defaultPlayerCount.showActualCount, "motd.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.SAFE,
                    null, null, null, List.of(), null, true, false, true, "", true, false));
        }
    }

    private void addTablistFields(List<ConfigField> fields) {
        TablistConfigHandler.Config current = TablistConfigHandler.getConfig();
        TablistConfigHandler.Config defaults = new TablistConfigHandler.Config();
        addConfigEntries(fields, "tablist", "tablist", TablistConfigHandler.Config.class, current, defaults,
                "tablist.json", ConfigReloadBehavior.LIVE);
        fields.removeIf(field -> List.of("tablist.header", "tablist.footer", "tablist.sorting", "tablist.refreshInterval").contains(field.key()));
        fields.add(stringListField("tablist.header", "tablist", "Header", current.header.description,
                current.header.value, defaults.header.value, "tablist.json", true, ConfigReloadBehavior.LIVE));
        fields.add(stringListField("tablist.footer", "tablist", "Footer", current.footer.description,
                current.footer.value, defaults.footer.value, "tablist.json", true, ConfigReloadBehavior.LIVE));
        fields.add(stringListField("tablist.sorting", "tablist", "Sorting", current.sorting.description,
                current.sorting.value, defaults.sorting.value, "tablist.json", false, ConfigReloadBehavior.LIVE));
        fields.add(simpleField("tablist.refreshInterval", "tablist", "Refresh Interval",
                current.refreshInterval.description, ConfigFieldType.INTEGER, current.refreshInterval.value,
                defaults.refreshInterval.value, "tablist.json", ConfigReloadBehavior.LIVE, ConfigRiskLevel.SAFE,
                1.0, 3600.0, 1.0, List.of(), null, true, true, false, "seconds", true, false));
        fields.add(stringListField("tablist.perWorldOverrides", "tablist", "Per-world Overrides",
                "Structured world-specific header, footer, player format, and ping overrides.",
                encodeWorldOverrides(current.perWorldOverrides), encodeWorldOverrides(defaults.perWorldOverrides),
                "tablist.json", false, ConfigReloadBehavior.LIVE));
    }

    private static List<String> encodeWorldOverrides(Map<String, TablistConfigHandler.WorldOverride> overrides) {
        if (overrides == null || overrides.isEmpty()) return List.of();
        Gson gson = new Gson();
        return overrides.entrySet().stream().map(entry -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("world", entry.getKey());
            TablistConfigHandler.WorldOverride value = entry.getValue();
            if (value != null) {
                row.put("header", value.header);
                row.put("footer", value.footer);
                row.put("playerFormat", value.playerFormat);
                row.put("showPing", value.showPing);
            }
            return gson.toJson(row);
        }).toList();
    }

    private void addModerationFields(List<ConfigField> fields) {
        ModerationConfigHandler.Config current = ModerationConfigHandler.getConfig();
        ModerationConfigHandler.Config defaults = new ModerationConfigHandler.Config();
        addConfigEntries(fields, "moderation", "moderation", ModerationConfigHandler.Config.class, current, defaults,
                "moderation-settings.json", ConfigReloadBehavior.RELOAD_REQUIRED);
    }

    private void addCommandFields(List<ConfigField> fields) {
        if (services == null || services.getCommandToggleStore() == null) {
            return;
        }
        for (Map.Entry<String, Boolean> entry : services.getCommandToggleStore().listStates().entrySet()) {
            boolean protectedCommand = services.getCommandToggleStore().isProtected(entry.getKey());
            fields.add(new ConfigField(
                    "commands." + entry.getKey(),
                    "commands",
                    "/" + entry.getKey(),
                    protectedCommand ? "Protected command; it cannot be disabled." : "Enable or disable this command root.",
                    ConfigFieldType.BOOLEAN,
                    ConfigFieldValue.plain(Boolean.TRUE.equals(entry.getValue())),
                    ConfigFieldValue.plain(true),
                    DashboardPermission.CONFIG_EDIT,
                    ConfigReloadBehavior.LIVE,
                    protectedCommand ? ConfigRiskLevel.ADVANCED : ConfigRiskLevel.SAFE,
                    "commands.json",
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    !protectedCommand
            ));
        }
    }

    private void addCooldownFields(List<ConfigField> fields) {
        CooldownConfigHandler.Config config = CooldownConfigHandler.getConfig();
        for (Map.Entry<String, Integer> entry : config.commandCooldownSeconds.entrySet()) {
            fields.add(durationField("cooldowns.cooldown." + entry.getKey(), "Cooldown: /" + entry.getKey(),
                    "Cooldown before this command can be used again.", entry.getValue(), "cooldowns.json", 0, 86_400, "seconds"));
        }
        for (Map.Entry<String, Integer> entry : config.commandWarmupSeconds.entrySet()) {
            fields.add(durationField("cooldowns.warmup." + entry.getKey(), "Warmup: /" + entry.getKey(),
                    "Warmup before this teleport/action executes.", entry.getValue(), "cooldowns.json", 0, 86_400, "seconds"));
        }
    }

    private void addStorageFields(List<ConfigField> fields) {
        if (services == null || services.getStorageService() == null) {
            return;
        }
        var status = services.getStorageService().status();
        var storage = services.getStorageService().config();
        fields.add(readOnly("storage.configuredDataProvider", "storage", "Configured Data Provider", status.configuredDataProvider(), "Provider requested by storage.json.", "storage.json"));
        fields.add(readOnly("storage.activeDataProvider", "storage", "Active Data Provider", status.activeDataProvider(), "Currently active data provider after fallback handling.", "storage.json"));
        fields.add(readOnly("storage.target", "storage", "Data Target", status.target(), "Masked data storage target.", "storage.json"));
        fields.add(readOnly("storage.dataLocation", "storage", "Data Location", status.dataLocation(), "Current data location.", "storage.json"));
        fields.add(readOnly("storage.serverId", "storage", "Server ID", status.serverIdentity().serverId(), "Current server identity.", "storage.json"));
        fields.add(readOnly("storage.networkId", "storage", "Network ID", status.serverIdentity().networkId(), "Current network identity.", "storage.json"));
        fields.add(readOnly("storage.version", "storage", "Paradigm Version", ParadigmAPI.getModVersion(), "Loaded Paradigm version.", "storage.json"));
        fields.add(readOnly("storage.sqliteDriver", "storage", "SQLite Driver", status.sqliteDriverState(), "Runtime SQLite driver state.", "storage.json"));
        fields.add(readOnly("storage.mysqlDriver", "storage", "MariaDB Driver", status.mysqlDriverState(), "Runtime MariaDB driver state.", "storage.json"));
        fields.add(secretMasked("storage.sql.password", "storage", "SQL Password", storage.resolvedPassword() != null && !storage.resolvedPassword().isBlank(),
                "SQL password is masked and cannot be edited in the dashboard.", "storage.json"));
    }

    private void addDashboardFields(List<ConfigField> fields) {
        if (services == null || services.getPlatformAdapter() == null || services.getPlatformAdapter().getConfig() == null) {
            return;
        }
        DashboardConfig config = DashboardConfig.load(services.getPlatformAdapter().getConfig(), services.getLogger());
        DashboardConfig defaults = new DashboardConfig();
        fields.add(dashboardBoolean("dashboard.enabled", "Dashboard Enabled", "Start dashboard automatically on server start.", config.enabled, defaults.enabled, ConfigReloadBehavior.RELOAD_REQUIRED));
        fields.add(dashboardBoolean("dashboard.requireLogin", "Require Login", "Require a Minecraft-bound dashboard login session.", config.requireLogin, defaults.requireLogin, ConfigReloadBehavior.RELOAD_REQUIRED));
        fields.add(dashboardBoolean("dashboard.allowRemoteAccess", "Allow Remote Access", "Acknowledges that the dashboard may be exposed beyond localhost.", config.allowRemoteAccess, defaults.allowRemoteAccess, ConfigReloadBehavior.RELOAD_REQUIRED));
        fields.add(dashboardInteger("dashboard.port", "Port", "Dashboard HTTP bind port. Requires dashboard reload.", config.port, defaults.port, 1, 65535, ConfigReloadBehavior.RELOAD_REQUIRED));
        fields.add(dashboardDuration("dashboard.loginTokenMinutes", "Login Token Lifetime", "One-time login token lifetime.", config.loginTokenMinutes, defaults.loginTokenMinutes, 1, 120, "minutes"));
        fields.add(dashboardDuration("dashboard.sessionMinutes", "Session Lifetime", "Dashboard session lifetime.", config.sessionMinutes, defaults.sessionMinutes, 5, 1440, "minutes"));
        fields.add(dashboardInteger("dashboard.rateLimitPerMinute", "Rate Limit", "Maximum dashboard requests per minute per remote address.", config.rateLimitPerMinute, defaults.rateLimitPerMinute, 10, 5000, ConfigReloadBehavior.RELOAD_REQUIRED));
        fields.add(dashboardInteger("dashboard.staticCacheSeconds", "Static Cache Seconds", "Browser cache seconds for dashboard static assets.", config.staticCacheSeconds, defaults.staticCacheSeconds, 0, 86400, ConfigReloadBehavior.RELOAD_REQUIRED));
        fields.add(stringListField("dashboard.allowedOrigins", "dashboard", "Allowed Origins",
                "Optional Origin allow-list for dashboard requests.", config.allowedOrigins, defaults.allowedOrigins,
                "dashboard.json", false, ConfigReloadBehavior.RELOAD_REQUIRED));
        fields.add(simpleField("dashboard.host", "dashboard", "Bind Host", "Local bind host such as 127.0.0.1. Use 0.0.0.0 only with remote access deliberately enabled.",
                ConfigFieldType.STRING, config.host, defaults.host, "dashboard.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.ADVANCED,
                null, null, null, List.of(), null, true, false, true, "", true, false));
        fields.add(simpleField("dashboard.publicBaseUrl", "dashboard", "Public Dashboard URL", "Optional browser-visible http(s) URL used by /paradigm dashboard open.",
                ConfigFieldType.STRING, config.publicBaseUrl, defaults.publicBaseUrl, "dashboard.json", ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.ADVANCED,
                null, null, null, List.of(), null, true, true, false, "", true, false));
    }

    private void addCustomCommandFields(List<ConfigField> fields) {
        if (services == null || services.getCmConfig() == null) {
            return;
        }
        var commands = services.getCmConfig().getLoadedCommands();
        fields.add(readOnly("custom_commands.count", "custom_commands", "Loaded Custom Commands",
                String.valueOf(commands != null ? commands.size() : 0), "Custom command object editing is intentionally not raw JSON in this typed editor.",
                "config/paradigm/commands/*.json"));
        if (commands != null) {
            for (var command : commands.stream().limit(30).toList()) {
                if (command == null || command.getName() == null) {
                    continue;
                }
                fields.add(readOnly("custom_commands." + command.getName(), "custom_commands", "/" + command.getName(),
                        command.getDescription() != null ? command.getDescription() : "", "Loaded custom command summary.", "config/paradigm/commands/*.json"));
            }
        }
    }

    private void addConfigEntries(List<ConfigField> fields, String prefix, String category, Class<?> configClass, Object current,
                                  Object defaults, String owner, ConfigReloadBehavior reloadBehavior) {
        Map<String, ConfigEntry<?>> defaultEntries = configEntries(configClass, defaults);
        for (Map.Entry<String, ConfigEntry<?>> entry : configEntries(configClass, current).entrySet()) {
            ConfigField field = entryField(prefix + "." + entry.getKey(), category, entry.getKey(), entry.getValue(), defaultEntries.get(entry.getKey()),
                    owner, reloadBehavior, ConfigRiskLevel.SAFE, true);
            if (field != null) {
                fields.add(field);
            }
        }
    }

    private static List<String> restartCommands(List<RestartConfigHandler.PreRestartCommand> commands) {
        if (commands == null) return List.of();
        return commands.stream().filter(java.util.Objects::nonNull)
                .map(command -> command.secondsBefore + " | " + (command.command != null ? command.command : ""))
                .toList();
    }

    private static List<String> restartThresholds(List<Integer> thresholds) {
        if (thresholds == null) return List.of();
        return thresholds.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }

    private ConfigField entryField(String key, String category, String entryName, ConfigEntry<?> entry, ConfigEntry<?> defaultEntry,
                                   String owner, ConfigReloadBehavior reloadBehavior, ConfigRiskLevel risk, boolean editable) {
        if (entry == null) {
            return null;
        }
        Object value = entry.value;
        Object defaultValue = defaultEntry != null ? defaultEntry.value : null;
        ConfigFieldType type = inferType(key, entryName, value);
        if (type == null) {
            return null;
        }
        List<String> options = optionsFor(key, entryName, type);
        Double min = minFor(key, entryName, type);
        Double max = maxFor(key, entryName, type);
        Double step = stepFor(type);
        String durationUnit = durationUnitFor(key, entryName, type);
        boolean allowEmptyItems = "motd.motdLines".equals(key);
        return simpleField(key, category, labelFromKey(entryName), entry.description, type, value, defaultValue, owner, reloadBehavior,
                risk, min, max, step, options, type == ConfigFieldType.STRING_LIST ? ConfigFieldType.STRING : null,
                editable, false, true, durationUnit, true, allowEmptyItems);
    }

    private ConfigField simpleField(String key, String category, String label, String help, ConfigFieldType type,
                                    Object value, Object defaultValue, String owner, ConfigReloadBehavior reloadBehavior,
                                    ConfigRiskLevel risk, Double min, Double max, Double step, List<String> options,
                                    ConfigFieldType listElementType, boolean editable, boolean required, boolean nullable,
                                    String durationUnit, boolean trim, boolean allowEmptyItems) {
        return new ConfigField(
                key,
                category,
                label,
                help != null ? help : "",
                type,
                ConfigFieldValue.plain(value),
                ConfigFieldValue.plain(defaultValue),
                editable ? DashboardPermission.CONFIG_EDIT : DashboardPermission.CONFIG_VIEW,
                reloadBehavior,
                risk,
                owner,
                min,
                max,
                step,
                options != null ? options : List.of(),
                listElementType,
                editable,
                required,
                nullable,
                false,
                durationUnit != null ? durationUnit : "",
                trim,
                allowEmptyItems
        );
    }

    private ConfigField stringListField(String key, String category, String label, String help, List<String> value, List<String> defaultValue,
                                        String owner, boolean allowEmptyItems, ConfigReloadBehavior reloadBehavior) {
        return simpleField(key, category, label, help, ConfigFieldType.STRING_LIST,
                value != null ? value : List.of(), defaultValue != null ? defaultValue : List.of(), owner, reloadBehavior, ConfigRiskLevel.SAFE,
                null, null, null, List.of(), ConfigFieldType.STRING, true, false, true, "", false, allowEmptyItems);
    }

    private ConfigField durationField(String key, String label, String help, Integer value, String owner, int min, int max, String unit) {
        return simpleField(key, "cooldowns", label, help, ConfigFieldType.DURATION, Math.max(0, value != null ? value : 0), 0,
                owner, ConfigReloadBehavior.LIVE, ConfigRiskLevel.SAFE, (double) min, (double) max, 1.0, List.of(), null,
                true, false, true, unit, true, false);
    }

    private ConfigField dashboardBoolean(String key, String label, String help, boolean value, boolean defaultValue, ConfigReloadBehavior reload) {
        return simpleField(key, "dashboard", label, help, ConfigFieldType.BOOLEAN, value, defaultValue, "dashboard.json",
                reload, ConfigRiskLevel.ADVANCED, null, null, null, List.of(), null, true, false, true, "", true, false);
    }

    private ConfigField dashboardInteger(String key, String label, String help, int value, int defaultValue, int min, int max, ConfigReloadBehavior reload) {
        return simpleField(key, "dashboard", label, help, ConfigFieldType.INTEGER, value, defaultValue, "dashboard.json",
                reload, ConfigRiskLevel.ADVANCED, (double) min, (double) max, 1.0, List.of(), null, true, false, true, "", true, false);
    }

    private ConfigField dashboardDuration(String key, String label, String help, int value, int defaultValue, int min, int max, String unit) {
        return simpleField(key, "dashboard", label, help, ConfigFieldType.DURATION, value, defaultValue, "dashboard.json",
                ConfigReloadBehavior.RELOAD_REQUIRED, ConfigRiskLevel.ADVANCED, (double) min, (double) max, 1.0, List.of(), null,
                true, false, true, unit, true, false);
    }

    private ConfigField readOnly(String key, String category, String label, String value, String help, String owner) {
        return simpleField(key, category, label, help, ConfigFieldType.READ_ONLY_TEXT, value, value, owner, ConfigReloadBehavior.LIVE,
                ConfigRiskLevel.SAFE, null, null, null, List.of(), null, false, false, true, "", true, false);
    }

    private ConfigField secretMasked(String key, String category, String label, boolean set, String help, String owner) {
        return new ConfigField(key, category, label, help, ConfigFieldType.SECRET_MASKED, ConfigFieldValue.masked(set), ConfigFieldValue.masked(false),
                DashboardPermission.CONFIG_VIEW, ConfigReloadBehavior.RESTART_REQUIRED, ConfigRiskLevel.DANGEROUS, owner,
                null, null, null, List.of(), null, false, false, true, true, "", true, false);
    }

    private static ConfigFieldType inferType(String key, String name, Object value) {
        if (value instanceof Boolean) {
            return ConfigFieldType.BOOLEAN;
        }
        if (value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String)) {
            return ConfigFieldType.STRING_LIST;
        }
        if (isEnumField(key, name)) {
            return ConfigFieldType.ENUM;
        }
        if (isDurationField(key, name)) {
            return ConfigFieldType.DURATION;
        }
        if (isColorField(key, name)) {
            return ConfigFieldType.COLOR;
        }
        if (value instanceof Integer || value instanceof Long) {
            return ConfigFieldType.INTEGER;
        }
        if (value instanceof Double || value instanceof Float) {
            return ConfigFieldType.DOUBLE;
        }
        if (value instanceof String) {
            return ConfigFieldType.STRING;
        }
        return null;
    }

    private static boolean isEnumField(String key, String name) {
        String normalized = (key + "." + name).toLowerCase(Locale.ROOT);
        return normalized.endsWith("defaultlanguage")
                || normalized.endsWith("externalcommandpermissionmode")
                || normalized.endsWith("ordermode")
                || normalized.endsWith("restarttype");
    }

    private static boolean isDurationField(String key, String name) {
        String normalized = (key + "." + name).toLowerCase(Locale.ROOT);
        return normalized.contains("interval")
                || normalized.contains("ratelimit")
                || normalized.contains("time")
                || normalized.contains("cooldown")
                || normalized.contains("warmup")
                || normalized.contains("sessionminutes")
                || normalized.contains("logintokenminutes");
    }

    private static boolean isColorField(String key, String name) {
        String normalized = (key + "." + name).toLowerCase(Locale.ROOT);
        return normalized.endsWith("color") || normalized.endsWith("bossbarcolor");
    }

    private static List<String> optionsFor(String key, String name, ConfigFieldType type) {
        String normalized = (key + "." + name).toLowerCase(Locale.ROOT);
        if (normalized.endsWith("defaultlanguage")) {
            return List.of("en", "cs", "ru");
        }
        if (normalized.endsWith("externalcommandpermissionmode")) {
            return List.of("deny_only", "strict");
        }
        if (normalized.endsWith("ordermode")) {
            return List.of("SEQUENTIAL", "RANDOM");
        }
        if (normalized.endsWith("restarttype")) {
            return List.of("Fixed", "Realtime", "None");
        }
        if (type == ConfigFieldType.COLOR && normalized.endsWith("bossbarcolor")) {
            return List.of("BLUE", "GREEN", "PINK", "PURPLE", "RED", "WHITE", "YELLOW");
        }
        return List.of();
    }

    private static Double minFor(String key, String name, ConfigFieldType type) {
        if (type == ConfigFieldType.INTEGER || type == ConfigFieldType.DOUBLE || type == ConfigFieldType.DURATION) {
            return 0.0;
        }
        return null;
    }

    private static Double maxFor(String key, String name, ConfigFieldType type) {
        String normalized = (key + "." + name).toLowerCase(Locale.ROOT);
        if (normalized.contains("port")) return 65_535.0;
        if (normalized.contains("interval") || normalized.contains("cooldown") || normalized.contains("warmup") || normalized.contains("ratelimit")) return 86_400.0;
        if (normalized.contains("time")) return 86_400.0;
        if (type == ConfigFieldType.INTEGER || type == ConfigFieldType.DOUBLE || type == ConfigFieldType.DURATION) return 1_000_000.0;
        return null;
    }

    private static Double stepFor(ConfigFieldType type) {
        if (type == ConfigFieldType.DOUBLE) {
            return 0.1;
        }
        if (type == ConfigFieldType.INTEGER || type == ConfigFieldType.DURATION) {
            return 1.0;
        }
        return null;
    }

    private static String durationUnitFor(String key, String name, ConfigFieldType type) {
        if (type != ConfigFieldType.DURATION) {
            return "";
        }
        String normalized = (key + "." + name).toLowerCase(Locale.ROOT);
        if (normalized.contains("minutes")) {
            return "minutes";
        }
        if (normalized.contains("restartinterval")) {
            return "hours";
        }
        return "seconds";
    }

    private static MOTDConfigHandler.ServerListMOTD firstMotd(MOTDConfigHandler.Config config) {
        List<MOTDConfigHandler.ServerListMOTD> motds = config != null && config.motds != null ? config.motds.value : null;
        if (motds == null || motds.isEmpty() || motds.get(0) == null) {
            return new MOTDConfigHandler.ServerListMOTD();
        }
        return motds.get(0);
    }

    private static Map<String, ConfigEntry<?>> configEntries(Class<?> configClass, Object config) {
        Map<String, ConfigEntry<?>> result = new LinkedHashMap<>();
        for (Field field : configClass.getDeclaredFields()) {
            if (field.getType() != ConfigEntry.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(config);
                if (value instanceof ConfigEntry<?> entry) {
                    result.put(field.getName(), entry);
                }
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    private static boolean isUnsafeMainField(String name) {
        return "debugEnable".equals(name)
                || "telemetryEnable".equals(name)
                || "telemetryServerId".equals(name)
                || "webEditorTestUrl".equals(name)
                || "spawnWorld".equals(name)
                || name != null && name.startsWith("spawn");
    }

    private static ConfigRiskLevel riskForMain(String name) {
        if ("internalPermissionsEnable".equals(name)
                || "externalCommandPermissionsEnable".equals(name)
                || "externalCommandPermissionMode".equals(name)
                || "registerForgePermissionHandler".equals(name)
                || "forceCommandPriorityEnable".equals(name)) {
            return ConfigRiskLevel.ADVANCED;
        }
        return ConfigRiskLevel.SAFE;
    }

    private static String categoryForMain(String name, ConfigEntry<?> entry) {
        if (name == null) {
            return "modules";
        }
        if (name.equals("adminUtilityCommandsEnable") || name.equals("moderationCommandsEnable")) {
            return "admin_utilities";
        }
        if (name.equals("homeCommandsEnable") || name.equals("tpaCommandsEnable") || name.equals("warpCommandsEnable") || name.equals("spawnCommandsEnable")) {
            return "teleports";
        }
        if (entry != null && entry.value instanceof Boolean && isCommandGroupToggle(name)) {
            return "command_groups";
        }
        return "modules";
    }

    private static boolean isCommandGroupToggle(String name) {
        return name != null && (name.endsWith("CommandsEnable") || name.endsWith("CommandEnable"));
    }

    private static String labelFromKey(String key) {
        if (key == null || key.isBlank()) {
            return "Config Field";
        }
        String spaced = key.replace('_', ' ')
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace("Enable", "")
                .trim();
        if (spaced.isBlank()) {
            return key;
        }
        return spaced.substring(0, 1).toUpperCase(Locale.ROOT) + spaced.substring(1);
    }
}
