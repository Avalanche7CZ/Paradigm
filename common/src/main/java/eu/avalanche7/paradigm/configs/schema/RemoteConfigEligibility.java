package eu.avalanche7.paradigm.configs.schema;

import java.util.Set;

public final class RemoteConfigEligibility {
    private static final Set<String> EXCLUDED_CATEGORIES = Set.of("storage", "dashboard");

    private RemoteConfigEligibility() {
    }

    public static boolean isRemoteEligible(ConfigField field) {
        return field != null && field.editable() && (field.value() == null || !field.value().secret()) && !field.masked() && field.type() != ConfigFieldType.SECRET_MASKED && !EXCLUDED_CATEGORIES.contains(field.category());
    }

    public static boolean isManagedCategory(String category) {
        return category != null && !EXCLUDED_CATEGORIES.contains(category);
    }
}
