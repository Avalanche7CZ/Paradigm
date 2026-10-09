package eu.avalanche7.paradigm.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import eu.avalanche7.paradigm.platform.Interfaces.IComponent;
import eu.avalanche7.paradigm.platform.Interfaces.IMenuPlatform;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.platform.menu.ParadigmMenuContainer;

public final class MinecraftMenuPlatform implements IMenuPlatform {
    private final PlatformAdapterImpl adapter;
    private final Map<UUID, ParadigmMenuContainer> open = new LinkedHashMap<>();
    private final Map<UUID, Integer> lastWindowIds = new LinkedHashMap<>();

    public MinecraftMenuPlatform(PlatformAdapterImpl adapter) { this.adapter = adapter; }

    @Override
    public Handle open(IPlayer player, IComponent title, int size, Map<Integer, ItemSpec> items, ClickListener listener) {
        if (!validSize(size)) throw new IllegalArgumentException("Chest menus require 9 to 54 slots in complete rows");
        if (!adapter.isServerThread() || player == null || !(player.getOriginalPlayer() instanceof EntityPlayerMP nativePlayer)) return null;
        InventoryBasic inventory = new InventoryBasic(legacyLimit(text(title), 32), true, size);
        apply(inventory, items);
        restoreWindowCounter(nativePlayer);
        nativePlayer.displayGUIChest(inventory);
        lastWindowIds.put(nativePlayer.getUniqueID(), nativePlayer.currentWindowId);
        ParadigmMenuContainer menu = new ParadigmMenuContainer(nativePlayer, inventory, listener,
                () -> open.remove(nativePlayer.getUniqueID()));
        menu.windowId = nativePlayer.openContainer.windowId;
        nativePlayer.openContainer = menu;
        open.put(nativePlayer.getUniqueID(), menu);
        menu.addCraftingToCrafters(nativePlayer);
        return new Handle() {
            @Override public void setItem(int slot, ItemSpec spec) {
                adapter.executeOnServerThread(() -> {
                    if (isOpen() && slot >= 0 && slot < size) {
                        inventory.setInventorySlotContents(slot, toStack(spec));
                        menu.detectAndSendChanges();
                    }
                });
            }
            @Override public void setItems(Map<Integer, ItemSpec> specs) {
                Map<Integer, ItemSpec> copy = specs != null ? new LinkedHashMap<>(specs) : Map.of();
                adapter.executeOnServerThread(() -> { if (isOpen()) { apply(inventory, copy); menu.detectAndSendChanges(); } });
            }
            @Override public void setTitle(IComponent newTitle) {}
            @Override public void close() { adapter.executeOnServerThread(() -> { if (isOpen()) nativePlayer.closeScreen(); }); }
            @Override public boolean isOpen() { return menu.active(nativePlayer); }
        };
    }

    public static boolean validSize(int size) { return size >= 9 && size <= 54 && size % 9 == 0; }

    @Override public boolean isItemValid(String itemId) { return registeredItem(itemId) != null; }

    private static Map<String, Item> normalizedItems;

    static Item registeredItem(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        Object item = Item.itemRegistry.getObject(itemId);
        if (item == null) {
            if (normalizedItems == null) {
                Map<String, Item> index = new java.util.HashMap<>();
                java.util.Set<String> ambiguous = new java.util.HashSet<>();
                for (Object key : Item.itemRegistry.getKeys()) {
                    String folded = key.toString().toLowerCase(java.util.Locale.ROOT);
                    Item registered = (Item) Item.itemRegistry.getObject(key);
                    if (index.putIfAbsent(folded, registered) != null) ambiguous.add(folded);
                }
                ambiguous.forEach(index::remove);
                normalizedItems = index;
            }
            item = normalizedItems.get(itemId.toLowerCase(java.util.Locale.ROOT));
        }
        return item instanceof Item nativeItem && nativeItem != Item.getItemFromBlock(net.minecraft.init.Blocks.air) ? nativeItem : null;
    }

    public static ItemStack toStack(ItemSpec spec) {
        if (spec == null) return null;
        Item item = registeredItem(spec.itemId());
        if (item == null) item = Items.paper;
        ItemStack stack = new ItemStack(item, Math.max(1, Math.min(item.getItemStackLimit(), spec.amount())));
        NBTTagCompound display = new NBTTagCompound();
        if (spec.name() != null) display.setString("Name", "§r" + text(spec.name()));
        if (spec.lore() != null && !spec.lore().isEmpty()) {
            NBTTagList lore = new NBTTagList();
            for (IComponent line : spec.lore()) lore.appendTag(new NBTTagString("§r" + text(line)));
            display.setTag("Lore", lore);
        }
        if (!display.hasNoTags()) { NBTTagCompound tag = new NBTTagCompound(); tag.setTag("display", display); stack.setTagCompound(tag); }
        if (spec.glint()) {
            if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
            NBTTagList enchantments = new NBTTagList();
            stack.getTagCompound().setTag("ench", enchantments);
        }
        return stack;
    }

    private static void apply(InventoryBasic inventory, Map<Integer, ItemSpec> items) {
        if (items == null) return;
        items.forEach((slot, spec) -> { if (slot != null && slot >= 0 && slot < inventory.getSizeInventory()) inventory.setInventorySlotContents(slot, toStack(spec)); });
    }

    public static String text(IComponent component) {
        return component instanceof MinecraftComponent wrapped ? wrapped.toLegacyText() : component != null ? component.getRawText() : "";
    }

    public static String legacyLimit(String text, int limit) {
        int end = Math.min(text.length(), limit);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        if (end > 0 && text.charAt(end - 1) == '§') end--;
        return text.substring(0, end);
    }

    public void disconnected(EntityPlayerMP player) {
        ParadigmMenuContainer menu = open.get(player.getUniqueID());
        if (menu != null && menu.owner() == player) menu.disconnected();
        lastWindowIds.remove(player.getUniqueID());
    }

    public void respawned(EntityPlayerMP replacement) {
        restoreWindowCounter(replacement);
        ParadigmMenuContainer menu = open.get(replacement.getUniqueID());
        if (menu != null) menu.disconnected();
    }

    private void restoreWindowCounter(EntityPlayerMP player) {
        if (player.currentWindowId == 0) player.currentWindowId = lastWindowIds.getOrDefault(player.getUniqueID(), 0);
    }

    public void stopped() {
        closeAll();
        lastWindowIds.clear();
    }

    @Override public void closeAll() {
        adapter.executeOnServerThread(() -> {
            for (ParadigmMenuContainer menu : List.copyOf(open.values())) {
                if (menu.active(menu.owner())) menu.owner().closeScreen();
                else menu.disconnected();
            }
            open.clear();
        });
    }
}
