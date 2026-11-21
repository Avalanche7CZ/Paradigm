package eu.avalanche7.paradigm.platform;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;
import net.minecraft.server.network.ServerPlayerEntity;

import eu.avalanche7.paradigm.mixin.PlayerTeleportSequenceAccess;

public class MinecraftPlayer implements IPlayer {
    private final ServerPlayerEntity player;

    public MinecraftPlayer(ServerPlayerEntity player) {
        this.player = player;
    }

    public ServerPlayerEntity getHandle() {
        return player;
    }

    @Override
    public int getTeleportSequence() {
        return player.networkHandler instanceof PlayerTeleportSequenceAccess connection ? connection.paradigm$teleportSequence() : 0;
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
            return player.getWorld().getRegistryKey().getValue().toString();
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
