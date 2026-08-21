package eu.avalanche7.paradigm.modules.tickets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import eu.avalanche7.paradigm.configs.TicketsConfigHandler;
import eu.avalanche7.paradigm.modules.permissions.PermissionDefinition;

public final class TicketCategories {

    public static final String DEFAULT_ID = "general";

    private TicketCategories() {
    }

    public static List<TicketsConfigHandler.CategoryEntry> enabled(TicketsConfigHandler.Config config) {
        List<TicketsConfigHandler.CategoryEntry> entries = new ArrayList<>();
        if (config == null || config.categories == null) {
            return entries;
        }
        for (TicketsConfigHandler.CategoryEntry entry : config.categories) {
            if (entry != null && entry.enabled && entry.id != null && !entry.id.isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    public static List<String> selectableIds(TicketsConfigHandler.Config config, TicketActor actor) {
        List<String> ids = new ArrayList<>();
        for (TicketsConfigHandler.CategoryEntry entry : enabled(config)) {
            if (actor == null || mayUse(entry, actor)) {
                ids.add(entry.id.toLowerCase(Locale.ROOT));
            }
        }
        return ids;
    }

    public static TicketsConfigHandler.CategoryEntry resolve(TicketsConfigHandler.Config config, String id) {
        if (config == null) {
            return null;
        }
        String wanted = id != null && !id.isBlank() ? id.trim() : DEFAULT_ID;
        TicketsConfigHandler.CategoryEntry entry = config.category(wanted);
        if (entry != null && entry.enabled) {
            return entry;
        }
        return null;
    }

    public static boolean mayUse(TicketsConfigHandler.CategoryEntry entry, TicketActor actor) {
        if (entry == null) {
            return false;
        }
        if (entry.permission == null || entry.permission.isBlank()) {
            return true;
        }
        return actor != null && actor.hasNode(entry.permission, PermissionDefinition.NO_VANILLA_FALLBACK);
    }

    public static boolean mayStaffHandle(TicketsConfigHandler.CategoryEntry entry, TicketActor actor) {
        if (entry == null) {
            return true;
        }
        if (entry.staffPermission == null || entry.staffPermission.isBlank()) {
            return true;
        }
        return actor != null && actor.hasNode(entry.staffPermission, PermissionDefinition.NO_VANILLA_FALLBACK);
    }

    public static TicketPriority defaultPriority(TicketsConfigHandler.CategoryEntry entry) {
        if (entry == null) {
            return TicketPriority.NORMAL;
        }
        return TicketPriority.parseOr(entry.defaultPriority, TicketPriority.NORMAL);
    }

    public static String displayName(TicketsConfigHandler.Config config, String id) {
        TicketsConfigHandler.CategoryEntry entry = config != null ? config.category(id) : null;
        if (entry != null && entry.displayName != null && !entry.displayName.isBlank()) {
            return entry.displayName;
        }
        return id != null ? id : DEFAULT_ID;
    }
}
