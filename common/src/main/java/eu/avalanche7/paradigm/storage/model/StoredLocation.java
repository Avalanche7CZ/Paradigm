package eu.avalanche7.paradigm.storage.model;

public record StoredLocation(
        String worldId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
}
