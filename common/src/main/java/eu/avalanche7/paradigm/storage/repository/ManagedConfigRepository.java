package eu.avalanche7.paradigm.storage.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import eu.avalanche7.paradigm.storage.identity.ServerScope;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigAdoptionRequest;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigAppliedState;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigEntry;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigRevisionView;
import eu.avalanche7.paradigm.storage.managedconfig.ManagedConfigUpsertResult;

public interface ManagedConfigRepository {
    Optional<ManagedConfigEntry> get(String networkId, ServerScope scope, String serverId, String section);

    List<ManagedConfigRevisionView> listRevisionsOnly(String networkId, String serverId);

    List<ManagedConfigEntry> listForNetwork(String networkId);

    ManagedConfigUpsertResult upsert(
            String networkId, ServerScope scope, String serverId, String section,
            Map<String, Object> data, String schemaFingerprint, long expectedRevision,
            String updatedByUuid, String updatedByName, String sourceServerId
    );

    Optional<ManagedConfigAppliedState> getApplied(String networkId, String serverId, String section);

    void upsertApplied(
            String networkId, String serverId, String section,
            long appliedGlobalRevision, long appliedServerRevision,
            String lastError, Map<String, Object> baseline
    );

    List<ManagedConfigAppliedState> listApplied(String networkId, String serverId);

    void createAdoptionRequest(String networkId, String serverId, String section, String requestedByUuid, String requestedByName);

    List<ManagedConfigAdoptionRequest> listPendingAdoptionRequests(String networkId, String serverId);

    void deleteAdoptionRequest(String networkId, String serverId, String section);
}
