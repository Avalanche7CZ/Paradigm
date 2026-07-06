package eu.avalanche7.paradigm.storage.repository;

import eu.avalanche7.paradigm.storage.model.StoredLocation;
import eu.avalanche7.paradigm.storage.model.StoredWarp;

import java.util.List;
import java.util.Optional;

public interface WarpRepository {
    void saveWarp(StoredWarp warp);
    Optional<StoredWarp> getWarp(String name);
    boolean deleteWarp(String name);
    List<StoredWarp> listWarps();

    Optional<StoredLocation> getGlobalSpawn();
    void setGlobalSpawn(StoredLocation location);
}
