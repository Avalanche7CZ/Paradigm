package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.server.network.ServerPlayerEntity;

public class MinecraftPlayer implements IPlayer {
    private final ServerPlayerEntity player;

    public MinecraftPlayer(ServerPlayerEntity player) {
        this.player = player;
    }

    public ServerPlayerEntity getHandle() {
        return player;
    }

    @Override
    public String getName() {
        return player.getName().getString();
    }

    @Override
    public String getUUID() {
        return player.getUuid().toString();
    }

    @Override
    public Object getOriginalPlayer() {
        return player;
    }

    public static IPlayer of(ServerPlayerEntity player) {
        return new MinecraftPlayer(player);
    }
}
