package eu.avalanche7.paradigm.platform.Interfaces;

public interface IPlayer {
    String getName();
    String getUUID();
    Object getOriginalPlayer();
    default String getWorldId() {
        return null;
    }
    default Double getX() {
        return null;
    }
    default Double getY() {
        return null;
    }
    default Double getZ() {
        return null;
    }
    default Float getYaw() {
        return null;
    }
    default Float getPitch() {
        return null;
    }
    default Integer getLevel() {
        return null;
    }
    default Double getHealth() {
        return null;
    }
    default Double getMaxHealth() {
        return null;
    }
}
