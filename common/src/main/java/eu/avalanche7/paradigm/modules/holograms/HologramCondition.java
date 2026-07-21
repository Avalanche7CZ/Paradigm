package eu.avalanche7.paradigm.modules.holograms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class HologramCondition {
    public String type = "permission";
    public String value = "";
    public Double minDistance;
    public Double maxDistance;
    public Long startTime;
    public Long endTime;
    public boolean negate;
    public String mode = "all";
    public List<HologramCondition> conditions = new ArrayList<>();

    public HologramCondition copy() {
        HologramCondition copy = new HologramCondition();
        copy.type = type;
        copy.value = value;
        copy.minDistance = minDistance;
        copy.maxDistance = maxDistance;
        copy.startTime = startTime;
        copy.endTime = endTime;
        copy.negate = negate;
        copy.mode = mode;
        if (conditions != null) {
            for (HologramCondition condition : conditions) {
                if (condition != null) copy.conditions.add(condition.copy());
            }
        }
        return copy;
    }

    public void normalize() {
        type = normalizeType(type);
        value = value != null ? value.trim() : "";
        mode = "any".equalsIgnoreCase(mode) ? "any" : "all";
        if (minDistance != null && (!Double.isFinite(minDistance) || minDistance < 0.0D)) minDistance = null;
        if (maxDistance != null && (!Double.isFinite(maxDistance) || maxDistance < 0.0D)) maxDistance = null;
        if (minDistance != null && maxDistance != null && minDistance > maxDistance) {
            double swap = minDistance;
            minDistance = maxDistance;
            maxDistance = swap;
        }
        if (startTime != null) startTime = Math.floorMod(startTime, 24000L);
        if (endTime != null) endTime = Math.floorMod(endTime, 24000L);
        if (conditions == null) conditions = new ArrayList<>();
        if (conditions.size() > 64) throw new IllegalArgumentException("A hologram condition group may contain at most 64 conditions.");
        for (HologramCondition condition : conditions) {
            if (condition == null) throw new IllegalArgumentException("Hologram conditions cannot contain null entries.");
            condition.normalize();
        }
        if (requiresValue() && value.isBlank()) {
            throw new IllegalArgumentException("Hologram " + type + " conditions require a value.");
        }
    }

    public boolean isGroup() {
        return "all".equals(type) || "any".equals(type);
    }

    public boolean requiresValue() {
        return switch (type) {
            case "permission", "group", "world", "weather" -> true;
            default -> false;
        };
    }

    public static String normalizeType(String value) {
        String normalized = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "permission", "group", "operator", "world", "dimension", "distance", "time", "weather", "all", "any" ->
                    "dimension".equals(normalized) ? "world" : normalized;
            default -> throw new IllegalArgumentException("Unknown hologram condition type: " + value);
        };
    }
}
