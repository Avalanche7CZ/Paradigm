package eu.avalanche7.paradigm.storage.managedconfig;

import java.util.Map;

import eu.avalanche7.paradigm.storage.identity.ServerScope;

public record ManagedConfigEntry(
        String id,
        String networkId,
        ServerScope scope,
        String serverId,
        String section,
        Map<String, Object> data,
        String schemaFingerprint,
        long revision,
        long updatedAtMs,
        String updatedByUuid,
        String updatedByName,
        String sourceServerId
) {
}
