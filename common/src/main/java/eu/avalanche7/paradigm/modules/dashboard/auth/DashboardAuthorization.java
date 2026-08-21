package eu.avalanche7.paradigm.modules.dashboard.auth;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import eu.avalanche7.paradigm.modules.permissions.ParadigmPermissions;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;

public final class DashboardAuthorization {

    @FunctionalInterface
    public interface PermissionCheck {
        boolean test(PermissionDefinition permission);
    }

    public record SectionAccess(boolean view, boolean edit) {
    }

    public record Capabilities(Map<String, Boolean> pages, Map<String, SectionAccess> config) {
    }

    private record SectionPermissions(String pageId, PermissionDefinition view, PermissionDefinition edit) {
    }

    // Each dashboard config page's view/edit permission pair, declared exactly once. "general" is
    // a UI grouping of three real ConfigSchemaRegistry categories (see buildCategoryPermissions);
    // "storageConfig" is the page id for the real category "storage". Everything else uses the
    // same id for both the page and the category.
    private static final SectionPermissions GENERAL = new SectionPermissions("general", ParadigmPermissions.DASHBOARD_CONFIG_GENERAL_VIEW, ParadigmPermissions.DASHBOARD_CONFIG_GENERAL_EDIT);
    private static final SectionPermissions TELEPORTS = new SectionPermissions("teleports", ParadigmPermissions.DASHBOARD_CONFIG_TELEPORTS_VIEW, ParadigmPermissions.DASHBOARD_CONFIG_TELEPORTS_EDIT);
    private static final SectionPermissions CHAT = new SectionPermissions("chat", ParadigmPermissions.DASHBOARD_CONFIG_CHAT_VIEW, ParadigmPermissions.DASHBOARD_CONFIG_CHAT_EDIT);
    private static final SectionPermissions ANNOUNCEMENTS = new SectionPermissions("announcements", ParadigmPermissions.DASHBOARD_CONFIG_ANNOUNCEMENTS_VIEW, ParadigmPermissions.DASHBOARD_CONFIG_ANNOUNCEMENTS_EDIT);
    private static final SectionPermissions RESTART = new SectionPermissions("restart", ParadigmPermissions.DASHBOARD_CONFIG_RESTART_VIEW, ParadigmPermissions.DASHBOARD_CONFIG_RESTART_EDIT);
    private static final SectionPermissions MOTD = new SectionPermissions("motd", ParadigmPermissions.DASHBOARD_CONFIG_MOTD_VIEW, ParadigmPermissions.DASHBOARD_CONFIG_MOTD_EDIT);
    private static final SectionPermissions TABLIST = new SectionPermissions("tablist", ParadigmPermissions.DASHBOARD_CONFIG_TABLIST_VIEW, ParadigmPermissions.DASHBOARD_CONFIG_TABLIST_EDIT);
    private static final SectionPermissions COMMANDS = new SectionPermissions("commands", ParadigmPermissions.DASHBOARD_CONFIG_COMMANDS_VIEW, ParadigmPermissions.DASHBOARD_CONFIG_COMMANDS_EDIT);
    private static final SectionPermissions COOLDOWNS = new SectionPermissions("cooldowns", ParadigmPermissions.DASHBOARD_CONFIG_COOLDOWNS_VIEW, ParadigmPermissions.DASHBOARD_CONFIG_COOLDOWNS_EDIT);
    private static final SectionPermissions DASHBOARD_SETTINGS = new SectionPermissions("dashboard", ParadigmPermissions.DASHBOARD_CONFIG_DASHBOARD_VIEW, ParadigmPermissions.DASHBOARD_CONFIG_DASHBOARD_EDIT);
    private static final SectionPermissions DISCORD = new SectionPermissions("discord", ParadigmPermissions.DASHBOARD_CONFIG_DISCORD_VIEW, ParadigmPermissions.DASHBOARD_CONFIG_DISCORD_EDIT);
    private static final SectionPermissions STORAGE_CONFIG = new SectionPermissions("storageConfig", ParadigmPermissions.DASHBOARD_STORAGECONFIG_VIEW, ParadigmPermissions.DASHBOARD_STORAGECONFIG_EDIT);

    // No dedicated dashboard page exists for these categories; only reachable via config-patch by
    // a manage/CONFIG_VIEW/CONFIG_EDIT holder, matching today's behavior.
    private static final SectionPermissions MODERATION_FIELDS = new SectionPermissions("moderation", ParadigmPermissions.DASHBOARD_MANAGE, ParadigmPermissions.DASHBOARD_MANAGE);
    private static final SectionPermissions CUSTOM_COMMAND_FIELDS = new SectionPermissions("custom_commands", ParadigmPermissions.DASHBOARD_MANAGE, ParadigmPermissions.DASHBOARD_MANAGE);
    private static final SectionPermissions AFK_FIELDS = new SectionPermissions("afk", ParadigmPermissions.DASHBOARD_MANAGE, ParadigmPermissions.DASHBOARD_MANAGE);

    // Pages with a real view/edit split, used to build the frontend capabilities payload.
    private static final SectionPermissions[] PAGE_SECTIONS = {
            GENERAL, TELEPORTS, CHAT, ANNOUNCEMENTS, RESTART, MOTD, TABLIST, COMMANDS, COOLDOWNS, DASHBOARD_SETTINGS, DISCORD, STORAGE_CONFIG,
    };

    // Real ConfigField.category() string -> the SectionPermissions governing it. This is the
    // single source of truth server-side enforcement (canViewConfigCategory/canEditConfigCategory)
    // and categoriesForPage() both read from.
    private static final Map<String, SectionPermissions> CATEGORY_PERMISSIONS = buildCategoryPermissions();

    private static Map<String, SectionPermissions> buildCategoryPermissions() {
        Map<String, SectionPermissions> map = new LinkedHashMap<>();
        map.put("modules", GENERAL);
        map.put("command_groups", GENERAL);
        map.put("admin_utilities", GENERAL);
        map.put("teleports", TELEPORTS);
        map.put("chat", CHAT);
        map.put("announcements", ANNOUNCEMENTS);
        map.put("restart", RESTART);
        map.put("motd", MOTD);
        map.put("tablist", TABLIST);
        map.put("commands", COMMANDS);
        map.put("cooldowns", COOLDOWNS);
        map.put("dashboard", DASHBOARD_SETTINGS);
        map.put("discord", DISCORD);
        map.put("storage", STORAGE_CONFIG);
        map.put("moderation", MODERATION_FIELDS);
        map.put("custom_commands", CUSTOM_COMMAND_FIELDS);
        map.put("afk", AFK_FIELDS);
        return Map.copyOf(map);
    }

    private DashboardAuthorization() {
    }

    public static boolean hasManageBypass(PermissionCheck check) {
        return check.test(ParadigmPermissions.DASHBOARD_MANAGE);
    }

    public static boolean canAccessDashboard(PermissionCheck check) {
        return hasManageBypass(check) || check.test(ParadigmPermissions.DASHBOARD_ACCESS);
    }

    public static boolean canAccessPage(PermissionCheck check, PermissionDefinition pagePermission, PermissionDefinition... legacyAlternatives) {
        if (hasManageBypass(check) || check.test(pagePermission)) {
            return true;
        }
        for (PermissionDefinition alternative : legacyAlternatives) {
            if (check.test(alternative)) {
                return true;
            }
        }
        return false;
    }

    public static boolean canViewConfigCategory(PermissionCheck check, String realCategory) {
        SectionPermissions section = CATEGORY_PERMISSIONS.get(realCategory);
        if (section == null) {
            return false;
        }
        return hasManageBypass(check)
                || check.test(ParadigmPermissions.CONFIG_VIEW)
                || check.test(ParadigmPermissions.CONFIG_EDIT)
                || check.test(section.view())
                || check.test(section.edit());
    }

    public static boolean canEditConfigCategory(PermissionCheck check, String realCategory) {
        SectionPermissions section = CATEGORY_PERMISSIONS.get(realCategory);
        if (section == null) {
            return false;
        }
        if (hasManageBypass(check) || check.test(ParadigmPermissions.CONFIG_EDIT) || check.test(section.edit())) {
            return true;
        }
        // The Storage Configuration page's dedicated write endpoint also accepts the broader
        // paradigm.storage.manage grant (see DashboardRouter's storage/configuration gate) -
        // kept here, in the one place edit access is decided, so capabilities() and real
        // enforcement can never disagree about it.
        return "storage".equals(realCategory) && check.test(ParadigmPermissions.STORAGE_MANAGE);
    }

    public static boolean canEditAllCategories(PermissionCheck check, Set<String> realCategories) {
        if (hasManageBypass(check) || check.test(ParadigmPermissions.CONFIG_EDIT)) {
            return true;
        }
        for (String category : realCategories) {
            if (!canEditConfigCategory(check, category)) {
                return false;
            }
        }
        return true;
    }

    /** Real ConfigField.category() ids that make up the given dashboard page. */
    public static Set<String> categoriesForPage(String pageId) {
        Set<String> categories = new LinkedHashSet<>();
        for (Map.Entry<String, SectionPermissions> entry : CATEGORY_PERMISSIONS.entrySet()) {
            if (entry.getValue().pageId().equals(pageId)) {
                categories.add(entry.getKey());
            }
        }
        return categories;
    }

    public static Capabilities computeCapabilities(PermissionCheck check) {
        Map<String, Boolean> pages = new LinkedHashMap<>();
        pages.put("overview", canAccessPage(check, ParadigmPermissions.DASHBOARD_OVERVIEW));
        pages.put("servers", canAccessPage(check, ParadigmPermissions.DASHBOARD_SERVERS));
        pages.put("storage", canAccessPage(check, ParadigmPermissions.DASHBOARD_STORAGE));
        pages.put("audit", canAccessPage(check, ParadigmPermissions.DASHBOARD_AUDIT));
        pages.put("permissions", canAccessPage(check, ParadigmPermissions.DASHBOARD_PERMISSIONS, ParadigmPermissions.GROUP_MANAGE));
        pages.put("moderation", canAccessPage(check, ParadigmPermissions.DASHBOARD_MODERATION,
                ParadigmPermissions.KICK, ParadigmPermissions.BAN, ParadigmPermissions.WARN, ParadigmPermissions.JAIL));
        pages.put("tickets", canAccessPage(check, ParadigmPermissions.DASHBOARD_TICKETS, ParadigmPermissions.TICKET_MANAGE));
        pages.put("holograms", canAccessPage(check, ParadigmPermissions.DASHBOARD_CONFIG_HOLOGRAMS_VIEW, ParadigmPermissions.HOLOGRAM_MANAGE));
        pages.put("menus", canAccessPage(check, ParadigmPermissions.DASHBOARD_CONFIG_MENUS_VIEW, ParadigmPermissions.MENU_MANAGE));
        pages.put("customCommands", canAccessPage(check, ParadigmPermissions.DASHBOARD_CONFIG_CUSTOMCOMMANDS_VIEW, ParadigmPermissions.CONFIG_EDIT));

        Map<String, SectionAccess> config = new LinkedHashMap<>();
        for (SectionPermissions section : PAGE_SECTIONS) {
            String realCategory = switch (section.pageId()) {
                case "general" -> "modules";
                case "storageConfig" -> "storage";
                default -> section.pageId();
            };
            boolean view = canViewConfigCategory(check, realCategory);
            boolean edit = canEditConfigCategory(check, realCategory);
            pages.put(section.pageId(), view);
            config.put(section.pageId(), new SectionAccess(view, edit));
        }
        return new Capabilities(pages, config);
    }
}
