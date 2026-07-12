package eu.avalanche7.paradigm.modules.audit;

import eu.avalanche7.paradigm.core.Services;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;
import eu.avalanche7.paradigm.platform.Interfaces.ICommandSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class AuditService {
    private final Services services;
    private final AuditRepository repository;
    private final Executor executor;

    public AuditService(Services services, Executor executor) {
        this.services = services;
        this.executor = executor;
        AuditRepository active;
        try {
            active = services.getStorageService().audit();
        } catch (Throwable ignored) {
            active = new JsonAuditRepository(services.getPlatformAdapter().getConfig(), services.getLogger());
        }
        this.repository = active;
    }

    public void dashboard(DashboardPrincipal actor, AuditActionType type, AuditResult result, String message, Map<String, String> details) {
        append(entry(actor != null ? actor.uuid() : "", actor != null ? actor.name() : "", AuditSource.DASHBOARD, type, result, message, details));
    }

    public void command(ICommandSource source, AuditActionType type, AuditResult result, String message, Map<String, String> details) {
        recordCommand(source, AuditSource.COMMAND, type, result, message, details);
    }

    public void recordCommand(ICommandSource source, AuditSource auditSource, AuditActionType type, AuditResult result, String message, Map<String, String> details) {
        String uuid = source != null && source.getPlayer() != null ? source.getPlayer().getUUID() : (source != null && source.isConsole() ? "console" : "");
        String name = source != null ? source.getSourceName() : "";
        append(entry(uuid, name, auditSource != null ? auditSource : AuditSource.COMMAND, type, result, message, details));
    }

    public CompletableFuture<List<AuditEntry>> recentAsync(int limit) {
        return CompletableFuture.supplyAsync(() -> repository.recent(limit), executor);
    }

    public CompletableFuture<List<AuditEntry>> actorAsync(String actor, int limit) {
        return CompletableFuture.supplyAsync(() -> repository.byActor(actor, limit), executor);
    }

    public CompletableFuture<List<AuditEntry>> typeAsync(String type, int limit) {
        return CompletableFuture.supplyAsync(() -> repository.byType(type, limit), executor);
    }

    public List<AuditEntry> recent(int limit) {
        return repository.recent(limit);
    }

    public List<AuditEntry> actor(String actor, int limit) {
        return repository.byActor(actor, limit);
    }

    public List<AuditEntry> type(String type, int limit) {
        return repository.byType(type, limit);
    }

    private void append(AuditEntry entry) {
        CompletableFuture.runAsync(() -> repository.append(entry), executor);
    }

    private AuditEntry entry(String actorUuid, String actorName, AuditSource source, AuditActionType type, AuditResult result, String message, Map<String, String> details) {
        var identity = services.getStorageService().context().serverIdentity();
        return new AuditEntry(
                UUID.randomUUID().toString(),
                System.currentTimeMillis(),
                identity.networkId(),
                identity.serverId(),
                actorUuid,
                actorName,
                source,
                type != null ? type : AuditActionType.UNKNOWN,
                null,
                null,
                result != null ? result : AuditResult.SUCCESS,
                message != null ? message : "",
                sanitize(details)
        );
    }

    private static Map<String, String> sanitize(Map<String, String> details) {
        if (details == null || details.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : details.entrySet()) {
            String key = entry.getKey() != null ? entry.getKey() : "";
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("password") || lower.contains("token") || lower.contains("secret")) {
                result.put(key, "<redacted>");
            } else {
                result.put(key, entry.getValue() != null ? entry.getValue() : "");
            }
        }
        return result;
    }
}
