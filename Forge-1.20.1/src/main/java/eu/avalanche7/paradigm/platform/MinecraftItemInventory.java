package eu.avalanche7.paradigm.platform;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MinecraftItemInventory {
    private MinecraftItemInventory() {}
    static Item resolve(ItemRequirement requirement) {
        if (requirement.numeric() || requirement.metadata() != null) throw new UnsupportedOperationException("Legacy numeric items/metadata are supported only on Forge 1.7.10");
        var id = net.minecraft.resources.ResourceLocation.tryParse(requirement.itemId());
        Item item = id != null ? net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id) : Items.AIR;
        if (item == null || item == Items.AIR) throw new IllegalArgumentException("Unknown item: " + requirement.itemId());
        return item;
    }
    public static InventoryConsumption prepare(ServerPlayer player, List<ItemRequirement> items) {
        return new SlotInventory<ItemStack>() {
            @Override protected int size() { return Math.min(36, player.getInventory().getContainerSize()); }
            @Override protected ItemStack get(int slot) { return player.getInventory().getItem(slot); }
            @Override protected ItemStack copy(ItemStack stack) { return stack != null ? stack.copy() : ItemStack.EMPTY; }
            @Override protected int count(ItemStack stack) { return stack != null && !stack.isEmpty() ? stack.getCount() : 0; }
            @Override protected ItemStack withCount(ItemStack stack, int count) { if (count == 0) return ItemStack.EMPTY; stack.setCount(count); return stack; }
            @Override protected void set(int slot, ItemStack stack) { player.getInventory().setItem(slot, stack); }
            @Override protected boolean same(ItemStack first, ItemStack second) { return ItemStack.matches(first, second); }
            @Override protected boolean matches(ItemStack stack, ItemRequirement item) { return stack != null && !stack.isEmpty() && stack.getItem() == resolve(item); }
            @Override protected void validate(ItemRequirement item) { resolve(item); }
            @Override protected void sync() { player.getInventory().setChanged(); player.containerMenu.broadcastChanges(); }
        }.prepare(items);
    }
}
