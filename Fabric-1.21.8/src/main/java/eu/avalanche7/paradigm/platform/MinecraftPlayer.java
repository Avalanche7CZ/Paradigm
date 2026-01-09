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

    @Override
    public Integer getLevel() {
        try {
            return player.experienceLevel;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Double getHealth() {
        try {
            return (double) player.getHealth();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Double getMaxHealth() {
        try {
            return (double) player.getMaxHealth();
        } catch (Throwable t) {
            return null;
        }
    }

    public static IPlayer of(ServerPlayerEntity player) {
        return new MinecraftPlayer(player);
    }
}
