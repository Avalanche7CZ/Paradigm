package eu.avalanche7.paradigm.support;

import eu.avalanche7.paradigm.platform.Interfaces.IPlayer;

public final class TestPlayer implements IPlayer {

    private final String name;
    private final String uuid;
    private final String worldId;

    public TestPlayer(String name, String uuid, String worldId) {
        this.name = name;
        this.uuid = uuid;
        this.worldId = worldId;
    }

    public static TestPlayer named(String name) {
        return new TestPlayer(name, "00000000-0000-0000-0000-000000000001", "minecraft:overworld");
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getUUID() {
        return uuid;
    }

    @Override
    public Object getOriginalPlayer() {
        return null;
    }

    @Override
    public String getWorldId() {
        return worldId;
    }

    @Override
    public Integer getLevel() {
        return 12;
    }

    @Override
    public Double getHealth() {
        return 18.5;
    }

    @Override
    public Double getMaxHealth() {
        return 20.0;
    }
}
