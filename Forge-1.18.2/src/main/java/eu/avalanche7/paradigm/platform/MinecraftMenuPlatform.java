package eu.avalanche7.paradigm.platform;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IMenuPlatform;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.registries.ForgeRegistries;

public final class MinecraftMenuPlatform implements IMenuPlatform {

    private final Map<java.util.UUID, ParadigmChestMenu> open = new HashMap<>();

    @Override
    public Handle open(IPlayer player, IComponent title, int size, Map<Integer, ItemSpec> items, ClickListener listener) {
        if (!(player.getOriginalPlayer() instanceof ServerPlayer serverPlayer)) {
            return null;
        }
        int rows = Math.max(1, Math.min(6, size / 9));
        SimpleContainer container = new SimpleContainer(rows * 9);
        applyItems(container, items);

        Component nativeTitle = toComponent(title);
        ParadigmChestMenu[] created = new ParadigmChestMenu[1];

        MenuProvider provider = new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return nativeTitle != null ? nativeTitle : new TextComponent("");
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player user) {
                ParadigmChestMenu menu = new ParadigmChestMenu(
                        typeFor(rows), containerId, inventory, container, rows, listener);
                created[0] = menu;
                return menu;
            }
        };

        serverPlayer.openMenu(provider);
        ParadigmChestMenu menu = created[0];
        if (menu == null) {
            return null;
        }
        open.put(serverPlayer.getUUID(), menu);
        return new MenuHandle(serverPlayer, menu, container);
    }

    @Override
    public boolean isItemValid(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        ResourceLocation identifier = ResourceLocation.tryParse(itemId);
        if (identifier == null) {
            return false;
        }
        Item item = ForgeRegistries.ITEMS.getValue(identifier);
        return item != null && item != Items.AIR;
    }

    @Override
    public void closeAll() {
        for (ParadigmChestMenu menu : new LinkedHashMap<>(open).values()) {
            menu.detach();
        }
        open.clear();
    }

    private static MenuType<ChestMenu> typeFor(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> MenuType.GENERIC_9x3;
        };
    }

    private static void applyItems(SimpleContainer container, Map<Integer, ItemSpec> items) {
        if (items == null) {
            return;
        }
        for (Map.Entry<Integer, ItemSpec> entry : items.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= container.getContainerSize()) {
                continue;
            }
            container.setItem(slot, toStack(entry.getValue()));
        }
    }

    static ItemStack toStack(ItemSpec spec) {
        if (spec == null) {
            return ItemStack.EMPTY;
        }
        ResourceLocation identifier = ResourceLocation.tryParse(spec.itemId());
        Item item = identifier != null ? ForgeRegistries.ITEMS.getValue(identifier) : null;
        if (item == null || item == Items.AIR) {
            item = Items.BARRIER;
        }
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(64, spec.amount())));
        Component name = toComponent(spec.name());
        if (name != null) {
            stack.setHoverName(name);
        }
        List<IComponent> lore = spec.lore();
        if (lore != null && !lore.isEmpty()) {
            ListTag lines = new ListTag();
            for (IComponent line : lore) {
                Component text = toComponent(line);
                lines.add(StringTag.valueOf(Component.Serializer.toJson(text != null ? text : new TextComponent(""))));
            }
            stack.getOrCreateTagElement("display").put("Lore", lines);
        }
        if (spec.glint()) {
            stack.enchant(Enchantments.UNBREAKING, 1);
            stack.getOrCreateTag().putInt("HideFlags", 1);
        }
        if (spec.customModelData() != null) {
            stack.getOrCreateTag().putInt("CustomModelData", spec.customModelData());
        }
        return stack;
    }

    static Component toComponent(IComponent component) {
        if (component == null) {
            return null;
        }
        if (component instanceof MinecraftComponent wrapped) {
            return wrapped.getHandle();
        }
        Object original = component.getOriginalText();
        return original instanceof Component text ? text : new TextComponent(component.getRawText());
    }

    static ClickKind mapClick(ClickType clickType, int button) {
        return switch (clickType) {
            case PICKUP -> button == 1 ? ClickKind.RIGHT : ClickKind.LEFT;
            case QUICK_MOVE -> button == 1 ? ClickKind.SHIFT_RIGHT : ClickKind.SHIFT_LEFT;
            case SWAP -> button == 40 ? ClickKind.SWAP_OFFHAND : ClickKind.NUMBER_KEY;
            case CLONE -> ClickKind.MIDDLE;
            case THROW -> button == 1 ? ClickKind.CONTROL_DROP : ClickKind.DROP;
            case QUICK_CRAFT -> ClickKind.DRAG;
            case PICKUP_ALL -> ClickKind.DOUBLE_CLICK;
        };
    }

    private final class MenuHandle implements Handle {
        private final ServerPlayer player;
        private final ParadigmChestMenu menu;
        private final SimpleContainer container;

        private MenuHandle(ServerPlayer player, ParadigmChestMenu menu, SimpleContainer container) {
            this.player = player;
            this.menu = menu;
            this.container = container;
        }

        @Override
        public void setItem(int slot, ItemSpec spec) {
            if (slot < 0 || slot >= container.getContainerSize() || !isOpen()) {
                return;
            }
            container.setItem(slot, spec != null ? toStack(spec) : ItemStack.EMPTY);
            menu.sendAllDataToRemote();
        }

        @Override
        public void setItems(Map<Integer, ItemSpec> items) {
            if (!isOpen()) {
                return;
            }
            applyItems(container, items);
            menu.sendAllDataToRemote();
        }

        @Override
        public void setTitle(IComponent title) {
        }

        @Override
        public void close() {
            menu.detach();
            open.remove(player.getUUID(), menu);
            if (player.containerMenu == menu) {
                player.closeContainer();
            }
        }

        @Override
        public boolean isOpen() {
            return !menu.isDetached() && player.containerMenu == menu;
        }
    }

    private final class ParadigmChestMenu extends ChestMenu {
        private final ClickListener listener;
        private final int menuSlots;
        private boolean detached;

        private ParadigmChestMenu(MenuType<ChestMenu> type, int containerId, Inventory inventory,
                SimpleContainer container, int rows, ClickListener listener) {
            super(type, containerId, inventory, container, rows);
            this.listener = listener;
            this.menuSlots = rows * 9;
        }

        private void detach() {
            detached = true;
        }

        private boolean isDetached() {
            return detached;
        }

        @Override
        public void clicked(int slotId, int button, ClickType clickType, Player player) {
            if (detached || !(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            setCarried(ItemStack.EMPTY);
            sendAllDataToRemote();
            broadcastChanges();
            if (slotId >= 0 && slotId < menuSlots && listener != null) {
                listener.onSlotActivated(new MinecraftPlayer(serverPlayer), slotId, mapClick(clickType, button));
            }
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
            return false;
        }

        @Override
        public boolean stillValid(Player player) {
            return !detached;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (detached) {
                return;
            }
            detached = true;
            if (player instanceof ServerPlayer serverPlayer) {
                open.remove(serverPlayer.getUUID(), this);
                if (listener != null) {
                    listener.onClosed(new MinecraftPlayer(serverPlayer));
                }
            }
        }
    }
}
