package eu.avalanche7.paradigm.api;

import java.util.Optional;
import java.util.UUID;

/** Loader-neutral context passed to an external placeholder resolver. */
public record PlaceholderContext(Optional<UUID> playerUuid) {
    public PlaceholderContext {
        playerUuid = playerUuid != null ? playerUuid : Optional.empty();
    }

    public static PlaceholderContext player(UUID playerUuid) {
        return new PlaceholderContext(Optional.of(playerUuid));
    }

    public static PlaceholderContext server() {
        return new PlaceholderContext(Optional.empty());
    }
}
