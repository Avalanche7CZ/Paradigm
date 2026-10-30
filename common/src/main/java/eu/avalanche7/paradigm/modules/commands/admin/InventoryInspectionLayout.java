package eu.avalanche7.paradigm.modules.commands.admin;

/** Chest positions mapped to vanilla inventory indices; equipment is resolved natively. */
public final class InventoryInspectionLayout {
    private InventoryInspectionLayout() {}

    public static int sourceSlot(int slot, boolean enderChest, boolean offhand) {
        if (enderChest) return slot >= 0 && slot < 27 ? slot : -1;
        if (slot >= 0 && slot < 4) return 39 - slot;
        if (slot == 5 && offhand) return 40;
        if (slot >= 18 && slot < 45) return slot - 9;
        if (slot >= 45 && slot < 54) return slot - 45;
        return -1;
    }
}
