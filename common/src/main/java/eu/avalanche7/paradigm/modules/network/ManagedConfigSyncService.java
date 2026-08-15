package eu.avalanche7.paradigm.modules.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import eu.avalanche7.paradigm.configs.schema.ConfigField;
import eu.avalanche7.paradigm.configs.schema.ConfigPatch;
import eu.avalanche7.paradigm.configs.schema.ConfigPatchOperation;
import eu.avalanche7.paradigm.configs.schema.ConfigPatchService;
import eu.avalanche7.paradigm.configs.schema.ConfigSchemaRegistry;
import eu.avalanche7.paradigm.configs.schema.ConfigSnapshot;
import eu.avalanche7.paradigm.configs.schema.ConfigValidationResult;
import eu.avalanche7.paradigm.configs.schema.RemoteConfigEligibility;
import eu.avalanche7.paradigm.configs.schema.RemoteConfigValidationService;
import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.storage.StorageService;
import eu.avalanche7.paradigm.storage.identity.ServerIdentity;
import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigAdoptionRequest;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigEntry;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigRevisionView;
import eu.avalanche7.paradigm.storage.repository.ManagedConfigRepository;

public class ManagedConfigSyncService {
    private static final long TICK_SECONDS = 3L;

    private final Services services;
    private final Map<String, long[]> lastAppliedTickRevisions = new ConcurrentHashMap<>();
    private final AtomicBoolean syncPending = new AtomicBoolean();
    private final AtomicBoolean syncInFlight = new AtomicBoolean();
    private volatile Set<String> managedSections = Set.of();
    private volatile ScheduledFuture<?> tickFuture;

    public ManagedConfigSyncService(Services services) {
        this.services = services;
    }

    public void start() {
        if (services.getTaskScheduler() == null) {
            return;
        }
        tickFuture = services.getTaskScheduler().scheduleAtFixedRate(this::scheduledTick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        ScheduledFuture<?> current = tickFuture;
        if (current != null) {
            current.cancel(false);
        }
        tickFuture = null;
        syncPending.set(false);
    }

    public boolean isManaged(String category) {
        return managedSections.contains(category);
    }

    public void triggerImmediateSync() {
        StorageService storage = services.getStorageService();
        if (storage == null || !storage.isMysqlActive()) {
            return;
        }
        requestSync(storage, "managed_config.sync.trigger");
    }

    public void reconcileOnStartup() {
        StorageService storage = services.getStorageService();
        if (storage == null || !storage.isMysqlActive()) {
            return;
        }
        try {
            tickOnStorageThread();
        } catch (Throwable t) {
            if (services.getLogger() != null) {
                services.getLogger().warn("Paradigm network: startup reconciliation failed: {}", t.getMessage());
            }
        }
    }

    private void scheduledTick() {
        StorageService storage = services.getStorageService();
        if (storage == null || !storage.isMysqlActive()) {
            return;
        }
        requestSync(storage, "managed_config.sync.tick");
    }

    private void requestSync(StorageService storage, String operation) {
        syncPending.set(true);
        if (!syncInFlight.compareAndSet(false, true)) {
            return;
        }
        storage.runStorageAsync(operation, () -> {
            syncPending.set(false);
            try {
                tickOnStorageThread();
            } finally {
                syncInFlight.set(false);
                if (syncPending.get()) {
                    StorageService current = services.getStorageService();
                    if (current != null && current.isMysqlActive()) {
                        requestSync(current, "managed_config.sync.coalesced");
                    }
                }
            }
        });
    }

    private void tickOnStorageThread() {
        StorageService storage = services.getStorageService();
        if (storage == null || !storage.isMysqlActive()) {
            return;
        }
        ServerIdentity identity = storage.context().serverIdentity();
        if (identity == null) {
            return;
        }
        String networkId = identity.networkId();
        String serverId = identity.serverId();
        processAdoptionRequests(storage, networkId, serverId);
        syncSections(storage, networkId, serverId);
    }

    private void processAdoptionRequests(StorageService storage, String networkId, String serverId) {
        List<ManagedConfigAdoptionRequest> pending;
        try {
            pending = storage.managedConfig().listPendingAdoptionRequests(networkId, serverId);
        } catch (Throwable t) {
            return;
        }
        for (ManagedConfigAdoptionRequest request : pending) {
            try {
                fulfillAdoptionRequest(storage, networkId, serverId, request.section());
            } catch (Throwable t) {
                if (services.getLogger() != null) {
                    services.getLogger().warn("Paradigm network: failed to fulfill adoption request for section '{}': {}",
                            request.section(), t.getMessage());
                }
            }
        }
    }

    private void fulfillAdoptionRequest(StorageService storage, String networkId, String serverId, String section) {
        ManagedConfigRepository repository = storage.managedConfig();
        String fingerprint = new ConfigSchemaRegistry(services).structuralFingerprint();
        var result = repository.upsert(networkId, ServerScope.SERVER, serverId, section, Map.of(), fingerprint, 0L, null, null, serverId);
        if (!result.ok() && !"stale_revision".equals(result.conflictReason())) {
            return;
        }
        Map<String, Object> baseline = captureCurrentValues(section);
        long serverRevision = result.ok() ? result.revision() : currentAppliedRevisions(storage, networkId, serverId, section)[1];
        repository.upsertApplied(networkId, serverId, section, 0L, serverRevision, "", baseline);
        repository.deleteAdoptionRequest(networkId, serverId, section);
    }

    private Map<String, Object> captureCurrentValues(String section) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (ConfigField field : new ConfigSchemaRegistry(services).snapshot().fields()) {
            if (field.category().equals(section) && RemoteConfigEligibility.isRemoteEligible(field)) {
                values.put(field.key(), field.value() != null ? field.value().value() : null);
            }
        }
        return values;
    }

    private void syncSections(StorageService storage, String networkId, String serverId) {
        List<ManagedConfigRevisionView> revisions;
        try {
            revisions = storage.managedConfig().listRevisionsOnly(networkId, serverId);
        } catch (Throwable t) {
            if (services.getLogger() != null) {
                services.getLogger().warn("Paradigm network: failed to list managed config revisions: {}", t.getMessage());
            }
            return;
        }
        Set<String> sections = new HashSet<>();
        Map<String, ManagedConfigRevisionView> globalBySection = new HashMap<>();
        Map<String, ManagedConfigRevisionView> serverBySection = new HashMap<>();
        for (ManagedConfigRevisionView view : revisions) {
            sections.add(view.section());
            if (view.scope() == ServerScope.GLOBAL) {
                globalBySection.put(view.section(), view);
            } else {
                serverBySection.put(view.section(), view);
            }
        }
        managedSections = Set.copyOf(sections);

        for (String section : sections) {
            long globalRev = globalBySection.containsKey(section) ? globalBySection.get(section).revision() : 0L;
            long serverRev = serverBySection.containsKey(section) ? serverBySection.get(section).revision() : 0L;
            long[] known = lastAppliedTickRevisions.get(section);
            if (known != null && known[0] == globalRev && known[1] == serverRev) {
                continue;
            }
            applySection(storage, networkId, serverId, section, globalRev, serverRev);
        }
    }

    private void applySection(StorageService storage, String networkId, String serverId, String section, long globalRev, long serverRev) {
        try {
            String myFingerprint = new ConfigSchemaRegistry(services).structuralFingerprint();
            ManagedConfigRepository repository = storage.managedConfig();

            ManagedConfigEntry globalEntry = globalRev > 0
                    ? repository.get(networkId, ServerScope.GLOBAL, "", section).orElse(null) : null;
            ManagedConfigEntry serverEntry = serverRev > 0
                    ? repository.get(networkId, ServerScope.SERVER, serverId, section).orElse(null) : null;

            if (globalEntry != null && !myFingerprint.equals(globalEntry.schemaFingerprint())) {
                recordApplyError(storage, networkId, serverId, section,
                        "schema_incompatible: network row authored for a different Paradigm build");
                return;
            }
            if (serverEntry != null && !myFingerprint.equals(serverEntry.schemaFingerprint())) {
                recordApplyError(storage, networkId, serverId, section,
                        "schema_incompatible: server row authored for a different Paradigm build");
                return;
            }

            Map<String, Object> merged = new LinkedHashMap<>();
            if (globalEntry != null) merged.putAll(globalEntry.data());
            if (serverEntry != null) merged.putAll(serverEntry.data());

            if (merged.isEmpty()) {
                recordAppliedRevision(storage, networkId, serverId, section, globalRev, serverRev);
                return;
            }

            ConfigSchemaRegistry registry = new ConfigSchemaRegistry(services);
            ConfigSnapshot snapshot = registry.snapshot();
            Map<String, ConfigField> fieldsByKey = new HashMap<>();
            for (ConfigField field : snapshot.fields()) {
                fieldsByKey.put(field.key(), field);
            }

            List<ConfigPatchOperation> operations = new ArrayList<>();
            List<String> rejections = new ArrayList<>();
            for (Map.Entry<String, Object> entry : merged.entrySet()) {
                ConfigField field = fieldsByKey.get(entry.getKey());
                if (field == null || !field.category().equals(section) || !RemoteConfigEligibility.isRemoteEligible(field)) {
                    rejections.add(entry.getKey() + ": not a managed field");
                    continue;
                }
                try {
                    Object validated = RemoteConfigValidationService.validateFieldValue(field, entry.getValue());
                    operations.add(new ConfigPatchOperation(entry.getKey(), validated));
                } catch (IllegalArgumentException invalid) {
                    rejections.add(entry.getKey() + ": " + invalid.getMessage());
                }
            }

            if (!rejections.isEmpty()) {
                recordApplyError(storage, networkId, serverId, section, "validation_failed: " + String.join("; ", rejections));
                return;
            }
            if (operations.isEmpty()) {
                recordAppliedRevision(storage, networkId, serverId, section, globalRev, serverRev);
                return;
            }

            ConfigPatch patch = new ConfigPatch(snapshot.revision(), operations);
            ConfigValidationResult result = new ConfigPatchService(services, registry).apply(patch);
            if (result.ok()) {
                recordAppliedRevision(storage, networkId, serverId, section, globalRev, serverRev);
            } else {
                String reason = result.rejected().stream()
                        .map(err -> err.key() + ": " + err.reason())
                        .collect(Collectors.joining("; "));
                recordApplyError(storage, networkId, serverId, section, reason);
            }
        } catch (Throwable t) {
            if (services.getLogger() != null) {
                services.getLogger().warn("Paradigm network: failed to apply managed config section '{}': {}", section, t.getMessage());
            }
        }
    }

    private void recordAppliedRevision(StorageService storage, String networkId, String serverId, String section, long globalRev, long serverRev) {
        storage.managedConfig().upsertApplied(networkId, serverId, section, globalRev, serverRev, "", null);
        lastAppliedTickRevisions.put(section, new long[]{globalRev, serverRev});
    }

    private void recordApplyError(StorageService storage, String networkId, String serverId, String section, String error) {
        long[] existing = currentAppliedRevisions(storage, networkId, serverId, section);
        storage.managedConfig().upsertApplied(networkId, serverId, section, existing[0], existing[1], error, null);
    }

    private long[] currentAppliedRevisions(StorageService storage, String networkId, String serverId, String section) {
        return storage.managedConfig().getApplied(networkId, serverId, section)
                .map(state -> new long[]{state.appliedGlobalRevision(), state.appliedServerRevision()})
                .orElse(new long[]{0L, 0L});
    }
}
