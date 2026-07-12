package eu.avalanche7.paradigm.configs.schema;

import java.util.List;

public record ConfigField(
        String key,
        String category,
        String label,
        String help,
        ConfigFieldType type,
        ConfigFieldValue value,
        ConfigFieldValue defaultValue,
        String requiredPermission,
        ConfigReloadBehavior reloadBehavior,
        ConfigRiskLevel riskLevel,
        String owner,
        Double min,
        Double max,
        Double step,
        List<String> options,
        ConfigFieldType listElementType,
        boolean editable,
        boolean required,
        boolean nullable,
        boolean masked,
        String durationUnit,
        boolean trim,
        boolean allowEmptyItems
) {
    public ConfigField(
            String key,
            String category,
            String label,
            String help,
            ConfigFieldType type,
            ConfigFieldValue value,
            ConfigFieldValue defaultValue,
            String requiredPermission,
            ConfigReloadBehavior reloadBehavior,
            ConfigRiskLevel riskLevel,
            String owner,
            Double min,
            Double max,
            Double step,
            List<String> options,
            ConfigFieldType listElementType,
            boolean editable
    ) {
        this(key, category, label, help, type, value, defaultValue, requiredPermission, reloadBehavior, riskLevel,
                owner, min, max, step, options, listElementType, editable, false, true, false, "", true, false);
    }
}
