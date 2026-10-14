package eu.avalanche7.paradigm.platform;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public final class MinecraftItemInventory {
    private MinecraftItemInventory() {}
    static Item resolve(ItemRequirement requirement) {
        Item item = requirement.numeric() ? Item.getItemById(Integer.parseInt(requirement.itemId())) : MinecraftMenuPlatform.registeredItem(requirement.itemId());
        if (item == null || item == Item.getItemFromBlock(net.minecraft.init.Blocks.air)) throw new IllegalArgumentException("Unknown item: " + requirement.itemId());
        return item;
    }
    public static InventoryConsumption prepare(EntityPlayerMP player, List<ItemRequirement> items) {
        return new SlotInventory<ItemStack>() {
            @Override protected int size() { return player.inventory.mainInventory.length; }
            @Override protected ItemStack get(int slot) { return player.inventory.mainInventory[slot]; }
            @Override protected ItemStack copy(ItemStack stack) { return stack != null ? stack.copy() : null; }
            @Override protected int count(ItemStack stack) { return stack != null ? stack.stackSize : 0; }
            @Override protected ItemStack withCount(ItemStack stack, int count) { if (count == 0) return null; stack.stackSize = count; return stack; }
            @Override protected void set(int slot, ItemStack stack) { player.inventory.mainInventory[slot] = stack; }
            @Override protected boolean same(ItemStack first, ItemStack second) { return ItemStack.areItemStacksEqual(first, second); }
            @Override protected boolean matches(ItemStack stack, ItemRequirement item) { return stack != null && stack.getItem() == resolve(item) && (item.metadata() == null || stack.getItemDamage() == item.metadata()); }
            @Override protected void validate(ItemRequirement item) { resolve(item); }
            @Override protected void sync() { player.inventory.markDirty(); player.inventoryContainer.detectAndSendChanges(); if (player.openContainer != null && player.openContainer != player.inventoryContainer) player.openContainer.detectAndSendChanges(); }
        }.prepare(items);
    }
}
