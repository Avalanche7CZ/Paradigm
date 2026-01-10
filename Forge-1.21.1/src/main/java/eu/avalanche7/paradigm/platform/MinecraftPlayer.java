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

    public static IPlayer of(ServerPlayer player) {
        return new MinecraftPlayer(player);
    }
}
