package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

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
    public UUID getUUID() {
        return player.getUuid();
    }

    @Override
    public ServerPlayerEntity getOriginalPlayer() {
        return player;
    }

    public static IPlayer of(ServerPlayerEntity player) {
        return new MinecraftPlayer(player);
    }
}
