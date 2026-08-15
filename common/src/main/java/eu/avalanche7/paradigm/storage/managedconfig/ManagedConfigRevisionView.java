package eu.avalanche7.paradigm.storage.managedconfig;

import eu.avalanche7.paradigm.storage.identity.ServerScope;

public record ManagedConfigRevisionView(
        String id,
        ServerScope scope,
        String serverId,
        String section,
        long revision,
        String schemaFingerprint
) {
}
