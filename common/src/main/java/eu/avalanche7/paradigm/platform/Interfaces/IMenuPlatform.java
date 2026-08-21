package eu.avalanche7.paradigm.platform.Interfaces;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

public interface IMenuPlatform {

    enum ClickKind {
        LEFT,
        RIGHT,
        MIDDLE,
        SHIFT_LEFT,
        SHIFT_RIGHT,
        NUMBER_KEY,
        SWAP_OFFHAND,
        DOUBLE_CLICK,
        DROP,
        CONTROL_DROP,
        DRAG,
        OTHER
    }

    record ItemSpec(
            String itemId,
            int amount,
            IComponent name,
            List<IComponent> lore,
            boolean glint,
            @Nullable Integer customModelData,
            boolean hideTooltip) {
    }

    interface Handle {
        void setItem(int slot, @Nullable ItemSpec spec);

        void setItems(Map<Integer, ItemSpec> items);

        void setTitle(IComponent title);

        void close();

        boolean isOpen();
    }

    interface ClickListener {
        void onSlotActivated(IPlayer player, int slot, ClickKind kind);

        void onClosed(IPlayer player);
    }

    @Nullable
    Handle open(IPlayer player, IComponent title, int size, Map<Integer, ItemSpec> items, ClickListener listener);

    boolean isItemValid(String itemId);

    void closeAll();

    default boolean supportsGlint() {
        return true;
    }

    default boolean supportsLiveTitle() {
        return false;
    }
}
