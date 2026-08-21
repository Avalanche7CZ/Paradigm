package eu.avalanche7.paradigm.modules.permissions;

import java.util.Map;

public record PermissionMutationResult(
        boolean applied,
        String code,
        String message,
        boolean confirmationRequired,
        Map<String, Object> details
) {
    public PermissionMutationResult(boolean applied, String code, String message, boolean confirmationRequired) {
        this(applied, code, message, confirmationRequired, Map.of());
    }
}
