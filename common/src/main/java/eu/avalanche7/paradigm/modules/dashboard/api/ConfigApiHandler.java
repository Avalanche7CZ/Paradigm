package eu.avalanche7.paradigm.modules.dashboard.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import eu.avalanche7.paradigm.configs.schema.ConfigField;
import eu.avalanche7.paradigm.configs.schema.ConfigPatch;
import eu.avalanche7.paradigm.configs.schema.ConfigPatchOperation;
import eu.avalanche7.paradigm.configs.schema.ConfigSnapshot;
import eu.avalanche7.paradigm.configs.schema.ConfigValidationResult;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;

public class ConfigApiHandler {
    private final DashboardService dashboard;

    public ConfigApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse snapshot(DashboardRequestContext ctx) {
        return DashboardResponse.apiOk(dashboard.schemaRegistry().snapshot());
    }

    public DashboardResponse patch(DashboardRequestContext ctx) {
        ConfigPatch patch = eu.avalanche7.paradigm.modules.dashboard.DashboardJson.fromJson(ctx.bodyReader(), ConfigPatch.class);
        if (patch == null || patch.operations() == null) {
            ConfigValidationResult empty = new ConfigValidationResult();
            empty.reject("<patch>", "Patch is empty.");
            return DashboardResponse.json(409, new DashboardResponse.ApiEnvelope(false, empty,
                    new DashboardResponse.ApiError("validation_failed", "One or more config fields were rejected."), empty.warnings()));
        }

        ConfigSnapshot current = dashboard.schemaRegistry().snapshot();
        if (patch.revision() != null && !patch.revision().isBlank()
                && !patch.revision().equals(current.revision())) {
            ConfigValidationResult stale = new ConfigValidationResult();
            stale.reject("<revision>", "Config changed while the dashboard was open. Reload the snapshot and try again.");
            stale.newRevision(current.revision());
            dashboard.audit().dashboard(ctx.principal(), AuditActionType.CONFIG_PATCH, AuditResult.FAILED,
                    "Config patch rejected.", Map.of("accepted", "0", "rejected", "1"));
            return DashboardResponse.json(409, new DashboardResponse.ApiEnvelope(false, stale,
                    new DashboardResponse.ApiError("stale_revision", "One or more config fields were rejected."), stale.warnings()));
        }

        Map<String, ConfigField> fieldsByKey = new HashMap<>();
        for (ConfigField field : current.fields()) {
            fieldsByKey.put(field.key(), field);
        }

        List<ConfigPatchOperation> unmanagedOps = new ArrayList<>();
        List<ConfigPatchOperation> managedOps = new ArrayList<>();
        for (ConfigPatchOperation op : patch.operations()) {
            ConfigField field = op != null ? fieldsByKey.get(op.key()) : null;
            if (field != null && dashboard.isManagedLocally(field.category())) {
                managedOps.add(op);
            } else {
                unmanagedOps.add(op);
            }
        }

        ConfigValidationResult result = new ConfigValidationResult();
        if (!unmanagedOps.isEmpty()) {
            mergeInto(result, dashboard.patchService().apply(new ConfigPatch(patch.revision(), unmanagedOps)));
        }
        boolean managedAccepted = false;
        for (ConfigPatchOperation op : managedOps) {
            ConfigField field = fieldsByKey.get(op.key());
            ConfigValidationResult fieldResult = dashboard.upsertSelfManagedField(field.category(), op.key(), op.value());
            mergeInto(result, fieldResult);
            managedAccepted = managedAccepted || fieldResult.ok();
        }
        if (managedAccepted) {
            dashboard.services().getManagedConfigSyncService().triggerImmediateSync();
        }
        result.newRevision(dashboard.schemaRegistry().snapshot().revision());

        Map<String, String> details = Map.of(
                "accepted", String.valueOf(result.accepted().size()),
                "rejected", String.valueOf(result.rejected().size())
        );
        if (!result.ok()) {
            dashboard.audit().dashboard(ctx.principal(), AuditActionType.CONFIG_PATCH, AuditResult.FAILED, "Config patch rejected.", details);
            String code = result.rejected().stream().anyMatch(error -> "<revision>".equals(error.key())) ? "stale_revision" : "validation_failed";
            return DashboardResponse.json(409, new DashboardResponse.ApiEnvelope(false, result, new DashboardResponse.ApiError(code, "One or more config fields were rejected."), result.warnings()));
        }
        dashboard.audit().dashboard(ctx.principal(), AuditActionType.CONFIG_PATCH, AuditResult.SUCCESS, "Config patch applied.", details);
        for (String key : result.accepted()) {
            if (key != null && key.startsWith("commands.")) {
                dashboard.audit().dashboard(ctx.principal(), AuditActionType.COMMAND_TOGGLE, AuditResult.SUCCESS, "Command toggle changed.", Map.of("field", key));
            } else if (key != null && key.startsWith("cooldowns.")) {
                dashboard.audit().dashboard(ctx.principal(), AuditActionType.COOLDOWN_CHANGE, AuditResult.SUCCESS, "Command timing changed.", Map.of("field", key));
            }
        }
        return DashboardResponse.apiOk(result, result.warnings());
    }

    public DashboardResponse apply(DashboardRequestContext ctx) throws Exception {
        ApplyRequest request = eu.avalanche7.paradigm.modules.dashboard.DashboardJson.fromJson(ctx.bodyReader(), ApplyRequest.class);
        String page = request != null ? request.page : "";
        Object result = dashboard.applyConfigAsync(page).get();
        dashboard.audit().dashboard(ctx.principal(), AuditActionType.CONFIG_PATCH, AuditResult.SUCCESS,
                "Dashboard config reload applied.", java.util.Map.of("page", page != null ? page : ""));
        return DashboardResponse.apiOk(result);
    }

    private static void mergeInto(ConfigValidationResult target, ConfigValidationResult source) {
        for (String key : source.accepted()) {
            target.accept(key);
        }
        for (ConfigValidationResult.FieldError error : source.rejected()) {
            target.reject(error.key(), error.reason());
        }
        for (String warning : source.warnings()) {
            target.warn(warning);
        }
    }

    public static final class ApplyRequest {
        public String page = "";
    }
}
