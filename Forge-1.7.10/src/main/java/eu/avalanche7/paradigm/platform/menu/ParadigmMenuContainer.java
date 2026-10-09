package eu.avalanche7.paradigm.platform.menu;


import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S2FPacketSetSlot;

import eu.avalanche7.paradigm.platform.Interfaces.IMenuPlatform;
import eu.avalanche7.paradigm.platform.MinecraftPlayer;

public final class ParadigmMenuContainer extends ContainerChest {
    private final EntityPlayerMP owner;
    private final IMenuPlatform.ClickListener listener;
    private final Runnable cleanup;
    private final int menuSlots;
    private boolean hasTransaction;
    private short lastTransaction;
    private boolean closed;
    private int pendingSlot = -1;
    private IMenuPlatform.ClickKind pendingKind = IMenuPlatform.ClickKind.OTHER;

    public ParadigmMenuContainer(EntityPlayerMP owner, IInventory inventory, IMenuPlatform.ClickListener listener, Runnable cleanup) {
        super(owner.inventory, inventory);
        this.owner = owner;
        this.listener = listener;
        this.cleanup = cleanup;
        menuSlots = inventory.getSizeInventory();
    }

    public EntityPlayerMP owner() { return owner; }
    public boolean active(EntityPlayer player) { return !closed && player == owner && player.openContainer == this; }

    public boolean acceptTransaction(short transaction) {
        if (hasTransaction && (short) (transaction - lastTransaction) <= 0) {
            resync(-1, -1);
            return false;
        }
        hasTransaction = true;
        lastTransaction = transaction;
        return true;
    }

    @Override public ItemStack slotClick(int slot, int button, int mode, EntityPlayer player) {
        if (!active(player)) return null;
        resync(slot, mode);
        IMenuPlatform.ClickKind kind = mapClick(mode, button);
        if (slot >= 0 && slot < menuSlots && kind != IMenuPlatform.ClickKind.OTHER && kind != IMenuPlatform.ClickKind.DRAG) {
            pendingSlot = slot;
            pendingKind = kind;
        }
        return null;
    }

    private void resync(int slot, int mode) {
        if ((mode == 0 || mode == 3 || mode == 4) && slot >= 0 && slot < inventorySlots.size()) {
            owner.playerNetServerHandler.sendPacket(new S2FPacketSetSlot(windowId, slot, getSlot(slot).getStack()));
        } else owner.sendContainerToPlayer(this);
        owner.playerNetServerHandler.sendPacket(new S2FPacketSetSlot(-1, -1, owner.inventory.getItemStack()));
    }

    public void flushClick() {
        int slot = pendingSlot;
        IMenuPlatform.ClickKind kind = pendingKind;
        pendingSlot = -1;
        pendingKind = IMenuPlatform.ClickKind.OTHER;
        if (slot >= 0 && active(owner) && listener != null) listener.onSlotActivated(new MinecraftPlayer(owner), slot, kind);
    }

    public static IMenuPlatform.ClickKind mapClick(int mode, int button) {
        return switch (mode) {
            case 0 -> button == 0 ? IMenuPlatform.ClickKind.LEFT : button == 1 ? IMenuPlatform.ClickKind.RIGHT : IMenuPlatform.ClickKind.OTHER;
            case 1 -> button == 0 ? IMenuPlatform.ClickKind.SHIFT_LEFT : button == 1 ? IMenuPlatform.ClickKind.SHIFT_RIGHT : IMenuPlatform.ClickKind.OTHER;
            case 2 -> button >= 0 && button < 9 ? IMenuPlatform.ClickKind.NUMBER_KEY : IMenuPlatform.ClickKind.OTHER;
            case 3 -> button == 2 ? IMenuPlatform.ClickKind.MIDDLE : IMenuPlatform.ClickKind.OTHER;
            case 4 -> button == 0 ? IMenuPlatform.ClickKind.DROP : button == 1 ? IMenuPlatform.ClickKind.CONTROL_DROP : IMenuPlatform.ClickKind.OTHER;
            case 5 -> button >= 0 && button <= 10 && (button & 3) <= 2 ? IMenuPlatform.ClickKind.DRAG : IMenuPlatform.ClickKind.OTHER;
            case 6 -> button == 0 ? IMenuPlatform.ClickKind.DOUBLE_CLICK : IMenuPlatform.ClickKind.OTHER;
            default -> IMenuPlatform.ClickKind.OTHER;
        };
    }

    @Override public ItemStack transferStackInSlot(EntityPlayer player, int index) { return null; }
    @Override public boolean canDragIntoSlot(net.minecraft.inventory.Slot slot) { return false; }
    @Override public boolean func_94530_a(ItemStack stack, net.minecraft.inventory.Slot slot) { return false; }
    @Override public boolean canInteractWith(EntityPlayer player) { return active(player); }

    @Override public void onContainerClosed(EntityPlayer player) {
        if (closed) return;
        closed = true;
        pendingSlot = -1;
        super.onContainerClosed(player);
        cleanup.run();
        if (listener != null) listener.onClosed(new MinecraftPlayer(owner));
    }

    public void disconnected() {
        if (closed) return;
        closed = true;
        pendingSlot = -1;
        cleanup.run();
    }
}
