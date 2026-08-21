package eu.avalanche7.paradigm.modules.dashboard.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import eu.avalanche7.paradigm.configs.schema.ConfigCategory;
import eu.avalanche7.paradigm.configs.schema.ConfigField;
import eu.avalanche7.paradigm.configs.schema.ConfigPatch;
import eu.avalanche7.paradigm.configs.schema.ConfigPatchOperation;
import eu.avalanche7.paradigm.configs.schema.ConfigSnapshot;
import eu.avalanche7.paradigm.configs.schema.ConfigValidationResult;
import eu.avalanche7.paradigm.modules.audit.AuditActionType;
import eu.avalanche7.paradigm.modules.audit.AuditResult;
import eu.avalanche7.paradigm.modules.dashboard.DashboardMutationFeedback;
import eu.avalanche7.paradigm.modules.dashboard.DashboardRequestContext;
import eu.avalanche7.paradigm.modules.dashboard.DashboardResponse;
import eu.avalanche7.paradigm.modules.dashboard.DashboardService;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardAuthorization;
import eu.avalanche7.paradigm.modules.dashboard.auth.DashboardPrincipal;

public class ConfigApiHandler {
    private final DashboardService dashboard;

    public ConfigApiHandler(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    public DashboardResponse snapshot(DashboardRequestContext ctx) {
        ConfigSnapshot full = dashboard.schemaRegistry().snapshot();
        DashboardPrincipal principal = ctx.principal();
        Set<String> viewableCategories = full.categories().stream()
                .map(ConfigCategory::id)
                .filter(category -> dashboard.canViewConfigCategory(principal, category))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ConfigField> visibleFields = full.fields().stream()
                .filter(field -> viewableCategories.contains(field.category()))
                .toList();
        List<ConfigCategory> visibleCategories = full.categories().stream()
                .filter(category -> viewableCategories.contains(category.id()))
                .toList();
        return DashboardResponse.apiOk(new ConfigSnapshot(full.revision(), full.createdAtMs(), visibleCategories, visibleFields));
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

        Set<String> touchedCategories = new LinkedHashSet<>();
        for (ConfigPatchOperation op : patch.operations()) {
            ConfigField field = op != null ? fieldsByKey.get(op.key()) : null;
            if (field != null) {
                touchedCategories.add(field.category());
            }
        }
        if (!dashboard.canEditConfigCategories(ctx.principal(), touchedCategories)) {
            ConfigValidationResult denied = new ConfigValidationResult();
            denied.reject("<permission>", "You do not have permission to edit one or more of the affected configuration sections.");
            dashboard.audit().dashboard(ctx.principal(), AuditActionType.CONFIG_PATCH, AuditResult.DENIED,
                    "Config patch rejected: insufficient section permission.", Map.of("categories", String.join(",", touchedCategories)));
            return DashboardResponse.json(403, new DashboardResponse.ApiEnvelope(false, denied,
                    new DashboardResponse.ApiError("permission_denied", "You do not have permission to edit one or more of the affected configuration sections."), denied.warnings()));
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
        ConfigSnapshot updated = dashboard.schemaRegistry().snapshot();
        result.newRevision(updated.revision());

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
        DashboardMutationFeedback.notifyConfigPatch(
                dashboard.services(), ctx.principal(), ctx.header("X-Paradigm-Locale"),
                current, updated, patch.operations(), result.accepted());
        return DashboardResponse.apiOk(result, result.warnings());
    }

    public DashboardResponse apply(DashboardRequestContext ctx) throws Exception {
        ApplyRequest request = eu.avalanche7.paradigm.modules.dashboard.DashboardJson.fromJson(ctx.bodyReader(), ApplyRequest.class);
        String page = request != null ? request.page : "";
        Set<String> categories = DashboardAuthorization.categoriesForPage(page != null ? page.toLowerCase(Locale.ROOT) : "");
        if (!dashboard.canEditConfigCategories(ctx.principal(), categories)) {
            return DashboardResponse.apiError(403, "permission_denied", "You do not have permission to reload this configuration section.");
        }
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
