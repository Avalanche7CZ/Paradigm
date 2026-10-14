package eu.avalanche7.paradigm.platform;

import java.util.List;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public final class MinecraftItemInventory {
    private MinecraftItemInventory() {}
    static Item resolve(ItemRequirement requirement) {
        if (requirement.numeric() || requirement.metadata() != null) throw new UnsupportedOperationException("Legacy numeric items/metadata are supported only on Forge 1.7.10");
        var id = net.minecraft.util.Identifier.tryParse(requirement.itemId());
        Item item = id != null ? net.minecraft.registry.Registries.ITEM.get(id) : Items.AIR;
        if (item == null || item == Items.AIR) throw new IllegalArgumentException("Unknown item: " + requirement.itemId());
        return item;
    }
    public static InventoryConsumption prepare(ServerPlayerEntity player, List<ItemRequirement> items) {
        return new SlotInventory<ItemStack>() {
            @Override protected int size() { return player.getInventory().size(); }
            @Override protected ItemStack get(int slot) { return player.getInventory().getStack(slot); }
            @Override protected ItemStack copy(ItemStack stack) { return stack != null ? stack.copy() : ItemStack.EMPTY; }
            @Override protected int count(ItemStack stack) { return stack != null && !stack.isEmpty() ? stack.getCount() : 0; }
            @Override protected ItemStack withCount(ItemStack stack, int count) { if (count == 0) return ItemStack.EMPTY; stack.setCount(count); return stack; }
            @Override protected void set(int slot, ItemStack stack) { player.getInventory().setStack(slot, stack); }
            @Override protected boolean same(ItemStack first, ItemStack second) { return ItemStack.areEqual(first, second); }
            @Override protected boolean matches(ItemStack stack, ItemRequirement item) { return stack != null && !stack.isEmpty() && stack.getItem() == resolve(item); }
            @Override protected void validate(ItemRequirement item) { resolve(item); }
            @Override protected void sync() { player.getInventory().markDirty(); player.currentScreenHandler.sendContentUpdates(); }
        }.prepare(items);
    }
}
