package eu.avalanche7.paradigm.modules.audit;

import java.util.List;

public interface AuditRepository {
    void append(AuditEntry entry);
    List<AuditEntry> recent(int limit);
    List<AuditEntry> byActor(String actor, int limit);
    List<AuditEntry> byType(String type, int limit);
}
