package eu.avalanche7.paradigm.storage;

import eu.avalanche7.paradigm.storage.repository.AdminStateRepository;
import eu.avalanche7.paradigm.storage.repository.ModerationRepository;
import eu.avalanche7.paradigm.storage.repository.PermissionRepository;
import eu.avalanche7.paradigm.storage.repository.PlayerRepository;
import eu.avalanche7.paradigm.storage.repository.ServerRepository;
import eu.avalanche7.paradigm.storage.repository.WarpRepository;

public interface StorageProvider extends AutoCloseable {
    StorageProviderType type();
    String displayName();
    void initialize();

    PlayerRepository players();
    WarpRepository warps();
    PermissionRepository permissions();
    ModerationRepository moderation();
    AdminStateRepository adminState();
    ServerRepository servers();

    StorageService.StorageTestResult test();
    int migrationVersion();

    @Override
    default void close() {
    }
}
