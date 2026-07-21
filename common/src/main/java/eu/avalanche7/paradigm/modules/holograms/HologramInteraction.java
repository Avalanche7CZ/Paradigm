package eu.avalanche7.paradigm.modules.holograms;

import java.util.ArrayList;
import java.util.List;

public final class HologramInteraction {
    public boolean enabled;
    public double width = 1.0D;
    public double height = 1.0D;
    public int cooldownSeconds;
    public HologramConditionGroup conditions = new HologramConditionGroup();
    public List<HologramAction> onInteract = new ArrayList<>();
    public List<HologramAction> onAttack = new ArrayList<>();

    public HologramInteraction copy() {
        HologramInteraction copy = new HologramInteraction();
        copy.enabled = enabled;
        copy.width = width;
        copy.height = height;
        copy.cooldownSeconds = cooldownSeconds;
        copy.conditions = conditions != null ? conditions.copy() : new HologramConditionGroup();
        copy.onInteract = copyActions(onInteract);
        copy.onAttack = copyActions(onAttack);
        return copy;
    }

    public void normalize() {
        if (!Double.isFinite(width)) width = 1.0D;
        if (!Double.isFinite(height)) height = 1.0D;
        width = Math.max(0.1D, Math.min(16.0D, width));
        height = Math.max(0.1D, Math.min(16.0D, height));
        cooldownSeconds = Math.max(0, Math.min(86400, cooldownSeconds));
        if (conditions == null) conditions = new HologramConditionGroup();
        conditions.normalize();
        onInteract = normalizeActions(onInteract);
        onAttack = normalizeActions(onAttack);
        if (onInteract.size() > 32 || onAttack.size() > 32) throw new IllegalArgumentException("A hologram interaction may contain at most 32 actions per input.");
    }

    private static List<HologramAction> copyActions(List<HologramAction> source) {
        List<HologramAction> copy = new ArrayList<>();
        if (source != null) for (HologramAction action : source) if (action != null) copy.add(action.copy());
        return copy;
    }

    private static List<HologramAction> normalizeActions(List<HologramAction> source) {
        List<HologramAction> normalized = new ArrayList<>();
        if (source == null) return normalized;
        for (HologramAction action : source) {
            if (action == null) throw new IllegalArgumentException("Hologram actions cannot contain null entries.");
            action.normalize();
            normalized.add(action);
        }
        return normalized;
    }
}
