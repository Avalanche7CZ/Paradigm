package eu.avalanche7.paradigm.modules.commands;

import eu.avalanche7.paradigm.ParadigmAPI;
import eu.avalanche7.paradigm.configs.*;
import eu.avalanche7.paradigm.core.ParadigmModule;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.platform.Interfaces.*;
import eu.avalanche7.paradigm.modules.Announcements;
import eu.avalanche7.paradigm.modules.Restart;
import eu.avalanche7.paradigm.utils.CommandToggleStore;
import eu.avalanche7.paradigm.utils.PermissionsHandler;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
                                    services.getCmConfig().reloadCommands();
                                    services.getPermissionsHandler().refreshCustomCommandPermissions();
                                    msg = "Custom commands config reloaded.";
                                }
                                case "all" -> {
                                    MainConfigHandler.reload();
                                    AnnouncementsConfigHandler.reload();
                                    ChatConfigHandler.reload();
                                    MOTDConfigHandler.reload();
                                    MentionConfigHandler.reload();
                                    RestartConfigHandler.reload();
                                    services.getCmConfig().reloadCommands();
                                    services.getPermissionsHandler().refreshCustomCommandPermissions();
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
                .then(buildCommandToggleBranch(platform, services));

        if (services.getPermissionsHandler().isInternalPermissionsEnabled()) {
            paradigm = paradigm.then(buildGroupBranch(platform, services));
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
                    sendToggleList(ctx.getSource(), services);
                    return 1;
                });

        ICommandBuilder list = platform.createCommandBuilder()
                .literal("list")
                .executes(ctx -> {
                    sendToggleList(ctx.getSource(), services);
                    return 1;
                });

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
                    sendToggleMessage(ctx.getSource(), services, "command_toggle.reloaded", "Command toggles reloaded from commands.json.");
                    return 1;
                });

        return root.then(list).then(status).then(enable).then(disable).then(reload);
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
        return 1;
    }

    private void sendToggleList(ICommandSource source, Services services) {
        Map<String, Boolean> states = services.getCommandToggleStore().listStates();
        StringBuilder sb = new StringBuilder();
        int index = 0;
        for (Map.Entry<String, Boolean> entry : states.entrySet()) {
            if (index++ > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue() ? "on" : "off");
        }

        sendToggleMessage(source, services, "command_toggle.list", "Command states: {list}", "{list}", sb.toString());
    }

    private boolean hasReloadPermission(ICommandSource src, Services services) {
        if (src == null) return false;
        if (src.isConsole()) return true;
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
        IPlayer player = src.getPlayer();
        return player != null && services.getPermissionsHandler().hasPermission(
                player,
                PermissionsHandler.COMMAND_TOGGLE_PERMISSION,
                PermissionsHandler.COMMAND_TOGGLE_PERMISSION_LEVEL
        );
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
                    sendGroupMessage(ctx.getSource(), services, "group.manage.help", "Use /paradigm group list|add|remove|info|parent|user");
                    return 1;
                });

        ICommandBuilder list = platform.createCommandBuilder()
                .literal("list")
                .executes(ctx -> {
                    List<String> groups = services.getPermissionsHandler().listPermissionGroups();
                    sendGroupMessage(ctx.getSource(), services, "group.manage.list", "Groups: {groups}",
                            "{groups}", groups.isEmpty() ? "-" : String.join(", ", groups));
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
                            var infoData = services.getPermissionsHandler().getPermissionGroupInfo(name);
                            if (infoData == null) {
                                sendGroupMessage(ctx.getSource(), services, "group.manage.not_found", "Group {group} was not found.", "{group}", name);
                                return 0;
                            }
                            sendGroupMessage(ctx.getSource(), services, "group.manage.info_header", "Group {group} | weight={weight}",
                                    "{group}", infoData.name(), "{weight}", String.valueOf(infoData.weight()));
                            sendGroupMessage(ctx.getSource(), services, "group.manage.info_parents", "Parents: {parents}",
                                    "{parents}", infoData.inherits().isEmpty() ? "-" : String.join(", ", infoData.inherits()));
                            sendGroupMessage(ctx.getSource(), services, "group.manage.info_perms", "Perms: {perms}",
                                    "{perms}", infoData.permissions().isEmpty() ? "-" : String.join(", ", infoData.permissions()));
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
                        .literal("list")
                        .then(platform.createCommandBuilder()
                                .argument("player", ICommandBuilder.ArgumentType.WORD)
                                .executes(ctx -> {
                                    UUID uuid = resolvePlayerUuid(services, ctx.getStringArgument("player"));
                                    if (uuid == null) {
                                        sendGroupMessage(ctx.getSource(), services, "group.manage.user_invalid", "Player must be online name or UUID.");
                                        return 0;
                                    }
                                    var data = services.getPermissionsHandler().getPlayerGroups(uuid);
                                    if (data == null) {
                                        sendGroupMessage(ctx.getSource(), services, "group.manage.user_empty", "No groups assigned.");
                                        return 1;
                                    }
                                    sendGroupMessage(ctx.getSource(), services, "group.manage.user_list_perm", "Permanent: {groups}",
                                            "{groups}", data.permanentGroups().isEmpty() ? "-" : String.join(", ", data.permanentGroups()));
                                    if (data.temporaryGroups().isEmpty()) {
                                        sendGroupMessage(ctx.getSource(), services, "group.manage.user_list_temp", "Temporary: -");
                                        return 1;
                                    }
                                    for (var temp : data.temporaryGroups()) {
                                        sendGroupMessage(ctx.getSource(), services, "group.manage.user_temp_line", "Temporary {group} until {until}",
                                                "{group}", temp.group(),
                                                "{until}", Instant.ofEpochMilli(temp.expiresAtMs()).toString());
                                    }
                                    return 1;
                                })));

        return root.then(list).then(add).then(remove).then(info).then(parent).then(user);
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

    private boolean hasGroupManagePermission(ICommandSource src, Services services) {
        if (src == null) return false;
        if (src.isConsole()) return true;
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
