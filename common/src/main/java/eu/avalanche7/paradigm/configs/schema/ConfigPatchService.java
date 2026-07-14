package eu.avalanche7.paradigm.configs.schema;

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
import eu.avalanche7.paradigm.modules.tab.Tablist;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.DashboardConfig;
import eu.avalanche7.paradigm.modules.commands.Reload;
import eu.avalanche7.paradigm.modules.commands.shared.DurationParser;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class ConfigPatchService {
    private static final Pattern HEX_COLOR = Pattern.compile("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})");
    private static final Pattern NAMED_COLOR = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Services services;
    private final ConfigSchemaRegistry registry;

    public ConfigPatchService(Services services, ConfigSchemaRegistry registry) {
        this.services = services;
        this.registry = registry;
    }

    public ConfigValidationResult apply(ConfigPatch patch) {
        ConfigValidationResult result = new ConfigValidationResult();
        ConfigSnapshot current = registry.snapshot();
        if (patch == null || patch.operations() == null) {
            result.reject("<patch>", "Patch is empty.");
            result.newRevision(current.revision());
            return result;
        }
        if (patch.revision() != null && !patch.revision().isBlank() && !patch.revision().equals(current.revision())) {
            result.reject("<revision>", "Config changed while the dashboard was open. Reload the snapshot and try again.");
            result.newRevision(current.revision());
            return result;
        }

        Map<String, ConfigField> fields = new HashMap<>();
        for (ConfigField field : current.fields()) {
            fields.put(field.key(), field);
        }

        boolean mainChanged = false;
        boolean chatChanged = false;
        boolean mentionsChanged = false;
        boolean announcementsChanged = false;
        boolean restartChanged = false;
        boolean motdChanged = false;
        boolean dashboardChanged = false;
        boolean moderationChanged = false;
        boolean commandsChanged = false;
        boolean cooldownsChanged = false;
        boolean tablistChanged = false;

        for (ConfigPatchOperation op : patch.operations()) {
            String key = op != null ? normalizeKey(op.key()) : null;
            ConfigField field = key != null ? fields.get(key) : null;
            if (field == null) {
                result.reject(key != null ? key : "<unknown>", "Unknown config field.");
                continue;
            }
            if (!field.editable()) {
                result.reject(key, "Field is read-only.");
                continue;
            }

            try {
                Object value = validateValue(field, op.value());
                if (key.startsWith("main.")) {
                    applyConfigEntry(MainConfigHandler.Config.class, MainConfigHandler.getConfig(), key.substring("main.".length()), value);
                    mainChanged = true;
                    result.accept(key);
                } else if (key.startsWith("chat.")) {
                    applyConfigEntry(ChatConfigHandler.Config.class, ChatConfigHandler.getConfig(), key.substring("chat.".length()), value);
                    chatChanged = true;
                    result.accept(key);
                } else if (key.startsWith("mentions.")) {
                    applyConfigEntry(MentionConfigHandler.Config.class, MentionConfigHandler.getConfig(), key.substring("mentions.".length()), value);
                    mentionsChanged = true;
                    result.accept(key);
                } else if (key.startsWith("announcements.")) {
                    applyConfigEntry(AnnouncementsConfigHandler.Config.class, AnnouncementsConfigHandler.getConfig(), key.substring("announcements.".length()), value);
                    announcementsChanged = true;
                    result.accept(key);
                } else if (key.startsWith("restart.")) {
                    applyRestart(key.substring("restart.".length()), value);
                    restartChanged = true;
                    result.accept(key);
                } else if (key.startsWith("motd.")) {
                    applyMotd(key.substring("motd.".length()), value);
                    motdChanged = true;
                    result.accept(key);
                } else if (key.startsWith("moderation.")) {
                    applyConfigEntry(ModerationConfigHandler.Config.class, ModerationConfigHandler.getConfig(), key.substring("moderation.".length()), value);
                    moderationChanged = true;
                    result.accept(key);
                } else if (key.startsWith("dashboard.")) {
                    applyDashboard(key.substring("dashboard.".length()), value);
                    dashboardChanged = true;
                    result.accept(key);
                } else if (key.startsWith("tablist.")) {
                    applyTablist(key.substring("tablist.".length()), value);
                    tablistChanged = true;
                    result.accept(key);
                } else if (key.startsWith("commands.")) {
                    applyCommand(key.substring("commands.".length()), value, result, key);
                    commandsChanged = true;
                } else if (key.startsWith("cooldowns.cooldown.")) {
                    applyCooldown(key.substring("cooldowns.cooldown.".length()), value, true);
                    cooldownsChanged = true;
                    result.accept(key);
                } else if (key.startsWith("cooldowns.warmup.")) {
                    applyCooldown(key.substring("cooldowns.warmup.".length()), value, false);
                    cooldownsChanged = true;
                    result.accept(key);
                } else {
                    result.reject(key, "Unsupported field.");
                }
            } catch (IllegalArgumentException e) {
                result.reject(key, e.getMessage());
            } catch (Throwable t) {
                result.reject(key, "Failed to apply field.");
                if (services != null && services.getLogger() != null) {
                    services.getLogger().warn("Paradigm Dashboard: failed to apply config field {}: {}", key, t.toString());
                }
            }
        }

        if (mainChanged) {
            MainConfigHandler.persistConfig();
            scheduleServerThread(() -> Reload.refreshModuleStatesForHelp(services));
            result.warn("Main config changes were saved. Some effects may require a reload or restart.");
        }
        if (chatChanged) {
            ChatConfigHandler.persistConfig();
            result.warn("Chat config changes were saved. Some effects may require a reload.");
        }
        if (mentionsChanged) {
            MentionConfigHandler.persistConfig();
            result.warn("Mention config changes were saved. Some effects may require a reload.");
        }
        if (announcementsChanged) {
            AnnouncementsConfigHandler.persistConfig();
            result.warn("Announcement config changes were saved. Some effects may require a reload.");
        }
        if (restartChanged) {
            RestartConfigHandler.persistConfig();
            result.warn("Restart config changes were saved. Some effects may require a reload.");
        }
        if (motdChanged) {
            MOTDConfigHandler.persistConfig();
            result.warn("MOTD config changes were saved. Some effects may require a reload.");
        }
        if (moderationChanged) {
            ModerationConfigHandler.persistConfig();
            services.getPunishmentService().refreshAsync();
            result.warn("Moderation settings were saved and apply immediately.");
        }
        if (dashboardChanged) {
            result.warn("Dashboard config changes were saved. Use Apply Reload to activate bind/session changes.");
        }
        if (commandsChanged) {
            scheduleServerThread(() -> services.getPlatformAdapter().refreshAllPlayerCommandTrees());
        }
        if (cooldownsChanged) {
            CooldownConfigHandler.persistConfig();
        }
        if (tablistChanged) {
            TablistConfigHandler.persistConfig();
            scheduleServerThread(() -> {
                Tablist tablist = Tablist.current();
                if (tablist != null) tablist.reload();
            });
            result.warn("Tablist settings were saved and applied.");
        }

        result.newRevision(registry.snapshot().revision());
        return result;
    }

    static Object validateValue(ConfigField field, Object rawValue) {
        if (field == null) {
            throw new IllegalArgumentException("Unknown config field.");
        }
        if (!field.editable() || field.type() == ConfigFieldType.READ_ONLY_TEXT) {
            throw new IllegalArgumentException("Field is read-only.");
        }
        if (field.masked() || field.type() == ConfigFieldType.SECRET_MASKED || (field.value() != null && field.value().secret())) {
            throw new IllegalArgumentException("Secret fields cannot be edited in the dashboard.");
        }
        if (rawValue == null) {
            if (!field.nullable() || field.required()) {
                throw new IllegalArgumentException("Value is required.");
            }
            return null;
        }

        return switch (field.type()) {
            case BOOLEAN -> requireBoolean(rawValue);
            case INTEGER -> validateRange(field, requireInteger(rawValue));
            case DOUBLE -> validateRange(field, requireDouble(rawValue));
            case STRING, PERMISSION_NODE, COMMAND_ID -> validateString(field, rawValue);
            case ENUM -> validateEnum(field, rawValue);
            case STRING_LIST -> validateStringList(field, rawValue);
            case DURATION -> validateDuration(field, rawValue);
            case COLOR -> validateColor(field, rawValue);
            case READ_ONLY_TEXT, SECRET_MASKED -> throw new IllegalArgumentException("Field is read-only.");
        };
    }

    private void applyRestart(String fieldName, Object value) throws Exception {
        if ("restartInterval".equals(fieldName)) {
            double hours = ((Number) value).doubleValue();
            if (!Double.isFinite(hours) || hours <= 0.0) throw new IllegalArgumentException("Fixed restart interval must be greater than zero.");
            RestartConfigHandler.getConfig().restartInterval.value = hours;
            return;
        }
        if ("realTimeInterval".equals(fieldName)) {
            RestartConfigHandler.getConfig().realTimeInterval.value = validateRestartTimes(stringList(value));
            return;
        }
        if ("timerBroadcast".equals(fieldName)) {
            RestartConfigHandler.getConfig().timerBroadcast.value = parseRestartThresholds(stringList(value));
            return;
        }
        if ("preRestartCommands".equals(fieldName)) {
            List<RestartConfigHandler.PreRestartCommand> commands = new ArrayList<>();
            for (String row : stringList(value)) {
                int separator = row.indexOf('|');
                if (separator <= 0) throw new IllegalArgumentException("Pre-restart commands must use: seconds before | command.");
                int seconds;
                try { seconds = Integer.parseInt(row.substring(0, separator).trim()); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("Pre-restart command seconds must be an integer."); }
                String command = row.substring(separator + 1).trim();
                if (seconds < 0 || seconds > 86_400 || command.isBlank() || command.length() > 512) {
                    throw new IllegalArgumentException("Pre-restart command values are invalid.");
                }
                commands.add(new RestartConfigHandler.PreRestartCommand(seconds, command));
            }
            RestartConfigHandler.getConfig().preRestartCommands.value = commands;
            return;
        }
        applyConfigEntry(RestartConfigHandler.Config.class, RestartConfigHandler.getConfig(), fieldName, value);
    }

    private void applyTablist(String fieldName, Object value) throws Exception {
        TablistConfigHandler.Config config = TablistConfigHandler.getConfig();
        if ("perWorldOverrides".equals(fieldName)) {
            Map<String, TablistConfigHandler.WorldOverride> overrides = new java.util.LinkedHashMap<>();
            Gson gson = new Gson();
            for (String raw : stringList(value)) {
                JsonObject row;
                try {
                    row = gson.fromJson(raw, JsonObject.class);
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("World override data is invalid.");
                }
                String world = row != null && row.has("world") ? row.get("world").getAsString().trim() : "";
                if (world.isEmpty() || world.length() > 128 || overrides.containsKey(world)) {
                    throw new IllegalArgumentException("World override names must be non-empty and unique.");
                }
                TablistConfigHandler.WorldOverride override = new TablistConfigHandler.WorldOverride();
                if (row.has("header") && !row.get("header").isJsonNull()) override.header = gson.fromJson(row.get("header"), new com.google.gson.reflect.TypeToken<List<String>>() { }.getType());
                if (row.has("footer") && !row.get("footer").isJsonNull()) override.footer = gson.fromJson(row.get("footer"), new com.google.gson.reflect.TypeToken<List<String>>() { }.getType());
                if (row.has("playerFormat") && !row.get("playerFormat").isJsonNull()) override.playerFormat = row.get("playerFormat").getAsString();
                if (row.has("showPing") && !row.get("showPing").isJsonNull()) override.showPing = row.get("showPing").getAsBoolean();
                overrides.put(world, override);
            }
            config.perWorldOverrides = overrides;
            return;
        }
        if ("sorting".equals(fieldName)) {
            List<String> rules = stringList(value);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String rule : rules) {
                if (eu.avalanche7.paradigm.modules.tab.TablistSortRule.parse(rule) == null || !seen.add(rule.toUpperCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Tablist sorting rules must be supported and unique.");
                }
            }
            config.sorting.value = rules.stream().map(rule -> rule.toUpperCase(Locale.ROOT)).toList();
            return;
        }
        applyConfigEntry(TablistConfigHandler.Config.class, config, fieldName, value);
    }

    static List<String> validateRestartTimes(List<String> values) {
        List<String> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String raw : values) {
            String value = raw == null ? "" : raw.trim();
            if (!value.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
                throw new IllegalArgumentException("Realtime restart values must use HH:mm.");
            }
            if (!seen.add(value)) throw new IllegalArgumentException("Realtime restart times must be unique.");
            result.add(value);
        }
        return result;
    }

    static List<Integer> parseRestartThresholds(List<String> values) {
        List<Integer> result = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (String raw : values) {
            int seconds;
            try { seconds = Integer.parseInt(raw.trim()); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Restart warning thresholds must be whole seconds."); }
            if (seconds <= 0 || seconds > 86_400) throw new IllegalArgumentException("Restart warning thresholds must be between 1 and 86400 seconds.");
            if (!seen.add(seconds)) throw new IllegalArgumentException("Restart warning thresholds must be unique.");
            result.add(seconds);
        }
        return result;
    }

    private void applyDashboard(String fieldName, Object value) {
        DashboardConfig config = DashboardConfig.load(services.getPlatformAdapter().getConfig(), services.getLogger());
        switch (fieldName) {
            case "enabled" -> config.enabled = (Boolean) value;
            case "requireLogin" -> config.requireLogin = (Boolean) value;
            case "allowRemoteAccess" -> config.allowRemoteAccess = (Boolean) value;
            case "port" -> config.port = (Integer) value;
            case "loginTokenMinutes" -> config.loginTokenMinutes = (Integer) value;
            case "sessionMinutes" -> config.sessionMinutes = (Integer) value;
            case "rateLimitPerMinute" -> config.rateLimitPerMinute = (Integer) value;
            case "staticCacheSeconds" -> config.staticCacheSeconds = (Integer) value;
            case "allowedOrigins" -> config.allowedOrigins = stringList(value);
            case "host" -> {
                String host = ((String) value).trim();
                if (host.length() > 255 || !host.matches("(?:[A-Za-z0-9.-]+|[0-9A-Fa-f:]+|\\[[0-9A-Fa-f:]+\\])")) throw new IllegalArgumentException("Dashboard host is invalid.");
                config.host = host;
            }
            case "publicBaseUrl" -> {
                String url = ((String) value).trim();
                if (!url.isBlank() && !(url.startsWith("http://") || url.startsWith("https://"))) throw new IllegalArgumentException("Public dashboard URL must use http or https.");
                config.publicBaseUrl = url.replaceAll("/+$", "");
            }
            default -> throw new IllegalArgumentException("Unsupported dashboard config field.");
        }
        DashboardConfig.save(services.getPlatformAdapter().getConfig(), config);
    }

    private void applyCommand(String commandId, Object value, ConfigValidationResult result, String key) {
        boolean enabled = (Boolean) value;
        var toggleResult = services.getCommandToggleStore().setEnabled(commandId, enabled);
        if (!toggleResult.ok()) {
            throw new IllegalArgumentException("protected".equals(toggleResult.reason()) ? "Protected command cannot be disabled." : "Unknown command.");
        }
        result.accept(key);
    }

    private void applyCooldown(String commandId, Object value, boolean cooldown) {
        int seconds = (Integer) value;
        String normalized = normalizeCommand(commandId);
        if (normalized == null) {
            throw new IllegalArgumentException("Invalid command id.");
        }
        if (cooldown) {
            CooldownConfigHandler.getConfig().commandCooldownSeconds.put(normalized, seconds);
        } else {
            CooldownConfigHandler.getConfig().commandWarmupSeconds.put(normalized, seconds);
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyConfigEntry(Class<?> configClass, Object config, String fieldName, Object value) throws Exception {
        Field field = configClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object entryObj = field.get(config);
        if (!(entryObj instanceof ConfigEntry<?>)) {
            throw new IllegalArgumentException("Field is not a typed config entry.");
        }
        ((ConfigEntry<Object>) entryObj).value = value;
    }

    @SuppressWarnings("unchecked")
    private void applyMotd(String fieldName, Object value) {
        MOTDConfigHandler.Config config = MOTDConfigHandler.getConfig();
        switch (fieldName) {
            case "motdLines" -> config.motdLines = stringList(value);
            case "iconEnabled" -> config.iconEnabled.value = (Boolean) value;
            case "serverlistMotdEnabled" -> config.serverlistMotdEnabled.value = (Boolean) value;
            case "serverlist.0.icon" -> firstMotd(config).icon = (String) value;
            case "serverlist.0.line1" -> firstMotd(config).line1 = (String) value;
            case "serverlist.0.line2" -> firstMotd(config).line2 = (String) value;
            case "serverlist.0.playerCount.hoverText" -> firstMotd(config).playerCount.hoverText = (String) value;
            case "serverlist.0.playerCount.maxPlayers" -> firstMotd(config).playerCount.maxPlayers = (Integer) value;
            case "serverlist.0.playerCount.showActualCount" -> firstMotd(config).playerCount.showActualCount = (Boolean) value;
            default -> throw new IllegalArgumentException("Unsupported MOTD config field.");
        }
        if (config.motds.value == null || config.motds.value.isEmpty()) {
            config.motds.value = List.of(firstMotd(config));
        }
    }

    private MOTDConfigHandler.ServerListMOTD firstMotd(MOTDConfigHandler.Config config) {
        List<MOTDConfigHandler.ServerListMOTD> motds = config.motds.value;
        if (motds == null || motds.isEmpty()) {
            MOTDConfigHandler.ServerListMOTD created = new MOTDConfigHandler.ServerListMOTD();
            created.playerCount = new MOTDConfigHandler.PlayerCountDisplay();
            config.motds.value = new ArrayList<>(List.of(created));
            return created;
        }
        MOTDConfigHandler.ServerListMOTD first = motds.get(0);
        if (first == null) {
            first = new MOTDConfigHandler.ServerListMOTD();
            if (motds instanceof ArrayList<MOTDConfigHandler.ServerListMOTD> mutable) {
                mutable.set(0, first);
            } else {
                config.motds.value = new ArrayList<>(motds);
                config.motds.value.set(0, first);
            }
        } else if (!(motds instanceof ArrayList<MOTDConfigHandler.ServerListMOTD>)) {
            config.motds.value = new ArrayList<>(motds);
        }
        if (first.playerCount == null) {
            first.playerCount = new MOTDConfigHandler.PlayerCountDisplay();
        }
        return first;
    }

    private void scheduleServerThread(Runnable runnable) {
        if (services != null && services.getTaskScheduler() != null) {
            services.getTaskScheduler().schedule(runnable, 0L, TimeUnit.MILLISECONDS);
        } else if (runnable != null) {
            runnable.run();
        }
    }

    private static boolean requireBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s))) {
            return Boolean.parseBoolean(s);
        }
        throw new IllegalArgumentException("Expected boolean value.");
    }

    private static int requireInteger(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Math.rint(d) != d) {
                throw new IllegalArgumentException("Expected integer value.");
            }
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Expected integer value.");
            }
        }
        throw new IllegalArgumentException("Expected integer value.");
    }

    private static double requireDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Expected number value.");
            }
        }
        throw new IllegalArgumentException("Expected number value.");
    }

    private static int validateRange(ConfigField field, int value) {
        if (field.min() != null && value < field.min()) {
            throw new IllegalArgumentException("Value must be at least " + trimNumber(field.min()) + ".");
        }
        if (field.max() != null && value > field.max()) {
            throw new IllegalArgumentException("Value must be at most " + trimNumber(field.max()) + ".");
        }
        return value;
    }

    private static double validateRange(ConfigField field, double value) {
        if (field.min() != null && value < field.min()) {
            throw new IllegalArgumentException("Value must be at least " + trimNumber(field.min()) + ".");
        }
        if (field.max() != null && value > field.max()) {
            throw new IllegalArgumentException("Value must be at most " + trimNumber(field.max()) + ".");
        }
        return value;
    }

    private static String validateString(ConfigField field, Object rawValue) {
        if (!(rawValue instanceof String raw)) {
            throw new IllegalArgumentException("Expected text value.");
        }
        String value = field.trim() ? raw.trim() : raw;
        if (field.required() && value.isEmpty()) {
            throw new IllegalArgumentException("Value is required.");
        }
        if (!field.nullable() && value.isEmpty()) {
            throw new IllegalArgumentException("Value cannot be empty.");
        }
        return value;
    }

    private static String validateEnum(ConfigField field, Object rawValue) {
        String value = validateString(field, rawValue);
        for (String option : field.options()) {
            if (option.equals(value)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Invalid option.");
    }

    private static List<String> validateStringList(ConfigField field, Object rawValue) {
        if (!(rawValue instanceof List<?> rawList)) {
            throw new IllegalArgumentException("Expected text list value.");
        }
        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            if (!(item instanceof String raw)) {
                throw new IllegalArgumentException("List items must be text.");
            }
            String value = field.trim() ? raw.trim() : raw;
            if (!field.allowEmptyItems() && value.isEmpty()) {
                throw new IllegalArgumentException("List items cannot be empty.");
            }
            result.add(value);
        }
        return result;
    }

    private static Object validateDuration(ConfigField field, Object rawValue) {
        if (rawValue instanceof String s && !s.trim().matches("-?\\d+(\\.\\d+)?")) {
            long millis = DurationParser.parseToMillis(s);
            if (millis <= 0L) {
                throw new IllegalArgumentException("Invalid duration.");
            }
            return durationFromMillis(field, millis);
        }
        if (field.value() != null && field.value().value() instanceof Double) {
            return validateRange(field, requireDouble(rawValue));
        }
        return validateRange(field, requireInteger(rawValue));
    }

    private static Object durationFromMillis(ConfigField field, long millis) {
        String unit = field.durationUnit() != null ? field.durationUnit() : "seconds";
        double divisor = switch (unit) {
            case "minutes" -> 60_000.0;
            case "hours" -> 3_600_000.0;
            default -> 1_000.0;
        };
        double normalized = millis / divisor;
        if (field.value() != null && field.value().value() instanceof Double) {
            return validateRange(field, normalized);
        }
        if (Math.rint(normalized) != normalized) {
            throw new IllegalArgumentException("Duration must align to " + unit + ".");
        }
        return validateRange(field, (int) normalized);
    }

    private static String validateColor(ConfigField field, Object rawValue) {
        String value = validateString(field, rawValue);
        if (!field.options().isEmpty()) {
            for (String option : field.options()) {
                if (option.equals(value)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Invalid color option.");
        }
        if (HEX_COLOR.matcher(value).matches() || NAMED_COLOR.matcher(value).matches()) {
            return value;
        }
        throw new IllegalArgumentException("Invalid color value.");
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    private static String normalizeKey(String key) {
        return key != null ? key.trim() : null;
    }

    private static String normalizeCommand(String commandId) {
        String normalized = commandId != null ? commandId.trim().toLowerCase(Locale.ROOT) : "";
        return normalized.isBlank() ? null : normalized;
    }

    private static String trimNumber(double value) {
        return Math.rint(value) == value ? String.valueOf((long) value) : String.valueOf(value);
    }
}
