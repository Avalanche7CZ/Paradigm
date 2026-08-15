package eu.avalanche7.paradigm.storage.json;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigAdoptionRequest;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigAppliedState;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigEntry;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigRevisionView;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigUpsertResult;
import eu.avalanche7.paradigm.storage.repository.ManagedConfigRepository;

public class JsonManagedConfigRepository implements ManagedConfigRepository {
    @Override
    public Optional<ManagedConfigEntry> get(String networkId, ServerScope scope, String serverId, String section) {
        return Optional.empty();
    }

    @Override
    public List<ManagedConfigRevisionView> listRevisionsOnly(String networkId, String serverId) {
        return List.of();
    }

    @Override
    public List<ManagedConfigEntry> listForNetwork(String networkId) {
        return List.of();
    }

    @Override
    public ManagedConfigUpsertResult upsert(
            String networkId, ServerScope scope, String serverId, String section,
            Map<String, Object> data, String schemaFingerprint, long expectedRevision,
            String updatedByUuid, String updatedByName, String sourceServerId
    ) {
        return ManagedConfigUpsertResult.conflict(0L, "sql_required");
    }

    @Override
    public Optional<ManagedConfigAppliedState> getApplied(String networkId, String serverId, String section) {
        return Optional.empty();
    }

    @Override
    public void upsertApplied(
            String networkId, String serverId, String section,
            long appliedGlobalRevision, long appliedServerRevision,
            String lastError, Map<String, Object> baseline
    ) {
    }

    @Override
    public List<ManagedConfigAppliedState> listApplied(String networkId, String serverId) {
        return List.of();
    }

    @Override
    public void createAdoptionRequest(String networkId, String serverId, String section, String requestedByUuid, String requestedByName) {
    }

    @Override
    public List<ManagedConfigAdoptionRequest> listPendingAdoptionRequests(String networkId, String serverId) {
        return List.of();
    }

    @Override
    public void deleteAdoptionRequest(String networkId, String serverId, String section) {
    }
}
