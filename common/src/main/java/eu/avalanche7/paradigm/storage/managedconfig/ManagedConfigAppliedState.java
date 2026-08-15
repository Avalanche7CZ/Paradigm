package eu.avalanche7.paradigm.storage.managedconfig;

import java.util.Map;

public record ManagedConfigAppliedState(
        String networkId,
        String serverId,
        String section,
        long appliedGlobalRevision,
        long appliedServerRevision,
        long appliedAtMs,
        String lastError,
        Map<String, Object> baseline
) {
}
