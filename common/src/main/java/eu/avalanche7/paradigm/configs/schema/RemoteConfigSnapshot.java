package eu.avalanche7.paradigm.configs.schema;

import java.util.List;

public record RemoteConfigSnapshot(
        String serverId,
        String networkId,
        boolean online,
        long lastSeenMs,
        boolean schemaCompatible,
        List<ConfigCategory> categories,
        List<RemoteConfigField> fields,
        List<SectionStatus> sections
) {
    public record SectionStatus(
            String section,
            boolean adopted,
            long networkRevision,
            long serverRevision,
            long appliedGlobalRevision,
            long appliedServerRevision,
            long appliedAtMs,
            String lastError
    ) {
    }
}
