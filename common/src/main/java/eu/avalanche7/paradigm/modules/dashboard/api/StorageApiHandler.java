package eu.avalanche7.paradigm.modules.dashboard.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.dashboard.DashboardJson;
import eu.avalanche7.paradigm.modules.dashboard.DashboardMutationFeedback;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.dashboard.StorageConfigurationRequest;
import eu.avalanche7.paradigm.storage.StorageService;

public class StorageApiHandler {
    private final DashboardService dashboard;

    public StorageApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse status(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.storageStatusAsync().get());
    }

    public DashboardResponse test(DashboardRequestContext ctx) throws Exception {
        Object result = dashboard.storageTestAsync().get();
        if (result instanceof StorageService.StorageTestResult test) {
            dashboard.audit().dashboard(ctx.principal(), AuditActionType.STORAGE_TEST,
                    test.success() ? AuditResult.SUCCESS : AuditResult.FAILED,
                    test.message(), Map.of("provider", test.provider()));
        }
        return DashboardResponse.apiOk(result);
    }

    public DashboardResponse health(DashboardRequestContext ctx) throws Exception {
        Object status = dashboard.storageStatusAsync().get();
        Object test = dashboard.storageTestAsync().get();
        return DashboardResponse.apiOk(Map.of("status", status, "test", test));
    }

    public DashboardResponse repairCheck(DashboardRequestContext ctx) throws Exception {
        Object status = dashboard.storageStatusAsync().get();
        return DashboardResponse.apiOk(Map.of("destructive", false, "checks", java.util.List.of(status)));
    }

    public DashboardResponse migrationDryRun(DashboardRequestContext ctx) throws Exception {
        MigrationDryRunRequest request = DashboardJson.fromJson(ctx.bodyReader(), MigrationDryRunRequest.class);
        if (request == null) {
            request = new MigrationDryRunRequest();
        }
        try {
            Object result = dashboard.storageMigrationDryRunAsync(ctx.principal(), request.source, request.target, request.policy).get();
            return DashboardResponse.apiOk(result);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return DashboardResponse.apiError(400, "validation_failed", cause.getMessage() != null ? cause.getMessage() : "Migration dry-run failed.");
        }
    }

    public DashboardResponse configuration(DashboardRequestContext ctx) throws Exception {
        return DashboardResponse.apiOk(dashboard.storageConfigurationAsync().get());
    }

    public DashboardResponse saveConfiguration(DashboardRequestContext ctx) throws Exception {
        StorageConfigurationRequest request = DashboardJson.fromJson(ctx.bodyReader(), StorageConfigurationRequest.class);
        Map<String, Object> before;
        try {
            before = safeMap(dashboard.storageConfigurationAsync().get());
        } catch (Throwable ignored) {
            before = Map.of();
        }
        try {
            Object result = dashboard.saveStorageConfigurationAsync(request).get();
            dashboard.audit().dashboard(ctx.principal(), AuditActionType.CONFIG_PATCH,
                    AuditResult.SUCCESS, "Storage configuration saved.",
                    Map.of("passwordReplaced", String.valueOf(request != null && request.sqlPassword != null && !request.sqlPassword.isBlank())));

            List<DashboardMutationFeedback.Change> changes = new ArrayList<>(
                    DashboardMutationFeedback.diffMap("storage.", before, nestedMap(result, "configuration")));
            if (request != null && request.sqlPassword != null && !request.sqlPassword.isBlank()) {
                changes.add(DashboardMutationFeedback.info("storage.sqlPassword replaced"));
            }
            DashboardMutationFeedback.notify(
                    dashboard.services(), ctx.principal(), ctx.header("X-Paradigm-Locale"),
                    DashboardMutationFeedback.Area.STORAGE, changes);
            return DashboardResponse.apiOk(result);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IllegalArgumentException) return DashboardResponse.apiError(400, "validation_failed", cause.getMessage());
            throw e;
        }
    }

    public DashboardResponse testConfiguration(DashboardRequestContext ctx) throws Exception {
        StorageConfigurationRequest request = DashboardJson.fromJson(ctx.bodyReader(), StorageConfigurationRequest.class);
        Object result = dashboard.testStorageConfigurationAsync(request).get();
        dashboard.audit().dashboard(ctx.principal(), AuditActionType.STORAGE_TEST,
                AuditResult.SUCCESS, "Proposed storage configuration tested.",
                Map.of("provider", request != null && request.provider != null ? request.provider : ""));
        return DashboardResponse.apiOk(result);
    }

    private static Map<String, Object> nestedMap(Object value, String key) {
        if (!(value instanceof Map<?, ?> root)) return Map.of();
        return safeMap(root.get(key));
    }

    private static Map<String, Object> safeMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, entry) -> {
            if (key != null) result.put(String.valueOf(key), entry);
        });
        return result;
    }

    public static class MigrationDryRunRequest {
        public String source = "json";
        public String target = "sql";
        public String policy = "overwrite";
    }
}
