package eu.avalanche7.paradigm.modules.holograms;

import java.util.ArrayList;
import java.util.List;

public final class HologramConditionGroup {
    public String mode = "all";
    public boolean negate;
    public List<HologramCondition> conditions = new ArrayList<>();

    public HologramConditionGroup copy() {
        HologramConditionGroup copy = new HologramConditionGroup();
        copy.mode = mode;
        copy.negate = negate;
        if (conditions != null) {
            for (HologramCondition condition : conditions) {
                if (condition != null) copy.conditions.add(condition.copy());
            }
        }
        return copy;
    }

    public void normalize() {
        mode = "any".equalsIgnoreCase(mode) ? "any" : "all";
        if (conditions == null) conditions = new ArrayList<>();
        if (conditions.size() > 64) throw new IllegalArgumentException("A hologram condition group may contain at most 64 conditions.");
        for (HologramCondition condition : conditions) {
            if (condition == null) throw new IllegalArgumentException("Hologram conditions cannot contain null entries.");
            condition.normalize();
        }
    }

    public boolean isEmpty() {
        return conditions == null || conditions.isEmpty();
    }
}
