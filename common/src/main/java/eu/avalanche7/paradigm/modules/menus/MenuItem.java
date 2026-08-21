package eu.avalanche7.paradigm.modules.menus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class MenuItem {

    public static final String FALLBACK_ITEM = "minecraft:barrier";
    public static final int MAX_LORE_LINES = 32;

    private static final Pattern ITEM_ID = Pattern.compile("^[a-z0-9_.-]+(:[a-z0-9_/.-]+)?$");

    public String itemId = "minecraft:stone";
    public int amount = 1;
    public String name = "";
    public List<String> lore = new ArrayList<>();
    public boolean glint;
    public Integer customModelData;
    public boolean hideTooltip;

    public MenuItem copy() {
        MenuItem copy = new MenuItem();
        copy.itemId = itemId;
        copy.amount = amount;
        copy.name = name;
        copy.glint = glint;
        copy.customModelData = customModelData;
        copy.hideTooltip = hideTooltip;
        copy.lore = lore != null ? new ArrayList<>(lore) : new ArrayList<>();
        return copy;
    }

    public void normalize() {
        itemId = itemId != null ? itemId.trim().toLowerCase(Locale.ROOT) : "";
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("Menu items require an item id.");
        }
        if (!ITEM_ID.matcher(itemId).matches()) {
            throw new IllegalArgumentException("Invalid item id: " + itemId);
        }
        if (!itemId.contains(":")) {
            itemId = "minecraft:" + itemId;
        }
        if (amount < 1) {
            amount = 1;
        }
        if (amount > 64) {
            amount = 64;
        }
        name = name != null ? name : "";
        if (lore == null) {
            lore = new ArrayList<>();
        }
        if (customModelData != null && customModelData < 0) {
            throw new IllegalArgumentException("customModelData must not be negative.");
        }
        if (lore.size() > MAX_LORE_LINES) {
            throw new IllegalArgumentException("A menu item may have at most " + MAX_LORE_LINES + " lore lines.");
        }
        lore.replaceAll(line -> line != null ? line : "");
    }

    public static MenuItem of(String itemId) {
        MenuItem item = new MenuItem();
        item.itemId = itemId;
        return item;
    }
}
