package eu.avalanche7.paradigm.modules.commands.permissions;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.PermissionAssignment;
import eu.avalanche7.paradigm.modules.permissions.PermissionDisplayFormatter;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.modules.permissions.PermissionNodeRegistry;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class PermissionPanelRenderer {
    private PermissionPanelRenderer() {
    }

    public static void sendPermissionsHome(ICommandSource source, Services services) {
        sendUiLine(source, services, header(services, translation(services, "permission.panel.title", "Permissions")));
        sendUiLine(source, services, row(services)
                .append(button(services, bracket(services, "permission.panel.groups", "groups"), "/paradigm group list", true,
                        translation(services, "permission.panel.tooltip.open_group_list", "Open group list"), "60A5FA"))
                .append(space(services))
                .append(button(services, addLabel(services, "permission.panel.group", "group"), "/paradigm group add ", false,
                        translation(services, "permission.panel.tooltip.create_group", "Prepare group creation"), "34D399"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.check", "check"), "/paradigm permission check ", false,
                        translation(services, "permission.panel.tooltip.check_permission", "Prepare permission check"), "FBBF24"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.nodes", "nodes"), "/paradigm permission nodes", true,
                        translation(services, "permission.panel.tooltip.list_nodes", "List discovered permission nodes"), "60A5FA")));
        sendUiLine(source, services, row(services)
                .append(text(services, translation(services, "permission.panel.quick", "Quick") + ": ", "64748B"))
                .append(button(services, bracket(services, "permission.panel.group", "group"), "/paradigm group info ", false,
                        translation(services, "permission.panel.tooltip.group_detail", "Prepare group detail"), "60A5FA"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.user", "user"), "/paradigm group user info ", false,
                        translation(services, "permission.panel.tooltip.user_detail", "Prepare user detail"), "60A5FA")));
    }

    public static void sendGroupList(ICommandSource source, Services services) {
        List<String> groups = services.getPermissionsHandler().listPermissionGroups();
        sendUiLine(source, services, header(services, translation(services, "permission.panel.group_count", "Groups ({count})", "{count}", String.valueOf(groups.size()))));
        if (groups.isEmpty()) {
            sendUiLine(source, services, text(services, translation(services, "permission.panel.group_empty", "No groups configured."), "FCA5A5"));
        }
        for (String group : groups) {
            IComponent line = row(services)
                    .append(text(services, "- " + group, "E5E7EB"))
                    .append(space(services))
                    .append(button(services, bracket(services, "permission.panel.open", "open"), "/paradigm group info " + group, true,
                            translation(services, "permission.panel.tooltip.group_details", "Open group details"), "60A5FA"))
                    .append(space(services))
                    .append(button(services, bracket(services, "permission.panel.permissions_short", "perms"), "/paradigm group perm list " + group, true,
                            translation(services, "permission.panel.tooltip.group_permissions", "Open group permissions"), "60A5FA"))
                    .append(space(services))
                    .append(button(services, bracket(services, "permission.panel.parent_add", "parent+"), "/paradigm group parent add " + group + " ", false,
                            translation(services, "permission.panel.tooltip.parent_add", "Prepare parent add"), "FBBF24"));
            if (!isBuiltInGroup(group)) {
                line.append(space(services))
                        .append(button(services, bracket(services, "permission.panel.delete", "del"), "/paradigm group remove " + group, false,
                                translation(services, "permission.panel.tooltip.delete_group", "Prepare group deletion"), "F87171"));
            }
            sendUiLine(source, services, line);
        }
        sendUiLine(source, services, row(services)
                .append(button(services, addLabel(services, "permission.panel.group", "group"), "/paradigm group add ", false,
                        translation(services, "permission.panel.tooltip.create_group", "Prepare group creation"), "34D399"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.home", "home"), "/paradigm perms", true,
                        translation(services, "permission.panel.tooltip.permissions_home", "Back to permissions home"), "94A3B8")));
    }

    public static void sendGroupInfo(ICommandSource source, Services services, String groupName) {
        PermissionAPI.GroupInfo info = services.getPermissionsHandler().getPermissionGroupInfo(groupName);
        if (info == null) {
            sendGroupMessage(source, services, "group.manage.not_found", "Group {group} was not found.", "{group}", groupName);
            return;
        }
        String group = info.name();
        sendUiLine(source, services, header(services, translation(services, "permission.panel.group_title", "Group {group}", "{group}", group)));
        sendUiLine(source, services, row(services)
                .append(text(services, translation(services, "permission.panel.weight", "weight") + ": " + info.weight(), "CBD5E1"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.edit", "edit"), "/paradigm group setweight " + group + " " + info.weight(), false,
                        translation(services, "permission.panel.tooltip.edit_weight", "Prepare weight edit"), "FBBF24")));
        sendUiLine(source, services, row(services)
                .append(text(services, translation(services, "permission.panel.prefix", "prefix") + ": " + blankDash(info.prefix()), "CBD5E1"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.edit", "edit"), "/paradigm group setprefix " + group + " " + info.prefix(), false,
                        translation(services, "permission.panel.tooltip.edit_prefix", "Prepare prefix edit"), "FBBF24")));
        sendUiLine(source, services, row(services)
                .append(text(services, translation(services, "permission.panel.suffix", "suffix") + ": " + blankDash(info.suffix()), "CBD5E1"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.edit", "edit"), "/paradigm group setsuffix " + group + " " + info.suffix(), false,
                        translation(services, "permission.panel.tooltip.edit_suffix", "Prepare suffix edit"), "FBBF24")));
        sendUiLine(source, services, row(services)
                .append(text(services, translation(services, "permission.panel.parents", "parents") + ": " + (info.inherits().isEmpty() ? "-" : String.join(", ", info.inherits())), "CBD5E1"))
                .append(space(services))
                .append(button(services, "[+]", "/paradigm group parent add " + group + " ", false,
                        translation(services, "permission.panel.tooltip.parent_add", "Prepare parent add"), "34D399"))
                .append(space(services))
                .append(button(services, "[-]", "/paradigm group parent remove " + group + " ", false,
                        translation(services, "permission.panel.tooltip.parent_remove", "Prepare parent removal"), "F87171")));
        sendUiLine(source, services, row(services)
                .append(text(services, translation(services, "permission.panel.permissions", "permissions") + ": " + info.assignments().size(), "CBD5E1"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.open", "open"), "/paradigm group perm list " + group, true,
                        translation(services, "permission.panel.tooltip.open_permissions", "Open permissions"), "60A5FA"))
                .append(space(services))
                .append(button(services, "[+]", "/paradigm group perm add " + group + " ", false,
                        translation(services, "permission.panel.tooltip.add_permission", "Prepare permission add"), "34D399"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.deny", "deny"), "/paradigm group perm deny " + group + " ", false,
                        translation(services, "permission.panel.tooltip.deny_permission", "Prepare permission deny"), "F87171")));
        sendUiLine(source, services, row(services)
                .append(button(services, bracket(services, "permission.panel.description", "desc"), "/paradigm group setdescription " + group + " " + info.description(), false,
                        translation(services, "permission.panel.tooltip.edit_description", "Prepare description edit"), "FBBF24"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.back", "back"), "/paradigm group list", true,
                        translation(services, "permission.panel.tooltip.back_to_groups", "Back to groups"), "94A3B8")));
    }

    public static void sendGroupPermissionList(ICommandSource source, Services services, String groupName) {
        PermissionAPI.GroupInfo info = services.getPermissionsHandler().getPermissionGroupInfo(groupName);
        if (info == null) {
            sendGroupMessage(source, services, "group.manage.not_found", "Group {group} was not found.", "{group}", groupName);
            return;
        }
        String group = info.name();
        sendUiLine(source, services, header(services, translation(services, "permission.panel.permissions_title", "Permissions {group}", "{group}", group)));
        if (info.assignments().isEmpty()) {
            sendUiLine(source, services, text(services, translation(services, "permission.panel.no_direct_permissions", "No direct permissions."), "94A3B8"));
        }
        for (PermissionAssignment assignment : info.assignments()) {
            sendUiLine(source, services, assignmentRow(
                    services,
                    assignment,
                    "/paradigm group perm remove-id " + group + " " + assignment.id(),
                    translation(services, "permission.assignment.remove_tooltip", "Remove this assignment")
            ));
        }
        sendUiLine(source, services, row(services)
                .append(button(services, addLabel(services, "permission.panel.add", "add"), "/paradigm group perm add " + group + " ", false,
                        translation(services, "permission.panel.tooltip.add_permission", "Prepare allow permission"), "34D399"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.deny", "deny"), "/paradigm group perm deny " + group + " ", false,
                        translation(services, "permission.panel.tooltip.deny_permission", "Prepare deny permission"), "F87171"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.group", "group"), "/paradigm group info " + group, true,
                        translation(services, "permission.panel.tooltip.back_to_group", "Back to group detail"), "94A3B8")));
    }

    /**
     * Render a user panel after the command handler has resolved the player identity.
     * The resolver remains outside the renderer so rendering never performs command parsing.
     */
    public static int sendUserInfo(ICommandSource source, Services services, UUID uuid, String label) {
        if (uuid == null) {
            sendGroupMessage(source, services, "group.manage.user_invalid", "Player must be online name or UUID.");
            return 0;
        }
        PermissionAPI.UserInfo info = services.getPermissionsHandler().getPlayerPermissionInfo(uuid);
        if (info == null) {
            sendGroupMessage(source, services, "group.manage.user_empty", "No groups assigned.");
            return 1;
        }

        String playerLabel = label != null && !label.isBlank() ? label : uuid.toString();
        sendUiLine(source, services, header(services, translation(services, "permission.panel.user_title", "User {user}", "{user}", playerLabel)));
        PermissionAPI.PermissionMeta meta = info.meta();
        sendUiLine(source, services, text(services,
                translation(services, "permission.panel.primary_group", "primary") + ": " + (meta != null ? meta.primaryGroup() : "-"), "CBD5E1"));
        List<PermissionAssignment> groupAssignments = info.groupAssignments();
        sendUiLine(source, services, row(services)
                .append(text(services, translation(services, "permission.panel.groups", "groups") + ": " + (groupAssignments.isEmpty() ? "-" : groupAssignments.size()), "CBD5E1"))
                .append(space(services))
                .append(button(services, "[+]", "/paradigm group user add " + playerLabel + " ", false,
                        translation(services, "permission.panel.tooltip.add_group", "Prepare group assignment"), "34D399")));
        for (PermissionAssignment assignment : groupAssignments) {
            sendUiLine(source, services, groupAssignmentRow(
                    services,
                    assignment,
                    "/paradigm group user remove-id " + playerLabel + " " + assignment.id(),
                    translation(services, "permission.assignment.remove_tooltip", "Remove this assignment")
            ));
        }
        sendUiLine(source, services, text(services, translation(services, "permission.panel.direct_permissions", "direct permissions") + ":", "CBD5E1"));
        if (info.assignments().isEmpty()) {
            sendUiLine(source, services, text(services, "- " + translation(services, "permission.panel.none", "none"), "94A3B8"));
        }
        for (PermissionAssignment assignment : info.assignments()) {
            sendUiLine(source, services, assignmentRow(
                    services,
                    assignment,
                    "/paradigm group user perm remove-id " + playerLabel + " " + assignment.id(),
                    translation(services, "permission.assignment.remove_tooltip", "Remove this assignment")
            ));
        }
        sendUiLine(source, services, row(services)
                .append(button(services, addLabel(services, "permission.panel.permissions_short", "perm"), "/paradigm group user perm add " + playerLabel + " ", false,
                        translation(services, "permission.panel.tooltip.add_direct_permission", "Prepare direct permission"), "34D399"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.deny", "deny"), "/paradigm group user perm deny " + playerLabel + " ", false,
                        translation(services, "permission.panel.tooltip.deny_direct_permission", "Prepare direct deny"), "F87171"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.check", "check"), "/paradigm permission check " + playerLabel + " ", false,
                        translation(services, "permission.panel.tooltip.check_permission", "Prepare permission check"), "FBBF24")));
        return 1;
    }

    public static void sendPermissionExplain(ICommandSource source, Services services, String playerInput, String permission, PermissionAPI.PermissionExplain explain) {
        String stateKey = explain.allowed() == null
                ? "permission.panel.undefined"
                : (explain.allowed() ? "permission.panel.allowed" : "permission.panel.denied");
        String stateFallback = explain.allowed() == null ? "UNDEFINED" : (explain.allowed() ? "ALLOWED" : "DENIED");
        String color = explain.allowed() == null ? "FBBF24" : (explain.allowed() ? "34D399" : "F87171");
        sendUiLine(source, services, header(services, translation(services, "permission.panel.check_title", "Check")));
        sendUiLine(source, services, row(services)
                .append(text(services, playerInput + " -> " + permission + ": ", "CBD5E1"))
                .append(text(services, translation(services, stateKey, stateFallback), color)));
        sendUiLine(source, services, text(services,
                translation(services, "permission.panel.match", "match") + ": " + blankDash(explain.sourceType()) + " " + blankDash(explain.sourceName()) + " " + blankDash(explain.rule()), "94A3B8"));
        sendUiLine(source, services, text(services,
                translation(services, "permission.panel.groups", "groups") + ": " + (explain.groupsChecked().isEmpty() ? "-" : String.join(", ", explain.groupsChecked())), "94A3B8"));
        sendUiLine(source, services, row(services)
                .append(button(services, bracket(services, "permission.panel.user", "user"), "/paradigm group user info " + playerInput, true,
                        translation(services, "permission.panel.tooltip.open_user_permissions", "Open user permissions"), "60A5FA"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.allow", "allow"), "/paradigm group user perm add " + playerInput + " " + permission, true,
                        translation(services, "permission.panel.tooltip.add_direct_allow", "Add direct allow"), "34D399"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.deny", "deny"), "/paradigm group user perm deny " + playerInput + " " + permission, true,
                        translation(services, "permission.panel.tooltip.add_direct_deny", "Add direct deny"), "F87171")));
    }

    public static void sendPermissionNodeList(ICommandSource source, Services services, String query) {
        List<PermissionNodeRegistry.DiscoveredPermission> nodes = services.getPermissionsHandler().listDiscoveredPermissionNodes(query, 20);
        String title = query == null || query.isBlank()
                ? translation(services, "permission.panel.nodes_title", "Nodes")
                : translation(services, "permission.panel.nodes_query_title", "Nodes {query}", "{query}", query.trim());
        boolean externalEnabled = services.getPermissionsHandler().isExternalCommandPermissionsEnabled();
        sendUiLine(source, services, header(services, title));
        sendUiLine(source, services, row(services)
                .append(text(services, translation(services, "permission.panel.external", "external") + ": "
                        + translation(services, externalEnabled ? "permission.panel.on" : "permission.panel.off", externalEnabled ? "on" : "off"),
                        externalEnabled ? "34D399" : "F87171"))
                .append(space(services))
                .append(text(services, translation(services, "permission.panel.mode", "mode") + ": "
                        + (services.getPermissionsHandler().isExternalCommandStrictMode() ? "strict" : "deny_only"), "CBD5E1"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.search", "search"), "/paradigm permission nodes ", false,
                        translation(services, "permission.panel.tooltip.search_nodes", "Search discovered nodes"), "60A5FA")));

        if (nodes.isEmpty()) {
            sendUiLine(source, services, text(services,
                    translation(services, "permission.panel.no_discovered_nodes", "No discovered nodes yet. They appear after command registration or companion API permission-node registration."), "FBBF24"));
            return;
        }

        for (PermissionNodeRegistry.DiscoveredPermission node : nodes) {
            String name = node.node != null ? node.node : "";
            String sourceName = permissionSourceLabel(services, node.source);
            sendUiLine(source, services, row(services)
                    .append(text(services, "- " + name, "E5E7EB"))
                    .append(space(services))
                    .append(text(services, sourceName, "94A3B8"))
                    .append(space(services))
                    .append(button(services, bracket(services, "permission.panel.allow", "allow"), "/paradigm group perm add default " + name, false,
                            translation(services, "permission.panel.tooltip.suggest_default_allow", "Suggest allow for default group"), "34D399"))
                    .append(space(services))
                    .append(button(services, bracket(services, "permission.panel.deny", "deny"), "/paradigm group perm deny default " + name, false,
                            translation(services, "permission.panel.tooltip.suggest_default_deny", "Suggest deny for default group"), "F87171")));
        }

        sendUiLine(source, services, row(services)
                .append(button(services, bracket(services, "permission.panel.permissions_short", "perms"), "/paradigm perms", true,
                        translation(services, "permission.panel.tooltip.permissions_home", "Back to permissions home"), "94A3B8"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.panel.groups", "groups"), "/paradigm group list", true,
                        translation(services, "permission.panel.tooltip.open_groups", "Open groups"), "60A5FA")));
    }

    private static IComponent assignmentRow(Services services, PermissionAssignment assignment, String removeCommand, String removeTooltip) {
        boolean denied = assignment.denied();
        String markerTooltip = translation(services, denied ? "permission.panel.deny" : "permission.panel.allow", denied ? "deny" : "allow");
        return row(services)
                .append(text(services, denied ? "x " : "+ ", denied ? "F87171" : "34D399").onHoverText(markerTooltip))
                .append(text(services, assignment.value(), "E5E7EB"))
                .append(space(services))
                .append(text(services, "[" + PermissionDisplayFormatter.context(services.getLang(), assignment.contexts()) + "]", "94A3B8"))
                .append(space(services))
                .append(text(services, "[" + PermissionDisplayFormatter.expiry(services.getLang(), assignment.expiresAtMs()) + "]", "94A3B8"))
                .append(space(services))
                .append(text(services, "[" + translation(services, "permission.assignment.id", "id: {id}", "{id}", assignment.id()) + "]", "64748B"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.assignment.remove", "remove"), removeCommand, true, removeTooltip, "F87171"));
    }

    private static IComponent groupAssignmentRow(Services services, PermissionAssignment assignment, String removeCommand, String removeTooltip) {
        return row(services)
                .append(text(services, "- " + assignment.value(), "94A3B8"))
                .append(space(services))
                .append(text(services, "[" + PermissionDisplayFormatter.context(services.getLang(), assignment.contexts()) + "]", "94A3B8"))
                .append(space(services))
                .append(text(services, "[" + PermissionDisplayFormatter.expiry(services.getLang(), assignment.expiresAtMs()) + "]", "94A3B8"))
                .append(space(services))
                .append(text(services, "[" + translation(services, "permission.assignment.id", "id: {id}", "{id}", assignment.id()) + "]", "64748B"))
                .append(space(services))
                .append(button(services, bracket(services, "permission.assignment.remove", "remove"), removeCommand, true, removeTooltip, "F87171"));
    }

    private static String permissionSourceLabel(Services services, String source) {
        if (source == null || source.isBlank()) {
            return translation(services, "permission.panel.source_manual", "manual");
        }
        return switch (source) {
            case PermissionNodeRegistry.SOURCE_COMMAND_TREE -> translation(services, "permission.panel.source_command", "command");
            case PermissionNodeRegistry.SOURCE_COMMAND_ALIAS -> translation(services, "permission.panel.source_alias", "alias");
            case PermissionNodeRegistry.SOURCE_FORGE_PERMISSION_API -> translation(services, "permission.panel.source_forge_api", "Forge API");
            case PermissionNodeRegistry.SOURCE_NEOFORGE_PERMISSION_API -> translation(services, "permission.panel.source_neoforge_api", "NeoForge API");
            case PermissionNodeRegistry.SOURCE_PARADIGM -> translation(services, "permission.panel.source_paradigm", "Paradigm");
            default -> source;
        };
    }

    private static String bracket(Services services, String key, String fallback) {
        return "[" + translation(services, key, fallback) + "]";
    }

    private static String addLabel(Services services, String key, String fallback) {
        return "[+ " + translation(services, key, fallback) + "]";
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

    private static void sendUiLine(ICommandSource source, Services services, IComponent component) {
        services.getPlatformAdapter().sendSuccess(source, component, false);
    }

    private static IComponent header(Services services, String title) {
        return services.getPlatformAdapter().createEmptyComponent()
                .append(text(services, "---- ", "475569"))
                .append(text(services, "[P] ", "A78BFA").withFormatting("bold"))
                .append(text(services, title, "F8FAFC").withFormatting("bold"));
    }

    private static IComponent row(Services services) {
        return services.getPlatformAdapter().createEmptyComponent();
    }

    private static IComponent space(Services services) {
        return services.getPlatformAdapter().createComponentFromLiteral(" ");
    }

    private static IComponent text(Services services, String value, String color) {
        return services.getPlatformAdapter().createComponentFromLiteral(value != null ? value : "").withColorHex(color);
    }

    private static IComponent button(Services services, String label, String command, boolean run, String hover, String color) {
        IComponent component = services.getPlatformAdapter()
                .createComponentFromLiteral(label)
                .withColorHex(color)
                .withFormatting("bold")
                .onHoverText(hover != null ? hover : command);
        return run ? component.onClickRunCommand(command) : component.onClickSuggestCommand(command);
    }

    private static void sendGroupMessage(ICommandSource source, Services services, String key, String fallback, String... placeholders) {
        String raw = translation(services, key, fallback, placeholders);
        IComponent message = services.getMessageParser().parseMessage(
                "<color:#A78BFA><bold>[Group]</bold></color> <color:#E5E7EB>" + raw + "</color>",
                source != null ? source.getPlayer() : null
        );
        services.getPlatformAdapter().sendSuccess(source, message, false);
    }

    private static String translation(Services services, String key, String fallback, String... placeholders) {
        String raw = services != null && services.getLang() != null ? services.getLang().getTranslation(key) : null;
        if (raw == null || raw.equals(key)) {
            raw = fallback;
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        return raw;
    }
}
