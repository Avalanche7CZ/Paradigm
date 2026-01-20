package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.server.level.ServerPlayer;

public class MinecraftPlayer implements IPlayer {
    private final ServerPlayer player;

    public MinecraftPlayer(ServerPlayer player) {
        this.player = player;
    }

    public ServerPlayer getHandle() {
        return player;
    }

    @Override
    public String getName() {
        return player.getName().getString();
    }

    @Override
    public String getUUID() {
        return player.getUUID().toString();
    }

    @Override
    public Object getOriginalPlayer() {
        return player;
    }

    public static IPlayer of(ServerPlayer player) {
        return new MinecraftPlayer(player);
    }
}
