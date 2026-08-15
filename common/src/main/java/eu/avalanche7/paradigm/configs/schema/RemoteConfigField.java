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
        ConfigFieldValue value = hasValue ? ConfigFieldValue.plain(effectiveValue) : new ConfigFieldValue(null, false, false);
        return new RemoteConfigField(
                field.key(), field.category(), field.label(), field.help(), field.type(),
                value, field.defaultValue(), field.requiredPermission(), field.reloadBehavior(), field.riskLevel(),
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
                configValue(effectiveValue, hasValue), field.defaultValue(), field.requiredPermission(),
                field.reloadBehavior(), field.riskLevel(), field.owner(), field.min(), field.max(), field.step(),
                field.options(), field.listElementType(), remoteEditable, field.required(), field.nullable(),
                field.masked(), field.durationUnit(), field.trim(), field.allowEmptyItems(), origin,
                configValue(networkValue, hasNetworkValue), configValue(serverValue, hasServerValue),
                configValue(baselineValue, hasBaselineValue)
        );
    }

    private static ConfigFieldValue configValue(Object value, boolean present) {
        return present ? new ConfigFieldValue(value, false, true) : new ConfigFieldValue(null, false, false);
    }
}
