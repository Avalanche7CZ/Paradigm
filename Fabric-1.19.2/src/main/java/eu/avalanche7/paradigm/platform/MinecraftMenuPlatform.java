package eu.avalanche7.paradigm.platform;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IMenuPlatform;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.registry.Registry;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class MinecraftMenuPlatform implements IMenuPlatform {

    private final Map<java.util.UUID, ParadigmScreenHandler> open = new HashMap<>();

    @Override
    public Handle open(IPlayer player, IComponent title, int size, Map<Integer, ItemSpec> items, ClickListener listener) {
        if (!(player.getOriginalPlayer() instanceof ServerPlayerEntity serverPlayer)) {
            return null;
        }
        int rows = Math.max(1, Math.min(6, size / 9));
        SimpleInventory inventory = new SimpleInventory(rows * 9);
        applyItems(inventory, items);

        Text nativeTitle = toText(title);
        ParadigmScreenHandler[] created = new ParadigmScreenHandler[1];

        NamedScreenHandlerFactory factory = new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return nativeTitle;
            }

            @Override
            public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity user) {
                ParadigmScreenHandler handler = new ParadigmScreenHandler(
                        typeFor(rows), syncId, playerInventory, inventory, rows, listener);
                created[0] = handler;
                return handler;
            }
        };

        serverPlayer.openHandledScreen(factory);
        ParadigmScreenHandler handler = created[0];
        if (handler == null) {
            return null;
        }
        open.put(serverPlayer.getUuid(), handler);
        return new ScreenHandle(serverPlayer, handler, inventory);
    }

    @Override
    public boolean isItemValid(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        Identifier identifier = Identifier.tryParse(itemId);
        return identifier != null && Registry.ITEM.get(identifier) != Items.AIR;
    }

    @Override
    public void closeAll() {
        for (ParadigmScreenHandler handler : new LinkedHashMap<>(open).values()) {
            handler.detach();
        }
        open.clear();
    }

    private static ScreenHandlerType<GenericContainerScreenHandler> typeFor(int rows) {
        return switch (rows) {
            case 1 -> ScreenHandlerType.GENERIC_9X1;
            case 2 -> ScreenHandlerType.GENERIC_9X2;
            case 4 -> ScreenHandlerType.GENERIC_9X4;
            case 5 -> ScreenHandlerType.GENERIC_9X5;
            case 6 -> ScreenHandlerType.GENERIC_9X6;
            default -> ScreenHandlerType.GENERIC_9X3;
        };
    }

    private static void applyItems(SimpleInventory inventory, Map<Integer, ItemSpec> items) {
        if (items == null) {
            return;
        }
        for (Map.Entry<Integer, ItemSpec> entry : items.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= inventory.size()) {
                continue;
            }
            inventory.setStack(slot, toStack(entry.getValue()));
        }
    }

    static ItemStack toStack(ItemSpec spec) {
        if (spec == null) {
            return ItemStack.EMPTY;
        }
        Identifier identifier = Identifier.tryParse(spec.itemId());
        Item item = identifier != null ? Registry.ITEM.get(identifier) : Items.AIR;
        if (item == Items.AIR) {
            item = Items.BARRIER;
        }
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(64, spec.amount())));
        Text name = toText(spec.name());
        if (name != null) {
            stack.setCustomName(name);
        }
        List<IComponent> lore = spec.lore();
        if (lore != null && !lore.isEmpty()) {
            NbtList lines = new NbtList();
            for (IComponent line : lore) {
                Text text = toText(line);
                lines.add(NbtString.of(Text.Serializer.toJson(text != null ? text : Text.empty())));
            }
            stack.getOrCreateSubNbt("display").put("Lore", lines);
        }
        if (spec.glint()) {
            stack.addEnchantment(Enchantments.UNBREAKING, 1);
            stack.getOrCreateNbt().putInt("HideFlags", 1);
        }
        if (spec.customModelData() != null) {
            stack.getOrCreateNbt().putInt("CustomModelData", spec.customModelData());
        }
        return stack;
    }

    static Text toText(IComponent component) {
        if (component == null) {
            return null;
        }
        if (component instanceof MinecraftComponent wrapped) {
            return wrapped.getHandle();
        }
        Object original = component.getOriginalText();
        return original instanceof Text text ? text : Text.literal(component.getRawText());
    }

    static ClickKind mapClick(SlotActionType actionType, int button) {
        return switch (actionType) {
            case PICKUP -> button == 1 ? ClickKind.RIGHT : ClickKind.LEFT;
            case QUICK_MOVE -> button == 1 ? ClickKind.SHIFT_RIGHT : ClickKind.SHIFT_LEFT;
            case SWAP -> button == 40 ? ClickKind.SWAP_OFFHAND : ClickKind.NUMBER_KEY;
            case CLONE -> ClickKind.MIDDLE;
            case THROW -> button == 1 ? ClickKind.CONTROL_DROP : ClickKind.DROP;
            case QUICK_CRAFT -> ClickKind.DRAG;
            case PICKUP_ALL -> ClickKind.DOUBLE_CLICK;
        };
    }

    private final class ScreenHandle implements Handle {
        private final ServerPlayerEntity player;
        private final ParadigmScreenHandler handler;
        private final SimpleInventory inventory;

        private ScreenHandle(ServerPlayerEntity player, ParadigmScreenHandler handler, SimpleInventory inventory) {
            this.player = player;
            this.handler = handler;
            this.inventory = inventory;
        }

        @Override
        public void setItem(int slot, ItemSpec spec) {
            if (slot < 0 || slot >= inventory.size() || !isOpen()) {
                return;
            }
            inventory.setStack(slot, spec != null ? toStack(spec) : ItemStack.EMPTY);
            handler.syncState();
        }

        @Override
        public void setItems(Map<Integer, ItemSpec> items) {
            if (!isOpen()) {
                return;
            }
            applyItems(inventory, items);
            handler.syncState();
        }

        @Override
        public void setTitle(IComponent title) {
        }

        @Override
        public void close() {
            handler.detach();
            open.remove(player.getUuid(), handler);
            if (player.currentScreenHandler == handler) {
                player.closeHandledScreen();
            }
        }

        @Override
        public boolean isOpen() {
            return !handler.isDetached() && player.currentScreenHandler == handler;
        }
    }

    private final class ParadigmScreenHandler extends GenericContainerScreenHandler {
        private final ClickListener listener;
        private final int menuSlots;
        private boolean detached;

        private ParadigmScreenHandler(ScreenHandlerType<GenericContainerScreenHandler> type, int syncId,
                PlayerInventory playerInventory, SimpleInventory inventory, int rows, ClickListener listener) {
            super(type, syncId, playerInventory, inventory, rows);
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
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            if (detached || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return;
            }
            setCursorStack(ItemStack.EMPTY);
            syncState();
            serverPlayer.currentScreenHandler.sendContentUpdates();
            if (slotIndex >= 0 && slotIndex < menuSlots && listener != null) {
                listener.onSlotActivated(new MinecraftPlayer(serverPlayer), slotIndex, mapClick(actionType, button));
            }
        }

        @Override
        public ItemStack transferSlot(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canInsertIntoSlot(ItemStack stack, net.minecraft.screen.slot.Slot slot) {
            return false;
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return !detached;
        }

        @Override
        public void close(PlayerEntity player) {
            super.close(player);
            if (detached) {
                return;
            }
            detached = true;
            if (player instanceof ServerPlayerEntity serverPlayer) {
                open.remove(serverPlayer.getUuid(), this);
                if (listener != null) {
                    listener.onClosed(new MinecraftPlayer(serverPlayer));
                }
            }
        }
    }
}
