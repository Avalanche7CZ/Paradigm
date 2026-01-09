package eu.avalanche7.paradigm.platform.Interfaces;

public interface IPlayer {
    String getName();
    String getUUID();
    Object getOriginalPlayer();
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
