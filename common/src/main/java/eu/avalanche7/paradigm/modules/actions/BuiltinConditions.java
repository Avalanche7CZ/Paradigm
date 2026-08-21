package eu.avalanche7.paradigm.modules.actions;

import java.util.Arrays;
import java.util.Locale;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.PermissionAPI;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class BuiltinConditions {

    private BuiltinConditions() {
    }

    public static void register(ConditionRegistry registry, Services services) {
        registry.register("has_permission", true, (condition, context) ->
                condition.getValue() != null
                        && services.getPermissionsHandler().hasPermission(context.player(), condition.getValue()),
                "permission");

        registry.register("has_item", true, (condition, context) ->
                condition.getValue() != null
                        && services.getPlatformAdapter().playerHasItem(
                                context.player(), condition.getValue(), condition.getItemAmount()));

        registry.register("health_above", true, (condition, context) -> {
            Double health = context.player().getHealth();
            Double limit = parseDouble(condition.getValue());
            return health != null && limit != null && health > limit;
        });

        registry.register("health_below", true, (condition, context) -> {
            Double health = context.player().getHealth();
            Double limit = parseDouble(condition.getValue());
            return health != null && limit != null && health < limit;
        });

        registry.register("is_op", true, (condition, context) -> {
            int level = 2;
            if (condition.getValue() != null) {
                try {
                    level = Integer.parseInt(condition.getValue().trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return services.getPermissionsHandler().hasPermission(context.player(), "minecraft.command.op", level);
        }, "operator");

        registry.register("has_group", true, (condition, context) ->
                hasGroup(services, context.player(), condition.getValue()), "group");

        registry.register("in_world", true, (condition, context) ->
                matchesAny(context.player().getWorldId(), condition.getValue()), "world", "dimension");

        registry.register("context_equals", false, (condition, context) -> {
            String raw = condition.getValue();
            if (raw == null || !raw.contains("=")) {
                return false;
            }
            int split = raw.indexOf('=');
            String key = raw.substring(0, split).trim();
            String expected = raw.substring(split + 1).trim();
            String actual = context.value(key);
            return actual != null && actual.equalsIgnoreCase(expected);
        });
    }

    private static boolean hasGroup(Services services, IPlayer player, String requiredGroup) {
        if (requiredGroup == null || requiredGroup.isBlank()) {
            return false;
        }
        PermissionAPI.PermissionMeta metadata = player != null && player.getUUID() != null
                ? services.getPermissionsHandler().resolvePlayerMetadata(java.util.UUID.fromString(player.getUUID()))
                : null;
        if (metadata == null) {
            return false;
        }
        String required = normalize(requiredGroup);
        if (required.equals(normalize(metadata.primaryGroup()))) {
            return true;
        }
        return metadata.groups() != null
                && metadata.groups().stream().map(BuiltinConditions::normalize).anyMatch(required::equals);
    }

    private static boolean matchesAny(String actual, String accepted) {
        if (actual == null || accepted == null) {
            return false;
        }
        String normalizedActual = normalize(actual);
        return Arrays.stream(accepted.split("[,|]"))
                .map(BuiltinConditions::normalize)
                .anyMatch(normalizedActual::equals);
    }

    private static Double parseDouble(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value != null ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
