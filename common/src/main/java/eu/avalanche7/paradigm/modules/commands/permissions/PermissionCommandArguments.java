package eu.avalanche7.paradigm.modules.commands.permissions;

import static eu.avalanche7.paradigm.modules.profile.PlayerProfileService.parseCanonicalUuid;

import java.util.UUID;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.permissions.context.PermissionMutationArgumentParser;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;
import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import eu.avalanche7.paradigm.storage.model.StoredPlayerProfile;

public final class PermissionCommandArguments {
    private PermissionCommandArguments() {
    }

    public static PermissionMutationArgumentParser.Result parse(ICommandSource source, Services services, String flags, boolean allowExpiry) {
        return parse(source, services, flags, allowExpiry, false);
    }

    public static PermissionMutationArgumentParser.Result parse(ICommandSource source, Services services, String flags,
                                                                 boolean allowExpiry, boolean allowTrackFlags) {
        return new PermissionMutationArgumentParser(() -> services.getStorageService() != null && services.getStorageService().context() != null
                ? services.getStorageService().context().serverIdentity() : null).parse(flags, source, allowExpiry, allowTrackFlags);
    }

    static PlayerTarget resolveImmediatePlayer(Services services, String input) {
        if (input == null || input.isBlank()) return null;
        String value = input.trim();
        UUID uuid = parseCanonicalUuid(value);
        if (uuid != null) return new PlayerTarget(uuid, null);
        IPlayer player = services.getPlatformAdapter().getPlayerByName(value);
        UUID onlineUuid = player != null ? parseCanonicalUuid(player.getUUID()) : null;
        return onlineUuid != null ? new PlayerTarget(onlineUuid, player.getName()) : null;
    }

    static PlayerTarget resolveStoredPlayer(Services services, String input, StoredPlayerProfile profile) {
        UUID uuid = parseCanonicalUuid(profile.uuid());
        return uuid != null && services.getPlayerProfileService().matchesOnlineIdentity(input.trim(), uuid)
                ? new PlayerTarget(uuid, profile.name()) : null;
    }

    record PlayerTarget(UUID uuid, String name) {
        String label(IPlayer player) {
            if (name != null && !name.isBlank()) return name;
            return player != null && player.getName() != null && !player.getName().isBlank()
                    ? player.getName() : uuid.toString();
        }
    }
}
