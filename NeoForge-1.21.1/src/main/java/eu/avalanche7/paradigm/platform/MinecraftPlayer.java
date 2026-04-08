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
    public String getWorldId() {
        try {
            Object level;
            try {
                level = player.getClass().getMethod("level").invoke(player);
            } catch (Throwable ignored) {
                level = player.getClass().getField("level").get(player);
            }
            if (level == null) return null;

            Object key = level.getClass().getMethod("dimension").invoke(level);
            if (key == null) return null;

            try {
                Object location = key.getClass().getMethod("location").invoke(key);
                return location != null ? location.toString() : null;
            } catch (Throwable ignored) {
            }
            try {
                Object value = key.getClass().getMethod("getValue").invoke(key);
                return value != null ? value.toString() : null;
            } catch (Throwable ignored) {
            }
            return key.toString();
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

    public static IPlayer of(ServerPlayer player) {
        return new MinecraftPlayer(player);
    }
}
