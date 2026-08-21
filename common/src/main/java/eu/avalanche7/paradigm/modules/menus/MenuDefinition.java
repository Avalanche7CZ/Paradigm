package eu.avalanche7.paradigm.modules.menus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

import eu.avalanche7.paradigm.data.CustomCommand;

public final class MenuDefinition {

    public static final int MIN_ROWS = 1;
    public static final int MAX_ROWS = 6;
    public static final int ROW_WIDTH = 9;
    public static final int MAX_REFRESH_SECONDS = 3600;

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    public String id = "";
    public String title = "Menu";
    public int rows = 3;
    public String permission = "";
    public List<CustomCommand.Condition> openConditions = new ArrayList<>();
    public List<MenuSlot> slots = new ArrayList<>();
    public MenuItem filler;
    public boolean fillEmpty;
    public int refreshSeconds;
    public List<CustomCommand.Action> onClose = new ArrayList<>();

    public int size() {
        return rows * ROW_WIDTH;
    }

    public boolean isDynamic() {
        if (refreshSeconds > 0) {
            return true;
        }
        for (MenuSlot slot : slots) {
            if (slot != null && slot.refresh) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public MenuSlot slotAt(int index) {
        for (MenuSlot slot : slots) {
            if (slot != null && slot.slot == index) {
                return slot;
            }
        }
        return null;
    }

    public MenuDefinition copy() {
        MenuDefinition copy = new MenuDefinition();
        copy.id = id;
        copy.title = title;
        copy.rows = rows;
        copy.permission = permission;
        copy.fillEmpty = fillEmpty;
        copy.refreshSeconds = refreshSeconds;
        copy.filler = filler != null ? filler.copy() : null;
        copy.openConditions = new ArrayList<>(openConditions != null ? openConditions : List.of());
        copy.onClose = new ArrayList<>(onClose != null ? onClose : List.of());
        copy.slots = new ArrayList<>();
        if (slots != null) {
            for (MenuSlot slot : slots) {
                if (slot != null) {
                    copy.slots.add(slot.copy());
                }
            }
        }
        return copy;
    }

    public void normalize() {
        id = id != null ? id.trim().toLowerCase(Locale.ROOT) : "";
        if (id.isBlank()) {
            throw new IllegalArgumentException("Menu definitions require an id.");
        }
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Invalid menu id '" + id + "'. Use lowercase letters, digits, '_' or '-' (max 64 characters).");
        }
        title = title != null && !title.isBlank() ? title : "Menu";
        if (rows < MIN_ROWS || rows > MAX_ROWS) {
            throw new IllegalArgumentException(
                    "Menu '" + id + "' has " + rows + " rows; chest menus support " + MIN_ROWS + "-" + MAX_ROWS + ".");
        }
        permission = permission != null ? permission.trim() : "";
        if (refreshSeconds < 0) {
            refreshSeconds = 0;
        }
        if (refreshSeconds > MAX_REFRESH_SECONDS) {
            refreshSeconds = MAX_REFRESH_SECONDS;
        }
        if (openConditions == null) {
            openConditions = new ArrayList<>();
        }
        openConditions.removeIf(java.util.Objects::isNull);
        if (onClose == null) {
            onClose = new ArrayList<>();
        }
        onClose.removeIf(java.util.Objects::isNull);
        if (slots == null) {
            slots = new ArrayList<>();
        }
        slots.removeIf(java.util.Objects::isNull);
        if (filler != null) {
            filler.normalize();
        }
        if (fillEmpty && filler == null) {
            filler = MenuItem.of("minecraft:gray_stained_glass_pane");
            filler.normalize();
        }
        int size = size();
        Set<Integer> seen = new HashSet<>();
        for (MenuSlot slot : slots) {
            slot.normalize(size);
            if (!seen.add(slot.slot)) {
                throw new IllegalArgumentException("Menu '" + id + "' defines slot " + slot.slot + " more than once.");
            }
        }
    }
}
