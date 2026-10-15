package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.jupiter.api.Test;

import eu.avalanche7.paradigm.platform.Interfaces.IMenuPlatform;

class NativeItemWorkflowTest {
    private void register(int id, String name, Item item) {
        var loader = mock(cpw.mods.fml.common.Loader.class); var container = mock(cpw.mods.fml.common.ModContainer.class);
        when(container.getModId()).thenReturn("test"); when(loader.activeModContainer()).thenReturn(container);
        try (var lifecycle = mockStatic(cpw.mods.fml.common.Loader.class)) {
            lifecycle.when(cpw.mods.fml.common.Loader::instance).thenReturn(loader);
            Item.itemRegistry.addObject(id, name, item);
        }
    }
    @Test void numericItemsMatchExactMetadataAndRollbackRetainsModdedNbt() {
        Item item = new Item().setHasSubtypes(true);
        register(26000, "test:workflow_material", item);
        var player = mock(EntityPlayerMP.class); player.inventory = new InventoryPlayer(player); player.inventoryContainer = mock(Container.class);
        var first = new ItemStack(item, 6, 32601); var tag = new NBTTagCompound(); tag.setString("modded", "preserve-me"); first.setTagCompound(tag);
        player.inventory.mainInventory[0] = first;
        player.inventory.mainInventory[1] = new ItemStack(item, 4, 32601);
        player.inventory.mainInventory[2] = new ItemStack(item, 64, 0);
        assertThrows(IllegalStateException.class, () -> MinecraftItemInventory.prepare(player, List.of(ItemRequirement.parse("26000:32601 11"))));
        var edit = MinecraftItemInventory.prepare(player, List.of(ItemRequirement.parse("26000:32601 9")));
        edit.consume(); assertNull(player.inventory.mainInventory[0]); assertEquals(1, player.inventory.mainInventory[1].stackSize); assertEquals(64, player.inventory.mainInventory[2].stackSize);
        edit.restore(); assertTrue(ItemStack.areItemStacksEqual(first, player.inventory.mainInventory[0]));
        assertEquals("preserve-me", player.inventory.mainInventory[0].getTagCompound().getString("modded"));
    }
    @Test void legacyIconsApplyMetadataWithoutUsingCustomModelData() {
        Item item = new Item().setHasSubtypes(true); register(26001, "test:workflow_icon", item);
        var spec = new IMenuPlatform.ItemSpec("26001:32601", 8, null, List.of(), true, 99, false);
        var stack = MinecraftMenuPlatform.toStack(spec);
        assertSame(item, stack.getItem()); assertEquals(32601, stack.getItemDamage()); assertEquals(8, stack.stackSize); assertTrue(stack.hasTagCompound());
        assertSame(item, MinecraftMenuPlatform.registeredItem("26001"));
        assertThrows(IllegalArgumentException.class, () -> MinecraftMenuPlatform.toStack(new IMenuPlatform.ItemSpec("26001:32601", 1, null, List.of(), false, null, false, 0)));
    }
}
