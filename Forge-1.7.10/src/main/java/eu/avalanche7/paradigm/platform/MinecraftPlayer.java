package eu.avalanche7.paradigm.platform;

import net.minecraft.entity.player.EntityPlayerMP;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class MinecraftPlayer implements IPlayer {
    private final EntityPlayerMP player;

    public MinecraftPlayer(EntityPlayerMP player) {
        this.player = player;
    }

    @Override
    public String getName() {
        return player.getCommandSenderName();
    }

    @Override
    public String getUUID() {
        return player.getUniqueID().toString();
    }

    @Override
    public Object getOriginalPlayer() {
        return player;
    }

    @Override
    public String getWorldId() {
        return String.valueOf(player.dimension);
    }

    @Override
    public Double getX() {
        return player.posX;
    }

    @Override
    public Double getY() {
        return player.posY;
    }

    @Override
    public Double getZ() {
        return player.posZ;
    }

    @Override
    public Float getYaw() {
        return player.rotationYaw;
    }

    @Override
    public Float getPitch() {
        return player.rotationPitch;
    }

    @Override
    public Integer getLevel() {
        return player.experienceLevel;
    }

    @Override
    public Double getHealth() {
        return (double) player.getHealth();
    }

    @Override
    public Double getMaxHealth() {
        return (double) player.getMaxHealth();
    }
}
