package eu.avalanche7.paradigm.utils;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class PlayerConnection {
    private final String uuid;
    private final Object identity;

    public PlayerConnection(IPlayer player) {
        this.uuid = player.getUUID();
        Object original = player.getOriginalPlayer();
        this.identity = original != null ? original : player;
    }

    public boolean matches(IPlayer player) {
        if (player == null || uuid == null || !uuid.equalsIgnoreCase(player.getUUID())) return false;
        Object original = player.getOriginalPlayer();
        return identity == (original != null ? original : player);
    }
}
