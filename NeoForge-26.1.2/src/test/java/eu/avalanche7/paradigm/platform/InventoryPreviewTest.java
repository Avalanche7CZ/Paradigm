package eu.avalanche7.paradigm.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import eu.avalanche7.paradigm.platform.Interfaces.IMenuPlatform;

class InventoryPreviewTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        // This isolated native test has no datapack load to bind item prototypes.
        var prototype = net.minecraft.core.component.DataComponentMap.builder()
                .set(DataComponents.MAX_STACK_SIZE, 64).set(DataComponents.MAX_DAMAGE, 2000).set(DataComponents.DAMAGE, 0).build();
        for (var item : List.of(Items.DIAMOND_PICKAXE, Items.APPLE, Items.DIAMOND_HELMET, Items.SHIELD, Items.DIAMOND, Items.GOLD_INGOT))
            item.builtInRegistryHolder().bindComponents(prototype);
    }

    @Test void copiesComponentsEquipmentAndEnderChestAndRefreshesIndependently() {
        var f = new Fixture();
        var stack = new ItemStack(Items.DIAMOND_PICKAXE, 80);
        stack.setDamageValue(123);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Custom machine"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Mod lore"))));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        var data = new CompoundTag(); data.putLong("modEnergy", 987654321L);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        var modComponent = net.minecraft.core.component.DataComponentType.<String>builder().persistent(com.mojang.serialization.Codec.STRING).build();
        stack.set(modComponent, "mod-specific component");
        f.contents[9] = stack; f.contents[0] = new ItemStack(Items.APPLE, 12);
        when(f.target.getItemBySlot(EquipmentSlot.HEAD)).thenReturn(new ItemStack(Items.DIAMOND_HELMET));
        when(f.target.getItemBySlot(EquipmentSlot.OFFHAND)).thenReturn(new ItemStack(Items.SHIELD));
        var h = f.open(54); assertTrue(h.refreshInventory(new MinecraftPlayer(f.target), false));
        var menu = f.viewer.containerMenu;
        ItemStack shown = menu.getSlot(18).getItem();
        assertTrue(ItemStack.matches(stack, shown)); assertNotSame(stack, shown);
        assertEquals("mod-specific component", shown.get(modComponent));
        assertEquals(Items.DIAMOND_HELMET, menu.getSlot(0).getItem().getItem());
        assertEquals(Items.SHIELD, menu.getSlot(5).getItem().getItem());
        assertEquals(12, menu.getSlot(45).getItem().getCount());
        stack.setCount(2); stack.setDamageValue(124); stack.set(modComponent, "updated");
        assertEquals(80, shown.getCount()); assertEquals(123, shown.getDamageValue());
        h.refreshInventory(new MinecraftPlayer(f.target), false);
        assertEquals(2, menu.getSlot(18).getItem().getCount()); assertEquals("updated", menu.getSlot(18).getItem().get(modComponent));
        f.contents[9] = ItemStack.EMPTY; h.refreshInventory(new MinecraftPlayer(f.target), false); assertTrue(menu.getSlot(18).getItem().isEmpty());
        f.ender.setItem(26, stack);
        var ender = f.open(27); ender.refreshInventory(new MinecraftPlayer(f.target), true);
        assertTrue(ItemStack.matches(stack, f.viewer.containerMenu.getSlot(26).getItem()));
        assertNotSame(stack, f.viewer.containerMenu.getSlot(26).getItem());
        f.ender.setItem(26, ItemStack.EMPTY); ender.refreshInventory(new MinecraftPlayer(f.target), true);
        assertTrue(f.viewer.containerMenu.getSlot(26).getItem().isEmpty());
    }

    @Test void allContainerInputsAndCreativePacketsPreserveCursorAndBothInventories() {
        var f = new Fixture(); f.contents[9] = new ItemStack(Items.DIAMOND, 17);
        var handle = f.open(54); handle.refreshInventory(new MinecraftPlayer(f.target), false);
        var menu = f.viewer.containerMenu;
        var cursor = new ItemStack(Items.GOLD_INGOT, 5); menu.setCarried(cursor);
        for (var kind : ContainerInput.values()) {
            for (int slot : new int[]{-999, 0, 18, 54, 81}) {
                for (int button : new int[]{0, 1, 2, 8, 40}) {
                    menu.clicked(slot, button, kind, f.viewer);
                    assertSame(cursor, menu.getCarried()); assertEquals(5, cursor.getCount());
                    assertEquals(17, f.contents[9].getCount()); assertEquals(17, menu.getSlot(18).getItem().getCount());
                }
            }
        }
        assertTrue(menu.quickMoveStack(f.viewer, 18).isEmpty());
        assertFalse(menu.canTakeItemForPickAll(f.contents[9], menu.getSlot(18)));
        assertTrue(MinecraftMenuPlatform.blockInventoryEdit(f.viewer));
        verify(f.ownMenu, atLeastOnce()).sendAllDataToRemote();
        assertSame(cursor, menu.getCarried());
        var level = mock(net.minecraft.server.level.ServerLevel.class);
        var server = mock(net.minecraft.server.MinecraftServer.class);
        var processor = mock(net.minecraft.network.PacketProcessor.class);
        when(f.viewer.level()).thenReturn(level); when(level.getServer()).thenReturn(server);
        when(server.packetProcessor()).thenReturn(processor); when(processor.isSameThread()).thenReturn(true);
        var connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class, CALLS_REAL_METHODS);
        connection.player = f.viewer;
        var packet = mock(net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket.class);
        connection.handleSetCreativeModeSlot(packet);
        verifyNoInteractions(packet);
        verify(f.viewer, never()).hasInfiniteMaterials();
        verify(f.viewer, never()).drop(any(ItemStack.class), anyBoolean());
        verify(f.viewerInventory, never()).setItem(anyInt(), any());
        verifyNoInteractions(f.listener);
        var replacement = mock(AbstractContainerMenu.class); f.viewer.containerMenu = replacement;
        assertFalse(handle.isOpen()); handle.close();
        assertSame(replacement, f.viewer.containerMenu); verify(f.viewer, never()).closeContainer();
    }

    @Test void respawnedViewerCannotRetainOrCloseTheReplacementPlayersWindow() {
        var f = new Fixture(); var handle = f.open(54);
        assertTrue(handle.refreshInventory(new MinecraftPlayer(f.target), false));
        assertTrue(handle.isOpen());
        var respawned = mock(ServerPlayer.class);
        when(f.playerList.getPlayer(f.viewer.getUUID())).thenReturn(respawned);
        assertFalse(handle.isOpen()); handle.close();
        verify(f.viewer, never()).closeContainer(); verify(respawned, never()).closeContainer();
    }

    static final class Fixture {
        final ServerPlayer viewer = mock(ServerPlayer.class), target = mock(ServerPlayer.class);
        final Inventory viewerInventory = mock(Inventory.class), targetInventory = mock(Inventory.class);
        final SimpleContainer ender = new SimpleContainer(27);
        final ItemStack[] contents = new ItemStack[36];
        final MinecraftMenuPlatform platform = new MinecraftMenuPlatform();
        final IMenuPlatform.ClickListener listener = mock(IMenuPlatform.ClickListener.class);
        final InventoryMenu ownMenu = mock(InventoryMenu.class);
        final net.minecraft.server.players.PlayerList playerList = mock(net.minecraft.server.players.PlayerList.class);
        Fixture() {
            try {
                var field = net.minecraft.world.entity.player.Player.class.getDeclaredField("inventoryMenu");
                field.setAccessible(true); field.set(viewer, ownMenu);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            Arrays.fill(contents, ItemStack.EMPTY);
            when(viewer.getUUID()).thenReturn(UUID.randomUUID());
            var level = mock(net.minecraft.server.level.ServerLevel.class);
            var server = mock(net.minecraft.server.MinecraftServer.class);
            when(viewer.level()).thenReturn(level); when(level.getServer()).thenReturn(server);
            when(server.getPlayerList()).thenReturn(playerList); when(playerList.getPlayer(viewer.getUUID())).thenReturn(viewer);
            when(viewer.getInventory()).thenReturn(viewerInventory);
            when(viewerInventory.getItem(anyInt())).thenReturn(ItemStack.EMPTY);
            when(viewerInventory.getContainerSize()).thenReturn(36);
            when(target.getInventory()).thenReturn(targetInventory);
            when(targetInventory.getItem(anyInt())).thenAnswer(call -> contents[call.getArgument(0, Integer.class)]);
            when(target.getItemBySlot(any())).thenReturn(ItemStack.EMPTY);
            when(target.getEnderChestInventory()).thenReturn(mock(net.minecraft.world.inventory.PlayerEnderChestContainer.class));
            when(target.getEnderChestInventory().getItem(anyInt())).thenAnswer(call -> ender.getItem(call.getArgument(0, Integer.class)));
            doAnswer(call -> {
                var provider = call.getArgument(0, MenuProvider.class);
                viewer.containerMenu = provider.createMenu(7, viewerInventory, viewer);
                return OptionalInt.of(7);
            }).when(viewer).openMenu(any(MenuProvider.class));
        }
        IMenuPlatform.Handle open(int size) {
            return platform.open(new MinecraftPlayer(viewer), new MinecraftComponent(Component.literal("RealAccount")), size, Map.of(), listener);
        }
    }
}
