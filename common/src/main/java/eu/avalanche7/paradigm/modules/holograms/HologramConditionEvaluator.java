package eu.avalanche7.paradigm.modules.holograms;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.platform.Interfaces.IHologramPlatform;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.util.Arrays;
import java.util.Locale;

public final class HologramConditionEvaluator {
    private final Services services;
    private final IHologramPlatform platform;

    public HologramConditionEvaluator(Services services, IHologramPlatform platform) {
        this.services = services;
        this.platform = platform;
    }

    public boolean test(HologramConditionGroup group, HologramDefinition definition, IPlayer player) {
        if (player == null) return false;
        return evaluate(group, definition, new Context() {
            @Override public boolean hasPermission(String node) { return services.getPermissionsHandler().hasPermission(player, node); }
            @Override public boolean hasGroup(String group) { return HologramConditionEvaluator.this.hasGroup(player, group); }
            @Override public boolean isOperator() { return services.getPlatformAdapter().hasPermission(player, "paradigm.hologram.operator", 2); }
            @Override public String world() { return player.getWorldId(); }
            @Override public Double x() { return player.getX(); }
            @Override public Double y() { return player.getY(); }
            @Override public Double z() { return player.getZ(); }
            @Override public IHologramPlatform.WorldState worldState(String dimension) { return platform != null ? platform.worldState(dimension) : null; }
        });
    }

    public static boolean evaluate(HologramConditionGroup group, HologramDefinition definition, Context context) {
        if (context == null || definition == null) return false;
        if (group == null || group.isEmpty()) return true;
        boolean result = "any".equals(group.mode)
                ? group.conditions.stream().anyMatch(condition -> test(condition, definition, context))
                : group.conditions.stream().allMatch(condition -> test(condition, definition, context));
        return group.negate ? !result : result;
    }

    private static boolean test(HologramCondition condition, HologramDefinition definition, Context context) {
        boolean result = switch (condition.type) {
            case "all", "any" -> testNested(condition, definition, context);
            case "permission" -> context.hasPermission(condition.value);
            case "group" -> context.hasGroup(condition.value);
            case "operator" -> context.isOperator();
            case "world" -> matchesAny(context.world(), condition.value);
            case "distance" -> distanceMatches(condition, definition, context);
            case "time" -> timeMatches(condition, definition, context);
            case "weather" -> weatherMatches(condition, definition, context);
            default -> false;
        };
        return condition.negate ? !result : result;
    }

    private static boolean testNested(HologramCondition condition, HologramDefinition definition, Context context) {
        if (condition.conditions == null || condition.conditions.isEmpty()) return true;
        return "any".equals(condition.type)
                ? condition.conditions.stream().anyMatch(child -> test(child, definition, context))
                : condition.conditions.stream().allMatch(child -> test(child, definition, context));
    }

    private boolean hasGroup(IPlayer player, String requiredGroup) {
        PermissionAPI.PermissionMeta metadata = services.getPermissionsHandler().resolvePlayerMetadata(player);
        if (metadata == null || requiredGroup == null || requiredGroup.isBlank()) return false;
        String required = requiredGroup.trim().toLowerCase(Locale.ROOT);
        if (required.equals(normalize(metadata.primaryGroup()))) return true;
        return metadata.groups() != null && metadata.groups().stream().map(HologramConditionEvaluator::normalize).anyMatch(required::equals);
    }

    private static boolean distanceMatches(HologramCondition condition, HologramDefinition definition, Context context) {
        if (context.world() == null || !context.world().equalsIgnoreCase(definition.dimension)
                || context.x() == null || context.y() == null || context.z() == null) return false;
        double dx = context.x() - definition.x;
        double dy = context.y() - definition.y;
        double dz = context.z() - definition.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return (condition.minDistance == null || distance >= condition.minDistance)
                && (condition.maxDistance == null || distance <= condition.maxDistance);
    }

    private static boolean timeMatches(HologramCondition condition, HologramDefinition definition, Context context) {
        IHologramPlatform.WorldState state = context.worldState(definition.dimension);
        if (state == null || state.timeOfDay() < 0L) return false;
        long start = condition.startTime != null ? condition.startTime : 0L;
        long end = condition.endTime != null ? condition.endTime : 23999L;
        long time = Math.floorMod(state.timeOfDay(), 24000L);
        return start <= end ? time >= start && time <= end : time >= start || time <= end;
    }

    private static boolean weatherMatches(HologramCondition condition, HologramDefinition definition, Context context) {
        IHologramPlatform.WorldState state = context.worldState(definition.dimension);
        return state != null && matchesAny(state.weather(), condition.value);
    }

    private static boolean matchesAny(String actual, String accepted) {
        if (actual == null || accepted == null) return false;
        String normalizedActual = normalize(actual);
        return Arrays.stream(accepted.split("[,|]"))
                .map(HologramConditionEvaluator::normalize)
                .anyMatch(normalizedActual::equals);
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    public interface Context {
        boolean hasPermission(String node);
        boolean hasGroup(String group);
        boolean isOperator();
        String world();
        Double x();
        Double y();
        Double z();
        IHologramPlatform.WorldState worldState(String dimension);
    }
}
