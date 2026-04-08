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
    public String getWorldId() {
        try {
            Object world;
            try {
                world = player.getClass().getMethod("getServerWorld").invoke(player);
            } catch (Throwable ignored) {
                world = player.getClass().getMethod("getWorld").invoke(player);
            }
            Object key = world.getClass().getMethod("getRegistryKey").invoke(world);
            Object value = key.getClass().getMethod("getValue").invoke(key);
            return value != null ? value.toString() : null;
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
            return player.getYaw();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Float getPitch() {
        try {
            return player.getPitch();
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

    public static IPlayer of(ServerPlayerEntity player) {
        return new MinecraftPlayer(player);
    }
}
