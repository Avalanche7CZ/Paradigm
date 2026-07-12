package eu.avalanche7.paradigm.modules.commands.permissions;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionMutationArgumentParser;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

import java.util.UUID;

public final class PermissionCommandArguments {
    private PermissionCommandArguments() {
    }

    public static PermissionMutationArgumentParser.Result parse(ICommandSource source, Services services, String flags, boolean allowExpiry) {
        return new PermissionMutationArgumentParser(() -> services.getStorageService() != null && services.getStorageService().context() != null
                ? services.getStorageService().context().serverIdentity() : null).parse(flags, source, allowExpiry);
    }

    public static UUID resolvePlayerUuid(Services services, String input) {
        if (input == null || input.isBlank()) return null;
        try {
            return UUID.fromString(input.trim());
        } catch (IllegalArgumentException ignored) {
        }
        IPlayer player = services.getPlatformAdapter().getPlayerByName(input.trim());
        if (player == null || player.getUUID() == null || player.getUUID().isBlank()) return null;
        try {
            return UUID.fromString(player.getUUID());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static String resolvePlayerLabel(Services services, UUID uuid, String fallback) {
        if (uuid != null) {
            IPlayer player = services.getPlatformAdapter().getPlayerByUuid(uuid.toString());
            if (player != null && player.getName() != null && !player.getName().isBlank()) {
                return player.getName();
            }
            return uuid.toString();
        }
        return fallback != null && !fallback.isBlank() ? fallback.trim() : "-";
    }
}
