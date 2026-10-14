package eu.avalanche7.paradigm.configs.schema;

import java.util.List;

public record RemoteConfigField(
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
        boolean allowEmptyItems,
        String origin,
        ConfigFieldValue networkValue,
        ConfigFieldValue serverValue,
        ConfigFieldValue baselineValue
) {
    public static RemoteConfigField of(ConfigField field, Object effectiveValue, boolean hasValue, String origin) {
        boolean remoteEditable = RemoteConfigEligibility.isRemoteEligible(field);
        ConfigFieldValue value = configValue(field, effectiveValue, hasValue);
        return new RemoteConfigField(
                field.key(), field.category(), field.label(), field.help(), field.type(),
                value, safeDefault(field), field.requiredPermission(), field.reloadBehavior(), field.riskLevel(),
                field.owner(), field.min(), field.max(), field.step(), field.options(), field.listElementType(),
                remoteEditable, field.required(), field.nullable(), field.masked(), field.durationUnit(),
                field.trim(), field.allowEmptyItems(), origin,
                new ConfigFieldValue(null, false, false),
                new ConfigFieldValue(null, false, false),
                new ConfigFieldValue(null, false, false)
        );
    }

    public static RemoteConfigField of(ConfigField field,
                                       Object effectiveValue, boolean hasValue, String origin,
                                       Object networkValue, boolean hasNetworkValue,
                                       Object serverValue, boolean hasServerValue,
                                       Object baselineValue, boolean hasBaselineValue) {
        boolean remoteEditable = RemoteConfigEligibility.isRemoteEligible(field);
        return new RemoteConfigField(
                field.key(), field.category(), field.label(), field.help(), field.type(),
                configValue(field, effectiveValue, hasValue), safeDefault(field), field.requiredPermission(),
                field.reloadBehavior(), field.riskLevel(), field.owner(), field.min(), field.max(), field.step(),
                field.options(), field.listElementType(), remoteEditable, field.required(), field.nullable(),
                field.masked(), field.durationUnit(), field.trim(), field.allowEmptyItems(), origin,
                configValue(field, networkValue, hasNetworkValue), configValue(field, serverValue, hasServerValue),
                configValue(field, baselineValue, hasBaselineValue)
        );
    }

    private static ConfigFieldValue safeDefault(ConfigField field) {
        if (field.masked() || field.type() == ConfigFieldType.SECRET_MASKED || field.value() != null && field.value().secret()) {
            return ConfigFieldValue.masked(field.defaultValue() != null && field.defaultValue().set());
        }
        return field.defaultValue();
    }

    private static ConfigFieldValue configValue(ConfigField field, Object value, boolean present) {
        if (field.masked() || field.type() == ConfigFieldType.SECRET_MASKED || field.value() != null && field.value().secret()) return ConfigFieldValue.masked(present && value != null);
        return present ? new ConfigFieldValue(value, false, true) : new ConfigFieldValue(null, false, false);
    }
}
