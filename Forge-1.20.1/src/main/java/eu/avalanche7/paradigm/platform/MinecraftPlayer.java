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
        return this.player;
    }

    @Override
    public String getWorldId() {
        try {
            return player.level().dimension().location().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Double getX() {
        try {
            return player.getX();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Double getY() {
        try {
            return player.getY();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Double getZ() {
        try {
            return player.getZ();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Float getYaw() {
        try {
            return player.getYRot();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Float getPitch() {
        try {
            return player.getXRot();
        } catch (Throwable t) {
            return null;
        }
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
