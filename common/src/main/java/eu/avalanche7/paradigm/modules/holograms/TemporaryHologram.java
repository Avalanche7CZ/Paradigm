package eu.avalanche7.paradigm.modules.holograms;

public final class TemporaryHologram {
    public String id;
    public HologramDefinition definition = new HologramDefinition();
    public long createdAt;
    public Long expiresAt;
    public String owner = "";

    public TemporaryHologram copy() {
        TemporaryHologram copy = new TemporaryHologram();
        copy.id = id;
        copy.definition = definition != null ? definition.copy() : new HologramDefinition();
        copy.createdAt = createdAt;
        copy.expiresAt = expiresAt;
        copy.owner = owner;
        return copy;
    }

    public boolean expired(long now) {
        return expiresAt != null && expiresAt <= now;
    }
}
