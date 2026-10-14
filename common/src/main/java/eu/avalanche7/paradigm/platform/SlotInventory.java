package eu.avalanche7.paradigm.platform;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public abstract class SlotInventory<T> {
    public static final class MissingItemsException extends IllegalStateException {
        public MissingItemsException(String message) { super(message); }
    }
    protected abstract int size();
    protected abstract T get(int slot);
    protected abstract T copy(T stack);
    protected abstract int count(T stack);
    protected abstract T withCount(T stack, int count);
    protected abstract void set(int slot, T stack);
    protected abstract boolean same(T first, T second);
    protected abstract boolean matches(T stack, ItemRequirement item);
    protected abstract void validate(ItemRequirement item);
    protected abstract void sync();

    public final InventoryConsumption prepare(List<ItemRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) throw new IllegalArgumentException("At least one item is required");
        requirements.forEach(this::validate);
        List<T> before = new ArrayList<>();
        int[] remaining = new int[size()];
        for (int slot = 0; slot < remaining.length; slot++) {
            T stack = copy(get(slot)); before.add(stack); remaining[slot] = Math.max(0, count(stack));
        }
        var ordered = new ArrayList<>(requirements);
        ordered.sort(Comparator.comparing(item -> item.metadata() == null));
        for (var item : ordered) {
            long needed = item.amount();
            for (int slot = 0; slot < remaining.length && needed > 0; slot++) {
                if (!matches(before.get(slot), item)) continue;
                int removed = (int) Math.min(needed, remaining[slot]);
                remaining[slot] -= removed; needed -= removed;
            }
            if (needed > 0) throw new MissingItemsException("Missing items: " + item.itemId() + " (" + needed + " short)");
        }
        return new InventoryConsumption() {
            private boolean attempted;
            private boolean restored;
            @Override public void consume() {
                if (attempted) throw new IllegalStateException("Inventory consumption already attempted");
                for (int slot = 0; slot < remaining.length; slot++)
                    if (!same(before.get(slot), get(slot))) throw new IllegalStateException("Inventory changed after validation");
                attempted = true;
                try {
                    for (int slot = 0; slot < remaining.length; slot++)
                        if (remaining[slot] != count(before.get(slot))) set(slot, withCount(copy(before.get(slot)), remaining[slot]));
                    sync();
                } catch (RuntimeException failure) {
                    try { restore(); } catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                    throw failure;
                }
            }
            @Override public void restore() {
                if (!attempted || restored) return;
                for (int slot = 0; slot < remaining.length; slot++)
                    if (remaining[slot] != count(before.get(slot))) set(slot, copy(before.get(slot)));
                sync(); restored = true;
            }
        };
    }
}
