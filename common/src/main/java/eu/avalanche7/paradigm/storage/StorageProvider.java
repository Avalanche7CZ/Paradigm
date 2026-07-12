package eu.avalanche7.paradigm.storage;

import eu.avalanche7.paradigm.modules.audit.AuditRepository;
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
    default AuditRepository audit() {
        return new AuditRepository() {
            @Override public void append(eu.avalanche7.paradigm.modules.audit.AuditEntry entry) {}
            @Override public java.util.List<eu.avalanche7.paradigm.modules.audit.AuditEntry> recent(int limit) { return java.util.List.of(); }
            @Override public java.util.List<eu.avalanche7.paradigm.modules.audit.AuditEntry> byActor(String actor, int limit) { return java.util.List.of(); }
            @Override public java.util.List<eu.avalanche7.paradigm.modules.audit.AuditEntry> byType(String type, int limit) { return java.util.List.of(); }
        };
    }

    StorageService.StorageTestResult test();
    int migrationVersion();

    @Override
    default void close() {
    }
}
