package eu.avalanche7.paradigm.modules.audit;

import java.util.Map;

public record AuditEntry(
        String id,
        long timestampMs,
        String networkId,
        String serverId,
        String actorUuid,
        String actorName,
        AuditSource source,
        AuditActionType actionType,
        String targetUuid,
        String targetName,
        AuditResult result,
        String message,
        Map<String, String> details
) {
    public AuditEntry {
        details = details != null ? Map.copyOf(details) : Map.of();
    }
}
